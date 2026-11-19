//! LAN transport for a single, explicitly approved Frame Player remote.
mod live;
mod preview;

use base64::{engine::general_purpose::STANDARD, Engine as _};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{io, sync::{Arc, Mutex}, time::{Duration, Instant}};
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::{io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader}, net::{TcpListener, TcpStream}, sync::{oneshot, Semaphore}};
use tokio_rustls::{rustls::{self, pki_types::{CertificateDer, PrivatePkcs8KeyDer}}, TlsAcceptor};

const SERVICE: &str = "_frame-remote._tcp.local.";
const PAIR_EVENT: &str = "remote-pair-request";
const COMMAND_EVENT: &str = "remote-command";
const MAX_REQUEST: u64 = 4096;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(4);
const PAIR_LIFETIME: Duration = Duration::from_secs(90);

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Pairing {
    name: String,
    code: String,
    #[serde(skip)]
    nonce: String,
    #[serde(skip)]
    created: Instant,
}

#[derive(Default)]
struct Session {
    pending: Option<Pairing>,
    approved: Option<TrustedPhone>,
    rejected: Option<String>,
    status: Value,
    preview: preview::Preview,
    live: Option<live::Playback>,
    last_scrub: Option<Instant>,
}

struct Server {
    mdns: ServiceDaemon,
    fullname: String,
    stop: Option<oneshot::Sender<()>>,
    session: Arc<Mutex<Session>>,
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.mdns.unregister(&self.fullname);
        let _ = self.mdns.shutdown();
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
    }
}

#[derive(Default)]
pub(crate) struct RemoteState(Mutex<Option<Server>>);

#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "snake_case")]
enum Request {
    Pair { nonce: String, name: String },
    Status { token: String, want_preview: Option<bool> },
    Preview { token: String, id: String },
    PreviewAt { token: String, source_id: String, position: f64 },
    PreviewLive { token: String, source_id: String },
    Command { token: String, command: Command },
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(tag = "action", content = "value", rename_all = "snake_case")]
enum Command {
    Pause(bool),
    Seek(f64),
    Volume(u8),
    Mute(bool),
    Subtitle(Option<u32>),
    Audio(u32),
    AudioMode(AudioMode),
    Repeat(RepeatMode),
    QueuePlay(String),
    CastSearch(bool),
    CastStart(String),
    CastStop,
    Next,
    Previous,
}

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum AudioMode {
    Auto,
    Stereo,
}

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum RepeatMode {
    Off,
    One,
    All,
}

#[derive(Clone, Deserialize, Serialize)]
struct TrustedPhone {
    name: String,
    token_hash: String,
}

#[derive(Deserialize, Serialize)]
struct SavedRemote {
    enabled: bool,
    certificate: String,
    private_key: String,
    phone: Option<TrustedPhone>,
}

#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RemotePreferences {
    enabled: bool,
    paired_name: Option<String>,
}

impl SavedRemote {
    fn create() -> Result<Self, String> {
        let rcgen::CertifiedKey { cert, key_pair } =
            rcgen::generate_simple_self_signed(vec!["frameplayer-remote.local".into()])
                .map_err(|error| error.to_string())?;
        Ok(Self {
            enabled: false,
            certificate: hex_encode(cert.der().as_ref()),
            private_key: hex_encode(&key_pair.serialize_der()),
            phone: None,
        })
    }

    fn preferences(&self) -> RemotePreferences {
        RemotePreferences {
            enabled: self.enabled,
            paired_name: self.phone.as_ref().map(|phone| phone.name.clone()),
        }
    }
}

fn hex_encode(bytes: &[u8]) -> String {
    use std::fmt::Write;
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(output, "{byte:02x}");
    }
    output
}

fn hex_decode(value: &str) -> Result<Vec<u8>, String> {
    if !value.is_ascii() || value.len() % 2 != 0 {
        return Err("Invalid remote certificate encoding".into());
    }
    (0..value.len()).step_by(2)
        .map(|offset| u8::from_str_radix(&value[offset..offset + 2], 16)
            .map_err(|_| "Invalid remote certificate encoding".into()))
        .collect()
}

