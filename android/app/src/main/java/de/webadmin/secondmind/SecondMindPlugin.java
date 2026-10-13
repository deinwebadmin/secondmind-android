package de.webadmin.secondmind;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONException;

/**
 * secondmind – native Schnittstelle für Android.
 *
 * 1) openAuth: Loopback-Server auf 127.0.0.1 (OAuth 2.0 "Installed App"-Flow)
 *    + Öffnen des System-Browsers. Der Google-Redirect landet lokal und der
 *    Auth-Code wird zurück an die Web-App gegeben.
 * 2) encrypt/decrypt: Tokenschlüssel im Android-Keystore (AES-256-GCM).
 * 3) openExternal: Links in der Standard-Browser-App öffnen.
 * 4) scanQr: Kamera-Scan für die Schlüsselübergabe (ZXing, offline).
 * 5) pairSend: Schlüsselübergabe über das WLAN – die WebView lädt die App von
 *    https://localhost und darf dort keinen http-Anfrage nachgehen (Mixed Content),
 *    deshalb läuft dieses Senden nativ.
 */
@CapacitorPlugin(
    name = "SecondMind",
    permissions = @Permission(alias = "camera", strings = { Manifest.permission.CAMERA })
)
public class SecondMindPlugin extends Plugin {

    private static final String KEY_ALIAS = "secondmind-secret";
    private static final int PAIR_TIMEOUT_MS = 2000;      // je Zieladresse (D.5 Stufe 3)
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

    /* ---------------- In-App-Update (APK) ---------------- */

    /**
     * Lädt eine neue APK in den Cache und öffnet den System-Installer.
     * Android verlangt bei Sideloading die Bestätigung durch den Nutzer –
     * „Apps aus unbekannten Quellen“ wird pro App erlaubt (einmalig).
     */
    @PluginMethod
    public void installApk(PluginCall call) {
        String url = call.getString("url");
        if (url == null || !url.startsWith("https://")) {
            call.reject("ungültige Download-URL");
            return;
        }

        if (!getContext().getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent perm = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(perm);
            } catch (Exception ignored) { /* Einstellungen nicht erreichbar */ }
            call.reject("permission-required");
            return;
        }

        final PluginCall theCall = call;
        new Thread(() -> {
            try {
                File out = new File(getContext().getCacheDir(), "secondmind-update.apk");
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(120000);
                con.setRequestProperty("User-Agent", "secondmind-updater");
                con.connect();
                try (InputStream in = new BufferedInputStream(con.getInputStream());
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                con.disconnect();
                if (out.length() < 1_000_000) {
                    throw new Exception("Datei unvollständig (" + out.length() + " Bytes)");
                }

                Uri uri = FileProvider.getUriForFile(getContext(),
                    getContext().getPackageName() + ".fileprovider", out);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                getActivity().startActivity(i);

                JSObject res = new JSObject();
                res.put("ok", true);
                res.put("file", out.getAbsolutePath());
                getActivity().runOnUiThread(() -> theCall.resolve(res));
            } catch (Exception e) {
                // Fallback: Download-URL im Browser öffnen – Chrome lädt die APK,
                // der Nutzer installiert sie aus den Benachrichtigungen/Downloads.
                try {
                    Intent b = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    b.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(b);
                    JSObject res = new JSObject();
                    res.put("ok", true);
                    res.put("fallback", "browser");
                    getActivity().runOnUiThread(() -> theCall.resolve(res));
                } catch (Exception e2) {
                    final String msg = e.getMessage();
                    getActivity().runOnUiThread(() -> theCall.reject("Download fehlgeschlagen: " + msg));
                }
            }
        }, "secondmind-update").start();
    }

    /* ---------------- QR-Scan (Schlüssel-Pairing, D.5) ---------------- */

