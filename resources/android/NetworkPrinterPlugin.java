package com.nembestil.pos3.app;

import android.util.Base64;
import android.net.Network;
import android.os.SystemClock;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
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
    private static final int DEFAULT_ESCPOS_PROBE_TIMEOUT_MS = 2000;

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
    private final AtomicBoolean repairing = new AtomicBoolean(false);
    private final AtomicBoolean repairCancelRequested = new AtomicBoolean(false);
    private final Map<String, UdpCandidate> repairCandidates = new LinkedHashMap<>();
    private volatile XprinterDiscovery scanDiscovery;
    private volatile XprinterDiscovery repairDiscovery;

    private static final class UdpCandidate {
        final XprinterDiscovery.Printer printer;
        final Network network;
        UdpCandidate(XprinterDiscovery.Printer printer, Network network) {
            this.printer = printer;
            this.network = network;
        }
    }

    private static final class ScanState {
        final String id;
        final boolean includeUdp;
        final Map<String, FoundPrinter> normal = new LinkedHashMap<>();
        final Map<String, UdpCandidate> udp = new LinkedHashMap<>();
        final AtomicBoolean udpFinished = new AtomicBoolean(true);
        volatile boolean udpUnavailable;
        XprinterDiscovery discovery;
        ScanState(String id, boolean includeUdp) { this.id = id; this.includeUdp = includeUdp; }
    }

    // Capacitor invokes @PluginMethod on a single background HandlerThread, so a
    // long print would block other plugin calls. Offload print/ping onto a cached
    // pool so they can run concurrently and never block the bridge thread.
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();

    // ========================================================================
    // Scan
    // ========================================================================

    @PluginMethod
    public void scan(PluginCall call) {
        if (repairing.get() || !scanning.compareAndSet(false, true)) {
            call.reject("A scan is already running");
            return;
        }
        cancelRequested.set(false);
        // Explicit opt-in preserves the exact legacy scan for older hosted POS.
        ScanState state = new ScanState(call.getString("scanId", UUID.randomUUID().toString()),
            call.getBoolean("includeXprinterDiscovery", false));
        Thread scanThread = new Thread(() -> runScan(state), "NetworkPrinterScanner");
        scanThread.setDaemon(true);
        scanThread.start();
        call.resolve();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelRequested.set(true);
        repairCancelRequested.set(true);
        XprinterDiscovery scan = scanDiscovery;
        if (scan != null) scan.close();
        XprinterDiscovery repair = repairDiscovery;
        if (repair != null) repair.close();
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("scanning", scanning.get());
        ret.put("supportsManualSetup", true);
        ret.put("supportsXprinterRepair", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void networkInfo(PluginCall call) {
        ioExecutor.submit(() -> {
            JSObject result = new JSObject();
            try {
                Inet4Address address = findPrivateLanAddress();
                if (address != null) {
                    String ip = address.getHostAddress();
                    int lastDot = ip.lastIndexOf('.');
                    result.put("ip", ip);
                    result.put("suggestedIpPrefix", ip.substring(0, lastDot + 1));
                }
            } catch (Exception ignored) {
            }
            call.resolve(result);
        });
    }

    @PluginMethod
    public void probeEscPos(PluginCall call) {
        final String ip = call.getString("ip");
        if (ip == null || ip.trim().isEmpty()) {
            call.reject("Missing 'ip'");
            return;
        }
        final int timeoutMs = call.getInt("timeoutMs", DEFAULT_ESCPOS_PROBE_TIMEOUT_MS);

        ioExecutor.submit(() -> runEscPosProbe(ip.trim(), timeoutMs, call));
    }

    private void runEscPosProbe(String ip, int timeoutMs, PluginCall call) {
        long started = System.currentTimeMillis();
        boolean portOpen = false;
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, PRINTER_PORT), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            portOpen = true;

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[] {
                0x10, 0x04, 0x01,
                0x10, 0x04, 0x02,
                0x10, 0x04, 0x03,
                0x10, 0x04, 0x04
            });
            out.flush();

            int[] statuses = new int[4];
            for (int index = 0; index < statuses.length; index++) {
                int statusByte = in.read();
                if (statusByte < 0 || !isValidEscPosStatusByte(statusByte)) {
                    throw new IllegalStateException("Device did not return valid ESC/POS status bytes");
                }
                statuses[index] = statusByte;
            }

            NetworkMetadata metadata = queryNetworkMetadata(ip, timeoutMs);
            JSObject result = new JSObject();
            JSONArray statusBytes = new JSONArray();
            for (int statusByte : statuses) {
                statusBytes.put(statusByte);
            }
            result.put("success", true);
            result.put("tcpPortOpen", true);
            result.put("statusBytes", statusBytes);
            result.put("online", (statuses[0] & 0x08) == 0);
            result.put("coverOpen", (statuses[1] & 0x04) != 0);
            result.put("paperNearEnd", (statuses[3] & 0x0c) == 0x0c);
            result.put("paperOut", (statuses[3] & 0x60) == 0x60);
            result.put("hasError", (statuses[1] & 0x40) != 0 || (statuses[2] & 0x6c) != 0);
            result.put("durationMs", System.currentTimeMillis() - started);
            if (metadata != null && metadata.mac != null) {
                result.put("mac", metadata.mac);
            }
            call.resolve(result);
        } catch (Exception e) {
            JSObject result = new JSObject();
            result.put("success", false);
            result.put("tcpPortOpen", portOpen);
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

    private static boolean isValidEscPosStatusByte(int statusByte) {
        // ESC/POS DLE EOT status bytes use the fixed pattern 0xx1xx10b.
        return (statusByte & 0x93) == 0x12;
    }

    private void runScan(ScanState state) {
        ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENT);
        AtomicInteger scanned = new AtomicInteger();
        int total = 0;
        JSObject complete = null;
        JSObject failure = null;
        try {
            synchronized (repairCandidates) { repairCandidates.clear(); }
            if (state.includeUdp) startUdpDiscovery(state);
            List<String> ips = enumerateIps();
            total = ips.size();
            for (final String ip : ips) {
                pool.submit(() -> {
                    try {
                        if (!cancelRequested.get()) {
                            FoundPrinter found = probe(ip);
                            if (found != null && !cancelRequested.get()) {
                                synchronized (state) { state.normal.put(XprinterDiscovery.normalizeMac(found.mac), found); }
                            }
                        }
                    } finally { scanned.incrementAndGet(); }
                });
            }
            pool.shutdown();
            long nextProgress = 0;
            while (!pool.isTerminated() || !state.udpFinished.get()) {
                if (cancelRequested.get()) { pool.shutdownNow(); break; }
                if (SystemClock.elapsedRealtime() >= nextProgress) {
                    emitScanProgress(state, scanned.get(), total);
                    nextProgress = SystemClock.elapsedRealtime() + PROGRESS_INTERVAL_MS;
                }
                pool.awaitTermination(200, TimeUnit.MILLISECONDS);
                if (pool.isTerminated() && !state.udpFinished.get()) Thread.sleep(100);
            }
            if (cancelRequested.get()) {
                synchronized (state) { state.udp.clear(); }
            } else {
                synchronized (state) {
                    synchronized (repairCandidates) {
                        for (Map.Entry<String, UdpCandidate> entry : state.udp.entrySet()) {
                            if (!state.normal.containsKey(entry.getKey())) repairCandidates.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
            emitScanProgress(state, scanned.get(), total);
            complete = scanResults(state);
            complete.put("scanned", scanned.get());
            complete.put("total", total);
            complete.put("cancelled", cancelRequested.get());
        } catch (Exception error) {
            failure = new JSObject();
            failure.put("scanId", state.id);
            failure.put("message", error.getMessage() == null ? "Scan failed" : error.getMessage());
        } finally {
            pool.shutdownNow();
            if (state.discovery != null) state.discovery.close();
            if (scanDiscovery == state.discovery) scanDiscovery = null;
            scanning.set(false);
        }
        if (complete != null) notifyListeners("scanComplete", complete);
        else if (failure != null) notifyListeners("scanError", failure);
    }

    private void startUdpDiscovery(ScanState state) {
        try {
            Network network = XprinterDiscovery.findLanNetwork(getContext());
            XprinterDiscovery discovery = new XprinterDiscovery(getContext(), network);
            state.discovery = discovery;
            scanDiscovery = discovery;
            discovery.find(); // Broadcast before starting the ordinary IP sweep.
            state.udpFinished.set(false);
            ioExecutor.submit(() -> {
                long deadline = SystemClock.elapsedRealtime() + 5000;
                long nextSend = SystemClock.elapsedRealtime() + 1000;
                try {
                    while (!cancelRequested.get() && SystemClock.elapsedRealtime() < deadline) {
                        if (SystemClock.elapsedRealtime() >= nextSend) {
                            discovery.find();
                            nextSend = SystemClock.elapsedRealtime() + 1000;
                        }
                        XprinterDiscovery.Printer printer = discovery.receive();
                        if (printer != null) {
                            synchronized (state) {
                                if (!cancelRequested.get()) state.udp.put(printer.mac, new UdpCandidate(printer, network));
                            }
                        }
                    }
                } catch (Exception error) {
                    if (!cancelRequested.get()) state.udpUnavailable = true;
                } finally {
                    discovery.close();
                    state.udpFinished.set(true);
                }
            });
        } catch (Exception error) {
            // UDP failure must never break the existing network scan.
            state.udpUnavailable = true;
        }
    }

    private JSObject scanResults(ScanState state) {
        JSObject result = new JSObject();
        JSONArray normal = new JSONArray();
        JSONArray udp = new JSONArray();
        synchronized (state) {
            for (FoundPrinter printer : state.normal.values()) normal.put(printerJson(printer.ip, printer.mac));
            for (Map.Entry<String, UdpCandidate> entry : state.udp.entrySet()) {
                if (state.normal.containsKey(entry.getKey())) continue;
                XprinterDiscovery.Printer printer = entry.getValue().printer;
                JSObject candidate = printerJson(printer.ip, printer.mac);
                candidate.put("printPort", printer.port);
                candidate.put("reportedDhcp", printer.reportedDhcp);
                udp.put(candidate);
            }
        }
        result.put("scanId", state.id);
        result.put("printers", normal); // Legacy contract: reachable ordinary results only.
        result.put("udpPrinters", udp);
        result.put("udpUnavailable", state.udpUnavailable);
        return result;
    }

    private static JSObject printerJson(String ip, String mac) {
        JSObject printer = new JSObject();
        printer.put("ip", ip);
        printer.put("mac", mac);
        return printer;
    }

    private void emitScanProgress(ScanState state, int scanned, int total) {
        JSObject results = scanResults(state);
        JSObject progress = new JSObject();
        progress.put("scanId", state.id);
        progress.put("scanned", scanned);
        progress.put("total", total);
        progress.put("percent", total > 0 ? (int) ((scanned * 100L) / total) : 0);
        progress.put("found", results.optJSONArray("printers").length() + results.optJSONArray("udpPrinters").length());
        notifyListeners("scanProgress", progress);
        if (state.includeUdp) notifyListeners("scanResults", results);
    }

    @PluginMethod
    public void repairXprinter(PluginCall call) {
        String mac = XprinterDiscovery.normalizeMac(call.getString("mac"));
        if (!call.getBoolean("macConfirmed", false)) {
            call.reject("Confirm the printer MAC address first", "MAC_NOT_CONFIRMED");
            return;
        }
        UdpCandidate candidate;
        synchronized (repairCandidates) { candidate = repairCandidates.get(mac); }
        if (candidate == null) {
            call.reject("Scan again to find this printer", "XPRINTER_NOT_FOUND");
            return;
        }
        if (scanning.get() || !repairing.compareAndSet(false, true)) {
            call.reject("Another scan or repair is running", "XPRINTER_BUSY");
            return;
        }
        repairCancelRequested.set(false);
        // Consume this scan's candidate. A failed/uncertain repair needs a new
        // full scan, rather than another write from the same cached result.
        synchronized (repairCandidates) { repairCandidates.remove(mac); }
        ioExecutor.submit(() -> {
            try (XprinterDiscovery discovery = new XprinterDiscovery(getContext(), candidate.network)) {
                repairDiscovery = discovery;
                XprinterDiscovery.Printer fresh = discovery.findMac(mac, SystemClock.elapsedRealtime() + 4000);
                if (fresh == null) throw new IllegalStateException("XPRINTER_NOT_FOUND");
                boolean requested = false;
                XprinterDiscovery.Printer verified = null;
                if (!repairCancelRequested.get() && discovery.reachable(fresh)) {
                    verified = fresh; // Already reachable: never reconfigure a healthy printer.
                } else if (!repairCancelRequested.get()) {
                    discovery.requestAutoIp(fresh); // Exactly one MAC-targeted SAVE, requesting DHCP.
                    requested = true;
                    long deadline = SystemClock.elapsedRealtime() + 90000;
                    while (!repairCancelRequested.get() && SystemClock.elapsedRealtime() < deadline) {
                        XprinterDiscovery.Printer reply = discovery.findMac(mac, Math.min(deadline, SystemClock.elapsedRealtime() + 2500));
                        if (reply != null && discovery.reachable(reply)) { verified = reply; break; }
                        SystemClock.sleep(500);
                    }
                }
                if (repairCancelRequested.get()) throw new IllegalStateException("XPRINTER_CANCELLED");
                if (verified == null) throw new IllegalStateException("XPRINTER_VERIFY_TIMEOUT");
                JSObject result = printerJson(verified.ip, verified.mac);
                result.put("success", true);
                result.put("printPort", verified.port);
                result.put("reportedDhcp", verified.reportedDhcp);
                result.put("automaticIpRequested", requested);
                result.put("persistentDhcpVerified", false);
                synchronized (repairCandidates) { repairCandidates.remove(mac); }
                call.resolve(result);
            } catch (Exception error) {
                String code = repairCancelRequested.get() ? "XPRINTER_CANCELLED" : error.getMessage();
                if (!"XPRINTER_NOT_FOUND".equals(code) && !"XPRINTER_VERIFY_TIMEOUT".equals(code)
                    && !"XPRINTER_CANCELLED".equals(code)) code = "XPRINTER_NETWORK_ERROR";
                call.reject("The printer IP could not be verified; scan again before retrying", code);
            } finally {
                repairDiscovery = null;
                repairing.set(false);
            }
        });
    }

    @Override protected void handleOnDestroy() {
        cancelRequested.set(true);
        repairCancelRequested.set(true);
        if (scanDiscovery != null) scanDiscovery.close();
        if (repairDiscovery != null) repairDiscovery.close();
        ioExecutor.shutdownNow();
        super.handleOnDestroy();
    }

    private FoundPrinter probe(String ip) {
        NetworkMetadata metadata = queryNetworkMetadata(ip, SCAN_REQUEST_TIMEOUT_MS);
        if (metadata != null && metadata.mac != null && metadata.ip != null) {
            FoundPrinter found = new FoundPrinter();
            found.ip = ip;
            found.mac = metadata.mac;
            return found;
        }
        return null;
    }

    private NetworkMetadata queryNetworkMetadata(String ip, int timeoutMs) {
        // The XPrinter web UI speaks HTTP/0.9: it accepts a bare "GET <path>\r\n"
        // request line and replies with a raw HTML body.
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, 80), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            OutputStream out = socket.getOutputStream();
            out.write("GET /ip_info.htm\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) > 0) {
                int allowed = Math.min(read, MAX_RESPONSE_BYTES - total);
                if (allowed > 0) {
                    buffer.write(chunk, 0, allowed);
                    total += allowed;
                }
                if (total >= MAX_RESPONSE_BYTES) {
                    break;
                }
            }

            String body = stripHttpPrologue(buffer.toString("ISO-8859-1"));
            if (!ETH_INFO_PATTERN.matcher(body).find()) {
                return null;
            }

            Matcher macMatch = MAC_PATTERN.matcher(body);
            Matcher ipMatch = IP_PATTERN.matcher(body);
            NetworkMetadata metadata = new NetworkMetadata();
            if (macMatch.find()) {
                metadata.mac = macMatch.group(1).toUpperCase().replace(':', '-');
            }
            if (ipMatch.find()) {
                metadata.ip = ipMatch.group(1);
            }
            return metadata;
        } catch (Exception ignored) {
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

    private Inet4Address findPrivateLanAddress() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
            return null;
        }

        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                continue;
            }

            String interfaceName = networkInterface.getName().toLowerCase(Locale.ROOT);
            boolean preferredInterface = interfaceName.startsWith("wlan") || interfaceName.startsWith("eth");
            if (!preferredInterface) {
                continue;
            }

            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address) || !isPrivateLanAddress(address.getAddress())) {
                    continue;
                }

                Inet4Address ipv4Address = (Inet4Address) address;
                return ipv4Address;
            }
        }

        return null;
    }

    private static boolean isPrivateLanAddress(byte[] address) {
        int first = address[0] & 0xff;
        int second = address[1] & 0xff;
        return first == 10
            || (first == 172 && second >= 16 && second <= 31)
            || (first == 192 && second == 168);
    }

    private static class FoundPrinter {
        String ip;
        String mac;
    }

    private static class NetworkMetadata {
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
