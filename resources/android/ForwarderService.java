package com.nembestil.pos3.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent foreground service that owns:
 *   1. The LAN print forwarder long-poll loop (was useAndroidLanPrintForwarder in JS).
 *   2. The payment terminal forwarder long-poll loop (was useAndroidPaymentTerminalForwarder in JS).
 *
 * Both loops authenticate against /api/_internal/* using the androidsync
 * Bearer token, so the forwarder keeps working even after the WebView signs
 * out. The wire format is SSE-flavoured (text/event-stream) for convenience,
 * but each request is one-shot: the server pushes a single event and closes
 * the connection, which is long-polling semantics rather than a real stream.
 *
 * Lifecycle: started/stopped through ForwarderServicePlugin. While alive it
 * shows a persistent low-priority notification so Android won't kill the
 * process; the long-poll loops keep running even when the WebView is backgrounded.
 */
public class ForwarderService extends Service {

    private static final String TAG = "ForwarderService";
    private static final String CHANNEL_ID = "nembestil_forwarder";
    private static final int NOTIFICATION_ID = 0x4E42; // "NB"

    public static final String ACTION_START = "com.nembestil.pos3.app.action.START_FORWARDER";
    public static final String ACTION_STOP = "com.nembestil.pos3.app.action.STOP_FORWARDER";
    public static final String ACTION_NOTIFY_CONFIG_CHANGED =
        "com.nembestil.pos3.app.action.NOTIFY_FORWARDER_CONFIG_CHANGED";
    public static final String EXTRA_BASE_URL = "baseUrl";
    public static final String EXTRA_TOKEN = "token";

    private static final String PREFS_NAME = "forwarder_service_prefs";
    private static final String PREFS_BASE_URL = "baseUrl";
    private static final String PREFS_TOKEN = "token";

    private static final int LAN_PRINTER_PORT = 9100;
    private static final int LAN_PING_TIMEOUT_MS = 1_500;
    private static final int LAN_PRINT_TIMEOUT_MS = 8_000;

    // Bluetooth Classic SPP (Serial Port Profile) — the channel ESC/POS printers expose.
    private static final UUID BLUETOOTH_SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int BLUETOOTH_PRINT_TIMEOUT_MS = 8_000;
    private static final int BLUETOOTH_PRINTERS_REFRESH_MS = 30_000;
    private static final int LONG_POLL_CONNECT_TIMEOUT_MS = 5_000;
    private static final int LONG_POLL_READ_TIMEOUT_MS = 35_000;
    private static final int RETRY_AFTER_ERROR_MS = 3_000;
    private static final int LAN_PRINTERS_REFRESH_MS = 30_000;
    private static final int TERMINAL_REQUEST_DEFAULT_TIMEOUT_MS = 5 * 60_000;
    private static final int RESPONSE_SUBMIT_RETRY_MS = 1_000;
    private static final int RESPONSE_SUBMIT_MAX_MS = 60_000;

    // Worldline UDP discovery
    private static final int DISCOVERY_PORT = 8000;
    private static final int DISCOVERY_BUFFER_SIZE = 64 * 1024;
    private static final String MULTICAST_LOCK_TAG = "ForwarderServiceDiscovery";
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicReference<String> activeBaseUrl = new AtomicReference<>(null);

    // Discovery state lives in static fields so the Capacitor plugin (running
    // in the same process) can subscribe and query without holding a reference
    // to the service instance.
    private static final ConcurrentMap<String, DiscoveredTerminal> discoveredTerminals = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<DiscoveryListener> discoveryListeners = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean discoveryRunning = new AtomicBoolean(false);

    private final String forwarderId = UUID.randomUUID().toString();
    private final AtomicBoolean lanPrintersDirty = new AtomicBoolean(true);
    private final AtomicBoolean bluetoothPrintersDirty = new AtomicBoolean(true);

    private volatile String baseUrl;
    private volatile String authToken;

    private Thread lanPrintThread;
    private Thread bluetoothPrintThread;
    private Thread paymentTerminalThread;
    private Thread discoveryThread;
    private Thread heartbeatThread;
    private DatagramSocket discoverySocket;
    private WifiManager.MulticastLock multicastLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network currentNetwork;

    /** True when the device is currently interactive. The forwarder advertises
     *  this on every long-poll handshake so the server can prefer screen-on
     *  tablets when dispatching jobs. Transitions take effect on the next
     *  long-poll reconnect — we never interrupt an in-flight window. */
    private final AtomicBoolean screenOn = new AtomicBoolean(true);
    private BroadcastReceiver screenStateReceiver;

    private final Object lanPrintLongPollLock = new Object();
    private final Object bluetoothPrintLongPollLock = new Object();
    private final Object paymentTerminalLongPollLock = new Object();
    private volatile HttpURLConnection currentLanPrintLongPoll;
    private volatile HttpURLConnection currentBluetoothPrintLongPoll;
    private volatile HttpURLConnection currentPaymentTerminalLongPoll;

    public static boolean isRunning() {
        return running.get();
    }