fn credentials() -> Result<keyring::Entry, String> {
    keyring::Entry::new("app.frameplayer.remote", "lan-identity-v1")
        .map_err(|error| error.to_string())
}

fn load_saved() -> Result<Option<SavedRemote>, String> {
    match credentials()?.get_password() {
        Ok(value) => serde_json::from_str(&value).map(Some)
            .map_err(|_| "Could not read saved remote settings".into()),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(error) => Err(error.to_string()),
    }
}

fn save(config: &SavedRemote) -> Result<(), String> {
    let data = serde_json::to_string(config).map_err(|error| error.to_string())?;
    credentials()?.set_password(&data).map_err(|error| error.to_string())
}

fn hash_token(value: &str) -> String {
    hex_encode(&Sha256::digest(value.as_bytes()))
}

fn is_token(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn valid_opaque_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64
        && id.bytes().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}

fn respond(request: Request, app: &AppHandle, session: &Mutex<Session>, fingerprint: &str) -> Value {
    let mut session = session.lock().expect("remote state");
    match request {
        Request::Pair { nonce, name } if is_token(&nonce) => {
            if session.rejected.as_deref() == Some(nonce.as_str()) {
                return json!({"ok": false, "error": "rejected"});
            }
            if session.approved.is_some() {
                return if authorized(&session, &nonce) {
                    json!({"ok": true, "paired": true})
                } else {
                    json!({"ok": false, "error": "already_paired"})
                };
            }
            if session.pending.as_ref().is_some_and(|p| p.created.elapsed() >= PAIR_LIFETIME) {
                session.pending = None;
            }
            if let Some(pairing) = &session.pending {
                return if pairing.nonce == nonce {
                    json!({"ok": true, "paired": false, "code": pairing.code})
                } else {
                    json!({"ok": false, "error": "pairing_busy"})
                };
            }
            let digest = Sha256::digest(format!("{fingerprint}:{nonce}"));
            // Compare four groups on both screens. A six-digit hash can be
            // matched cheaply by a hostile peer choosing its own nonce.
            let code = digest[..8].chunks_exact(2)
                .map(|pair| format!("{:02X}{:02X}", pair[0], pair[1]))
                .collect::<Vec<_>>().join(" ");
            let pairing = Pairing {
                name: name.chars().filter(|c| !c.is_control()).take(64).collect(),
                code,
                nonce,
                created: Instant::now(),
            };
            session.pending = Some(pairing.clone());
            drop(session);
            if app.emit_to("main", PAIR_EVENT, &pairing).is_err() {
                return json!({"ok": false, "error": "player_unavailable"});
            }
            json!({"ok": true, "paired": false, "code": pairing.code})
        }
        Request::Status { token, want_preview } if authorized(&session, &token) => {
            if let Some(want) = want_preview {
                session.preview.request(want, Instant::now());
            }
            let mut status = session.status.clone();
            if let Some(state) = status.as_object_mut() {
                let id = session.preview.frame().map(|frame| &frame.id);
                let ready = state.get("ready").and_then(Value::as_bool).unwrap_or(false);
                let preview_status = session.preview.status(ready, id.is_some());
                state.insert("previewId".into(), json!(id));
                state.insert("previewStatus".into(), json!(preview_status));
                let live = session.live.as_ref().is_some_and(|source| source.available(Instant::now()));
                state.insert("livePreview".into(), json!(live));
            }
            json!({"ok": true, "state": status})
        }
        Request::Preview { token, id } if authorized(&session, &token) => {
            let jpeg = session.preview.frame()
                .filter(|frame| frame.id == id)
                .map(|frame| &frame.jpeg);
            json!({"ok": true, "jpeg": jpeg})
        }
        Request::Command { token, command } if authorized(&session, &token) => {
            let valid = match &command {
                Command::Seek(position) => position.is_finite() && *position >= 0.0,
                Command::Volume(volume) => *volume <= 100,
                Command::CastStart(id) | Command::QueuePlay(id) => valid_opaque_id(id),
                _ => true,
            };
            if valid && matches!(command, Command::Seek(_)) {
                session.preview.seek(Instant::now());
            }
            drop(session);
            json!({"ok": valid && app.emit_to("main", COMMAND_EVENT, command).is_ok()})
        }
        _ => json!({"ok": false, "error": "not_paired"}),
    }
}

