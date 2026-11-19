package app.frameplayer.remote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import java.io.IOException;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Foreground preview transport. The activity owns this controller on the main thread. */
final class LivePreview implements AutoCloseable {
    interface Listener {
        void onFrame(RemoteClient client, String sourceId, Bitmap image, boolean scrubbing);
    }

    private static final int MAX_EDGE = 640;
    private static final int MAX_BASE64 = RemoteClient.MAX_JPEG_BYTES * 4 / 3;
    private static final long SCRUB_INTERVAL_MS = 140;
    private static final long LIVE_RETRY_MS = 1500;

    private record Request(RemoteClient client, String sourceId, boolean scrubbing,
                           double position, long revision, Socket socket) {}

    private record Frame(Request request, Bitmap image) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final AtomicBoolean deliveryPending = new AtomicBoolean();
    private final Runnable pump = this::startWork;
    private final Listener listener;

    private RemoteClient client;
    private String sourceId;
    private boolean scrubbing;
    private boolean closed;
    private volatile long revision;
    private long scrubAfter;
    private long retryAfter;
    private double queuedPosition = Double.NaN;
    private Request active;

    LivePreview(Listener listener) {
        this.listener = listener;
    }

    void play(RemoteClient player, String source) {
        if (closed || !validSource(player, source)) return;
        select(player, source, false);
        startWork();
    }

    void scrub(RemoteClient player, String source, double seconds) {
        if (closed || !validSource(player, source) || !Double.isFinite(seconds) || seconds < 0) return;
        select(player, source, true);
        queuedPosition = seconds;
        startWork();
    }

    void stop() {
        select(null, null, false);
        main.removeCallbacks(pump);
    }

    @Override public void close() {
        if (closed) return;
        stop();
        closed = true;
        worker.shutdown();
    }

    private static boolean validSource(RemoteClient player, String source) {
        return player != null && source != null && !source.isEmpty() && source.length() <= 128;
    }

    private void select(RemoteClient player, String source, boolean seek) {
        if (client == player && Objects.equals(sourceId, source) && scrubbing == seek) return;
        client = player;
        sourceId = source;
        scrubbing = seek;
        revision++;
        retryAfter = 0;
        queuedPosition = Double.NaN;
        if (active != null) {
            try {
                active.socket().close();
            } catch (IOException ignored) {
                // The worker still owns the request and its final close.
            }
        }
        discard(latest.getAndSet(null));
    }

    private void startWork() {
        main.removeCallbacks(pump);
        if (closed || client == null || active != null || (scrubbing && Double.isNaN(queuedPosition))) return;
        long now = SystemClock.uptimeMillis();
        long earliest = scrubbing ? scrubAfter : retryAfter;
        if (earliest > now) {
            main.postAtTime(pump, earliest);
            return;
        }
        Request request = new Request(client, sourceId, scrubbing, queuedPosition, revision, new Socket());
        active = request;
        queuedPosition = Double.NaN;
        if (request.scrubbing()) scrubAfter = now + SCRUB_INTERVAL_MS;
        worker.execute(() -> fetch(request));
    }

    private void fetch(Request request) {
        try (Socket socket = request.socket()) {
            if (request.scrubbing()) {
                String jpeg = request.client().previewAt(socket, request.sourceId(), request.position());
                if (jpeg != null && jpeg.length() <= MAX_BASE64) {
                    receive(request, Base64.decode(jpeg, Base64.NO_WRAP));
                }
            } else {
                request.client().previewLive(socket, request.sourceId(), jpeg -> receive(request, jpeg));
            }
        } catch (Exception ignored) {
            // Preview failures leave the control connection and still-image fallback available.
        } finally {
            main.post(() -> finished(request));
        }
    }

    private void finished(Request request) {
        if (request != active) return;
        active = null;
        if (request.revision() == revision && !request.scrubbing()) {
            retryAfter = SystemClock.uptimeMillis() + LIVE_RETRY_MS;
        }
        startWork();
    }

    private void receive(Request request, byte[] jpeg) {
        if (request.revision() != revision) return;
        Bitmap image = decode(jpeg);
        if (image != null) {
            discard(latest.getAndSet(new Frame(request, image)));
            scheduleDelivery();
        }
    }

    private void scheduleDelivery() {
        if (deliveryPending.compareAndSet(false, true)) main.post(this::deliver);
    }

    private void deliver() {
        Frame frame = latest.getAndSet(null);
        try {
            if (frame != null && !closed && frame.request().revision() == revision) {
                Request request = frame.request();
                listener.onFrame(request.client(), request.sourceId(), frame.image(), request.scrubbing());
            } else {
                discard(frame);
            }
        } finally {
            deliveryPending.set(false);
            if (latest.get() != null) scheduleDelivery();
        }
    }

    private static void discard(Frame frame) {
        if (frame != null) frame.image().recycle();
    }

    private static Bitmap decode(byte[] jpeg) {
        if (jpeg.length == 0 || jpeg.length > RemoteClient.MAX_JPEG_BYTES) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (!"image/jpeg".equals(options.outMimeType) || options.outWidth < 1 || options.outHeight < 1
                || options.outWidth > MAX_EDGE || options.outHeight > MAX_EDGE) return null;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
    }
}