    /**
     * Scannet einen QR-Code mit der Kamera und liefert dessen Text zurück.
     * Die Kamera läuft offline in der ZXing-Erfassungs-Activity; nichts wird
     * hochgeladen. Ohne Kamera-Recht wird es zuerst angefragt, danach scannt
     * die App weiter (Webcam-Fallback in pairing.js greift nur, wenn die
     * native Kamera nicht startbar ist).
     */
    @PluginMethod
    public void scanQr(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissionForAlias("camera", call, "scanQrPermission");
            return;
        }
        launchQrScanner(call);
    }

    @PermissionCallback
    private void scanQrPermission(PluginCall call) {
        // Recht wurde gewährt (sonst wäre der Call bereits abgelehnt worden)
        launchQrScanner(call);
    }

    private void launchQrScanner(PluginCall call) {
        try {
            IntentIntegrator integrator = new IntentIntegrator(getActivity());
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            integrator.setPrompt("QR-Code in den Rahmen halten");
            integrator.setBeepEnabled(false);
            integrator.setBarcodeImageEnabled(false);
            integrator.setOrientationLocked(true);
            startActivityForResult(call, integrator.createScanIntent(), "scanQrResult");
        } catch (Exception e) {
            call.reject("Kamera konnte nicht gestartet werden: " + e.getMessage());
        }
    }

    @ActivityCallback
    private void scanQrResult(PluginCall call, ActivityResult result) {
        IntentResult parsed = IntentIntegrator.parseActivityResult(result.getResultCode(), result.getData());
        if (parsed != null && parsed.getContents() != null) {
            JSObject res = new JSObject();
            res.put("value", parsed.getContents());
            call.resolve(res);
        } else {
            call.reject("abgebrochen");
        }
    }

    /* ---------------- Pairing über das WLAN (D.5 Stufe 3) ---------------- */

    /**
     * Schickt den verschlüsselten Schlüssel an das andere Gerät.
     * Alle IPv4-Kandidaten aus dem QR-Code werden der Reihe nach versucht,
     * je mit 2 s Verbindungs- und Antwort-Timeout. Der Körper ist nur
     * Chiffretext – das Session-Secret steht ausschließlich im QR-Code.
     */
    @PluginMethod
    public void pairSend(PluginCall call) {
        JSArray ips = call.getArray("ips");
        int port = call.getInt("port", 0);
        String body = call.getString("body");

        final List<String> ziele = new ArrayList<>();
        if (ips != null) {
            for (int i = 0; i < ips.length(); i++) {
                try {
                    String ip = ips.getString(i);
                    if (ip != null && ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) ziele.add(ip);
                } catch (JSONException ignored) {
                    // unbrauchbarer Eintrag wird übersprungen
                }
            }
        }
        if (ziele.isEmpty() || port <= 0 || body == null || body.isEmpty()) {
            call.reject("keine Zieladresse im QR-Code");
            return;
        }

        final PluginCall theCall = call;
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        new Thread(() -> {
            for (String ip : ziele) {
                HttpURLConnection con = null;
                try {
                    URL url = new URL("http://" + ip + ":" + port + "/");
                    con = (HttpURLConnection) url.openConnection();
                    con.setConnectTimeout(PAIR_TIMEOUT_MS);
                    con.setReadTimeout(PAIR_TIMEOUT_MS);
                    con.setRequestMethod("POST");
                    con.setDoOutput(true);
                    con.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                    con.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream os = con.getOutputStream()) { os.write(bytes); }
                    int code = con.getResponseCode();
                    if (code >= 200 && code < 300) {
                        JSObject ok = new JSObject();
                        ok.put("ok", true);
                        getActivity().runOnUiThread(() -> theCall.resolve(ok));
                        return;
                    }
                } catch (Exception ignored) {
                    // nächster Kandidat
                } finally {
                    if (con != null) con.disconnect();
                }
            }
            JSObject fehler = new JSObject();
            fehler.put("ok", false);
            fehler.put("error", "nicht_erreichbar");
            getActivity().runOnUiThread(() -> theCall.resolve(fehler));
        }, "secondmind-pair").start();
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