fn authorized(session: &Session, token: &str) -> bool {
    is_token(token) && session.approved.as_ref().is_some_and(|device| {
        let hash = hash_token(token);
        device.token_hash.len() == hash.len()
            && device.token_hash.bytes().zip(hash.bytes())
                .fold(0u8, |difference, (a, b)| difference | (a ^ b)) == 0
    })
}

async fn handle(stream: TcpStream, tls: TlsAcceptor, app: AppHandle, session: Arc<Mutex<Session>>, fingerprint: Arc<str>) -> io::Result<()> {
    let (mut stream, line) = tokio::time::timeout(REQUEST_TIMEOUT, async {
        let stream = tls.accept(stream).await?;
        let mut request = BufReader::new(stream).take(MAX_REQUEST);
        let mut line = Vec::new();
        request.read_until(b'\n', &mut line).await?;
        Ok::<_, io::Error>((request.into_inner().into_inner(), line))
    }).await.map_err(io::Error::other)??;
    if line.last() != Some(&b'\n') {
        return Ok(());
    }
    let reply = match serde_json::from_slice::<Request>(&line) {
        Ok(Request::PreviewLive { token, source_id }) => {
            return live::serve(stream, session, token, source_id).await;
        }
        Ok(Request::PreviewAt { token, source_id, position }) => {
            live::at(app, &session, token, source_id, position).await
        }
        Ok(request) => respond(request, &app, &session, &fingerprint),
        Err(_) => json!({"ok": false, "error": "invalid_request"}),
    };
    let mut bytes = serde_json::to_vec(&reply).map_err(io::Error::other)?;
    bytes.push(b'\n');
    tokio::time::timeout(REQUEST_TIMEOUT, async {
        stream.write_all(&bytes).await?;
        stream.shutdown().await
    }).await.map_err(io::Error::other)?
}

