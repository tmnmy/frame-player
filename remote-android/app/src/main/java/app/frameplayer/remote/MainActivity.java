package app.frameplayer.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String SERVICE = "_frame-remote._tcp.";
    private static final String PREFS = "remote_pairing";
    private static final int BACKGROUND = 0xff111116;
    private static final int SURFACE = 0xff1b1b25;
    private static final int OUTLINE = 0xff343440;
    private static final int TEXT = 0xfff2f1f9;
    private static final int MUTED = 0xffababbc;
    private static final int ACCENT = 0xff6358ef;
    private static final int SLIDER = 0xffa4a2ff;
    private static final int CONNECTED = 0xffa2d9c8;
    private static final int MAX_PREVIEW_ATTEMPTS = 3;
    private static final long PREVIEW_RETRY_DELAY = 1200;
    private static final long LIVE_PREVIEW_STALE_DELAY = 1800;
    private static final long LIVE_PREVIEW_SEEK_DELAY = 750;
    private static final ColorStateList ICON_TINT = new ColorStateList(
            new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
            new int[]{0xff777789, TEXT});
    private static final int MAX_PREVIEW_BASE64 = 60_000;
    private static final int MAX_PREVIEW_EDGE = 640;

    private enum Connection { PAIRING, RECONNECTING, CONNECTED }

    private record TrustedReader(String fingerprint, String token, String name) {}
    private record CastTarget(String id, String name) {}
    private record CastPickerState(String note, String destination, List<CastTarget> targets, boolean refresh) {}
    private record QueueEntry(String id, String title, boolean current) {}
    private record QueuePickerState(List<QueueEntry> entries, String note) {}

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService previewNetwork = Executors.newSingleThreadExecutor();
    private final LivePreview livePreview = new LivePreview(this::showLivePreviewFrame);
    private final Map<String, NsdServiceInfo> devices = new LinkedHashMap<>();
    private final Set<String> resolvedNames = new HashSet<>();
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private SharedPreferences preferences;
    private TrustedReader trusted;
    private NsdServiceInfo selected;
    private int selectionRevision;
    private TextView message, title, elapsed, total, output, volumePercent, connectionTarget;
    private TextView previewPlaceholder;
    private View connectionDot;
    private ActionLayout connectionChip;
    private ImageView previewImage;
    private String previewId, displayedPreviewId;
    private int previewRevision, previewAttempts;
    private long retryPreviewAt, lastLiveFrameAt, livePreviewResumeAt;
    private boolean previewInFlight, previewVisible;
    private LinearLayout deviceList, controls;
    private AlertDialog playersDialog;
    private AlertDialog queueDialog, castDialog;
    private LinearLayout queueOptions;
    private QueuePickerState queuePickerState;
    private LinearLayout castOptions;
    private CastPickerState castPickerState;
    private RemoteClient castSearchClient;
    private String castPending, castError;
    private boolean castSearchStarted;
    private Button forget;
    private ImageButton play, mute, watchOnTv;
    private TrackTile repeat, queue, audio, subs, audioMode;
    private SeekBar seek, volume;
    private JogStrip jog;
    private volatile RemoteClient client;
    private volatile long playbackRevision;
    private String playerName;
    private JSONObject snapshot = new JSONObject();
    private Connection connection;
    private boolean foreground, resolving, seeking, changingVolume, busy;
    private int failedPolls;
    private long pairUntil;
    private final Runnable poll = this::poll;
    private final Runnable reconnect = this::resolveNext;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        nsd = getSystemService(NsdManager.class);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        restoreTrustedReader();
        LinearLayout content = column();
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setFitsSystemWindows(true);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);

        buildHeader(content);

        controls = column();
        controls.setVisibility(View.GONE);
        content.addView(controls);
        controls.addView(buildVideoPreview(), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout times = new LinearLayout(this);
        times.setPadding(dp(8), 0, dp(8), 0);
        elapsed = timestamp();
        total = timestamp();
        total.setGravity(Gravity.END);
        times.addView(elapsed, weight());
        times.addView(total, weight());
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setContentDescription("Playback position");
        tintSlider(seek);
        LinearLayout.LayoutParams timelineSize = new LinearLayout.LayoutParams(-1, dp(44));
        timelineSize.topMargin = dp(4);
        controls.addView(seek, timelineSize);
        controls.addView(times);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && seeking) {
                    double seconds = snapshot.optDouble("duration", 0) * progress / bar.getMax();
                    elapsed.setText(time(seconds));
                    scrubVideoPreview(seconds);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                jog.cancelGesture();
                seeking = true;
                beginPreviewScrub();
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (!seeking) return;
                seeking = false;
                commitSeek(snapshot.optDouble("duration", 0) * bar.getProgress() / bar.getMax());
            }
        });
        seek.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                seeking = false;
                cancelPreviewScrub();
            }
            return false;
        });
        jog = new JogStrip(this, new JogStrip.Listener() {
            @Override public void preview(double seconds) {
                showPosition(seconds);
                scrubVideoPreview(seconds);
            }

            @Override public void finish(double seconds, boolean commit) {
                if (commit) commitSeek(seconds);
                else cancelPreviewScrub();
            }
        });
        controls.addView(jog, new LinearLayout.LayoutParams(-1, dp(48)));

        buildTransport();
        buildPlaybackOptions();
        buildVolumeAndTracks();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        startDiscovery();
        scheduleReconnect(0);
        schedulePoll(0);
    }

    @Override protected void onResume() {
        super.onResume();
        previewVisible = true;
        schedulePoll(0);
    }

    @Override protected void onPause() {
        previewVisible = false;
        livePreview.stop();
        setRequestedPreview(null);
        lastLiveFrameAt = 0;
        if (castDialog != null) castDialog.dismiss();
        if (queueDialog != null) queueDialog.dismiss();
        super.onPause();
    }

    @Override protected void onStop() {
        foreground = false;
        ui.removeCallbacks(poll);
        ui.removeCallbacks(reconnect);
        selectionRevision++;
        selected = null;
        // Keep an explicit pairing in flight while the user checks the desktop
        // dialog. An established connection will be freshly resolved on return.
        if (connection != Connection.PAIRING) clearConnection();
        stopDiscovery();
        devices.clear();
        resolvedNames.clear();
        super.onStop();
    }

    @Override protected void onDestroy() {
        client = null;
        livePreview.close();
        if (playersDialog != null) playersDialog.dismiss();
        network.shutdownNow();
        previewNetwork.shutdownNow();
        super.onDestroy();
    }

    private void restoreTrustedReader() {
        String fingerprint = preferences.getString("fingerprint", "");
        String token = preferences.getString("token", "");
        if (RemoteClient.isHexSecret(fingerprint) && RemoteClient.isHexSecret(token)) {
            trusted = new TrustedReader(fingerprint, token, preferences.getString("name", "Frame Player"));
        }
    }

    private void saveTrustedReader(RemoteClient source) {
        trusted = new TrustedReader(source.fingerprint(), source.token(), playerName);
        preferences.edit()
                .putString("fingerprint", trusted.fingerprint())
                .putString("token", trusted.token())
                .putString("name", trusted.name())
                .apply();
        if (forget != null) forget.setVisibility(View.VISIBLE);
    }

    private void removeTrustedReader() {
        trusted = null;
        preferences.edit().remove("fingerprint").remove("token").remove("name").apply();
        if (forget != null) forget.setVisibility(View.GONE);
    }

    private void forgetReader() {
        new AlertDialog.Builder(this).setTitle("Forget this player?")
                .setMessage("You will need to authorize this phone again on the PC.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", (dialog, which) -> {
                    selectionRevision++;
                    selected = null;
                    ui.removeCallbacks(reconnect);
                    removeTrustedReader();
                    clearConnection();
                    drawDevices();
                }).show();
    }

    private void refreshDiscovery() {
        selectionRevision++;
        selected = null;
        stopDiscovery();
        devices.clear();
        resolvedNames.clear();
        drawDevices();
        startDiscovery();
        scheduleReconnect(0);
    }

    private void startDiscovery() {
        if (!foreground || discovery != null) return;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int code) {
                ui.post(() -> {
                    if (discovery != this) return;
                    discovery = null;
                    if (client == null) message.setText("Could not find players (" + code + ").");
                    scheduleReconnect(4000);
                });
            }
            @Override public void onStopDiscoveryFailed(String type, int code) {}
            @Override public void onServiceFound(NsdServiceInfo service) {
                ui.post(() -> {
                    if (discovery != this) return;
                    String name = service.getServiceName();
                    if (devices.put(name, service) == null) resolvedNames.remove(name);
                    drawDevices();
                    scheduleReconnect(100);
                });
            }
            @Override public void onServiceLost(NsdServiceInfo service) {
                ui.post(() -> {
                    if (discovery != this) return;
                    devices.remove(service.getServiceName());
                    resolvedNames.remove(service.getServiceName());
                    drawDevices();
                });
            }
        };
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException error) {
            discovery = null;
            if (client == null) message.setText("Could not find players on the local network.");
            scheduleReconnect(4000);
        }
    }

    private void stopDiscovery() {
        if (discovery == null) return;
        try {
            nsd.stopServiceDiscovery(discovery);
        } catch (IllegalArgumentException ignored) {
            // Android may already have stopped a failed discovery.
        }
        discovery = null;
    }

    private void drawDevices() {
        if (deviceList != null) {
            deviceList.removeAllViews();
            for (NsdServiceInfo device : devices.values()) {
                Button player = button(device.getServiceName(), () -> {
                    if (playersDialog != null) playersDialog.dismiss();
                    if (client == null || connection != Connection.CONNECTED
                            || !device.getServiceName().equals(playerName)) {
                        selectReader(device);
                    }
                });
                LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
                row.bottomMargin = dp(8);
                deviceList.addView(player, row);
            }
            if (devices.isEmpty()) {
                TextView empty = text("No players found yet.");
                empty.setTextColor(MUTED);
                empty.setTextSize(13);
                deviceList.addView(empty, new LinearLayout.LayoutParams(-1, dp(36)));
            }
        }
        if (client != null || resolving || selected != null) return;
        if (trusted != null) {
            message.setText("Looking for " + trusted.name() + " to reconnect…");
        } else {
            message.setText(devices.isEmpty()
                    ? "Enable remote control in Frame Player on the same network."
                    : "Choose a player to control.");
        }
    }

    private void selectReader(NsdServiceInfo device) {
        selectionRevision++;
        selected = device;
        clearConnection();
        message.setText("Connecting to " + device.getServiceName() + "…");
        scheduleReconnect(0);
    }

    private void scheduleReconnect(long delay) {
        if (!foreground || resolving || client != null || (selected == null && trusted == null)) return;
        ui.removeCallbacks(reconnect);
        ui.postDelayed(reconnect, delay);
    }

    private void resolveNext() {
        if (!foreground || resolving || client != null) return;
        if (selected != null) {
            NsdServiceInfo requested = selected;
            selected = null;
            resolveReader(requested, true);
            return;
        }
        if (trusted == null) return;
        startDiscovery();
        NsdServiceInfo candidate = devices.get(trusted.name());
        if (candidate == null || resolvedNames.contains(candidate.getServiceName())) {
            candidate = devices.values().stream()
                    .filter(device -> !resolvedNames.contains(device.getServiceName()))
                    .findFirst().orElse(null);
        }
        if (candidate == null) {
            resolvedNames.clear();
            scheduleReconnect(4000);
            return;
        }
        resolvedNames.add(candidate.getServiceName());
        resolveReader(candidate, false);
    }

    @SuppressWarnings("deprecation")
    private void resolveReader(NsdServiceInfo device, boolean explicit) {
        resolving = true;
        int revision = selectionRevision;
        NsdManager.DiscoveryListener listener = discovery;
        try {
            nsd.resolveService(device, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int code) {
                    ui.post(() -> resolved(revision, listener, explicit, null));
                }
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    ui.post(() -> resolved(revision, listener, explicit, info));
                }
            });
        } catch (RuntimeException error) {
            resolved(revision, listener, explicit, null);
        }
    }

    @SuppressWarnings("deprecation")
    private void resolved(int revision, NsdManager.DiscoveryListener listener, boolean explicit,
                          NsdServiceInfo info) {
        resolving = false;
        if (!foreground || revision != selectionRevision || listener != discovery) {
            scheduleReconnect(0);
            return;
        }
        if (info != null) {
            try {
                byte[] version = info.getAttributes().get("version");
                byte[] hash = info.getAttributes().get("sha256");
                if (version == null || !"1".equals(new String(version, StandardCharsets.UTF_8)) ||
                        hash == null || info.getHost() == null || info.getPort() <= 0) {
                    throw new IllegalArgumentException("Unsupported player");
                }
                String fingerprint = new String(hash, StandardCharsets.UTF_8);
                boolean known = trusted != null && trusted.fingerprint().equals(fingerprint);
                // An mDNS name is only a discovery hint. A saved token is sent
                // only through a TLS connection pinned to the saved certificate.
                if (!known && !explicit) {
                    scheduleReconnect(0);
                    return;
                }
                client = new RemoteClient(new InetSocketAddress(info.getHost(), info.getPort()),
                        fingerprint, known ? trusted.token() : null);
                playerName = info.getServiceName();
                connection = known ? Connection.RECONNECTING : Connection.PAIRING;
                failedPolls = 0;
                if (known) {
                    message.setText("Reconnecting to " + playerName + "…");
                } else {
                    pairUntil = android.os.SystemClock.elapsedRealtime() + 85_000;
                    message.setText("Check that the code " + client.code() + " appears on the PC, then authorize this phone.");
                }
                schedulePoll(0);
                return;
            } catch (Exception error) {
                clearConnection();
            }
        }
        if (explicit) message.setText("Could not reach the player. Try again.");
        scheduleReconnect(explicit ? 1000 : 100);
    }

    private void schedulePoll(long delay) {
        ui.removeCallbacks(poll);
        if (foreground && client != null) ui.postDelayed(poll, delay);
    }

    private void poll() {
        RemoteClient current = client;
        boolean confirming = connection == Connection.PAIRING;
        if (!foreground || current == null) return;
        if (busy) {
            schedulePoll(300);
            return;
        }
        if (confirming && android.os.SystemClock.elapsedRealtime() >= pairUntil) {
            clearConnection();
            message.setText("Pairing expired. Select the player again.");
            return;
        }
        boolean wantPreview = !confirming && previewVisible && !seeking && !jog.isDragging() && !hasRecentLiveFrame();
        busy = true;
        network.execute(() -> {
            try {
                JSONObject result = confirming ? current.pair(Build.MODEL) : current.status(wantPreview);
                ui.post(() -> handlePoll(current, confirming, wantPreview, result));
            } catch (Exception error) {
                ui.post(() -> handleConnectionFailure(current, confirming));
            }
        });
    }

    private void handlePoll(RemoteClient current, boolean confirming, boolean wantPreview, JSONObject result) {
        busy = false;
        if (current != client) return;
        failedPolls = 0;
        if (!result.optBoolean("ok")) {
            String error = result.optString("error");
            if (confirming && "pairing_busy".equals(error)) {
                schedulePoll(600);
                return;
            }
            if (!confirming && "not_paired".equals(error)) {
                removeTrustedReader();
            }
            clearConnection();
            if (foreground) {
                message.setText(confirming
                        ? "Pairing was declined. Select the player to try again."
                        : "Authorization expired. Select the player to pair again.");
            }
            return;
        }
        if (confirming && result.optBoolean("paired")) {
            saveTrustedReader(current);
            connection = Connection.CONNECTED;
        } else if (!confirming && connection == Connection.RECONNECTING) {
            if (trusted != null && !trusted.name().equals(playerName)) saveTrustedReader(current);
            connection = Connection.CONNECTED;
        }
        if (foreground && connection == Connection.CONNECTED) {
            JSONObject state = result.optJSONObject("state");
            if (state != null) render(state, wantPreview);
            else setConnectionPresentation(true, "Connected · player remembered");
        }
        schedulePoll(600);
    }

    private void handleConnectionFailure(RemoteClient current, boolean confirming) {
        busy = false;
        if (current != client || !foreground) return;
        if (confirming || ++failedPolls < 2) {
            setConnectionPresentation(false, "Connection lost. Retrying…");
            refreshCastPicker();
            playbackRevision++;
            seeking = false;
            jog.cancelGesture();
            jog.setEnabled(false);
            seek.setEnabled(false);
            clearVideoPreview();
            message.setTextColor(MUTED);
            schedulePoll(1300);
            return;
        }
        clearConnection();
        refreshDiscovery();
    }

    private void clearConnection() {
        if (castDialog != null) castDialog.dismiss();
        if (queueDialog != null) queueDialog.dismiss();
        client = null;
        connection = null;
        failedPolls = 0;
        snapshot = new JSONObject();
        playbackRevision++;
        seeking = false;
        jog.update(0, 0);
        clearVideoPreview();
        setConnectionPresentation(false, "Looking for players on the local network.");
        updateCastButton(false);
        controls.setVisibility(View.GONE);
        ui.removeCallbacks(poll);
    }

    private void render(JSONObject state, boolean wantPreview) {
        JSONObject previous = snapshot;
        snapshot = state;
        boolean ready = state.optBoolean("ready");
        String destination = state.isNull("output") ? "PC" : state.optString("output", "PC");
        setConnectionPresentation(true, ready
                ? "Connected · player remembered" : "Connected · no file open");
        updateCastButton(state.optBoolean("castActive"));
        maybeStartCastSearch();
        refreshCastPicker();
        updatePlaybackOptions();
        refreshQueuePicker();
        double duration = state.optDouble("duration", 0);
        boolean seekable = Double.isFinite(duration) && duration > 0;
        boolean sourceChanged = changedSource(previous, state, duration);
        if (sourceChanged || (!ready && previous.optBoolean("ready"))) {
            playbackRevision++;
            clearVideoPreview();
        }
        if (!ready || !seekable || sourceChanged) {
            seeking = false;
            jog.cancelGesture();
        }
        controls.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (!ready) return;
        title.setText(state.isNull("title") ? "Playing" : state.optString("title", "Playing"));
        double position = state.optDouble("position", 0);
        output.setText(seekable ? destination + " · " + Math.max(1, Math.round(duration / 60)) + " min" : destination);
        total.setText(seekable ? time(duration) : "–:––");
        jog.update(position, duration);
        if (!seeking && !jog.isDragging() && SystemClock.elapsedRealtime() >= livePreviewResumeAt) {
            showPosition(position);
            playVideoPreview();
            if (wantPreview && previewVisible && !hasRecentLiveFrame()) updateVideoPreview(state);
        }
        if (!changingVolume) volume.setProgress(state.optInt("volume", 0));
        seek.setEnabled(seekable);
        boolean paused = state.optBoolean("paused");
        boolean muted = state.optBoolean("muted");
        setActionIcon(play, paused ? R.drawable.remote_play : R.drawable.remote_pause, paused ? "Play" : "Pause");
        setActionIcon(mute, muted ? R.drawable.remote_muted : R.drawable.remote_volume, muted ? "Unmute" : "Mute");
        boolean adjustable = state.optBoolean("volumeAdjustable");
        volume.setEnabled(adjustable);
        mute.setEnabled(adjustable);
        audio.setEnabled(hasTracks("audio"));
        audio.setValue(trackValue("audio", "Auto"));
        subs.setEnabled(hasTracks("subtitles"));
        subs.setValue(trackValue("subtitles", "Off"));
        boolean audioAvailable = state.optBoolean("audioModeAvailable");
        boolean audioSwitching = state.optBoolean("audioSwitching");
        audioMode.setVisibility(audioAvailable ? View.VISIBLE : View.GONE);
        audioMode.setEnabled(audioAvailable && !audioSwitching);
        audioMode.setValue(audioSwitching ? "Switching…"
                : "stereo".equals(state.optString("audioMode")) ? "TV stereo" : "Auto");
    }

    private static boolean changedSource(JSONObject previous, JSONObject state, double duration) {
        if (!state.optString("output").equals(previous.optString("output"))) return true;
        Object source = state.opt("sourceId");
        Object earlierSource = previous.opt("sourceId");
        if (source instanceof String || earlierSource instanceof String) {
            return !Objects.equals(source, earlierSource);
        }
        return !state.optString("title").equals(previous.optString("title"))
                || Math.abs(duration - previous.optDouble("duration", 0)) > 0.5;
    }

    private FrameLayout buildVideoPreview() {
        FrameLayout hero = new FrameLayout(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int screenHeight = getResources().getConfiguration().screenHeightDp;
                int maximumHeight = dp(screenHeight > 0 ? Math.min(208f, screenHeight * 0.36f) : 208f);
                int height = Math.min(maximumHeight, Math.round(MeasureSpec.getSize(widthMeasureSpec) * 9f / 16f));
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff292a3a, 0xff16161f});
        background.setCornerRadius(dp(16));
        hero.setBackground(background);
        hero.setClipToOutline(true);

        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hero.addView(previewImage, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x000a0a10, 0x160a0a10, 0xf20a0a10}));
        hero.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        previewPlaceholder = text("Waiting for a video frame…");
        previewPlaceholder.setTextSize(12);
        previewPlaceholder.setTextColor(MUTED);
        previewPlaceholder.setGravity(Gravity.CENTER);
        previewPlaceholder.setPadding(dp(16), 0, dp(16), 0);
        hero.addView(previewPlaceholder, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        LinearLayout caption = column();
        caption.setPadding(dp(16), dp(8), dp(16), dp(14));
        title = text("");
        title.setTextSize(22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        caption.addView(title);
        output = text("");
        output.setTextColor(0xffd0d0dd);
        output.setTextSize(13);
        output.setSingleLine();
        output.setEllipsize(TextUtils.TruncateAt.END);
        output.setPadding(0, dp(3), 0, 0);
        caption.addView(output);
        hero.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return hero;
    }

    private void setRequestedPreview(String id) {
        if (Objects.equals(previewId, id)) return;
        previewRevision++;
        previewId = id;
        previewAttempts = 0;
        retryPreviewAt = 0;
    }

    private String liveSourceId() {
        if (!previewVisible || !foreground || connection != Connection.CONNECTED || failedPolls != 0
                || !snapshot.optBoolean("ready") || !snapshot.optBoolean("livePreview")) return null;
        Object id = snapshot.opt("sourceId");
        return id instanceof String source && !source.isEmpty() ? source : null;
    }

    private void playVideoPreview() {
        String source = liveSourceId();
        RemoteClient current = client;
        if (source != null && current != null) {
            livePreview.play(current, source);
        } else {
            livePreview.stop();
            lastLiveFrameAt = 0;
        }
    }

    private void beginPreviewScrub() {
        livePreview.stop();
        lastLiveFrameAt = 0;
        setRequestedPreview(null);
    }

    private void scrubVideoPreview(double seconds) {
        String source = liveSourceId();
        RemoteClient current = client;
        if (source == null || current == null || !Double.isFinite(seconds)) return;
        lastLiveFrameAt = 0;
        setRequestedPreview(null);
        livePreview.scrub(current, source, seconds);
    }

    private void cancelPreviewScrub() {
        showPosition(snapshot.optDouble("position", 0));
        if (!seeking && SystemClock.elapsedRealtime() >= livePreviewResumeAt) playVideoPreview();
    }

    private boolean hasRecentLiveFrame() {
        return lastLiveFrameAt != 0 && SystemClock.elapsedRealtime() - lastLiveFrameAt < LIVE_PREVIEW_STALE_DELAY;
    }

    private void showLivePreviewFrame(RemoteClient current, String source, Bitmap frame, boolean scrubbing) {
        if (current != client || !Objects.equals(source, liveSourceId()) || scrubbing != (seeking || jog.isDragging())
                || (!scrubbing && SystemClock.elapsedRealtime() < livePreviewResumeAt)) {
            frame.recycle();
            return;
        }
        if (!scrubbing) lastLiveFrameAt = SystemClock.elapsedRealtime();
        displayedPreviewId = null;
        showPreviewFrame(frame);
    }

    private void showPreviewFrame(Bitmap frame) {
        previewImage.setImageBitmap(frame);
        if (previewPlaceholder.getVisibility() != View.GONE) previewPlaceholder.setVisibility(View.GONE);
    }

    private void clearVideoPreview() {
        livePreview.stop();
        lastLiveFrameAt = 0;
        livePreviewResumeAt = 0;
        previewRevision++;
        setRequestedPreview(null);
        displayedPreviewId = null;
        previewImage.setImageDrawable(null);
        showPreviewStatus("Waiting for a video frame…");
    }

    private void showPreviewStatus(String label) {
        boolean missing = previewImage.getDrawable() == null;
        if (missing && !TextUtils.equals(previewPlaceholder.getText(), label)) previewPlaceholder.setText(label);
        previewPlaceholder.setVisibility(missing ? View.VISIBLE : View.GONE);
    }

    private void updateVideoPreview(JSONObject state) {
        String status = state.optString("previewStatus");
        Object hintedId = state.opt("previewId");
        if ("unsupported_source".equals(status)) {
            if (previewId != null || previewImage.getDrawable() != null) clearVideoPreview();
            showPreviewStatus("Preview unavailable for this source");
            return;
        }
        if (!(hintedId instanceof String id) || id.length() != 32) {
            setRequestedPreview(null);
            if (!state.has("previewStatus") && !state.has("previewId")) {
                if (previewImage.getDrawable() != null && liveSourceId() == null) clearVideoPreview();
                showPreviewStatus("Update Frame Player on your PC to see previews");
            } else if ("decode_failed".equals(status)) {
                showPreviewStatus("Could not generate a preview");
            } else {
                // Keep the last still from this source while a seek settles.
                showPreviewStatus("Waiting for a video frame…");
            }
            return;
        }
        setRequestedPreview(id);
        if (previewAttempts < MAX_PREVIEW_ATTEMPTS) showPreviewStatus("Loading video preview…");
        fetchVideoPreview();
    }

    private void fetchVideoPreview() {
        RemoteClient current = client;
        String id = previewId;
        if (previewInFlight || !foreground || !previewVisible || current == null || id == null
                || id.equals(displayedPreviewId) || hasRecentLiveFrame() || previewAttempts >= MAX_PREVIEW_ATTEMPTS
                || SystemClock.elapsedRealtime() < Math.max(retryPreviewAt, livePreviewResumeAt)
                || seeking || jog.isDragging()) return;

        previewInFlight = true;
        previewAttempts++;
        retryPreviewAt = SystemClock.elapsedRealtime() + PREVIEW_RETRY_DELAY;
        int revision = previewRevision;
        previewNetwork.execute(() -> {
            Bitmap frame = null;
            try {
                if (current == client) frame = decodeVideoPreview(current.preview(id));
            } catch (Exception ignored) {
                // A failed preview can be retried without interrupting the controls.
            }
            Bitmap completed = frame;
            ui.post(() -> {
                previewInFlight = false;
                if (foreground && previewVisible && current == client && revision == previewRevision
                        && id.equals(previewId) && !hasRecentLiveFrame() && !seeking && !jog.isDragging()) {
                    if (completed != null) {
                        displayedPreviewId = id;
                        showPreviewFrame(completed);
                    } else if (previewAttempts >= MAX_PREVIEW_ATTEMPTS) {
                        showPreviewStatus("Could not load the preview");
                    }
                }
                fetchVideoPreview();
            });
        });
    }

    private static Bitmap decodeVideoPreview(String jpeg) {
        if (jpeg == null || jpeg.isEmpty() || jpeg.length() > MAX_PREVIEW_BASE64) return null;
        byte[] bytes = Base64.decode(jpeg, Base64.NO_WRAP);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (!"image/jpeg".equals(options.outMimeType) || options.outWidth < 1 || options.outHeight < 1
                || options.outWidth > MAX_PREVIEW_EDGE || options.outHeight > MAX_PREVIEW_EDGE) return null;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void showPosition(double seconds) {
        double duration = snapshot.optDouble("duration", 0);
        double position = Double.isFinite(seconds) ? Math.max(0, seconds) : 0;
        elapsed.setText(time(position));
        seek.setProgress(Double.isFinite(duration) && duration > 0
                ? (int) Math.round(seek.getMax() * Math.min(position, duration) / duration) : 0);
    }

    private void commitSeek(double seconds) {
        if (!foreground || client == null || failedPolls > 0
                || !snapshot.optBoolean("ready") || !Double.isFinite(seconds)) return;
        double duration = snapshot.optDouble("duration", 0);
        if (!Double.isFinite(duration) || duration <= 0) return;
        double target = Math.max(0, Math.min(duration, seconds));
        jog.update(target, duration);
        showPosition(target);
        if (liveSourceId() != null) {
            beginPreviewScrub();
            livePreviewResumeAt = SystemClock.elapsedRealtime() + LIVE_PREVIEW_SEEK_DELAY;
        }
        command("seek", target);
    }

    private void chooseAudioMode() {
        if (!snapshot.optBoolean("audioModeAvailable") || snapshot.optBoolean("audioSwitching")) return;
        boolean stereo = "stereo".equals(snapshot.optString("audioMode"));
        new AlertDialog.Builder(this).setTitle("Audio output")
                .setSingleChoiceItems(new String[]{"Auto", "TV stereo (2.0)"}, stereo ? 1 : 0, (dialog, index) -> {
                    dialog.dismiss();
                    if (snapshot.optBoolean("audioModeAvailable") && !snapshot.optBoolean("audioSwitching")) {
                        command("audio_mode", index == 1 ? "stereo" : "auto");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean hasTracks(String kind) {
        JSONArray tracks = snapshot.optJSONArray(kind);
        return tracks != null && tracks.length() > 0;
    }

    private void chooseTrack(String kind, String label) {
        JSONArray tracks = snapshot.optJSONArray(kind);
        if (tracks == null || tracks.length() == 0) return;
        boolean subtitles = kind.equals("subtitles");
        int offset = subtitles ? 1 : 0;
        String[] labels = new String[tracks.length() + offset];
        if (subtitles) labels[0] = "Off";
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            labels[i + offset] = track.optString("label");
        }
        new AlertDialog.Builder(this).setTitle(label).setItems(labels, (dialog, index) -> {
            if (subtitles && index == 0) {
                command("subtitle", JSONObject.NULL);
            } else {
                JSONObject track = tracks.optJSONObject(index - offset);
                command(subtitles ? "subtitle" : "audio", track.optInt("id"));
            }
        }).show();
    }

    private void command(String action, Object value) {
        RemoteClient current = client;
        if (current == null || connection != Connection.CONNECTED) return;
        boolean seekCommand = "seek".equals(action);
        long revision = playbackRevision;
        network.execute(() -> {
            try {
                if (client != current || (seekCommand && revision != playbackRevision)) return;
                if (!current.command(action, value).optBoolean("ok")) {
                    throw new IllegalStateException("Command declined");
                }
                if (seekCommand || "repeat".equals(action) || "queue_play".equals(action)) {
                    ui.post(() -> {
                        if (client == current && (!seekCommand || revision == playbackRevision)) schedulePoll(0);
                    });
                }
            } catch (Exception error) {
                ui.post(() -> {
                    if (client == current && foreground) message.setText("Could not send command. Try again.");
                });
            }
        });
    }

    private static String time(double seconds) {
        int total = Math.max(0, (int) seconds);
        if (total >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    private void buildHeader(LinearLayout content) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = glyph(R.drawable.frame_player_logo);
        LinearLayout.LayoutParams logoSize = new LinearLayout.LayoutParams(dp(24), dp(24));
        logoSize.setMarginEnd(dp(8));
        header.addView(logo, logoSize);

        TextView heading = text("Frame Player");
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setTextSize(18);
        heading.setSingleLine();
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, weight());

        watchOnTv = iconButton(R.drawable.remote_cast, "Watch on TV", this::showCastPicker, SURFACE, 10);
        LinearLayout.LayoutParams castSize = new LinearLayout.LayoutParams(dp(44), dp(44));
        castSize.setMarginStart(dp(8));
        header.addView(watchOnTv, castSize);

        connectionChip = new ActionLayout("PC connection", this::showPlayers);
        connectionChip.setTooltipText("PC connection");
        connectionChip.setGravity(Gravity.CENTER);
        connectionChip.setMinimumWidth(dp(44));
        connectionChip.setPadding(dp(9), 0, dp(10), 0);
        connectionChip.setBackground(pressable(SURFACE, 10, OUTLINE));
        connectionTarget = text("Players");
        connectionTarget.setTextSize(12);
        connectionTarget.setSingleLine();
        connectionTarget.setMaxWidth(dp(96));
        connectionTarget.setEllipsize(TextUtils.TruncateAt.END);
        connectionTarget.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        connectionChip.addView(connectionTarget);
        LinearLayout.LayoutParams chipSize = new LinearLayout.LayoutParams(-2, dp(44));
        chipSize.setMarginStart(dp(8));
        header.addView(connectionChip, chipSize);
        content.addView(header);

        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(2), 0, 0, 0);
        connectionDot = new View(this);
        connectionDot.setBackground(surface(MUTED, 3, 0));
        connectionDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(dp(6), dp(6));
        dot.setMarginEnd(dp(7));
        status.addView(connectionDot, dot);
        message = text("Looking for players on the local network.");
        message.setTextColor(MUTED);
        message.setTextSize(12);
        status.addView(message, weight());
        LinearLayout.LayoutParams statusSize = new LinearLayout.LayoutParams(-1, -2);
        statusSize.topMargin = dp(8);
        statusSize.bottomMargin = dp(12);
        content.addView(status, statusSize);
    }

    private void setConnectionPresentation(boolean connected, String status) {
        String label = connected ? "PC" : "Players";
        if (!TextUtils.equals(connectionTarget.getText(), label)) connectionTarget.setText(label);
        if (!TextUtils.equals(message.getText(), status)) message.setText(status);
        connectionChip.setContentDescription(connected
                ? "PC connection. Connected to " + playerName : "PC connection");
        int color = connected ? CONNECTED : MUTED;
        message.setTextColor(color);
        connectionDot.getBackground().setTint(color);
    }

    private void showPlayers() {
        if (playersDialog != null) return;
        LinearLayout panel = column();
        panel.setPadding(dp(16), dp(8), dp(16), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        if (connection == Connection.CONNECTED) {
            TextView current = text(playerName);
            current.setTextSize(14);
            current.setTextColor(CONNECTED);
            panel.addView(current);
        }
        deviceList = column();
        addSpaced(panel, deviceList, 8);
        LinearLayout actions = new LinearLayout(this);
        addSpaced(panel, actions, 4);
        forget = button("Forget player", this::forgetReader);
        forget.setVisibility(trusted == null ? View.GONE : View.VISIBLE);
        actions.addView(forget, startCell());
        actions.addView(button("Refresh", this::refreshDiscovery), weight());

        playersDialog = new AlertDialog.Builder(this)
                .setTitle("Players")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();
        playersDialog.setOnDismissListener(dialog -> {
            playersDialog = null;
            deviceList = null;
            forget = null;
        });
        playersDialog.show();
        drawDevices();
    }

    private void updateCastButton(boolean active) {
        if (active == watchOnTv.isSelected()) return;
        watchOnTv.setSelected(active);
        watchOnTv.setImageTintList(active ? ColorStateList.valueOf(SLIDER) : ICON_TINT);
        watchOnTv.setContentDescription(active ? "Watch on TV. Casting" : "Watch on TV");
    }

    private void showCastPicker() {
        if (castDialog != null) return;
        castOptions = column();
        castOptions.setPadding(dp(16), dp(8), dp(16), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(castOptions);
        castDialog = new AlertDialog.Builder(this).setTitle("Watch on TV")
                .setView(scroll).setNegativeButton("Close", null).create();
        castDialog.setOnDismissListener(dialog -> closeCastPicker());
        castDialog.show();
        maybeStartCastSearch();
        refreshCastPicker();
    }

    private void closeCastPicker() {
        RemoteClient searching = castSearchClient;
        castDialog = null;
        castOptions = null;
        castPickerState = null;
        castPending = null;
        castError = null;
        castSearchStarted = false;
        castSearchClient = null;
        if (searching != null && !network.isShutdown()) {
            network.execute(() -> {
                try {
                    searching.command("cast_search", false);
                } catch (Exception ignored) {
                    // Discovery on the PC has its own timeout if the phone disappears.
                }
            });
        }
    }

    private boolean canBrowseForTv() {
        return client != null && connection == Connection.CONNECTED && failedPolls == 0
                && snapshot.optBoolean("castAvailable") && !snapshot.optBoolean("castActive")
                && !snapshot.optBoolean("castStarting");
    }

    private void maybeStartCastSearch() {
        if (castDialog != null && !castSearchStarted && canBrowseForTv()) startCastSearch();
    }

    private void startCastSearch() {
        if (castDialog == null || !canBrowseForTv() || castPending != null) return;
        castSearchStarted = true;
        castSearchClient = client;
        sendCastCommand("cast_search", true, "Looking for TVs…", "Could not start searching for TVs.");
    }

    private void refreshCastPicker() {
        if (castOptions == null) return;
        String note = null;
        String destination = null;
        List<CastTarget> targets = new ArrayList<>();
        boolean refresh = false;
        if (client == null || connection != Connection.CONNECTED || failedPolls != 0) {
            note = "Connect to Frame Player on your PC first.";
        } else if (!snapshot.has("ready")) {
            note = "Waiting for the PC…";
        } else if (!snapshot.has("castAvailable")) {
            note = "Update Frame Player on your PC to cast from the phone.";
        } else if (castPending != null) {
            note = castPending;
        } else if (snapshot.optBoolean("castStarting")) {
            note = "Connecting to the TV…";
        } else if (snapshot.optBoolean("castActive")) {
            destination = snapshot.optString("output", "TV");
            if (destination.isEmpty() || snapshot.isNull("output")) destination = "TV";
            note = castError;
        } else if (!snapshot.optBoolean("castAvailable")) {
            note = snapshot.optBoolean("ready")
                    ? "This media cannot be cast. Open a local video on the PC."
                    : "Open a video on the PC to watch it on a TV.";
        } else {
            JSONArray choices = snapshot.optJSONArray("castTargets");
            if (choices != null) {
                for (int i = 0; i < choices.length(); i++) {
                    JSONObject target = choices.optJSONObject(i);
                    if (target == null) continue;
                    String id = target.optString("id", "");
                    String name = target.optString("name", "");
                    if (!id.isEmpty() && !name.isEmpty()) targets.add(new CastTarget(id, name));
                }
            }
            boolean searching = snapshot.optBoolean("castSearching");
            note = castError == null ? castIssueLabel() : castError;
            if (note == null && targets.isEmpty()) {
                note = searching ? "Looking for TVs…" : "No TVs found on the PC’s network.";
            }
            refresh = !searching;
        }

        CastPickerState next = new CastPickerState(note, destination, targets, refresh);
        if (next.equals(castPickerState)) return;
        castPickerState = next;
        castOptions.removeAllViews();
        if (note != null) {
            TextView status = text(note);
            status.setTextSize(14);
            status.setTextColor(MUTED);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addSpaced(castOptions, status, 4);
        }
        if (destination != null) {
            TextView current = text("Casting to " + destination);
            current.setTextColor(CONNECTED);
            addSpaced(castOptions, current, 4);
            addSpaced(castOptions, button("Stop casting",
                    () -> sendCastCommand("cast_stop", null, "Stopping cast…", "Could not stop casting.")), 8);
        }
        for (CastTarget target : targets) {
            addSpaced(castOptions, button(target.name(), () -> sendCastCommand("cast_start", target.id(),
                    "Connecting to " + target.name() + "…", "Could not start casting. Try again.")), 6);
        }
        if (refresh) addSpaced(castOptions, button("Search again", this::startCastSearch), 8);
    }

    private String castIssueLabel() {
        return switch (snapshot.optString("castIssue")) {
            case "target_gone" -> "That TV is no longer available. Search again.";
            case "start_failed" -> "Could not start casting. Try again.";
            case "not_available" -> "Casting is unavailable for the current media.";
            default -> null;
        };
    }

    private void sendCastCommand(String action, Object value, String pending, String failure) {
        RemoteClient current = client;
        AlertDialog picker = castDialog;
        if (picker == null || current == null || connection != Connection.CONNECTED || castPending != null) return;
        castPending = pending;
        castError = null;
        refreshCastPicker();
        network.execute(() -> {
            boolean ok = false;
            try {
                if (current == client) ok = current.command(action, value).optBoolean("ok");
            } catch (Exception ignored) {
                // A failed request leaves the picker open with a retry action.
            }
            boolean accepted = ok;
            ui.post(() -> {
                if (picker != castDialog || current != client) return;
                castPending = null;
                if (accepted && "cast_stop".equals(action)) castSearchStarted = true;
                if (!accepted) {
                    castError = failure;
                    refreshCastPicker();
                }
                // The reply acknowledges dispatch; the next status reports what happened.
                schedulePoll(0);
            });
        });
    }

    private void buildTransport() {
        LinearLayout playback = new LinearLayout(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = Math.min(dp(284), MeasureSpec.getSize(widthMeasureSpec));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
            }
        };
        playback.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
        row.gravity = Gravity.CENTER_HORIZONTAL;
        controls.addView(playback, row);

        ImageButton previous = iconButton(R.drawable.remote_previous, "Previous file",
                () -> command("previous", null), BACKGROUND, 12);
        playback.addView(previous, transportCell(48, .95f, 3));
        playback.addView(skipButton(R.drawable.remote_rewind, "−10 s", "Back 10 seconds",
                () -> commitSeek(snapshot.optDouble("position", 0) - 10)), transportCell(48, 1.05f, 8));
        play = iconButton(R.drawable.remote_play, "Play",
                () -> command("pause", !snapshot.optBoolean("paused")), ACCENT, 16);
        playback.addView(play, transportCell(56, 1.35f, 8));
        playback.addView(skipButton(R.drawable.remote_forward, "+10 s", "Forward 10 seconds",
                () -> commitSeek(snapshot.optDouble("position", 0) + 10)), transportCell(48, 1.05f, 3));
        ImageButton next = iconButton(R.drawable.remote_next, "Next file",
                () -> command("next", null), BACKGROUND, 12);
        playback.addView(next, transportCell(48, .95f, 0));
    }

    private LinearLayout.LayoutParams transportCell(int height, float weight, int nextGap) {
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(height), weight);
        cell.setMarginEnd(dp(nextGap));
        return cell;
    }

    private ActionLayout skipButton(int icon, String seconds, String description, Runnable action) {
        ActionLayout button = new ActionLayout(description, action);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setBackground(pressable(SURFACE, 14, 0));
        button.setTooltipText(description);
        button.addView(glyph(icon), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView amount = timestamp();
        amount.setText(seconds);
        amount.setTextSize(10);
        amount.setTextColor(TEXT);
        amount.setSingleLine();
        amount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(amount);
        return button;
    }

    private void buildPlaybackOptions() {
        LinearLayout options = new LinearLayout(this);
        addSpaced(controls, options, 8);
        repeat = new TrackTile(R.drawable.remote_repeat, "Repeat", this::cycleRepeat);
        options.addView(repeat, startCell());
        queue = new TrackTile(R.drawable.remote_queue, "Queue", this::showQueuePicker);
        options.addView(queue, weight());
    }

    private void updatePlaybackOptions() {
        String mode = snapshot.optString("repeat");
        repeat.setEnabled("off".equals(mode) || "one".equals(mode) || "all".equals(mode));
        boolean enabled = "one".equals(mode) || "all".equals(mode);
        if (enabled != repeat.isSelected()) {
            repeat.setSelected(enabled);
            repeat.setBackground(pressable(enabled ? 0xff2a2353 : SURFACE, 12, 0));
        }
        repeat.setValue(switch (mode) {
            case "off" -> "Off";
            case "one" -> "One item";
            case "all" -> "All items";
            default -> "Update PC";
        });
        JSONArray entries = snapshot.optJSONArray("queue");
        int count = entries == null ? 0 : Math.max(entries.length(), snapshot.optInt("queueTotal"));
        queue.setValue(entries == null ? "Update PC" : count == 0 ? "Empty" : count + (count == 1 ? " item" : " items"));
    }

    private void cycleRepeat() {
        String next = switch (snapshot.optString("repeat")) {
            case "off" -> "one";
            case "one" -> "all";
            case "all" -> "off";
            default -> null;
        };
        if (next != null) command("repeat", next);
    }

    private void showQueuePicker() {
        if (queueDialog != null) return;
        queueOptions = column();
        queueOptions.setPadding(dp(16), dp(8), dp(16), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(queueOptions);
        queueDialog = new AlertDialog.Builder(this).setTitle("Queue")
                .setView(scroll).setNegativeButton("Close", null).create();
        queueDialog.setOnDismissListener(dialog -> {
            queueDialog = null;
            queueOptions = null;
            queuePickerState = null;
        });
        queueDialog.show();
        refreshQueuePicker();
    }

    private void refreshQueuePicker() {
        if (queueOptions == null) return;
        JSONArray items = snapshot.optJSONArray("queue");
        List<QueueEntry> entries = new ArrayList<>();
        String note = null;
        if (items == null) {
            note = "Update Frame Player on your PC to see the queue.";
        } else {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id");
                if (!id.isEmpty()) entries.add(new QueueEntry(id, item.optString("title", "Untitled media"),
                        item.optBoolean("current")));
            }
            int total = snapshot.optInt("queueTotal");
            if (entries.isEmpty()) note = "The queue is empty.";
            else if (total > entries.size()) note = "Showing " + entries.size() + " of " + total + " items.";
        }

        QueuePickerState next = new QueuePickerState(entries, note);
        if (next.equals(queuePickerState)) return;
        queuePickerState = next;
        queueOptions.removeAllViews();
        if (note != null) {
            TextView label = text(note);
            label.setTextSize(14);
            label.setTextColor(MUTED);
            addSpaced(queueOptions, label, 4);
        }
        for (QueueEntry entry : entries) {
            if (entry.current()) {
                TextView current = text("Now playing · " + entry.title());
                current.setTextSize(14);
                current.setGravity(Gravity.CENTER_VERTICAL);
                current.setPadding(dp(10), dp(4), dp(10), dp(4));
                current.setMinimumHeight(dp(48));
                current.setBackground(surface(0xff2a2353, 12, OUTLINE));
                addSpaced(queueOptions, current, 6);
            } else {
                Button item = button(entry.title(), () -> {
                    command("queue_play", entry.id());
                    if (queueDialog != null) queueDialog.dismiss();
                });
                addSpaced(queueOptions, item, 6);
            }
        }
    }

    private void buildVolumeAndTracks() {
        LinearLayout volumeRow = new LinearLayout(this);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        addSpaced(controls, volumeRow, 14);
        mute = iconButton(R.drawable.remote_volume, "Mute",
                () -> command("mute", !snapshot.optBoolean("muted")), SURFACE, 12);
        volumeRow.addView(mute, new LinearLayout.LayoutParams(dp(44), dp(44)));
        volumePercent = timestamp();
        volumePercent.setGravity(Gravity.END);
        volumePercent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        volume = new SeekBar(this);
        volume.setMax(100);
        volume.setContentDescription("Volume");
        tintSlider(volume);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                volumePercent.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { changingVolume = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                changingVolume = false;
                command("volume", bar.getProgress());
            }
        });
        volumePercent.setText(volume.getProgress() + "%");
        volumeRow.addView(volume, new LinearLayout.LayoutParams(0, dp(48), 1));
        volumeRow.addView(volumePercent, new LinearLayout.LayoutParams(dp(38), -2));

        LinearLayout tracks = new LinearLayout(this);
        addSpaced(controls, tracks, 6);
        audio = new TrackTile(R.drawable.remote_audio, "Audio", () -> chooseTrack("audio", "Audio track"));
        tracks.addView(audio, startCell());
        subs = new TrackTile(R.drawable.remote_subtitles, "Subtitles",
                () -> chooseTrack("subtitles", "Subtitles"));
        tracks.addView(subs, weight());
        audioMode = new TrackTile(R.drawable.remote_volume, "Audio output", this::chooseAudioMode);
        audioMode.setVisibility(View.GONE);
        addSpaced(controls, audioMode, 8);
    }

    private String trackValue(String name, String fallback) {
        JSONArray tracks = snapshot.optJSONArray(name);
        if (tracks != null) {
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.optJSONObject(i);
                if (track != null && track.optBoolean("selected")) {
                    return track.optString("label", fallback);
                }
            }
        }
        return fallback;
    }

    private ImageView glyph(int icon) {
        ImageView view = new ImageView(this);
        view.setImageResource(icon);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private ImageButton iconButton(int icon, String description, Runnable action, int color, int radius) {
        ImageButton button = new ImageButton(this);
        button.setImageTintList(ICON_TINT);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(pressable(color, radius, 0));
        button.setOnClickListener(view -> action.run());
        setActionIcon(button, icon, description);
        return button;
    }

    private static void setActionIcon(ImageButton button, int icon, String description) {
        if (!TextUtils.equals(description, button.getContentDescription())) {
            button.setImageResource(icon);
            button.setContentDescription(description);
            button.setTooltipText(description);
        }
    }

    private class ActionLayout extends LinearLayout {
        ActionLayout(String description, Runnable action) {
            super(MainActivity.this);
            setContentDescription(description);
            setFocusable(true);
            setOnClickListener(view -> action.run());
        }

        @Override public CharSequence getAccessibilityClassName() {
            return Button.class.getName();
        }
    }

    private final class TrackTile extends ActionLayout {
        private final String label;
        private final ImageView icon;
        private final TextView heading, value;

        TrackTile(int resource, String label, Runnable action) {
            super(label, action);
            this.label = label;
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(50));
            setPadding(dp(10), dp(7), dp(8), dp(7));
            setBackground(pressable(SURFACE, 12, 0));
            icon = glyph(resource);
            icon.setImageTintList(new ColorStateList(
                    new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                    new int[]{0xff777789, SLIDER}));
            LinearLayout.LayoutParams imageSize = new LinearLayout.LayoutParams(dp(18), dp(18));
            imageSize.setMarginEnd(dp(8));
            addView(icon, imageSize);

            LinearLayout copy = column();
            heading = trackText(label, 12);
            heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            heading.setTextColor(ICON_TINT);
            value = trackText("", 11);
            value.setTextColor(MUTED);
            copy.addView(heading);
            copy.addView(value);
            addView(copy, weight());
        }

        private TextView trackText(String label, int size) {
            TextView view = text(label);
            view.setTextSize(size);
            view.setSingleLine();
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return view;
        }

        void setValue(String current) {
            if (TextUtils.equals(value.getText(), current)) return;
            value.setText(current);
            setContentDescription(label + ", " + current);
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            icon.setEnabled(enabled);
            heading.setEnabled(enabled);
        }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams startCell() {
        LinearLayout.LayoutParams params = weight();
        params.setMarginEnd(dp(8));
        return params;
    }

    private void addSpaced(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(view, params);
    }

    private GradientDrawable surface(int color, int radius, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }

    private RippleDrawable pressable(int color, int radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(0x44b1aeff),
                surface(color, radius, stroke), null);
    }

    private void tintSlider(SeekBar slider) {
        ColorStateList colors = new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{0xff57576a, SLIDER});
        slider.setThumbTintList(colors);
        slider.setProgressTintList(colors);
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(OUTLINE));
        slider.setPadding(dp(8), 0, dp(8), 0);
        slider.setSplitTrack(false);
    }

    private Button button(String name, Runnable action) {
        Button button = new Button(this);
        button.setText(name);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setBackground(pressable(SURFACE, 12, OUTLINE));
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{0xff848498, TEXT}));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView timestamp() {
        TextView view = text("");
        view.setTextColor(MUTED);
        view.setTextSize(12);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private TextView text(String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(TEXT);
        view.setTextSize(16);
        return view;
    }
}
