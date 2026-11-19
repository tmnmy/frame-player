//! Bounded image delivery on the paired remote channel.
use super::{authorized, Session};
use crate::thumb_service::{self, LivePreviewDecoder, ThumbState, REMOTE_JPEG_LIMIT};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde_json::{json, Value};
use std::{
    io,
    sync::{Arc, Mutex},
    thread,
    time::{Duration, Instant},
};
use tauri::{AppHandle, Manager};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    sync::{mpsc, oneshot, Semaphore},
};

const MAGIC: &[u8; 4] = b"FPL1";
const FRAME_PERIOD: Duration = Duration::from_micros(66_667);
const HEARTBEAT: Duration = Duration::from_secs(1);
const WRITE_TIMEOUT: Duration = Duration::from_secs(2);
const OPEN_TIMEOUT: Duration = Duration::from_secs(2);
const SCRUB_TIMEOUT: Duration = Duration::from_millis(1500);
const SCRUB_PERIOD: Duration = Duration::from_millis(125);
const ANCHOR_LIFETIME: Duration = Duration::from_secs(5);
const MAX_EXTRAPOLATION: f64 = 1.0;

static LIVE_SLOT: Semaphore = Semaphore::const_new(1);
static SCRUB_SLOT: Semaphore = Semaphore::const_new(1);

/// This copy of the desktop-only media path is never included in status or frames.
pub(super) struct Playback {
    id: String,
    path: String,
    anchor: Instant,
    position: f64,
    duration: f64,
    speed: f64,
    paused: bool,
    seek: u64,
}

impl Playback {
    pub(super) fn observe(
        previous: Option<&Self>,
        status: &Value,
        path: Option<&str>,
        now: Instant,
    ) -> Option<Self> {
        let path = path.filter(|path| thumb_service::can_remote_preview_path(path))?;
        if status.get("ready")?.as_bool() != Some(true) {
            return None;
        }
        let id = status.get("sourceId")?.as_str().filter(|id| !id.is_empty())?;
        let duration = status.get("duration")?.as_f64().filter(|duration| *duration > 0.0)?;
        let position = status.get("position")?.as_f64()?.clamp(0.0, duration);
        let paused = status.get("paused")?.as_bool()?;
        let speed = status.get("speed").and_then(Value::as_f64).unwrap_or(1.0).clamp(0.1, 8.0);
        let seek = previous
            .filter(|previous| previous.id == id && previous.path == path)
            .map_or(0, |previous| {
                let jumped = (position - previous.position_at(now)).abs() > 0.65;
                previous.seek.wrapping_add(u64::from(jumped))
            });
        Some(Self {
            id: id.to_owned(),
            path: path.to_owned(),
            anchor: now,
            position,
            duration,
            speed,
            paused,
            seek,
        })
    }

    fn position_at(&self, now: Instant) -> f64 {
        let elapsed = now.saturating_duration_since(self.anchor).as_secs_f64();
        let offset = if self.paused { 0.0 } else { elapsed.min(MAX_EXTRAPOLATION) * self.speed };
        self.clamp(self.position + offset)
    }

    fn clamp(&self, position: f64) -> f64 {
        position.clamp(0.0, (self.duration - 0.03).max(0.0))
    }

    pub(super) fn available(&self, now: Instant) -> bool {
        now.saturating_duration_since(self.anchor) <= ANCHOR_LIFETIME
            && !thumb_service::is_private_preview_path(&self.path)
    }
}

/// Held by an accepted request. Comparing the source and approval again after
/// decode keeps obsolete work from reaching the phone after a source switch or
/// revocation. The random paired token is never retained in the stream worker.
struct Access {
    id: String,
    path: String,
    approval: String,
}

impl Access {
    fn authorize(session: &Session, token: &str, source_id: &str, now: Instant) -> Option<Self> {
        let playback = session.live.as_ref()?;
        if playback.id != source_id || !playback.available(now) || !authorized(session, token) {
            return None;
        }
        Some(Self {
            id: playback.id.clone(),
            path: playback.path.clone(),
            approval: session.approved.as_ref()?.token_hash.clone(),
        })
    }

    fn current<'a>(&self, session: &'a Session, now: Instant) -> Option<&'a Playback> {
        let playback = session.live.as_ref()?;
        if playback.id == self.id && playback.path == self.path && playback.available(now)
            && session.approved.as_ref().is_some_and(|phone| phone.token_hash == self.approval)
        {
            Some(playback)
        } else {
            None
        }
    }

    fn target(&self, session: &Mutex<Session>) -> Option<(f64, u64)> {
        let guard = session.lock().ok()?;
        let now = Instant::now();
        let playback = self.current(&guard, now)?;
        Some((playback.position_at(now), playback.seek))
    }
}

struct Rendered {
    seek: u64,
    jpeg: Vec<u8>,
}

async fn write_packet(writer: &mut (impl AsyncWrite + Unpin), bytes: &[u8]) -> io::Result<()> {
    tokio::time::timeout(WRITE_TIMEOUT, async {
        writer.write_all(&(bytes.len() as u32).to_be_bytes()).await?;
        writer.write_all(bytes).await?;
        writer.flush().await
    })
    .await
    .map_err(io::Error::other)?
}