#[tauri::command]
pub(crate) async fn remote_enable(app: AppHandle, state: State<'_, RemoteState>) -> Result<RemotePreferences, String> {
    let listener = TcpListener::bind(("0.0.0.0", 0)).await.map_err(|err| err.to_string())?;
    let port = listener.local_addr().map_err(|err| err.to_string())?.port();
    let mut server = state.0.lock().expect("remote server");
    let existing = load_saved()?;
    if server.is_some() {
        return existing.map(|config| config.preferences()).ok_or("Remote identity missing".into());
    }
    let mut config = match existing {
        Some(config) => config,
        None => SavedRemote::create()?,
    };
    let certificate = hex_decode(&config.certificate)?;
    let private_key = hex_decode(&config.private_key)?;
    let fingerprint = hex_encode(&Sha256::digest(&certificate));
    let tls = rustls::ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions().map_err(|err| err.to_string())?
        .with_no_client_auth()
        .with_single_cert(vec![CertificateDer::from(certificate)], PrivatePkcs8KeyDer::from(private_key).into())
        .map_err(|err| err.to_string())?;
    let tls = TlsAcceptor::from(Arc::new(tls));
    let label = std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "Desktop".into());
    let label: String = label.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-').take(32).collect();
    let instance = format!("Frame Player {label}");
    let host = format!("frameplayer-{}.local.", &fingerprint[..12]);
    let txt = [("version", "1"), ("sha256", fingerprint.as_str())];
    let interfaces: Vec<_> = if_addrs::get_if_addrs()
        .map_err(|err| err.to_string())?
        .into_iter()
        .filter_map(|interface| match interface.addr {
            if_addrs::IfAddr::V4(address) if address.ip.is_private() => Some(address),
            _ => None,
        })
        .collect();
    if interfaces.is_empty() {
        return Err("remote_no_ipv4".into());
    }
    let addresses = interfaces.iter().map(|interface| interface.ip.to_string())
        .collect::<Vec<_>>().join(",");
    let info = ServiceInfo::new(SERVICE, &instance, &host, addresses.as_str(), port, &txt[..])
        .map_err(|err| err.to_string())?;
    let mdns = ServiceDaemon::new().map_err(|err| err.to_string())?;
    let fullname = info.get_fullname().to_string();
    config.enabled = true;
    let session = Arc::new(Mutex::new(Session {
        approved: config.phone.clone(),
        ..Session::default()
    }));
    let (stop, mut stopped) = oneshot::channel();
    if let Err(error) = mdns.register(info) {
        let _ = mdns.shutdown();
        return Err(error.to_string());
    }
    if let Err(error) = save(&config) {
        let _ = mdns.unregister(&fullname);
        let _ = mdns.shutdown();
        return Err(error);
    }
    *server = Some(Server { mdns, fullname, stop: Some(stop), session: session.clone() });
    let fingerprint: Arc<str> = fingerprint.into();
    tauri::async_runtime::spawn(async move {
        let capacity = Arc::new(Semaphore::new(12));
        let mut connections = tokio::task::JoinSet::new();
        loop {
            tokio::select! {
                _ = &mut stopped => break,
                Some(_) = connections.join_next(), if !connections.is_empty() => {}
                incoming = listener.accept() => {
                    let Ok((stream, peer)) = incoming else { break };
                    let std::net::IpAddr::V4(peer) = peer.ip() else { continue };
                    if !interfaces.iter().any(|interface| {
                        let mask = u32::from(interface.netmask);
                        u32::from(peer) & mask == u32::from(interface.ip) & mask
                    }) {
                        continue;
                    }
                    let Ok(permit) = capacity.clone().try_acquire_owned() else { continue };
                    let (tls, app, session, fingerprint) = (tls.clone(), app.clone(), session.clone(), fingerprint.clone());
                    connections.spawn(async move {
                        let _permit = permit;
                        let _ = handle(stream, tls, app, session, fingerprint).await;
                    });
                }
            }
        }
    });
    Ok(config.preferences())
}

#[tauri::command]
pub(crate) fn remote_preferences() -> Result<RemotePreferences, String> {
    Ok(load_saved()?.map(|config| config.preferences()).unwrap_or_default())
}

#[tauri::command]
pub(crate) fn remote_disable(state: State<'_, RemoteState>) -> Result<(), String> {
    let mut server = state.0.lock().expect("remote server");
    if let Some(mut config) = load_saved()? {
        config.enabled = false;
        save(&config)?;
    }
    server.take();
    Ok(())
}

#[tauri::command]
pub(crate) fn remote_forget(state: State<'_, RemoteState>) -> Result<(), String> {
    let server = state.0.lock().expect("remote server");
    if let Some(mut config) = load_saved()? {
        config.phone = None;
        save(&config)?;
    }
    if let Some(server) = server.as_ref() {
        let mut session = server.session.lock().expect("remote state");
        session.approved = None;
        session.pending = None;
        session.rejected = None;
        session.preview.forget();
    }
    Ok(())
}

#[tauri::command]
pub(crate) fn remote_decide(state: State<'_, RemoteState>, code: String, allow: bool) -> Result<RemotePreferences, String> {
    let server = state.0.lock().expect("remote server");
    let server = server.as_ref().ok_or("remote_disabled")?;
    let mut session = server.session.lock().expect("remote state");
    let current = session.pending.as_ref().ok_or("pairing_expired")?;
    if current.code != code || current.created.elapsed() >= PAIR_LIFETIME {
        return Err("pairing_expired".into());
    }
    let mut config = load_saved()?.ok_or("Remote identity missing")?;
    if allow {
        let phone = TrustedPhone { name: current.name.clone(), token_hash: hash_token(&current.nonce) };
        config.phone = Some(phone.clone());
        save(&config)?;
        session.approved = Some(phone);
    } else {
        session.rejected = Some(current.nonce.clone());
    }
    session.pending = None;
    Ok(config.preferences())
}

