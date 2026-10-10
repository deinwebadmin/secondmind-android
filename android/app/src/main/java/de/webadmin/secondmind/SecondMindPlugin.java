package de.webadmin.secondmind;

import android.content.Intent;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * secondmind – native Schnittstelle für Android.
 *
 * 1) openAuth: Loopback-Server auf 127.0.0.1 (OAuth 2.0 "Installed App"-Flow)
 *    + Öffnen des System-Browsers. Der Google-Redirect landet lokal und der
 *    Auth-Code wird zurück an die Web-App gegeben.
 * 2) encrypt/decrypt: Tokenschlüssel im Android-Keystore (AES-256-GCM).
 * 3) openExternal: Links in der Standard-Browser-App öffnen.
 */
@CapacitorPlugin(name = "SecondMind")
public class SecondMindPlugin extends Plugin {

    private static final String KEY_ALIAS = "secondmind-secret";
    private static final String HTML_OK =
        "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<body style=\"font-family:system-ui,sans-serif;display:grid;place-items:center;height:100vh;margin:0;background:#f2f2f7;color:#111\">"
        + "<div style=\"text-align:center;padding:24px\"><div style=\"font-size:44px\">%s</div>"
        + "<h1 style=\"font-size:22px;margin:12px 0\">%s</h1>"
        + "<p style=\"color:#555;margin:0\">Du kannst dieses Fenster schließen und zu secondmind zurückkehren.</p></div></body>";

    private ServerSocket serverSocket;
    private PluginCall pendingCall;
    private String redirectUri = "";
    private String expectedState = "";
    private final Object lock = new Object();

    /* ---------------- OAuth-Loopback ---------------- */

    @PluginMethod
    public void openAuth(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("Keine Auth-URL übergeben");
            return;
        }

        try {
            ensureServer();
        } catch (Exception e) {
            call.reject("Lokaler Server konnte nicht gestartet werden: " + e.getMessage());
            return;
        }

        int port = serverSocket.getLocalPort();
        redirectUri = "http://127.0.0.1:" + port;

        String finalUrl = replaceRedirectUri(url, redirectUri);
        expectedState = queryParam(finalUrl, "state");

        synchronized (lock) {
            if (pendingCall != null) {
                JSObject old = new JSObject();
                old.put("error", "abgebrochen");
                pendingCall.resolve(old);
            }
            pendingCall = call;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            synchronized (lock) { pendingCall = null; }
            call.reject("Browser konnte nicht geöffnet werden: " + e.getMessage());
        }
    }

    private void ensureServer() throws Exception {
        if (serverSocket != null && !serverSocket.isClosed()) return;
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::acceptLoop, "secondmind-oauth");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try (Socket socket = serverSocket.accept()) {
                handleRequest(socket);
            } catch (Exception ignored) {
                // nächste Verbindung abwarten
            }
        }
    }

    private void handleRequest(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        if (requestLine == null) return;

        // Header konsumieren
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) { /* leer */ }

        String[] parts = requestLine.split(" ");
        String target = parts.length > 1 ? parts[1] : "/";
        String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";

        Map<String, String> params = new HashMap<>();
        for (String p : query.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) {
                params.put(URLDecoder.decode(p.substring(0, i), "UTF-8"),
                            URLDecoder.decode(p.substring(i + 1), "UTF-8"));
            }
        }

        String code = params.get("code");
        String error = params.get("error");
        String state = params.get("state");

        boolean isCallback = code != null || error != null;
        String body = isCallback
            ? String.format(HTML_OK, error != null ? "⚠️" : "✅",
                error != null ? "Anmeldung abgebrochen" : "Verbunden")
            : "<!doctype html><meta charset=\"utf-8\"><body style=\"font-family:system-ui;padding:24px\">"
              + "Öffne secondmind und führe die Anmeldung dort aus.</body>";

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: text/html; charset=utf-8\r\n"
            + "Content-Length: " + bytes.length + "\r\n"
            + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();

        if (!isCallback) return;

        JSObject res = new JSObject();
        if (error != null) {
            res.put("error", error);
        } else {
            boolean stateOk = expectedState.isEmpty() || expectedState.equals(state);
            if (!stateOk) res.put("error", "state-Abgleich fehlgeschlagen");
            else res.put("code", code);
        }
        res.put("redirectUri", redirectUri);

        PluginCall call;
        synchronized (lock) {
            call = pendingCall;
            pendingCall = null;
        }
        if (call != null) call.resolve(res);
    }

    /* ---------------- Token-Speicher (Keystore) ---------------- */

    @PluginMethod
    public void encrypt(PluginCall call) {
        String value = call.getString("value");
        if (value == null) { call.reject("Kein Wert"); return; }
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            byte[] joined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, joined, 0, iv.length);
            System.arraycopy(ct, 0, joined, iv.length, ct.length);

            JSObject res = new JSObject();
            res.put("value", Base64.encodeToString(joined, Base64.NO_WRAP));
            call.resolve(res);
        } catch (Exception e) {
            call.reject("Verschlüsselung fehlgeschlagen: " + e.getMessage());
        }
    }

    @PluginMethod
    public void decrypt(PluginCall call) {
        String value = call.getString("value");
        if (value == null) { call.reject("Kein Wert"); return; }
        try {
            SecretKey key = getOrCreateKey();
            byte[] joined = Base64.decode(value, Base64.NO_WRAP);
            if (joined.length < 13) { call.reject("Ungültige Daten"); return; }

            byte[] iv = new byte[12];
            byte[] ct = new byte[joined.length - 12];
            System.arraycopy(joined, 0, iv, 0, 12);
            System.arraycopy(joined, 12, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(ct);

            JSObject res = new JSObject();
            res.put("value", new String(plain, StandardCharsets.UTF_8));
            call.resolve(res);
        } catch (Exception e) {
            call.reject("Entschlüsselung fehlgeschlagen: " + e.getMessage());
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return kg.generateKey();
    }

    /* ---------------- Externe Links ---------------- */

    @PluginMethod
    public void openExternal(PluginCall call) {
        String url = call.getString("url");
        if (url != null && url.matches("^https?://.*")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch (Exception ignored) { /* keine Browser-App vorhanden */ }
        }
        call.resolve();
    }

    /* ---------------- Hilfen ---------------- */

    /** Platzhalter-Redirect der Web-App durch die echte Loopback-URI ersetzen. */
    private static String replaceRedirectUri(String url, String redirectUri) {
        try {
            Uri u = Uri.parse(url);
            StringBuilder sb = new StringBuilder();
            sb.append(u.getScheme()).append("://").append(u.getAuthority())
              .append(u.getPath() == null ? "" : u.getPath())
              .append('?');

            boolean first = true;
            for (String name : u.getQueryParameterNames()) {
                if ("redirect_uri".equals(name)) continue;
                for (String v : u.getQueryParameters(name)) {
                    if (!first) sb.append('&');
                    first = false;
                    sb.append(Uri.encode(name)).append('=').append(Uri.encode(v));
                }
            }
            if (!first) sb.append('&');
            sb.append("redirect_uri=").append(Uri.encode(redirectUri));
            return sb.toString();
        } catch (Exception e) {
            return url;
        }
    }

    private static String queryParam(String url, String name) {
        try {
            Uri u = Uri.parse(url);
            return u.getQueryParameter(name) == null ? "" : u.getQueryParameter(name);
        } catch (Exception e) {
            return "";
        }
    }
}