pub(super) async fn serve(
    stream: impl AsyncRead + AsyncWrite + Unpin,
    session: Arc<Mutex<Session>>,
    token: String,
    source_id: String,
) -> io::Result<()> {
    let access = session.lock().ok().and_then(|session| {
        Access::authorize(&session, &token, &source_id, Instant::now())
    });
    let Some(access) = access.map(Arc::new) else { return Ok(()) };
    let Ok(permit) = LIVE_SLOT.try_acquire() else { return Ok(()) };
    let (ready, opened) = oneshot::channel();
    let (frames, mut receiver) = mpsc::channel::<Rendered>(1);
    let session_for_decode = session.clone();
    let access_for_decode = access.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let _permit = permit;
        let Ok(mut decoder) = LivePreviewDecoder::open(&access_for_decode.path) else { return };
        if ready.send(decoder.canonical_path().to_owned()).is_err() {
            return;
        }
        let mut last_seek = None;
        while !frames.is_closed() {
            let started = Instant::now();
            let Some((position, seek)) = access_for_decode.target(&session_for_decode) else { break };
            if decoder.is_private() {
                break;
            }
            let reset = last_seek != Some(seek);
            last_seek = Some(seek);
            match decoder.render(position, reset) {
                Ok(Some(jpeg)) if jpeg.len() <= REMOTE_JPEG_LIMIT => {
                    // A slow receiver may keep one frame. It cannot queue an
                    // entire video or increase latency for the controls.
                    let _ = frames.try_send(Rendered { seek, jpeg });
                }
                Err(_) => break,
                _ => {}
            }
            thread::sleep(FRAME_PERIOD.saturating_sub(started.elapsed()).max(Duration::from_millis(5)));
        }
    });
    let Ok(Ok(canonical)) = tokio::time::timeout(OPEN_TIMEOUT, opened).await else {
        return Ok(());
    };
    if access.target(&session).is_none() || thumb_service::is_private_preview_path(&canonical) {
        return Ok(());
    }

    let (mut reader, mut writer) = tokio::io::split(stream);
    tokio::time::timeout(WRITE_TIMEOUT, async {
        writer.write_all(MAGIC).await?;
        writer.flush().await
    }).await.map_err(io::Error::other)??;

    let mut heartbeat = tokio::time::interval_at(tokio::time::Instant::now() + HEARTBEAT, HEARTBEAT);
    let mut input = [0u8; 1];
    loop {
        tokio::select! {
            // The client sends one request only. An EOF or further input ends
            // this stream even if the player is paused.
            _ = reader.read(&mut input) => return Ok(()),
            frame = receiver.recv() => {
                let Some(frame) = frame else { return Ok(()) };
                let Some((_, seek)) = access.target(&session) else { return Ok(()) };
                if thumb_service::is_private_preview_path(&canonical) {
                    return Ok(());
                }
                if frame.seek == seek {
                    write_packet(&mut writer, &frame.jpeg).await?;
                }
            }
            _ = heartbeat.tick() => {
                if access.target(&session).is_none() || thumb_service::is_private_preview_path(&canonical) {
                    return Ok(());
                }
                write_packet(&mut writer, &[]).await?;
            }
        }
    }
}

pub(super) async fn at(
    app: AppHandle,
    session: &Mutex<Session>,
    token: String,
    source_id: String,
    position: f64,
) -> Value {
    if !position.is_finite() {
        return json!({"ok": false, "error": "invalid_request"});
    }
    let (access, target, permit) = {
        let mut guard = session.lock().expect("remote state");
        let now = Instant::now();
        let Some(access) = Access::authorize(&guard, &token, &source_id, now) else {
            return json!({"ok": false, "error": "preview_unavailable"});
        };
        if guard.last_scrub.is_some_and(|last| now.saturating_duration_since(last) < SCRUB_PERIOD) {
            return json!({"ok": true, "jpeg": null});
        }
        let Ok(permit) = SCRUB_SLOT.try_acquire() else { return json!({"ok": true, "jpeg": null}) };
        let target = guard.live.as_ref().expect("authorized source").clamp(position);
        guard.last_scrub = Some(now);
        (access, target, permit)
    };

    let path = access.path.clone();
    let job = tauri::async_runtime::spawn_blocking(move || {
        let _permit = permit;
        let thumbs = app.state::<ThumbState>();
        thumb_service::remote_scrub_frame(&thumbs, &path, target)
    });
    let frame = tokio::time::timeout(SCRUB_TIMEOUT, job).await.ok()
        .and_then(Result::ok)
        .flatten()
        .filter(|(canonical, _)| !thumb_service::is_private_preview_path(canonical))
        .filter(|_| access.target(session).is_some())
        .map(|(_, jpeg)| STANDARD.encode(jpeg));
    json!({"ok": true, "jpeg": frame})
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn live_playback_clock_tracks_pause_speed_and_seeks() {
        let start = Instant::now();
        let path = if cfg!(windows) { r"C:\fixtures\video.mp4" } else { "/fixtures/video.mp4" };
        let mut state = json!({
            "ready": true, "sourceId": "media", "duration": 100.0,
            "position": 10.0, "paused": false, "speed": 2.0
        });
        let first = Playback::observe(None, &state, Some(path), start).unwrap();
        let later = start + Duration::from_millis(500);
        assert_eq!(first.position_at(later), 11.0);
        state["position"] = json!(11.0);
        state["paused"] = json!(true);
        let paused = Playback::observe(Some(&first), &state, Some(path), later).unwrap();
        assert_eq!(paused.seek, first.seek);
        assert_eq!(paused.position_at(later + Duration::from_secs(1)), 11.0);
        state["position"] = json!(40.0);
        let jumped = Playback::observe(Some(&paused), &state, Some(path), later).unwrap();
        assert_ne!(jumped.seek, paused.seek);
        assert!(!jumped.available(later + Duration::from_secs(6)));
        assert!(Playback::observe(None, &state, Some("https://example.org/video"), start).is_none());
    }
}
