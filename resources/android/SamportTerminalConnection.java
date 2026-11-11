package com.nembestil.pos3.app;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import org.json.JSONObject;
import org.json.JSONArray;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Samport v1 uses the vendor CA over TLS; v2 uses signed plain HTTP. */
final class SamportTerminalConnection {
    // Worldline's public CA, not a terminal or integration secret.
    // https://docs.instore.worldline-solutions.com/img/samport/ECR-REST.zip
    // SHA-256: CC87E1C5FA3D4B41361EA45719DF32929287C19F01A2710474DB9EB5362D1B27
    private static SSLSocketFactory v1SocketFactory;

    private static synchronized SSLSocketFactory getV1SocketFactory(Context context) throws IOException {
        if (v1SocketFactory != null) return v1SocketFactory;
        try (InputStream certificate = context.getResources().openRawResource(R.raw.samport_terminal_ca_v1)) {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("samport-v1", CertificateFactory.getInstance("X.509")
                .generateCertificate(certificate));
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trustStore);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, managers.getTrustManagers(), null);
            v1SocketFactory = tls.getSocketFactory();
            return v1SocketFactory;
        } catch (Exception error) {
            throw new IOException("Could not load the Samport terminal CA.", error);
        }
    }

    static JSONObject probeIp(Context context, String ipAddress, JSONArray probes) throws Exception {
        if (ipAddress == null || !ipAddress.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            throw new IOException("Enter a valid IPv4 address.");
        }
        for (String octet : ipAddress.split("\\.")) {
            if (Integer.parseInt(octet) > 255) throw new IOException("Enter a valid IPv4 address.");
        }
        for (int index = 0; index < probes.length(); index++) {
            JSONObject probe = probes.getJSONObject(index);
            String path = probe.getString("path");
            boolean v1 = "/api/v1/DeviceInformation".equals(path);
            if (!v1 && !"/api/v2/DeviceInformation".equals(path)) {
                throw new IOException("Only terminal identity discovery is allowed.");
            }
            int port = probe.getInt("port");
            if (port != (v1 ? 443 : 8080)) throw new IOException("Invalid terminal discovery port.");
            HttpURLConnection connection = null;
            try {
                connection = open(context, ipAddress, port, path);
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setRequestMethod("GET");
                JSONObject headers = probe.getJSONObject("headers");
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!key.equalsIgnoreCase("Integration-Key") && !key.equalsIgnoreCase("Authorization")
                        && !key.equalsIgnoreCase("Accept") && !key.equalsIgnoreCase("User-Agent")
                        && !key.equalsIgnoreCase("x-terminalid")) throw new IOException("Invalid discovery header.");
                    connection.setRequestProperty(key, headers.getString(key));
                }
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > 128 * 1024) throw new IOException("Terminal identity response is too large.");
                        bytes.write(buffer, 0, count);
                    }
                }
                JSONObject information = new JSONObject(bytes.toString("UTF-8"));
                String terminalId = information.getJSONObject("terminal").getString("id");
                String version = connection.getHeaderField("Protocol-Version");
                if (terminalId.isEmpty() || version == null || !version.startsWith(v1 ? "1." : "2.")) continue;
                JSONObject device = new JSONObject();
                device.put("terminalId", terminalId);
                device.put("identity", terminalId);
                device.put("ipAddress", ipAddress);
                device.put("port", port);
                device.put("protocolType", "EcrRestApi");
                device.put("protocolVersion", version);
                return device;
            } catch (IOException error) {
                // The other API version may be listening on its own port.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new IOException("No supported payment terminal responded at this IP address.");
    }

    static HttpURLConnection open(Context context, String ipAddress, int port, String path) throws IOException {
        boolean v1 = path.startsWith("/api/v1/");
        if (!v1 && !path.startsWith("/api/v2/")) throw new IOException("Unsupported terminal API path.");
        URL url = new URL(v1 ? "https" : "http", ipAddress, port, path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        if (v1) {
            HttpsURLConnection tls = (HttpsURLConnection) connection;
            tls.setSSLSocketFactory(getV1SocketFactory(context));
            // Worldline certificates identify the terminal service, not its changing LAN IP.
            // Trust remains restricted to the vendor CA, for this connection only.
            tls.setHostnameVerifier((hostname, session) -> true);
        }
        return connection;
    }
}