    public static String getActiveBaseUrl() {
        return activeBaseUrl.get();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "Stop requested");
            stopForwarder();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_NOTIFY_CONFIG_CHANGED.equals(action)) {
            Log.i(TAG, "Config change notified; restarting LAN and Bluetooth print loops");
            lanPrintersDirty.set(true);
            bluetoothPrintersDirty.set(true);
            interruptLanPrintLongPoll();
            interruptBluetoothPrintLongPoll();
            return START_STICKY;
        }

        String requestedBaseUrl = intent != null ? intent.getStringExtra(EXTRA_BASE_URL) : null;
        String requestedToken = intent != null ? intent.getStringExtra(EXTRA_TOKEN) : null;

        // When Android restarts the service on its own (START_STICKY with null
        // intent), the extras are gone — fall back to the last-known values.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (requestedBaseUrl == null || requestedBaseUrl.isEmpty()) {
            requestedBaseUrl = prefs.getString(PREFS_BASE_URL, null);
        }
        if (requestedToken == null || requestedToken.isEmpty()) {
            requestedToken = prefs.getString(PREFS_TOKEN, null);
        }

        if (requestedBaseUrl == null || requestedBaseUrl.isEmpty()
            || requestedToken == null || requestedToken.isEmpty()) {
            Log.w(TAG, "Start without baseUrl/token; cannot run");
            if (!running.get()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        baseUrl = stripTrailingSlash(requestedBaseUrl);
        authToken = requestedToken;
        activeBaseUrl.set(baseUrl);
        prefs.edit().putString(PREFS_BASE_URL, baseUrl).putString(PREFS_TOKEN, authToken).apply();

        startForegroundWithNotification();

        if (running.compareAndSet(false, true)) {
            Log.i(TAG, "Starting forwarder loops baseUrl=" + baseUrl + " forwarderId=" + forwarderId);
            registerScreenStateReceiver();
            startDiscoveryListener();
            startLanPrintLoop();
            startBluetoothPrintLoop();
            startPaymentTerminalLoop();
            startHeartbeatLoop();
        } else {
            Log.i(TAG, "Forwarder already running; credentials refreshed");
            // Reset the open long-poll connections so they reconnect with the new token.
            interruptLanPrintLongPoll();
            interruptBluetoothPrintLongPoll();
            interruptPaymentTerminalLongPoll();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        stopForwarder();
        super.onDestroy();
    }

    // ========================================================================
    // Foreground notification
    // ========================================================================

    private void startForegroundWithNotification() {
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "NemBestil forwarder",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps printer and payment terminal forwarding alive.");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = null;
        if (launchIntent != null) {
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NemBestil POS")
            .setContentText("This tablet is handling printer and payment terminal traffic.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE);
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }
        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ========================================================================
    // Lifecycle helpers
    // ========================================================================

    private void stopForwarder() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        activeBaseUrl.set(null);
        interruptLanPrintLongPoll();
        interruptBluetoothPrintLongPoll();
        interruptPaymentTerminalLongPoll();
        unregisterScreenStateReceiver();
        stopDiscoveryListener();
        Thread lan = lanPrintThread;
        Thread bluetooth = bluetoothPrintThread;
        Thread pay = paymentTerminalThread;
        Thread heartbeat = heartbeatThread;
        lanPrintThread = null;
        bluetoothPrintThread = null;
        paymentTerminalThread = null;
        heartbeatThread = null;
        if (lan != null) {
            lan.interrupt();
        }
        if (bluetooth != null) {
            bluetooth.interrupt();
        }
        if (pay != null) {
            pay.interrupt();
        }
        if (heartbeat != null) {
            heartbeat.interrupt();
        }
        // The stored credentials stay so that an automatic restart can recover.
    }

    private void interruptLanPrintLongPoll() {
        synchronized (lanPrintLongPollLock) {
            HttpURLConnection conn = currentLanPrintLongPoll;
            currentLanPrintLongPoll = null;
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void interruptBluetoothPrintLongPoll() {
        synchronized (bluetoothPrintLongPollLock) {
            HttpURLConnection conn = currentBluetoothPrintLongPoll;
            currentBluetoothPrintLongPoll = null;
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void interruptPaymentTerminalLongPoll() {
        synchronized (paymentTerminalLongPollLock) {
            HttpURLConnection conn = currentPaymentTerminalLongPoll;
            currentPaymentTerminalLongPoll = null;
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ========================================================================
    // LAN print long-poll loop
    // ========================================================================

    private void startLanPrintLoop() {
        lanPrintThread = new Thread(this::runLanPrintLoop, "ForwarderLanPrint");
        lanPrintThread.setDaemon(true);
        lanPrintThread.start();
    }

    private void runLanPrintLoop() {
        long lastPrinterFetchAt = 0L;
        List<LanPrinter> reachable = new ArrayList<>();

        while (running.get()) {
            try {
                long now = System.currentTimeMillis();
                boolean dirty = lanPrintersDirty.getAndSet(false);
                if (dirty || reachable.isEmpty() || now - lastPrinterFetchAt > LAN_PRINTERS_REFRESH_MS) {
                    List<LanPrinter> active = fetchActiveLanPrinters();
                    reachable = filterReachableLanPrinters(active);
                    lastPrinterFetchAt = System.currentTimeMillis();
                    Log.i(TAG, "LAN printers fetched: total=" + active.size()
                        + " reachable=" + reachable.size());
                }

                if (reachable.isEmpty()) {
                    sleepQuietly(LAN_PRINTERS_REFRESH_MS);
                    continue;
                }

                boolean handled = longPollLanPrintJob(reachable);
                if (!handled) {
                    sleepQuietly(RETRY_AFTER_ERROR_MS);
                }
            } catch (Throwable t) {
                Log.w(TAG, "LAN print loop iteration failed", t);
                sleepQuietly(RETRY_AFTER_ERROR_MS);
            }
        }
        Log.i(TAG, "LAN print loop exited");
    }

    private List<LanPrinter> fetchActiveLanPrinters() {
        List<LanPrinter> result = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/api/_internal/lan-printers");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            applyCookies(conn);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "lan-printers HTTP " + status);
                return result;
            }
            String body = readAll(conn.getInputStream());
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                LanPrinter printer = new LanPrinter();
                printer.printerId = obj.optString("printerId", null);
                printer.ip = obj.optString("ip", null);
                if (printer.printerId != null && printer.ip != null) {
                    result.add(printer);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchActiveLanPrinters failed", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return result;
    }

    private List<LanPrinter> filterReachableLanPrinters(List<LanPrinter> printers) {
        List<LanPrinter> reachable = new ArrayList<>();
        for (LanPrinter printer : printers) {
            if (pingLanPrinter(printer.ip)) {
                reachable.add(printer);
            }
        }
        return reachable;
    }

    private boolean pingLanPrinter(String ip) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, LAN_PRINTER_PORT), LAN_PING_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean longPollLanPrintJob(List<LanPrinter> printers) {
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (LanPrinter printer : printers) {
                JSONObject obj = new JSONObject();
                obj.put("printerId", printer.printerId);
                obj.put("ip", printer.ip);
                arr.put(obj);
            }
            JSONObject body = new JSONObject();
            body.put("printers", arr);
            body.put("screenOn", screenOn.get());

            URL url = new URL(baseUrl + "/api/_internal/lan-print-forward");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            applyCookies(conn);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            synchronized (lanPrintLongPollLock) {
                currentLanPrintLongPoll = conn;
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "lan-print-forward HTTP " + status);
                return false;
            }

            Map<String, String> ipByPrinterId = new java.util.HashMap<>();
            for (LanPrinter printer : printers) {
                ipByPrinterId.put(printer.printerId, printer.ip);
            }

            readLongPollResponse(conn, (event, data) -> {
                if ("print-job".equals(event)) {
                    handleLanPrintJob(data, ipByPrinterId);
                }
            });
            return true;
        } catch (Exception e) {
            if (running.get()) {
                Log.w(TAG, "longPollLanPrintJob failed", e);
            }
            return false;
        } finally {
            synchronized (lanPrintLongPollLock) {
                if (currentLanPrintLongPoll == conn) {
                    currentLanPrintLongPoll = null;
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handleLanPrintJob(String data, Map<String, String> ipByPrinterId) {
        try {
            JSONObject job = new JSONObject(data);
            String jobId = job.optString("jobId", "");
            String printerId = job.optString("printerId", null);
            String ipFromJob = job.optString("ip", null);
            String payloadBase64 = job.optString("payloadBase64", null);
            int timeoutMs = job.optInt("timeoutMs", LAN_PRINT_TIMEOUT_MS);
            if (payloadBase64 == null) {
                Log.w(TAG, "LAN print-job missing payloadBase64 jobId=" + jobId);
                return;
            }
            String ip = ipByPrinterId.getOrDefault(printerId, ipFromJob);
            if (ip == null || ip.isEmpty()) {
                Log.w(TAG, "LAN print-job has no IP jobId=" + jobId);
                return;
            }
            byte[] bytes = Base64.decode(payloadBase64, Base64.DEFAULT);
            sendBytesToPrinter(ip, bytes, timeoutMs, jobId);
        } catch (Exception e) {
            Log.w(TAG, "handleLanPrintJob failed", e);
        }
    }

    private void sendBytesToPrinter(String ip, byte[] bytes, int timeoutMs, String jobId) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, LAN_PRINTER_PORT), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            int lingerSeconds = Math.max(1, (timeoutMs + 999) / 1000);
            socket.setSoLinger(true, lingerSeconds);

            OutputStream out = socket.getOutputStream();
            out.write(bytes);
            out.flush();
            socket.shutdownOutput();

            InputStream in = socket.getInputStream();
            byte[] sink = new byte[256];
            try {
                while (in.read(sink) > 0) {
                    // drain whatever the printer sends back
                }
            } catch (Exception ignored) {
            }
            Log.i(TAG, "LAN print delivered jobId=" + jobId + " ip=" + ip + " bytes=" + bytes.length);
        } catch (Exception e) {
            Log.w(TAG, "LAN print failed jobId=" + jobId + " ip=" + ip, e);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ========================================================================
    // Bluetooth print long-poll loop
    // ========================================================================
    //
    // Mirrors the LAN loop, but the destination is a bonded (paired) Bluetooth
    // printer reachable only from this tablet. We advertise the bonded printers
    // we can actually reach, and deliver each job over a Bluetooth Classic SPP
    // (RFCOMM) socket, which is what ESC/POS printers expose.

    private void startBluetoothPrintLoop() {
        bluetoothPrintThread = new Thread(this::runBluetoothPrintLoop, "ForwarderBluetoothPrint");
        bluetoothPrintThread.setDaemon(true);
        bluetoothPrintThread.start();
    }

    private void runBluetoothPrintLoop() {
        long lastPrinterFetchAt = 0L;
        List<BluetoothPrinter> bonded = new ArrayList<>();

        while (running.get()) {
            try {
                if (!hasBluetoothConnectPermission()) {
                    sleepQuietly(BLUETOOTH_PRINTERS_REFRESH_MS);
                    continue;
                }

                long now = System.currentTimeMillis();
                boolean dirty = bluetoothPrintersDirty.getAndSet(false);
                if (dirty || bonded.isEmpty() || now - lastPrinterFetchAt > BLUETOOTH_PRINTERS_REFRESH_MS) {
                    List<BluetoothPrinter> active = fetchActiveBluetoothPrinters();
                    bonded = filterBondedBluetoothPrinters(active);
                    lastPrinterFetchAt = System.currentTimeMillis();
                    Log.i(TAG, "Bluetooth printers fetched: total=" + active.size() + " bonded=" + bonded.size());
                }

                if (bonded.isEmpty()) {
                    sleepQuietly(BLUETOOTH_PRINTERS_REFRESH_MS);
                    continue;
                }

                boolean handled = longPollBluetoothPrintJob(bonded);
                if (!handled) {
                    sleepQuietly(RETRY_AFTER_ERROR_MS);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Bluetooth print loop iteration failed", t);
                sleepQuietly(RETRY_AFTER_ERROR_MS);
            }
        }
        Log.i(TAG, "Bluetooth print loop exited");
    }

    private List<BluetoothPrinter> fetchActiveBluetoothPrinters() {
        List<BluetoothPrinter> result = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/api/_internal/bluetooth-printers");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            applyCookies(conn);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "bluetooth-printers HTTP " + status);
                return result;
            }
            String body = readAll(conn.getInputStream());
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                BluetoothPrinter printer = new BluetoothPrinter();
                printer.printerId = obj.optString("printerId", null);
                String address = obj.optString("address", null);
                printer.address = address == null ? null : address.toUpperCase();
                if (printer.printerId != null && printer.address != null) {
                    result.add(printer);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchActiveBluetoothPrinters failed", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return result;
    }

    private List<BluetoothPrinter> filterBondedBluetoothPrinters(List<BluetoothPrinter> printers) {
        List<BluetoothPrinter> bonded = new ArrayList<>();
        if (printers.isEmpty()) {
            return bonded;
        }
        Set<String> bondedAddresses = getBondedAddresses();
        for (BluetoothPrinter printer : printers) {
            if (bondedAddresses.contains(printer.address)) {
                bonded.add(printer);
            }
        }
        return bonded;
    }

    private Set<String> getBondedAddresses() {
        Set<String> addresses = new HashSet<>();
        BluetoothAdapter adapter = resolveBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled() || !hasBluetoothConnectPermission()) {
            return addresses;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice device : bonded) {
                    String address = device.getAddress();
                    if (address != null) {
                        addresses.add(address.toUpperCase());
                    }
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "getBondedDevices denied", e);
        }
        return addresses;
    }

    private boolean longPollBluetoothPrintJob(List<BluetoothPrinter> printers) {
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (BluetoothPrinter printer : printers) {
                JSONObject obj = new JSONObject();
                obj.put("printerId", printer.printerId);
                obj.put("address", printer.address);
                arr.put(obj);
            }
            JSONObject body = new JSONObject();
            body.put("printers", arr);
            body.put("screenOn", screenOn.get());

            URL url = new URL(baseUrl + "/api/_internal/bluetooth-print-forward");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            applyCookies(conn);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            synchronized (bluetoothPrintLongPollLock) {
                currentBluetoothPrintLongPoll = conn;
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "bluetooth-print-forward HTTP " + status);
                return false;
            }

            Map<String, String> addressByPrinterId = new java.util.HashMap<>();
            for (BluetoothPrinter printer : printers) {
                addressByPrinterId.put(printer.printerId, printer.address);
            }

            readLongPollResponse(conn, (event, data) -> {
                if ("print-job".equals(event)) {
                    handleBluetoothPrintJob(data, addressByPrinterId);
                }
            });
            return true;
        } catch (Exception e) {
            if (running.get()) {
                Log.w(TAG, "longPollBluetoothPrintJob failed", e);
            }
            return false;
        } finally {
            synchronized (bluetoothPrintLongPollLock) {
                if (currentBluetoothPrintLongPoll == conn) {
                    currentBluetoothPrintLongPoll = null;
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handleBluetoothPrintJob(String data, Map<String, String> addressByPrinterId) {
        try {
            JSONObject job = new JSONObject(data);
            String jobId = job.optString("jobId", "");
            String printerId = job.optString("printerId", null);
            String addressFromJob = job.optString("target", null);
            String payloadBase64 = job.optString("payloadBase64", null);
            int timeoutMs = job.optInt("timeoutMs", BLUETOOTH_PRINT_TIMEOUT_MS);
            if (payloadBase64 == null) {
                Log.w(TAG, "Bluetooth print-job missing payloadBase64 jobId=" + jobId);
                return;
            }
            String address = addressByPrinterId.getOrDefault(printerId, addressFromJob);
            if (address == null || address.isEmpty()) {
                Log.w(TAG, "Bluetooth print-job has no address jobId=" + jobId);
                return;
            }
            byte[] bytes = Base64.decode(payloadBase64, Base64.DEFAULT);
            sendBytesToBluetoothPrinter(address, bytes, timeoutMs, jobId);
        } catch (Exception e) {
            Log.w(TAG, "handleBluetoothPrintJob failed", e);
        }
    }

    private void sendBytesToBluetoothPrinter(String address, byte[] bytes, int timeoutMs, String jobId) {
        BluetoothAdapter adapter = resolveBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth adapter unavailable jobId=" + jobId);
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Bluetooth connect permission missing jobId=" + jobId);
            return;
        }
        BluetoothSocket socket = null;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            // Discovery slows down (and can break) an outgoing RFCOMM connection.
            // Cancelling needs BLUETOOTH_SCAN on newer Androids, so it's best-effort.
            try {
                adapter.cancelDiscovery();
            } catch (Exception ignored) {
            }
            socket = device.createRfcommSocketToServiceRecord(BLUETOOTH_SPP_UUID);
            socket.connect();

            OutputStream out = socket.getOutputStream();
            out.write(bytes);
            out.flush();
            // Give the printer a moment to drain its buffer before we close the socket.
            try {
                Thread.sleep(Math.min(Math.max(timeoutMs, 1), 400));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.i(TAG, "Bluetooth print delivered jobId=" + jobId + " address=" + address + " bytes=" + bytes.length);
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth print denied jobId=" + jobId + " address=" + address, e);
        } catch (IOException e) {
            Log.w(TAG, "Bluetooth print failed jobId=" + jobId + " address=" + address, e);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private BluetoothAdapter resolveBluetoothAdapter() {
        try {
            BluetoothManager manager =
                (BluetoothManager) getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
            return manager != null ? manager.getAdapter() : null;
        } catch (Exception e) {
            Log.w(TAG, "resolveBluetoothAdapter failed", e);
            return null;
        }
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED;
    }

    // Container used internally for bonded Bluetooth printer entries.
    private static class BluetoothPrinter {
        String printerId;
        String address;
    }

    // ========================================================================
    // Payment terminal long-poll loop
    // ========================================================================

    private void startPaymentTerminalLoop() {
        paymentTerminalThread = new Thread(this::runPaymentTerminalLoop, "ForwarderPaymentTerminal");
        paymentTerminalThread.setDaemon(true);
        paymentTerminalThread.start();
    }

    private void runPaymentTerminalLoop() {
        while (running.get()) {
            try {
                List<String> terminalIds = getReachableTerminalIds();
                if (terminalIds.isEmpty()) {
                    sleepQuietly(LAN_PRINTERS_REFRESH_MS);
                    continue;
                }

                boolean ok = longPollPaymentTerminalRequest(terminalIds);
                if (!ok) {
                    sleepQuietly(RETRY_AFTER_ERROR_MS);
                }
            } catch (Throwable t) {
                Log.w(TAG, "payment terminal loop iteration failed", t);
                sleepQuietly(RETRY_AFTER_ERROR_MS);
            }
        }
        Log.i(TAG, "Payment terminal loop exited");
    }

    private boolean longPollPaymentTerminalRequest(List<String> terminalIds) {
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (String id : terminalIds) {
                arr.put(id);
            }
            JSONObject body = new JSONObject();
            body.put("forwarderId", forwarderId);
            body.put("terminalIds", arr);
            body.put("screenOn", screenOn.get());

            URL url = new URL(baseUrl + "/api/_internal/payment-terminal-forward");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            applyCookies(conn);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            synchronized (paymentTerminalLongPollLock) {
                currentPaymentTerminalLongPoll = conn;
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "payment-terminal-forward HTTP " + status);
                return false;
            }

            readLongPollResponse(conn, (event, data) -> {
                if ("terminal-request".equals(event)) {
                    handleTerminalRequest(data);
                }
            });
            return true;
        } catch (Exception e) {
            if (running.get()) {
                Log.w(TAG, "longPollPaymentTerminalRequest failed", e);
            }
            return false;
        } finally {
            synchronized (paymentTerminalLongPollLock) {
                if (currentPaymentTerminalLongPoll == conn) {
                    currentPaymentTerminalLongPoll = null;
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handleTerminalRequest(String data) {
        try {
            JSONObject job = new JSONObject(data);
            String jobId = job.optString("jobId", "");
            String url = job.optString("url", null);
            String method = job.optString("method", "POST");
            int timeoutMs = job.optInt("timeoutMs", TERMINAL_REQUEST_DEFAULT_TIMEOUT_MS);
            JSONObject headers = job.optJSONObject("headers");
            String requestBody = job.isNull("body") ? null : job.optString("body", null);
            if (url == null) {
                postTerminalError(jobId, "Forwarded terminal request missing url.");
                return;
            }
            // Run the actual terminal call in its own thread so the long-poll
            // can close right away and the next job can come in without waiting on
            // the (possibly multi-minute) terminal interaction.
            final String finalJobId = jobId;
            final String finalUrl = url;
            final String finalMethod = method;
            final int finalTimeoutMs = timeoutMs;
            final JSONObject finalHeaders = headers;
            final String finalBody = requestBody;
            Thread worker = new Thread(() -> dispatchTerminalRequest(
                finalJobId, finalUrl, finalMethod, finalHeaders, finalBody, finalTimeoutMs
            ), "ForwarderTerminalJob-" + jobId);
            worker.setDaemon(true);
            worker.start();
        } catch (Exception e) {
            Log.w(TAG, "handleTerminalRequest failed to parse", e);
        }
    }

    private void dispatchTerminalRequest(
        String jobId,
        String url,
        String method,
        JSONObject headers,
        String body,
        int timeoutMs
    ) {
        Log.i(TAG, "dispatch terminal request jobId=" + jobId + " " + method + " " + url);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(Math.max(timeoutMs, TERMINAL_REQUEST_DEFAULT_TIMEOUT_MS));
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = headers.optString(key, "");
                    conn.setRequestProperty(key, value);
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = stream == null ? "" : readAll(stream);

            JSONObject responseHeaders = new JSONObject();
            for (Map.Entry<String, java.util.List<String>> entry : conn.getHeaderFields().entrySet()) {
                String key = entry.getKey();
                if (key == null) continue;
                List<String> values = entry.getValue();
                if (values == null || values.isEmpty()) continue;
                responseHeaders.put(key.toLowerCase(), values.get(0));
            }

            JSONObject result = new JSONObject();
            result.put("status", status);
            result.put("headers", responseHeaders);
            result.put("body", responseBody);
            postTerminalResult(jobId, result, null);
        } catch (Exception e) {
            Log.w(TAG, "dispatchTerminalRequest failed jobId=" + jobId, e);
            postTerminalError(jobId, e.getMessage() == null
                ? "Forwarded terminal request failed." : e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void postTerminalResult(String jobId, JSONObject result, String errorMessage) {
        long startedAt = System.currentTimeMillis();
        while (running.get() && System.currentTimeMillis() - startedAt <= RESPONSE_SUBMIT_MAX_MS) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(baseUrl + "/api/_internal/payment-terminal-forward-response");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json");
                applyCookies(conn);
                JSONObject body = new JSONObject();
                body.put("jobId", jobId);
                if (errorMessage != null) {
                    JSONObject errObj = new JSONObject();
                    errObj.put("message", errorMessage);
                    body.put("result", errObj);
                } else {
                    body.put("result", result);
                }
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    Log.i(TAG, "Submitted terminal response jobId=" + jobId);
                    return;
                }
                Log.w(TAG, "Submit terminal response HTTP " + status + " jobId=" + jobId);
            } catch (Exception e) {
                Log.w(TAG, "Submit terminal response failed jobId=" + jobId, e);
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
            sleepQuietly(RESPONSE_SUBMIT_RETRY_MS);
        }
        Log.w(TAG, "Gave up submitting terminal response jobId=" + jobId);
    }

    private void postTerminalError(String jobId, String message) {
        postTerminalResult(jobId, null, message);
    }

    // ========================================================================
    // Discovery heartbeat (keeps server's lastSeenAt fresh while logged out)
    // ========================================================================

    private void startHeartbeatLoop() {
        heartbeatThread = new Thread(this::runHeartbeatLoop, "ForwarderHeartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void runHeartbeatLoop() {
        while (running.get()) {
            try {
                postDiscoveryHeartbeat();
            } catch (Throwable t) {
                Log.w(TAG, "heartbeat iteration failed", t);
            }
            sleepQuietly(HEARTBEAT_INTERVAL_MS);
        }
        Log.i(TAG, "Heartbeat loop exited");
    }

    private void postDiscoveryHeartbeat() {
        List<DiscoveredTerminal> terminals = getDiscoveredTerminals();
        if (terminals.isEmpty()) {
            return;
        }
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (DiscoveredTerminal terminal : terminals) {
                JSONObject obj = new JSONObject();
                obj.put("terminalId", terminal.terminalId);
                obj.put("ipAddress", terminal.ipAddress);
                obj.put("lastSeenAt", formatIsoUtc(terminal.lastSeenAtMs));
                arr.put(obj);
            }
            JSONObject body = new JSONObject();
            body.put("terminals", arr);

            URL url = new URL(baseUrl + "/api/_internal/payment-terminal-heartbeat");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            applyCookies(conn);

            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "heartbeat HTTP " + status);
            }
        } catch (Exception e) {
            Log.w(TAG, "heartbeat failed", e);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ========================================================================
    // Long-poll response parsing
    // ========================================================================
    //
    // The server replies with SSE framing (`event:` / `data:` lines, blank line
    // separator) but exactly one event followed by `event: close` and a graceful
    // socket close. We parse that as long-poll output: as soon as we receive the
    // job event, we invoke the handler and return; the close event ends the loop.

    private interface LongPollEventHandler {
        void onEvent(String event, String data);
    }

    private void readLongPollResponse(HttpURLConnection conn, LongPollEventHandler handler) throws Exception {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            String eventName = "message";
            StringBuilder dataBuf = new StringBuilder();
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (dataBuf.length() > 0) {
                        String dataStr = dataBuf.toString();
                        dataBuf.setLength(0);
                        String currentEvent = eventName;
                        eventName = "message";
                        if ("close".equals(currentEvent)) {
                            return;
                        }
                        try {
                            handler.onEvent(currentEvent, dataStr);
                        } catch (Exception e) {
                            Log.w(TAG, "Long-poll handler failed for event=" + currentEvent, e);
                        }
                    }
                } else if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (dataBuf.length() > 0) {
                        dataBuf.append('\n');
                    }
                    dataBuf.append(line.substring(5).trim());
                }
            }
        }
    }

    // ========================================================================
    // HTTP helpers
    // ========================================================================

    private void applyCookies(HttpURLConnection conn) {
        String token = authToken;
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = stream.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
        }
        return buf.toString("UTF-8");
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Container used internally for active LAN printer entries.
    private static class LanPrinter {
        String printerId;
        String ip;
    }

    // ========================================================================
    // Worldline UDP discovery
    // ========================================================================

    public interface DiscoveryListener {
        void onTerminalSeen(DiscoveredTerminal terminal, boolean isNew);
    }

    public static class DiscoveredTerminal {
        public final String terminalId;
        public final String identity;
        public final String ipAddress;
        public final int port;
        public final String protocolType;
        public final String protocolVersion;
        public final long lastSeenAtMs;

        DiscoveredTerminal(
            String terminalId,
            String identity,
            String ipAddress,
            int port,
            String protocolType,
            String protocolVersion,
            long lastSeenAtMs
        ) {
            this.terminalId = terminalId;
            this.identity = identity;
            this.ipAddress = ipAddress;
            this.port = port;
            this.protocolType = protocolType;
            this.protocolVersion = protocolVersion;
            this.lastSeenAtMs = lastSeenAtMs;
        }
    }

    public static void registerDiscoveryListener(DiscoveryListener listener) {
        discoveryListeners.addIfAbsent(listener);
    }

    public static void unregisterDiscoveryListener(DiscoveryListener listener) {
        discoveryListeners.remove(listener);
    }

    public static boolean isDiscoveryRunning() {
        return discoveryRunning.get();
    }

    public static int getDiscoveredTerminalCount() {
        return discoveredTerminals.size();
    }

    /** Snapshot of every terminal ever announced while the service has been running. */
    public static List<DiscoveredTerminal> getDiscoveredTerminals() {
        List<DiscoveredTerminal> snapshot = new ArrayList<>(discoveredTerminals.values());
        snapshot.sort(Comparator.comparing(t -> t.terminalId));
        return snapshot;
    }

    /**
     * Terminals this tablet has seen at least once while attached to the
     * current network. Deliberately not time-filtered: Android throttles
     * Wi-Fi multicast on lock screen / in Doze, so UDP heartbeats stop arriving
     * even though the terminal is still happily on the LAN. Being optimistic
     * here lets the cashier keep paying — if the terminal really is gone, the
     * HTTP call to it will surface the failure. The {@code networkChanged}
     * callback below resets this set whenever we actually move networks.
     */
    public List<String> getReachableTerminalIds() {
        List<String> ids = new ArrayList<>();
        for (DiscoveredTerminal terminal : discoveredTerminals.values()) {
            ids.add(terminal.terminalId);
        }
        return ids;
    }

    private void startDiscoveryListener() {
        if (!discoveryRunning.compareAndSet(false, true)) {
            return;
        }
        discoveredTerminals.clear();
        acquireMulticastLock();
        registerNetworkCallback();
        discoveryThread = new Thread(this::runDiscoveryListener, "ForwarderDiscovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
    }

    private void stopDiscoveryListener() {
        if (!discoveryRunning.compareAndSet(true, false)) {
            return;
        }
        DatagramSocket s = discoverySocket;
        discoverySocket = null;
        if (s != null && !s.isClosed()) {
            s.close();
        }
        Thread t = discoveryThread;
        discoveryThread = null;
        if (t != null) {
            t.interrupt();
        }
        unregisterNetworkCallback();
        releaseMulticastLock();
        discoveredTerminals.clear();
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm =
                (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Network previous = currentNetwork;
                    currentNetwork = network;
                    if (previous != null && !previous.equals(network)) {
                        Log.i(TAG, "Active network changed; resetting discovered terminals");
                        discoveredTerminals.clear();
                    }
                }

                @Override
                public void onLost(Network network) {
                    if (network.equals(currentNetwork)) {
                        currentNetwork = null;
                    }
                }
            };
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register network callback", e);
            networkCallback = null;
        }
    }

    private void registerScreenStateReceiver() {
        try {
            PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
            screenOn.set(pm == null || pm.isInteractive());
        } catch (Exception e) {
            Log.w(TAG, "Failed to read initial screen state", e);
            screenOn.set(true);
        }
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }
                boolean wasOn = screenOn.get();
                boolean nowOn;
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    nowOn = true;
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    nowOn = false;
                } else {
                    return;
                }
                if (wasOn == nowOn) {
                    return;
                }
                screenOn.set(nowOn);
                Log.i(TAG, "Screen state changed: " + (nowOn ? "ON" : "OFF"));
                // Deliberately do NOT interrupt the open long-polls: each
                // window is at most 25s, so the new value is picked up on the
                // next reconnect anyway, and bouncing the connection risks
                // dropping a job the server was about to push.
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            getApplicationContext().registerReceiver(screenStateReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register screen state receiver", e);
            screenStateReceiver = null;
        }
    }

    private void unregisterScreenStateReceiver() {
        BroadcastReceiver receiver = screenStateReceiver;
        screenStateReceiver = null;
        if (receiver == null) {
            return;
        }
        try {
            getApplicationContext().unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager.NetworkCallback cb = networkCallback;
        networkCallback = null;
        currentNetwork = null;
        if (cb == null) {
            return;
        }
        try {
            ConnectivityManager cm =
                (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(cb);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister network callback", e);
        }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi =
                (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return;
            }
            multicastLock = wifi.createMulticastLock(MULTICAST_LOCK_TAG);
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire multicast lock", e);
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null) {
            try {
                if (multicastLock.isHeld()) {
                    multicastLock.release();
                }
            } catch (Exception ignored) {
            }
            multicastLock = null;
        }
    }

    private void runDiscoveryListener() {
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(DISCOVERY_PORT));
            discoverySocket = s;
            Log.i(TAG, "Worldline discovery listening on 0.0.0.0:" + DISCOVERY_PORT);

            byte[] buf = new byte[DISCOVERY_BUFFER_SIZE];
            while (discoveryRunning.get()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(packet);
                } catch (Exception e) {
                    if (!discoveryRunning.get()) {
                        return;
                    }
                    Log.w(TAG, "UDP receive failed", e);
                    continue;
                }
                handleDiscoveryPacket(packet.getData(), packet.getLength());
            }
        } catch (Exception e) {
            Log.e(TAG, "Discovery listener crashed", e);
        } finally {
            DatagramSocket s = discoverySocket;
            discoverySocket = null;
            if (s != null && !s.isClosed()) {
                s.close();
            }
        }
    }

    private void handleDiscoveryPacket(byte[] data, int length) {
        DiscoveredTerminal terminal = parseDiscoveryPayload(data, length);
        if (terminal == null) {
            return;
        }
        boolean isNew = !discoveredTerminals.containsKey(terminal.terminalId);
        discoveredTerminals.put(terminal.terminalId, terminal);
        if (isNew) {
            Log.i(TAG, "Discovered Worldline terminal: "
                + terminal.terminalId + " (" + terminal.ipAddress + ")");
        }
        for (DiscoveryListener listener : discoveryListeners) {
            try {
                listener.onTerminalSeen(terminal, isNew);
            } catch (Exception e) {
                Log.w(TAG, "Discovery listener threw", e);
            }
        }
    }

    private static DiscoveredTerminal parseDiscoveryPayload(byte[] data, int length) {
        try {
            String text = new String(data, 0, length, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONObject worldline = root.optJSONObject("WORLDLINE_TERMINAL");
            if (worldline == null) {
                return null;
            }
            JSONObject v1 = worldline.optJSONObject("v1");
            if (v1 == null) {
                return null;
            }
            String terminalId = optStringOrNull(v1, "terminalId");
            String ipAddress = optStringOrNull(v1, "ipAddress");
            if (terminalId == null || ipAddress == null) {
                return null;
            }
            return new DiscoveredTerminal(
                terminalId,
                optStringOrNull(v1, "identity"),
                ipAddress,
                normalizePort(v1.opt("port")),
                optStringOrNull(v1, "protocolType"),
                optStringOrNull(v1, "protocolVersion"),
                System.currentTimeMillis()
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String optStringOrNull(JSONObject obj, String key) {
        if (!obj.has(key) || obj.isNull(key)) {
            return null;
        }
        String value = obj.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    private static int normalizePort(Object value) {
        if (value == null) {
            return 80;
        }
        try {
            int port = (value instanceof Number)
                ? ((Number) value).intValue()
                : Integer.parseInt(value.toString());
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
        }
        return 80;
    }

    public static String formatIsoUtc(long epochMs) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(epochMs));
    }

    // ========================================================================
    // Static helpers used by the plugin to start/stop the service.
    // ========================================================================

    public static void requestStart(Context context, String baseUrl, String token) {
        Intent intent = new Intent(context, ForwarderService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_BASE_URL, baseUrl);
        intent.putExtra(EXTRA_TOKEN, token);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void requestStop(Context context) {
        Intent intent = new Intent(context, ForwarderService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignored) {
            // App may be in a state where it can't start services; nothing to do.
        }
    }

    public static void requestNotifyConfigChanged(Context context) {
        if (!isRunning()) {
            return;
        }
        Intent intent = new Intent(context, ForwarderService.class);
        intent.setAction(ACTION_NOTIFY_CONFIG_CHANGED);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignored) {
        }
    }

    @SuppressWarnings("unused")
    private static Set<String> dedup(List<String> items) {
        return new HashSet<>(items);
    }
}