#[tauri::command]
pub(crate) fn remote_snapshot(
    app: AppHandle,
    state: State<'_, RemoteState>,
    snapshot: Value,
    preview_path: Option<String>,
) {
    let session = {
        let server = state.0.lock().expect("remote server");
        let Some(server) = server.as_ref() else { return };
        server.session.clone()
    };
    let job = {
        let mut session = session.lock().expect("remote state");
        let now = Instant::now();
        session.live = live::Playback::observe(session.live.as_ref(), &snapshot, preview_path.as_deref(), now);
        let job = session.preview.observe(&snapshot, preview_path, now);
        session.status = snapshot;
        job
    };
    let Some(job) = job else { return };
    tauri::async_runtime::spawn(async move {
        let generation = job.generation;
        let frame = tauri::async_runtime::spawn_blocking(move || {
            let thumbs = app.state::<crate::thumb_service::ThumbState>();
            let (canonical_path, jpeg) =
                crate::thumb_service::remote_preview_frame(&thumbs, &job.path, job.position)?;
            Some(preview::Frame {
                id: format!("{:032x}", rand::random::<u128>()),
                canonical_path,
                jpeg: STANDARD.encode(jpeg),
            })
        })
        .await
        .ok()
        .flatten();
        session.lock().expect("remote state").preview.complete(generation, frame, Instant::now());
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cast_commands_use_opaque_targets_on_the_paired_channel() {
        let value = serde_json::json!({
            "action": "command", "token": "a".repeat(64),
            "command": {"action": "cast_start", "value": "session-17"}
        });
        let request: Request = serde_json::from_value(value).unwrap();
        assert!(matches!(request, Request::Command {
            command: Command::CastStart(id), ..
        } if valid_opaque_id(&id)));
        assert!(matches!(serde_json::from_str::<Command>(r#"{"action":"cast_search","value":true}"#).unwrap(),
            Command::CastSearch(true)));
        assert!(matches!(serde_json::from_str::<Command>(r#"{"action":"cast_stop"}"#).unwrap(),
            Command::CastStop));
        assert!(!valid_opaque_id(""));
        assert!(!valid_opaque_id("192.168.1.20"));
        assert!(!valid_opaque_id(&"a".repeat(65)));
    }

    #[test]
    fn remote_preview_and_queue_requests_follow_the_wire_contract() {
        let live = serde_json::from_str::<Request>(
            r#"{"action":"preview_live","token":"token","source_id":"current-file"}"#,
        ).unwrap();
        assert!(matches!(live, Request::PreviewLive { source_id, .. } if source_id == "current-file"));
        let scrub = serde_json::from_str::<Request>(
            r#"{"action":"preview_at","token":"token","source_id":"current-file","position":7.25}"#,
        ).unwrap();
        assert!(matches!(scrub, Request::PreviewAt { position, .. } if position == 7.25));
        assert!(matches!(serde_json::from_str::<Command>(r#"{"action":"repeat","value":"one"}"#).unwrap(),
            Command::Repeat(RepeatMode::One)));
        assert!(matches!(serde_json::from_str::<Command>(r#"{"action":"queue_play","value":"queue-8"}"#).unwrap(),
            Command::QueuePlay(id) if valid_opaque_id(&id)));
    }

    #[test]
    fn saved_pairing_survives_restart() {
        let first_token = "a".repeat(64);
        let mut config = SavedRemote::create().unwrap();
        config.enabled = true;
        config.phone = Some(TrustedPhone {
            name: "Android".into(),
            token_hash: hash_token(&first_token),
        });

        let persisted = serde_json::to_string(&config).unwrap();
        assert!(!persisted.contains(&first_token));
        let restored: SavedRemote = serde_json::from_str(&persisted).unwrap();
        assert_eq!(restored.certificate, config.certificate);
        assert_eq!(restored.private_key, config.private_key);
        assert_eq!(restored.preferences().paired_name.as_deref(), Some("Android"));
        assert!(restored.preferences().enabled);

        let mut session = Session { approved: restored.phone, ..Session::default() };
        assert!(authorized(&session, &first_token));
        assert!(!authorized(&session, &"b".repeat(64)));
        session.approved = None;
        assert!(!authorized(&session, &first_token));
    }
}
