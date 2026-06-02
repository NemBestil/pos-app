package com.nembestil.pos3.app;

import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CapacitorPlugin(name = "NetworkPrinter")
public class NetworkPrinterPlugin extends Plugin {

    // --- Scan settings ---------------------------------------------------
    private static final int MAX_CONCURRENT = 64;
    private static final int SCAN_REQUEST_TIMEOUT_MS = 2000;
    // Cap the scan to a /24 (254 hosts) around the device when the real subnet is larger.
    private static final int MAX_HOST_BITS = 8;
    private static final int PROGRESS_INTERVAL_MS = 1000;
    private static final int MAX_RESPONSE_BYTES = 8192;

    // --- Print / ping settings ------------------------------------------
    private static final int PRINTER_PORT = 9100;
    private static final int DEFAULT_PRINT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_PING_TIMEOUT_MS = 1500;

    private static final Pattern ETH_INFO_PATTERN = Pattern.compile(
        "Ethernet\\s+Information",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MAC_PATTERN = Pattern.compile(
        "Mac\\s*Address.*?>\\s*([0-9A-Fa-f]{2}(?:[-:][0-9A-Fa-f]{2}){5})",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IP_PATTERN = Pattern.compile(
        "IP\\s*Address.*?>\\s*(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    // Capacitor invokes @PluginMethod on a single background HandlerThread, so a
    // long print would block other plugin calls. Offload print/ping onto a cached
    // pool so they can run concurrently and never block the bridge thread.
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();

    // ========================================================================
    // Scan
    // ========================================================================

    @PluginMethod
    public void scan(PluginCall call) {
        if (!scanning.compareAndSet(false, true)) {
            call.reject("A scan is already running");
            return;
        }
        cancelRequested.set(false);
        Thread scanThread = new Thread(this::runScan, "NetworkPrinterScanner");
        scanThread.setDaemon(true);
        scanThread.start();
        call.resolve();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelRequested.set(true);
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("scanning", scanning.get());
        call.resolve(ret);
    }

    private void runScan() {
        List<FoundPrinter> printers = new ArrayList<>();
        int total = 0;
        try {
            List<String> ips = enumerateIps();
            total = ips.size();

            if (total == 0) {
                emitProgress(0, 0, 0);
                emitComplete(printers, 0, 0);
                return;
            }

            ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENT);
            AtomicInteger scanned = new AtomicInteger(0);

            for (final String ip : ips) {
                pool.submit(() -> {
                    if (!cancelRequested.get()) {
                        FoundPrinter found = probe(ip);
                        if (found != null) {
                            synchronized (printers) {
                                printers.add(found);
                            }
                        }
                    }
                    scanned.incrementAndGet();
                });
            }

            pool.shutdown();
            while (!pool.isTerminated()) {
                if (cancelRequested.get()) {
                    pool.shutdownNow();
                    break;
                }
                int foundCount;
                synchronized (printers) {
                    foundCount = printers.size();
                }
                emitProgress(scanned.get(), total, foundCount);
                try {
                    if (pool.awaitTermination(PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pool.shutdownNow();
                    break;
                }
            }

            int finalScanned = scanned.get();
            List<FoundPrinter> snapshot;
            synchronized (printers) {
                snapshot = new ArrayList<>(printers);
            }
            emitProgress(finalScanned, total, snapshot.size());
            emitComplete(snapshot, finalScanned, total);
        } catch (Exception e) {
            JSObject data = new JSObject();
            data.put("message", e.getMessage() == null ? "Scan failed" : e.getMessage());
            notifyListeners("scanError", data);
        } finally {
            scanning.set(false);
            cancelRequested.set(false);
        }
    }

    private void emitProgress(int scanned, int total, int found) {
        JSObject data = new JSObject();
        data.put("scanned", scanned);
        data.put("total", total);
        data.put("percent", total > 0 ? (int) ((scanned * 100L) / total) : 0);
        data.put("found", found);
        notifyListeners("scanProgress", data);
    }

    private void emitComplete(List<FoundPrinter> printers, int scanned, int total) {
        JSObject data = new JSObject();
        JSONArray arr = new JSONArray();
        for (FoundPrinter p : printers) {
            JSObject o = new JSObject();
            o.put("ip", p.ip);
            o.put("mac", p.mac);
            arr.put(o);
        }
        data.put("printers", arr);
        data.put("scanned", scanned);
        data.put("total", total);
        notifyListeners("scanComplete", data);
    }

    private FoundPrinter probe(String ip) {
        // The XPrinter web UI speaks HTTP/0.9: it accepts a bare "GET <path>\r\n"
        // request line (no version, no headers) and replies with the raw HTML body,
        // no status line and no headers. Java's HttpURLConnection cannot parse that,
        // so we use a raw socket and read until close.
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, 80), SCAN_REQUEST_TIMEOUT_MS);
            socket.setSoTimeout(SCAN_REQUEST_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            out.write("GET /ip_info.htm\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream is = socket.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[1024];
            int total = 0;
            int n;
            while ((n = is.read(tmp)) > 0) {
                int allowed = Math.min(n, MAX_RESPONSE_BYTES - total);
                if (allowed > 0) {
                    buf.write(tmp, 0, allowed);
                    total += allowed;
                }
                if (total >= MAX_RESPONSE_BYTES) {
                    break;
                }
            }

            // If the device happened to reply with HTTP/1.x (status line + headers),
            // strip the prologue so the regex patterns see clean markup.
            String body = stripHttpPrologue(buf.toString("ISO-8859-1"));

            if (!ETH_INFO_PATTERN.matcher(body).find()) {
                return null;
            }
            Matcher macMatch = MAC_PATTERN.matcher(body);
            Matcher ipMatch = IP_PATTERN.matcher(body);
            if (!macMatch.find() || !ipMatch.find()) {
                return null;
            }

            FoundPrinter found = new FoundPrinter();
            found.ip = ip;
            found.mac = macMatch.group(1).toUpperCase().replace(':', '-');
            return found;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String stripHttpPrologue(String response) {
        if (!response.startsWith("HTTP/")) {
            return response;
        }
        int idx = response.indexOf("\r\n\r\n");
        if (idx >= 0) {
            return response.substring(idx + 4);
        }
        idx = response.indexOf("\n\n");
        if (idx >= 0) {
            return response.substring(idx + 2);
        }
        return response;
    }

    private List<String> enumerateIps() throws Exception {
        List<String> result = new ArrayList<>();
        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        if (ifaces == null) {
            return result;
        }
        while (ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();
            if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                continue;
            }
            for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
                InetAddress addr = ia.getAddress();
                if (!(addr instanceof Inet4Address)) {
                    continue;
                }
                short prefix = ia.getNetworkPrefixLength();
                if (prefix < 8 || prefix > 30) {
                    continue;
                }

                byte[] ipBytes = addr.getAddress();
                int ipInt = ((ipBytes[0] & 0xff) << 24)
                    | ((ipBytes[1] & 0xff) << 16)
                    | ((ipBytes[2] & 0xff) << 8)
                    | (ipBytes[3] & 0xff);
                int hostBits = Math.min(32 - prefix, MAX_HOST_BITS);
                int mask = hostBits >= 32 ? 0 : (~0 << hostBits);
                int network = ipInt & mask;
                int broadcast = network | (~mask);

                String deviceIp = addr.getHostAddress();

                for (int i = network + 1; i != broadcast; i++) {
                    String candidate = ((i >> 24) & 0xff) + "."
                        + ((i >> 16) & 0xff) + "."
                        + ((i >> 8) & 0xff) + "."
                        + (i & 0xff);
                    if (!candidate.equals(deviceIp)) {
                        result.add(candidate);
                    }
                }
                return result;
            }
        }
        return result;
    }

    private static class FoundPrinter {
        String ip;
        String mac;
    }

    // ========================================================================
    // Print
    // ========================================================================

    @PluginMethod
    public void print(PluginCall call) {
        final String ip = call.getString("ip");
        final String dataBase64 = call.getString("data");
        if (ip == null || ip.isEmpty()) {
            call.reject("Missing 'ip'");
            return;
        }
        if (dataBase64 == null) {
            call.reject("Missing 'data'");
            return;
        }
        final int timeoutMs = call.getInt("timeoutMs", DEFAULT_PRINT_TIMEOUT_MS);

        final byte[] payload;
        try {
            payload = Base64.decode(dataBase64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("Invalid base64 in 'data'");
            return;
        }

        ioExecutor.submit(() -> doPrint(ip, payload, timeoutMs, call));
    }

    private void doPrint(String ip, byte[] payload, int timeoutMs, PluginCall call) {
        long started = System.currentTimeMillis();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, PRINTER_PORT), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            // On close(), block until all buffered bytes are ACKed (or the timeout
            // expires). This is what makes the resolved result a meaningful
            // "delivery confirmed" signal.
            int lingerSeconds = Math.max(1, (timeoutMs + 999) / 1000);
            socket.setSoLinger(true, lingerSeconds);

            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
            // Send FIN so the printer knows the job is complete and can close
            // its side. After this, read until EOF to confirm receipt.
            socket.shutdownOutput();

            InputStream is = socket.getInputStream();
            byte[] sink = new byte[256];
            try {
                while (is.read(sink) > 0) {
                    // Most ESC/POS printers send nothing back; drain anything they do.
                }
            } catch (Exception ignored) {
                // Read timeout is acceptable — the bytes are already out the door.
            }

            JSObject result = new JSObject();
            result.put("success", true);
            result.put("bytesSent", payload.length);
            result.put("durationMs", System.currentTimeMillis() - started);
            call.resolve(result);
        } catch (Exception e) {
            JSObject result = new JSObject();
            result.put("success", false);
            result.put("bytesSent", 0);
            result.put("durationMs", System.currentTimeMillis() - started);
            result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            call.resolve(result);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ========================================================================
    // Ping
    // ========================================================================

    @PluginMethod
    public void ping(PluginCall call) {
        final String ip = call.getString("ip");
        if (ip == null || ip.isEmpty()) {
            call.reject("Missing 'ip'");
            return;
        }
        final int timeoutMs = call.getInt("timeoutMs", DEFAULT_PING_TIMEOUT_MS);

        ioExecutor.submit(() -> doPing(ip, timeoutMs, call));
    }

    private void doPing(String ip, int timeoutMs, PluginCall call) {
        long started = System.currentTimeMillis();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, PRINTER_PORT), timeoutMs);
            long latency = System.currentTimeMillis() - started;
            socket.close();

            String mac = null;
            String reportedIp = null;
            Socket httpSocket = new Socket();
            try {
                httpSocket.connect(new InetSocketAddress(ip, 80), timeoutMs);
                httpSocket.setSoTimeout(timeoutMs);
                OutputStream out = httpSocket.getOutputStream();
                out.write("GET /ip_info.htm\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();

                InputStream is = httpSocket.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[1024];
                int total = 0;
                int n;
                while ((n = is.read(tmp)) > 0) {
                    int allowed = Math.min(n, MAX_RESPONSE_BYTES - total);
                    if (allowed > 0) {
                        buf.write(tmp, 0, allowed);
                        total += allowed;
                    }
                    if (total >= MAX_RESPONSE_BYTES) break;
                }
                String body = stripHttpPrologue(buf.toString("ISO-8859-1"));
                Matcher macMatch = MAC_PATTERN.matcher(body);
                Matcher ipMatch = IP_PATTERN.matcher(body);
                if (macMatch.find()) mac = macMatch.group(1).toUpperCase().replace(':', '-');
                if (ipMatch.find()) reportedIp = ipMatch.group(1);
            } catch (Exception ignored) {
                // metadata is optional; online is already confirmed
            } finally {
                try { httpSocket.close(); } catch (Exception ignored) {}
            }

            JSObject result = new JSObject();
            result.put("online", true);
            result.put("latencyMs", latency);
            if (mac != null) result.put("mac", mac);
            if (reportedIp != null) result.put("ip", reportedIp);
            call.resolve(result);
        } catch (Exception e) {
            JSObject result = new JSObject();
            result.put("online", false);
            result.put("latencyMs", System.currentTimeMillis() - started);
            call.resolve(result);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
