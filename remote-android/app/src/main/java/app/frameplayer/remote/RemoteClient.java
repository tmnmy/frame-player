package app.frameplayer.remote;

import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class RemoteClient {
    static final int MAX_JPEG_BYTES = 45_000;
    private static final int LIVE_MAGIC = 0x46504c31; // FPL1
    private static final int REQUEST_TIMEOUT_MS = 3_000;
    private static final int LIVE_TIMEOUT_MS = 5_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final InetSocketAddress endpoint;
    private final String fingerprint;
    private final String token;
    private final SSLContext tls;

    RemoteClient(InetSocketAddress endpoint, String fingerprint, String savedToken) throws Exception {
        if (!isHexSecret(fingerprint)) {
            throw new IllegalArgumentException("Invalid player fingerprint");
        }
        this.endpoint = endpoint;
        this.fingerprint = fingerprint;
        if (savedToken == null) {
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            token = hex(random);
        } else {
            if (!isHexSecret(savedToken)) throw new IllegalArgumentException("Invalid pairing token");
            token = savedToken;
        }

        // Pairing compares the code derived from this exact certificate on both screens.
        // All requests on later connections require the same certificate.
        TrustManager[] trust = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type)
                    throws CertificateException {
                throw new CertificateException("Unexpected client certificate");
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String type)
                    throws CertificateException {
                try {
                    if (chain.length != 1 || !RemoteClient.this.fingerprint.equals(
                            hex(sha256(chain[0].getEncoded())))) {
                        throw new CertificateException("The player certificate changed");
                    }
                    chain[0].checkValidity();
                } catch (Exception error) {
                    throw new CertificateException(error);
                }
            }

            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        tls = SSLContext.getInstance("TLS");
        tls.init(null, trust, null);
    }

    static boolean isHexSecret(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    String fingerprint() {
        return fingerprint;
    }

    String token() {
        return token;
    }

    String code() throws Exception {
        byte[] hash = sha256((fingerprint + ":" + token).getBytes(StandardCharsets.UTF_8));
        String hexCode = hex(hash).substring(0, 16).toUpperCase(Locale.ROOT);
        return String.join(" ", hexCode.substring(0, 4), hexCode.substring(4, 8),
                hexCode.substring(8, 12), hexCode.substring(12, 16));
    }

    JSONObject pair(String name) throws Exception {
        return send(new JSONObject().put("action", "pair").put("name", name).put("nonce", token));
    }

    JSONObject status(boolean wantPreview) throws Exception {
        return send(new JSONObject().put("action", "status").put("token", token)
                .put("want_preview", wantPreview));
    }

    String preview(String id) throws Exception {
        JSONObject result = send(new JSONObject().put("action", "preview").put("token", token).put("id", id));
        return result.optBoolean("ok") && result.opt("jpeg") instanceof String jpeg ? jpeg : null;
    }

    JSONObject command(String action, Object value) throws Exception {
        JSONObject command = new JSONObject().put("action", action);
        if (value != null) command.put("value", value);
        return send(new JSONObject().put("action", "command").put("token", token).put("command", command));
    }

    String previewAt(Socket socket, String sourceId, double seconds) throws Exception {
        JSONObject response = send(socket, new JSONObject().put("action", "preview_at")
                .put("token", token).put("source_id", sourceId).put("position", seconds));
        return response.optBoolean("ok") && response.opt("jpeg") instanceof String jpeg ? jpeg : null;
    }

    void previewLive(Socket socket, String sourceId, Consumer<byte[]> frames) throws Exception {
        JSONObject request = new JSONObject().put("action", "preview_live")
                .put("token", token).put("source_id", sourceId);
        try (SSLSocket stream = connect(socket, LIVE_TIMEOUT_MS)) {
            writeRequest(stream, request, LIVE_TIMEOUT_MS);
            DataInputStream input = new DataInputStream(new BufferedInputStream(stream.getInputStream()));
            if (input.readInt() != LIVE_MAGIC) throw new IOException("Unexpected preview stream");
            while (!socket.isClosed()) {
                int length = input.readInt();
                if (length == 0) continue;
                if (length < 0 || length > MAX_JPEG_BYTES) throw new IOException("Invalid preview frame size");
                byte[] jpeg = new byte[length];
                input.readFully(jpeg);
                frames.accept(jpeg);
            }
        }
    }

    private JSONObject send(JSONObject request) throws Exception {
        try (Socket socket = new Socket()) {
            return send(socket, request);
        }
    }

    private JSONObject send(Socket socket, JSONObject request) throws Exception {
        try (SSLSocket stream = connect(socket, REQUEST_TIMEOUT_MS)) {
            writeRequest(stream, request, REQUEST_TIMEOUT_MS);
            InputStream input = new BufferedInputStream(stream.getInputStream());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < 65536; i++) {
                int next = input.read();
                if (next == '\n') {
                    return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                }
                if (next == -1) break;
                bytes.write(next);
            }
            throw new IOException("Incomplete response from the player");
        }
    }

    private SSLSocket connect(Socket socket, int timeout) throws IOException {
        socket.connect(endpoint, 2000);
        socket.setSoTimeout(timeout);
        return (SSLSocket) tls.getSocketFactory().createSocket(
                socket, endpoint.getHostString(), endpoint.getPort(), false);
    }

    private static void writeRequest(SSLSocket stream, JSONObject request, int timeout) throws IOException {
        stream.setSoTimeout(timeout);
        stream.startHandshake();
        stream.getOutputStream().write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        stream.getOutputStream().flush();
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String hex(byte[] data) {
        StringBuilder result = new StringBuilder(data.length * 2);
        for (byte value : data) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}