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
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
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
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Currency;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent foreground service that owns:
 *   1. The LAN print forwarder long-poll loop (was useAndroidLanPrintForwarder in JS).
 *   2. The local Bluetooth/USB print forwarder long-poll loop.
 *   3. The payment terminal forwarder long-poll loop (was useAndroidPaymentTerminalForwarder in JS).
 *   4. The feature-gated takeaway order broadcast long-poll loop.
 *   5. The feature-gated table booking broadcast long-poll loop.
 *
 * These loops authenticate against /api/_internal/* using the androidsync
 * Bearer token, so the forwarder keeps working even after the WebView signs
 * out. Each request is one-shot: the server pushes a single event and closes
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
    // A separate, higher-importance channel/id for the "your token died" heads-up.
    // It must outlive the ongoing service notification, so it can't share an id.
    private static final String ALERT_CHANNEL_ID = "nembestil_forwarder_alerts";
    private static final int ALERT_NOTIFICATION_ID = 0x4E43;
    private static final String TAKEAWAY_CHANNEL_ID = "nembestil_takeaway_orders";
    private static final int TAKEAWAY_NOTIFICATION_ID_BASE = 0x540000;
    private static final String TABLE_BOOKING_CHANNEL_ID = "nembestil_table_bookings";
    private static final int TABLE_BOOKING_NOTIFICATION_ID_BASE = 0x550000;
    public static final String EXTRA_OPEN_TAKEAWAY_ORDERS = "openTakeawayOrders";
    public static final String EXTRA_TAKEAWAY_ORDER_ID = "takeawayOrderId";
    public static final String EXTRA_OPEN_TABLE_BOOKINGS = "openTableBookings";
    public static final String EXTRA_TABLE_BOOKING_ID = "tableBookingId";

    public static final String ACTION_START = "com.nembestil.pos3.app.action.START_FORWARDER";
    public static final String ACTION_STOP = "com.nembestil.pos3.app.action.STOP_FORWARDER";
    public static final String ACTION_NOTIFY_CONFIG_CHANGED =
        "com.nembestil.pos3.app.action.NOTIFY_FORWARDER_CONFIG_CHANGED";
    public static final String ACTION_UPDATE_TAKEAWAY_STATE =
        "com.nembestil.pos3.app.action.UPDATE_TAKEAWAY_STATE";
    public static final String ACTION_UPDATE_TABLE_BOOKING_STATE =
        "com.nembestil.pos3.app.action.UPDATE_TABLE_BOOKING_STATE";
    public static final String EXTRA_BASE_URL = "baseUrl";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_TAKEAWAY_ENABLED = "takeawayEnabled";
    public static final String EXTRA_TABLE_BOOKING_ENABLED = "tableBookingEnabled";

    private static final String PREFS_NAME = "forwarder_service_prefs";
    private static final String PREFS_BASE_URL = "baseUrl";
    private static final String PREFS_TOKEN = "token";
    private static final String PREFS_TAKEAWAY_ENABLED = "takeawayEnabled";
    private static final String PREFS_TABLE_BOOKING_ENABLED = "tableBookingEnabled";

    private static final int LAN_PRINTER_PORT = 9100;
    private static final int LAN_PING_TIMEOUT_MS = 1_500;
    private static final int LAN_PRINT_TIMEOUT_MS = 8_000;

    // Bluetooth Classic SPP (Serial Port Profile) — the channel ESC/POS printers expose.
    private static final UUID BLUETOOTH_SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int BLUETOOTH_PRINT_TIMEOUT_MS = 8_000;
    private static final int LOCAL_PRINTERS_REFRESH_MS = 30_000;
    private static final int LONG_POLL_CONNECT_TIMEOUT_MS = 5_000;
    private static final int LONG_POLL_READ_TIMEOUT_MS = 35_000;
    private static final int RETRY_AFTER_ERROR_MS = 3_000;
    private static final int LAN_PRINTERS_REFRESH_MS = 30_000;
    private static final int TERMINAL_REQUEST_DEFAULT_TIMEOUT_MS = 5 * 60_000;
    private static final int RESPONSE_SUBMIT_RETRY_MS = 1_000;
    private static final int RESPONSE_SUBMIT_MAX_MS = 60_000;
    private static final int PRINT_RESULT_SUBMIT_MAX_MS = 15_000;
    private static final int PAYMENT_TERMINAL_FORWARDER_PROTOCOL = 2;
    private static final int TERMINAL_JOB_CONTROL_POLL_MS = 1_000;

    // Worldline UDP discovery
    private static final int DISCOVERY_PORT = 8000;
    private static final int DISCOVERY_BUFFER_SIZE = 64 * 1024;
    private static final int DISCOVERY_RESTART_DELAY_MS = 1_000;
    private static final String MULTICAST_LOCK_TAG = "ForwarderServiceDiscovery";
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicReference<String> activeBaseUrl = new AtomicReference<>(null);
    private static final AtomicBoolean appFocused = new AtomicBoolean(false);

    // Discovery state lives in static fields so the Capacitor plugin (running
    // in the same process) can subscribe and query without holding a reference
    // to the service instance.
    private static final ConcurrentMap<String, DiscoveredTerminal> discoveredTerminals = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<DiscoveryListener> discoveryListeners = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean discoveryRunning = new AtomicBoolean(false);

    // Listeners (the Capacitor plugin) that want to know when the server rejected
    // our token, so the WebView can drop its own copy and re-mint after login.
    private static final CopyOnWriteArrayList<TokenListener> tokenListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<TakeawayOrderListener> takeawayOrderListeners =
        new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<TableBookingListener> tableBookingListeners =
        new CopyOnWriteArrayList<>();

    private final String forwarderId = UUID.randomUUID().toString();
    private final AtomicBoolean lanPrintersDirty = new AtomicBoolean(true);
    private final AtomicBoolean localPrintersDirty = new AtomicBoolean(true);
    // Latches the first time the server rejects our token (401) so only one loop
    // tears things down; cleared again whenever fresh credentials arrive.
    private final AtomicBoolean tokenInvalidated = new AtomicBoolean(false);
    private final AtomicBoolean takeawayEnabled = new AtomicBoolean(false);
    private final AtomicBoolean tableBookingEnabled = new AtomicBoolean(false);
    private final ConcurrentMap<String, ExecutorService> paymentTerminalExecutors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TerminalJobExecution> activeTerminalJobs = new ConcurrentHashMap<>();

    private volatile String baseUrl;
    private volatile String authToken;

    private Thread lanPrintThread;
    private Thread localPrintThread;
    private Thread paymentTerminalThread;
    private Thread discoveryThread;
    private Thread heartbeatThread;
    private Thread takeawayOrderThread;
    private Thread tableBookingThread;
    private volatile DatagramSocket discoverySocket;
    private WifiManager.MulticastLock multicastLock;
    private volatile ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network currentNetwork;

    /** True when the device is currently interactive. The forwarder advertises
     *  this on every long-poll handshake so the server can prefer screen-on
     *  tablets when dispatching jobs. Transitions take effect on the next
     *  long-poll reconnect — we never interrupt an in-flight window. */
    private final AtomicBoolean screenOn = new AtomicBoolean(true);
    private BroadcastReceiver screenStateReceiver;

    private final Object lanPrintLongPollLock = new Object();
    private final Object localPrintLongPollLock = new Object();
    private final Object paymentTerminalLongPollLock = new Object();
    private final Object paymentTerminalSignal = new Object();
    private final Object takeawayOrderLongPollLock = new Object();
    private final Object takeawayOrderSignal = new Object();
    private final Object tableBookingLongPollLock = new Object();
    private final Object tableBookingSignal = new Object();
    private final Object discoveryThreadLock = new Object();
    private final Object networkCallbackLock = new Object();
    private volatile HttpURLConnection currentLanPrintLongPoll;
    private volatile HttpURLConnection currentLocalPrintLongPoll;
    private volatile HttpURLConnection currentPaymentTerminalLongPoll;
    private volatile HttpURLConnection currentTakeawayOrderLongPoll;
    private volatile HttpURLConnection currentTableBookingLongPoll;
    private volatile String takeawayOrderCursor;
    private final String takeawayOrderStartedAt = formatIsoUtc(System.currentTimeMillis());
    private volatile String tableBookingCursor;
    private final String tableBookingStartedAt = formatIsoUtc(System.currentTimeMillis());

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
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "Stop requested");
            prefs.edit().remove(PREFS_BASE_URL).remove(PREFS_TOKEN).apply();
            stopForwarder();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_NOTIFY_CONFIG_CHANGED.equals(action)) {
            Log.i(TAG, "Config change notified; restarting LAN and local print loops");
            lanPrintersDirty.set(true);
            localPrintersDirty.set(true);
            interruptLanPrintLongPoll();
            interruptLocalPrintLongPoll();
            return START_STICKY;
        }

        if (ACTION_UPDATE_TAKEAWAY_STATE.equals(action)) {
            boolean enabled = intent != null && intent.getBooleanExtra(EXTRA_TAKEAWAY_ENABLED, false);
            takeawayEnabled.set(enabled);
            prefs.edit().putBoolean(PREFS_TAKEAWAY_ENABLED, enabled).apply();
            if (enabled) {
                signalTakeawayOrderLoop();
            } else {
                interruptTakeawayOrderLongPoll();
            }
            return running.get() ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_UPDATE_TABLE_BOOKING_STATE.equals(action)) {
            boolean enabled = intent != null && intent.getBooleanExtra(EXTRA_TABLE_BOOKING_ENABLED, false);
            tableBookingEnabled.set(enabled);
            prefs.edit().putBoolean(PREFS_TABLE_BOOKING_ENABLED, enabled).apply();
            if (enabled) {
                signalTableBookingLoop();
            } else {
                interruptTableBookingLongPoll();
            }
            return running.get() ? START_STICKY : START_NOT_STICKY;
        }

        String requestedBaseUrl = intent != null ? intent.getStringExtra(EXTRA_BASE_URL) : null;
        String requestedToken = intent != null ? intent.getStringExtra(EXTRA_TOKEN) : null;

        // When Android restarts the service on its own (START_STICKY with null
        // intent), the extras are gone — fall back to the last-known values.
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
        takeawayEnabled.set(prefs.getBoolean(PREFS_TAKEAWAY_ENABLED, false));
        tableBookingEnabled.set(prefs.getBoolean(PREFS_TABLE_BOOKING_ENABLED, false));
        activeBaseUrl.set(baseUrl);
        prefs.edit()
            .putString(PREFS_BASE_URL, baseUrl)
            .putString(PREFS_TOKEN, authToken)
            .apply();
        // Fresh credentials — let the loops trust the server again.
        tokenInvalidated.set(false);

        startForegroundWithNotification();

        if (running.compareAndSet(false, true)) {
            Log.i(TAG, "Starting forwarder loops baseUrl=" + baseUrl + " forwarderId=" + forwarderId);
            registerScreenStateReceiver();
            startDiscoveryListener();
            startLanPrintLoop();
            startLocalPrintLoop();
            startPaymentTerminalLoop();
            startHeartbeatLoop();
            startTakeawayOrderLoop();
            startTableBookingLoop();
        } else {
            Log.i(TAG, "Forwarder already running; credentials refreshed");
            // Reset the open long-poll connections so they reconnect with the new token.
            interruptLanPrintLongPoll();
            interruptLocalPrintLongPoll();
            interruptPaymentTerminalLongPoll();
            interruptTakeawayOrderLongPoll();
            interruptTableBookingLongPoll();
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
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NemBestil POS")
            .setContentText("This tablet is handling printer and payment terminal traffic.")
            .setSmallIcon(R.drawable.ic_stat_forwarder)
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
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
        interruptLocalPrintLongPoll();
        interruptPaymentTerminalLongPoll();
        interruptTakeawayOrderLongPoll();
        interruptTableBookingLongPoll();
        signalPaymentTerminalLoop();
        signalTakeawayOrderLoop();
        signalTableBookingLoop();
        unregisterScreenStateReceiver();
        stopDiscoveryListener();
        Thread lan = lanPrintThread;
        Thread local = localPrintThread;
        Thread pay = paymentTerminalThread;
        Thread heartbeat = heartbeatThread;
        Thread takeaway = takeawayOrderThread;
        Thread tableBooking = tableBookingThread;
        lanPrintThread = null;
        localPrintThread = null;
        paymentTerminalThread = null;
        heartbeatThread = null;
        takeawayOrderThread = null;
        tableBookingThread = null;
        if (lan != null) {
            lan.interrupt();
        }
        if (local != null) {
            local.interrupt();
        }
        if (pay != null) {
            pay.interrupt();
        }
        if (heartbeat != null) {
            heartbeat.interrupt();
        }
        if (takeaway != null) {
            takeaway.interrupt();
        }
        if (tableBooking != null) {
            tableBooking.interrupt();
        }
        for (TerminalJobExecution execution : activeTerminalJobs.values()) {
            execution.requestCancellation();
        }
        activeTerminalJobs.clear();
        for (ExecutorService executor : paymentTerminalExecutors.values()) {
            executor.shutdownNow();
        }
        paymentTerminalExecutors.clear();
        // Unexpected shutdowns keep the stored credentials so Android can
        // restore the service. An explicit stop removes them before this runs.
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

    private void interruptLocalPrintLongPoll() {
        synchronized (localPrintLongPollLock) {
            HttpURLConnection conn = currentLocalPrintLongPoll;
            currentLocalPrintLongPoll = null;
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

    private void interruptTakeawayOrderLongPoll() {
        synchronized (takeawayOrderLongPollLock) {
            HttpURLConnection conn = currentTakeawayOrderLongPoll;
            currentTakeawayOrderLongPoll = null;
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void interruptTableBookingLongPoll() {
        synchronized (tableBookingLongPollLock) {
            HttpURLConnection conn = currentTableBookingLongPoll;
            currentTableBookingLongPoll = null;
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
            final String usedToken = authToken;
            URL url = new URL(baseUrl + "/api/_internal/lan-printers");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            applyCookies(conn);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (handledUnauthorized(status, usedToken)) {
                return result;
            }
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
            body.put("supportsPrintResults", true);

            final String usedToken = authToken;
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
            if (handledUnauthorized(status, usedToken)) {
                return false;
            }
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
            String deliveryToken = job.optString("deliveryToken", "");
            String printerId = job.optString("printerId", null);
            String ipFromJob = job.optString("ip", null);
            String payloadBase64 = job.optString("payloadBase64", null);
            int timeoutMs = job.optInt("timeoutMs", LAN_PRINT_TIMEOUT_MS);
            if (payloadBase64 == null) {
                Log.w(TAG, "LAN print-job missing payloadBase64 jobId=" + jobId);
                postPrintJobResult(jobId, deliveryToken, "failed", "The print job payload is missing.");
                return;
            }
            String ip = ipByPrinterId.getOrDefault(printerId, ipFromJob);
            if (ip == null || ip.isEmpty()) {
                Log.w(TAG, "LAN print-job has no IP jobId=" + jobId);
                postPrintJobResult(jobId, deliveryToken, "failed", "The LAN printer address is missing.");
                return;
            }
            byte[] bytes = Base64.decode(payloadBase64, Base64.DEFAULT);
            if (!postPrintJobResult(jobId, deliveryToken, "accepted", null)) {
                Log.w(TAG, "Server did not acknowledge LAN print acceptance jobId=" + jobId);
                return;
            }
            String errorMessage = sendBytesToPrinter(ip, bytes, timeoutMs, jobId);
            postPrintJobResult(jobId, deliveryToken, errorMessage == null ? "succeeded" : "failed", errorMessage);
        } catch (Exception e) {
            Log.w(TAG, "handleLanPrintJob failed", e);
        }
    }

    private String sendBytesToPrinter(String ip, byte[] bytes, int timeoutMs, String jobId) {
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
            return null;
        } catch (Exception e) {
            Log.w(TAG, "LAN print failed jobId=" + jobId + " ip=" + ip, e);
            return e.getMessage() == null ? "The LAN printer write failed." : e.getMessage();
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ========================================================================
    // Local printer long-poll loop
    // ========================================================================
    //
    // The endpoint names remain Bluetooth-based for deployed-client compatibility,
    // but this loop advertises every locally attached printer it can currently
    // reach and dispatches each job according to its Bluetooth or USB transport.

    private void startLocalPrintLoop() {
        localPrintThread = new Thread(this::runLocalPrintLoop, "ForwarderLocalPrint");
        localPrintThread.setDaemon(true);
        localPrintThread.start();
    }

    private void runLocalPrintLoop() {
        long lastPrinterFetchAt = 0L;
        List<LocalPrinter> available = new ArrayList<>();

        while (running.get()) {
            try {
                long now = System.currentTimeMillis();
                boolean dirty = localPrintersDirty.getAndSet(false);
                if (dirty || available.isEmpty() || now - lastPrinterFetchAt > LOCAL_PRINTERS_REFRESH_MS) {
                    List<LocalPrinter> active = fetchActiveLocalPrinters();
                    available = filterAvailableLocalPrinters(active);
                    lastPrinterFetchAt = System.currentTimeMillis();
                    Log.i(TAG, "Local printers fetched: configured=" + active.size() + " available=" + available.size());
                }

                if (available.isEmpty()) {
                    sleepQuietly(LOCAL_PRINTERS_REFRESH_MS);
                    continue;
                }

                boolean handled = longPollLocalPrintJob(available);
                if (!handled) {
                    sleepQuietly(RETRY_AFTER_ERROR_MS);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Local print loop iteration failed", t);
                sleepQuietly(RETRY_AFTER_ERROR_MS);
            }
        }
        Log.i(TAG, "Local print loop exited");
    }

    private List<LocalPrinter> fetchActiveLocalPrinters() {
        List<LocalPrinter> result = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            final String usedToken = authToken;
            // Kept unchanged so old app and server releases remain interoperable.
            URL url = new URL(baseUrl + "/api/_internal/bluetooth-printers");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            applyCookies(conn);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (handledUnauthorized(status, usedToken)) {
                return result;
            }
            if (status < 200 || status >= 300) {
                Log.w(TAG, "local-printers HTTP " + status);
                return result;
            }
            String body = readAll(conn.getInputStream());
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                LocalPrinter printer = new LocalPrinter();
                printer.printerId = obj.optString("printerId", null);
                printer.transport = obj.optString("transport", "bluetooth");
                printer.target = obj.optString("target", obj.optString("address", null));
                if ("bluetooth".equals(printer.transport) && printer.target != null) {
                    printer.target = printer.target.toUpperCase();
                }
                printer.usbDeviceName = obj.optString("usbDeviceName", null);
                printer.usbVendorId = obj.optInt("usbVendorId", -1);
                printer.usbProductId = obj.optInt("usbProductId", -1);
                printer.usbSerialNumber = obj.isNull("usbSerialNumber")
                    ? null
                    : obj.optString("usbSerialNumber", null);
                if (printer.printerId != null && printer.target != null) {
                    result.add(printer);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchActiveLocalPrinters failed", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return result;
    }

    private List<LocalPrinter> filterAvailableLocalPrinters(List<LocalPrinter> printers) {
        List<LocalPrinter> available = new ArrayList<>();
        Set<String> bondedAddresses = hasBluetoothConnectPermission() ? getBondedAddresses() : new HashSet<>();
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        for (LocalPrinter printer : printers) {
            if ("bluetooth".equals(printer.transport)) {
                if (bondedAddresses.contains(printer.target)) {
                    available.add(printer);
                }
                continue;
            }

            if (!"usb".equals(printer.transport) || usbManager == null) {
                continue;
            }

            UsbDevice device = findUsbDevice(usbManager, printer, printers);
            if (device != null && usbManager.hasPermission(device)) {
                printer.target = device.getDeviceName();
                available.add(printer);
            }
        }
        return available;
    }

    private UsbDevice findUsbDevice(UsbManager manager, LocalPrinter printer, List<LocalPrinter> configured) {
        List<UsbDevice> candidates = new ArrayList<>();
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == printer.usbVendorId && device.getProductId() == printer.usbProductId) {
                candidates.add(device);
            }
        }

        if (printer.usbSerialNumber != null) {
            for (UsbDevice device : candidates) {
                if (!manager.hasPermission(device)) continue;
                try {
                    if (printer.usbSerialNumber.equals(device.getSerialNumber())) return device;
                } catch (SecurityException ignored) {
                }
            }
            return null;
        }

        for (UsbDevice device : candidates) {
            if (device.getDeviceName().equals(printer.usbDeviceName)) return device;
        }

        int configuredWithSameProduct = 0;
        for (LocalPrinter entry : configured) {
            if ("usb".equals(entry.transport)
                && entry.usbVendorId == printer.usbVendorId
                && entry.usbProductId == printer.usbProductId) {
                configuredWithSameProduct++;
            }
        }
        return candidates.size() == 1 && configuredWithSameProduct == 1 ? candidates.get(0) : null;
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

    private boolean longPollLocalPrintJob(List<LocalPrinter> printers) {
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (LocalPrinter printer : printers) {
                JSONObject obj = new JSONObject();
                obj.put("printerId", printer.printerId);
                obj.put("transport", printer.transport);
                obj.put("target", printer.target);
                if ("bluetooth".equals(printer.transport)) {
                    obj.put("address", printer.target);
                }
                arr.put(obj);
            }
            JSONObject body = new JSONObject();
            body.put("printers", arr);
            body.put("screenOn", screenOn.get());
            body.put("supportsPrintResults", true);

            final String usedToken = authToken;
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

            synchronized (localPrintLongPollLock) {
                currentLocalPrintLongPoll = conn;
            }

            int status = conn.getResponseCode();
            if (handledUnauthorized(status, usedToken)) {
                return false;
            }
            if (status < 200 || status >= 300) {
                Log.w(TAG, "bluetooth-print-forward HTTP " + status);
                return false;
            }

            Map<String, LocalPrinter> printerById = new java.util.HashMap<>();
            for (LocalPrinter printer : printers) {
                printerById.put(printer.printerId, printer);
            }

            readLongPollResponse(conn, (event, data) -> {
                if ("print-job".equals(event)) {
                    handleLocalPrintJob(data, printerById);
                }
            });
            return true;
        } catch (Exception e) {
            if (running.get()) {
                Log.w(TAG, "longPollLocalPrintJob failed", e);
            }
            return false;
        } finally {
            synchronized (localPrintLongPollLock) {
                if (currentLocalPrintLongPoll == conn) {
                    currentLocalPrintLongPoll = null;
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

    private void handleLocalPrintJob(String data, Map<String, LocalPrinter> printerById) {
        try {
            JSONObject job = new JSONObject(data);
            String jobId = job.optString("jobId", "");
            String deliveryToken = job.optString("deliveryToken", "");
            String printerId = job.optString("printerId", null);
            LocalPrinter printer = printerById.get(printerId);
            String transport = printer == null ? job.optString("transport", "bluetooth") : printer.transport;
            String target = printer == null ? job.optString("target", null) : printer.target;
            String payloadBase64 = job.optString("payloadBase64", null);
            int timeoutMs = job.optInt("timeoutMs", BLUETOOTH_PRINT_TIMEOUT_MS);
            if (payloadBase64 == null) {
                Log.w(TAG, "Local print-job missing payloadBase64 jobId=" + jobId);
                postPrintJobResult(jobId, deliveryToken, "failed", "The print job payload is missing.");
                return;
            }
            if (target == null || target.isEmpty()) {
                Log.w(TAG, "Local print-job has no target jobId=" + jobId);
                postPrintJobResult(jobId, deliveryToken, "failed", "The local printer target is missing.");
                return;
            }
            byte[] bytes = Base64.decode(payloadBase64, Base64.DEFAULT);
            if (!postPrintJobResult(jobId, deliveryToken, "accepted", null)) {
                Log.w(TAG, "Server did not acknowledge local print acceptance jobId=" + jobId);
                return;
            }
            String errorMessage;
            if ("usb".equals(transport)) {
                errorMessage = sendBytesToUsbPrinter(target, bytes, timeoutMs, jobId);
            } else {
                errorMessage = sendBytesToBluetoothPrinter(target, bytes, timeoutMs, jobId);
            }
            postPrintJobResult(jobId, deliveryToken, errorMessage == null ? "succeeded" : "failed", errorMessage);
        } catch (Exception e) {
            Log.w(TAG, "handleLocalPrintJob failed", e);
        }
    }

    private String sendBytesToBluetoothPrinter(String address, byte[] bytes, int timeoutMs, String jobId) {
        BluetoothAdapter adapter = resolveBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth adapter unavailable jobId=" + jobId);
            return "The Bluetooth adapter is unavailable.";
        }
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Bluetooth connect permission missing jobId=" + jobId);
            return "Bluetooth printer permission is missing.";
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
            return null;
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth print denied jobId=" + jobId + " address=" + address, e);
            return e.getMessage() == null ? "Bluetooth printer access was denied." : e.getMessage();
        } catch (IOException e) {
            Log.w(TAG, "Bluetooth print failed jobId=" + jobId + " address=" + address, e);
            return e.getMessage() == null ? "The Bluetooth printer write failed." : e.getMessage();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String sendBytesToUsbPrinter(String deviceName, byte[] bytes, int timeoutMs, String jobId) {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice device = manager == null ? null : manager.getDeviceList().get(deviceName);
        if (manager == null || device == null || !manager.hasPermission(device)) {
            Log.w(TAG, "USB printer unavailable jobId=" + jobId + " device=" + deviceName);
            return "The USB printer is unavailable or permission is missing.";
        }

        UsbInterface selectedInterface = null;
        UsbEndpoint outputEndpoint = null;
        for (int interfaceIndex = 0; interfaceIndex < device.getInterfaceCount(); interfaceIndex++) {
            UsbInterface candidate = device.getInterface(interfaceIndex);
            for (int endpointIndex = 0; endpointIndex < candidate.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = candidate.getEndpoint(endpointIndex);
                if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    if (outputEndpoint == null || candidate.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                        selectedInterface = candidate;
                        outputEndpoint = endpoint;
                    }
                }
            }
            if (selectedInterface != null && selectedInterface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                break;
            }
        }

        if (selectedInterface == null || outputEndpoint == null) {
            Log.w(TAG, "USB printer has no bulk OUT endpoint jobId=" + jobId + " device=" + deviceName);
            return "The USB printer has no writable endpoint.";
        }

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null || !connection.claimInterface(selectedInterface, true)) {
            if (connection != null) connection.close();
            Log.w(TAG, "Could not claim USB printer interface jobId=" + jobId + " device=" + deviceName);
            return "The USB printer interface could not be opened.";
        }

        try {
            int offset = 0;
            while (offset < bytes.length) {
                int length = Math.min(16_384, bytes.length - offset);
                int transferred = connection.bulkTransfer(outputEndpoint, bytes, offset, length, timeoutMs);
                if (transferred <= 0) {
                    throw new IOException("USB bulk transfer failed at byte " + offset);
                }
                offset += transferred;
            }
            Log.i(TAG, "USB print delivered jobId=" + jobId + " device=" + deviceName + " bytes=" + bytes.length);
            return null;
        } catch (IOException e) {
            Log.w(TAG, "USB print failed jobId=" + jobId + " device=" + deviceName, e);
            return e.getMessage() == null ? "The USB printer write failed." : e.getMessage();
        } finally {
            connection.releaseInterface(selectedInterface);
            connection.close();
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

    // Container used internally for locally attached printer entries.
    private static class LocalPrinter {
        String printerId;
        String transport;
        String target;
        String usbDeviceName;
        int usbVendorId;
        int usbProductId;
        String usbSerialNumber;
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
                List<DiscoveredTerminal> terminals = getDiscoveredTerminals();
                if (terminals.isEmpty()) {
                    waitForPaymentTerminalSignal(LAN_PRINTERS_REFRESH_MS);
                    continue;
                }

                boolean ok = longPollPaymentTerminalRequest(terminals);
                if (!ok) {
                    waitForPaymentTerminalSignal(RETRY_AFTER_ERROR_MS);
                }
            } catch (Throwable t) {
                Log.w(TAG, "payment terminal loop iteration failed", t);
                waitForPaymentTerminalSignal(RETRY_AFTER_ERROR_MS);
            }
        }
        Log.i(TAG, "Payment terminal loop exited");
    }

    private void waitForPaymentTerminalSignal(long timeoutMs) {
        synchronized (paymentTerminalSignal) {
            if (!running.get()) {
                return;
            }
            try {
                paymentTerminalSignal.wait(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void signalPaymentTerminalLoop() {
        synchronized (paymentTerminalSignal) {
            paymentTerminalSignal.notifyAll();
        }
    }

    private boolean longPollPaymentTerminalRequest(List<DiscoveredTerminal> terminals) {
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (DiscoveredTerminal terminal : terminals) {
                arr.put(terminal.terminalId);
            }
            JSONObject body = new JSONObject();
            body.put("forwarderId", forwarderId);
            body.put("terminalIds", arr);
            body.put("screenOn", screenOn.get());
            body.put("appVersion", BuildConfig.VERSION_NAME);
            body.put("forwarderProtocol", PAYMENT_TERMINAL_FORWARDER_PROTOCOL);
            JSONArray capabilities = new JSONArray();
            capabilities.put("terminal-job-claim");
            capabilities.put("terminal-job-cancel");
            capabilities.put("per-terminal-serialization");
            body.put("capabilities", capabilities);

            final String usedToken = authToken;
            URL url = new URL(baseUrl + "/api/_internal/payment-terminal-forward");
            conn = (HttpURLConnection) url.openConnection();
            synchronized (paymentTerminalLongPollLock) {
                currentPaymentTerminalLongPoll = conn;
            }
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

            int status = conn.getResponseCode();
            if (handledUnauthorized(status, usedToken)) {
                return false;
            }
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
            String paymentTerminalId = job.optString("paymentTerminalId", "");
            String forwardedUrl = job.optString("url", null);
            String method = job.optString("method", "POST");
            int timeoutMs = job.optInt("timeoutMs", TERMINAL_REQUEST_DEFAULT_TIMEOUT_MS);
            int forwarderProtocol = job.optInt("forwarderProtocol", 1);
            String deliveryToken = job.optString("deliveryToken", "");
            String operationMode = job.optString("operationMode", "exclusive");
            String responseDeliveryToken = forwarderProtocol >= 2 ? deliveryToken : null;
            JSONObject headers = job.optJSONObject("headers");
            String requestBody = job.isNull("body") ? null : job.optString("body", null);
            if (paymentTerminalId.isEmpty()) {
                postTerminalError(jobId, responseDeliveryToken, "Forwarded terminal request missing paymentTerminalId.");
                return;
            }
            if (forwardedUrl == null) {
                postTerminalError(jobId, responseDeliveryToken, "Forwarded terminal request missing url.");
                return;
            }
            if (forwarderProtocol >= 2 && deliveryToken.isEmpty()) {
                postTerminalError(jobId, "Forwarded terminal v2 request is missing its delivery token.");
                return;
            }
            URL parsedForwardedUrl = new URL(forwardedUrl);
            String path = parsedForwardedUrl.getFile();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                postTerminalError(jobId, responseDeliveryToken, "Forwarded terminal request url has an invalid path.");
                return;
            }
            final String finalJobId = jobId;
            final String finalPaymentTerminalId = paymentTerminalId;
            final String finalPath = path;
            final String finalMethod = method;
            final int finalTimeoutMs = timeoutMs;
            final JSONObject finalHeaders = headers;
            final String finalBody = requestBody;
            final String finalDeliveryToken = deliveryToken;

            if (forwarderProtocol < 2) {
                // Protocol v1 stays byte-for-byte compatible while customers roll
                // forward. It deliberately retains the old concurrent worker model.
                Thread worker = new Thread(() -> dispatchTerminalRequestV1(
                    finalJobId,
                    finalPaymentTerminalId,
                    finalPath,
                    finalMethod,
                    finalHeaders,
                    finalBody,
                    finalTimeoutMs
                ), "ForwarderTerminalJobV1-" + jobId);
                worker.setDaemon(true);
                worker.start();
                return;
            }

            if (!updateTerminalJobLifecycle(finalJobId, finalDeliveryToken, "claim")) {
                Log.i(TAG, "Server rejected terminal job claim jobId=" + finalJobId);
                return;
            }

            Runnable operation = () -> dispatchTerminalRequestV2(
                finalJobId,
                finalPaymentTerminalId,
                finalPath,
                finalMethod,
                finalHeaders,
                finalBody,
                finalTimeoutMs,
                finalDeliveryToken
            );
            if ("modeless".equals(operationMode)) {
                Thread worker = new Thread(operation, "ForwarderTerminalModelessJob-" + jobId);
                worker.setDaemon(true);
                worker.start();
            } else {
                ExecutorService executor = paymentTerminalExecutors.computeIfAbsent(
                    paymentTerminalId,
                    ignored -> Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(
                            runnable,
                            "ForwarderTerminalExclusive-" + paymentTerminalId
                        );
                        thread.setDaemon(true);
                        return thread;
                    })
                );
                executor.execute(operation);
            }
        } catch (Exception e) {
            Log.w(TAG, "handleTerminalRequest failed to parse", e);
        }
    }

    private void dispatchTerminalRequestV1(
        String jobId,
        String paymentTerminalId,
        String path,
        String method,
        JSONObject headers,
        String body,
        int timeoutMs
    ) {
        dispatchTerminalRequest(jobId, paymentTerminalId, path, method, headers, body, timeoutMs, null);
    }

    private void dispatchTerminalRequestV2(
        String jobId,
        String paymentTerminalId,
        String path,
        String method,
        JSONObject headers,
        String body,
        int timeoutMs,
        String deliveryToken
    ) {
        if (!updateTerminalJobLifecycle(jobId, deliveryToken, "start")) {
            Log.i(TAG, "Server cancelled terminal job before start jobId=" + jobId);
            postTerminalCancellation(jobId, deliveryToken, false);
            return;
        }
        dispatchTerminalRequest(jobId, paymentTerminalId, path, method, headers, body, timeoutMs, deliveryToken);
    }

    private void dispatchTerminalRequest(
        String jobId,
        String paymentTerminalId,
        String path,
        String method,
        JSONObject headers,
        String body,
        int timeoutMs,
        String deliveryToken
    ) {
        HttpURLConnection conn = null;
        TerminalJobExecution execution = deliveryToken == null ? null : new TerminalJobExecution();
        Thread cancellationWatcher = null;
        try {
            if (execution != null) {
                activeTerminalJobs.put(jobId, execution);
                cancellationWatcher = startTerminalJobCancellationWatcher(jobId, deliveryToken, execution);
            }
            DiscoveredTerminal terminal = discoveredTerminals.get(paymentTerminalId);
            if (terminal == null) {
                throw new IOException(
                    "Payment terminal " + paymentTerminalId + " is not discovered on the current Wi-Fi network."
                );
            }
            URL url = new URL("http", terminal.ipAddress, terminal.port, path);
            Log.i(
                TAG,
                "dispatch terminal request jobId=" + jobId + " terminal=" + paymentTerminalId
                    + " " + method + " " + url
            );
            conn = (HttpURLConnection) url.openConnection();
            if (execution != null) {
                execution.setConnection(conn);
                if (execution.isCancellationRequested()) {
                    throw new IOException("Forwarded terminal request was cancelled before dispatch.");
                }
            }
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
            postTerminalResult(jobId, deliveryToken, result, null);
        } catch (Exception e) {
            Log.w(TAG, "dispatchTerminalRequest failed jobId=" + jobId, e);
            if (execution != null && execution.isCancellationRequested()) {
                postTerminalCancellation(jobId, deliveryToken, true);
            } else {
                postTerminalError(jobId, deliveryToken, e.getMessage() == null
                    ? "Forwarded terminal request failed." : e.getMessage());
            }
        } finally {
            if (execution != null) {
                execution.markFinished();
                activeTerminalJobs.remove(jobId, execution);
            }
            if (cancellationWatcher != null) {
                cancellationWatcher.interrupt();
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean updateTerminalJobLifecycle(String jobId, String deliveryToken, String action) {
        HttpURLConnection conn = null;
        try {
            final String usedToken = authToken;
            URL url = new URL(baseUrl + "/api/_internal/payment-terminal-forward-job");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            applyCookies(conn);

            JSONObject body = new JSONObject();
            body.put("jobId", jobId);
            body.put("forwarderId", forwarderId);
            body.put("deliveryToken", deliveryToken);
            body.put("action", action);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (handledUnauthorized(status, usedToken) || status < 200 || status >= 300) {
                return false;
            }
            String responseBody = readAll(conn.getInputStream());
            JSONObject response = new JSONObject(responseBody);
            return "poll".equals(action)
                ? response.optBoolean("cancelRequested", false)
                : response.optBoolean("accepted", false);
        } catch (Exception e) {
            Log.w(TAG, "terminal job lifecycle failed jobId=" + jobId + " action=" + action, e);
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Thread startTerminalJobCancellationWatcher(
        String jobId,
        String deliveryToken,
        TerminalJobExecution execution
    ) {
        Thread watcher = new Thread(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted() && !execution.isFinished()) {
                if (updateTerminalJobLifecycle(jobId, deliveryToken, "poll")) {
                    Log.i(TAG, "Cancelling active terminal request jobId=" + jobId);
                    execution.requestCancellation();
                    return;
                }
                sleepQuietly(TERMINAL_JOB_CONTROL_POLL_MS);
            }
        }, "ForwarderTerminalJobControl-" + jobId);
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    private void postTerminalCancellation(String jobId, String deliveryToken, boolean requestStarted) {
        JSONObject result = new JSONObject();
        try {
            result.put("cancelled", true);
            result.put("requestStarted", requestStarted);
        } catch (Exception ignored) {
        }
        postTerminalResult(jobId, deliveryToken, result, null);
    }

    private boolean postPrintJobResult(String jobId, String deliveryToken, String status, String errorMessage) {
        if (jobId == null || jobId.isEmpty() || deliveryToken == null || deliveryToken.isEmpty()) {
            return false;
        }

        long startedAt = System.currentTimeMillis();
        while (running.get() && System.currentTimeMillis() - startedAt <= PRINT_RESULT_SUBMIT_MAX_MS) {
            HttpURLConnection conn = null;
            try {
                final String usedToken = authToken;
                URL url = new URL(baseUrl + "/api/_internal/print-forward-result");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json");
                applyCookies(conn);

                JSONObject body = new JSONObject();
                body.put("jobId", jobId);
                body.put("deliveryToken", deliveryToken);
                body.put("status", status);
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    body.put("errorMessage", errorMessage.substring(0, Math.min(errorMessage.length(), 1_000)));
                }
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int responseStatus = conn.getResponseCode();
                if (handledUnauthorized(responseStatus, usedToken)) {
                    return false;
                }
                if (responseStatus >= 200 && responseStatus < 300) {
                    Log.i(TAG, "Submitted print result jobId=" + jobId + " status=" + status);
                    return true;
                }
                Log.w(TAG, "Submit print result HTTP " + responseStatus + " jobId=" + jobId + " status=" + status);
            } catch (Exception e) {
                Log.w(TAG, "Submit print result failed jobId=" + jobId + " status=" + status, e);
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

        Log.w(TAG, "Gave up submitting print result jobId=" + jobId + " status=" + status);
        return false;
    }

    private void postTerminalResult(String jobId, String deliveryToken, JSONObject result, String errorMessage) {
        long startedAt = System.currentTimeMillis();
        while (running.get() && System.currentTimeMillis() - startedAt <= RESPONSE_SUBMIT_MAX_MS) {
            HttpURLConnection conn = null;
            try {
                final String usedToken = authToken;
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
                if (deliveryToken != null) {
                    body.put("forwarderId", forwarderId);
                    body.put("deliveryToken", deliveryToken);
                }
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
                if (handledUnauthorized(status, usedToken)) {
                    return;
                }
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
        postTerminalError(jobId, null, message);
    }

    private void postTerminalError(String jobId, String deliveryToken, String message) {
        postTerminalResult(jobId, deliveryToken, null, message);
    }

    private static final class TerminalJobExecution {
        private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile HttpURLConnection connection;

        void setConnection(HttpURLConnection connection) {
            this.connection = connection;
            if (cancellationRequested.get()) {
                connection.disconnect();
            }
        }

        boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        boolean isFinished() {
            return finished.get();
        }

        void markFinished() {
            finished.set(true);
        }

        void requestCancellation() {
            cancellationRequested.set(true);
            HttpURLConnection activeConnection = connection;
            if (activeConnection != null) {
                activeConnection.disconnect();
            }
        }
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
                if (networkCallback == null) {
                    registerNetworkCallback();
                }
                acquireMulticastLock();
                startDiscoveryThreadIfNeeded();
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
                obj.put("port", terminal.port);
                obj.put("lastSeenAt", formatIsoUtc(terminal.lastSeenAtMs));
                arr.put(obj);
            }
            JSONObject body = new JSONObject();
            body.put("terminals", arr);

            final String usedToken = authToken;
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
            if (handledUnauthorized(status, usedToken)) {
                return;
            }
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
    // Takeaway order long-poll loop
    // ========================================================================

    private void startTakeawayOrderLoop() {
        takeawayOrderThread = new Thread(this::runTakeawayOrderLoop, "ForwarderTakeawayOrders");
        takeawayOrderThread.setDaemon(true);
        takeawayOrderThread.start();
    }

    private void runTakeawayOrderLoop() {
        while (running.get()) {
            if (!takeawayEnabled.get()) {
                waitForTakeawayOrderSignal();
                continue;
            }

            try {
                if (!longPollTakeawayOrder()) {
                    sleepQuietly(RETRY_AFTER_ERROR_MS);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Takeaway order loop iteration failed", t);
                sleepQuietly(RETRY_AFTER_ERROR_MS);
            }
        }
        Log.i(TAG, "Takeaway order loop exited");
    }

    private void waitForTakeawayOrderSignal() {
        synchronized (takeawayOrderSignal) {
            if (!running.get() || takeawayEnabled.get()) {
                return;
            }
            try {
                takeawayOrderSignal.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void signalTakeawayOrderLoop() {
        synchronized (takeawayOrderSignal) {
            takeawayOrderSignal.notifyAll();
        }
    }

    private boolean longPollTakeawayOrder() {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            String cursor = takeawayOrderCursor;
            if (cursor != null && !cursor.isEmpty()) {
                body.put("cursor", cursor);
            } else {
                body.put("startedAt", takeawayOrderStartedAt);
            }

            final String usedToken = authToken;
            URL url = new URL(baseUrl + "/api/_internal/takeaway-orders");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            applyCookies(conn);

            synchronized (takeawayOrderLongPollLock) {
                currentTakeawayOrderLongPoll = conn;
            }

            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (handledUnauthorized(status, usedToken)) {
                return false;
            }
            if (status < 200 || status >= 300) {
                Log.w(TAG, "takeaway-orders HTTP " + status);
                return false;
            }

            JSONObject response = new JSONObject(readAll(conn.getInputStream()));
            String responseCursor = response.optString("cursor", "");
            if (!responseCursor.isEmpty()) {
                takeawayOrderCursor = responseCursor;
            }

            JSONObject event = response.optJSONObject("event");
            if (event != null) {
                handleTakeawayOrderEvent(event);
            }
            return true;
        } catch (Exception e) {
            if (running.get() && takeawayEnabled.get()) {
                Log.w(TAG, "Takeaway order long poll failed", e);
            }
            return false;
        } finally {
            synchronized (takeawayOrderLongPollLock) {
                if (currentTakeawayOrderLongPoll == conn) {
                    currentTakeawayOrderLongPoll = null;
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

    private void handleTakeawayOrderEvent(JSONObject event) {
        if (!"created".equals(event.optString("type", ""))) {
            JSONObject updatedOrder = event.optJSONObject("order");
            if (updatedOrder != null) {
                cancelTakeawayOrderNotification(updatedOrder.optString("id", ""));
            }
            notifyTakeawayOrder(event);
            return;
        }

        JSONObject order = event.optJSONObject("order");
        if (order == null) {
            return;
        }

        boolean showAndroidNotification = shouldShowAndroidNotification();
        try {
            event.put("notificationMode", showAndroidNotification ? "android" : "web");
        } catch (Exception e) {
            Log.w(TAG, "Could not attach takeaway notification mode", e);
        }
        notifyTakeawayOrder(event);

        if (showAndroidNotification) {
            showTakeawayOrderNotification(
                order,
                event.optString("notificationActionToken", "")
            );
        } else {
            playNotificationSound();
        }
    }

    private void playNotificationSound() {
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone ringtone = RingtoneManager.getRingtone(getApplicationContext(), sound);
            if (ringtone != null) {
                ringtone.play();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not play takeaway notification sound", e);
        }
    }

    private void showTakeawayOrderNotification(JSONObject order, String notificationActionToken) {
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                TAKEAWAY_CHANNEL_ID,
                "Takeaway orders",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("New takeaway orders received by NemBestil POS.");
            channel.enableVibration(true);
            channel.setSound(sound, null);
            manager.createNotificationChannel(channel);
        }

        String orderId = order.optString("id", UUID.randomUUID().toString());
        String orderNumber = order.optString("orderNumber", "");
        String title = orderNumber.isEmpty()
            ? "New takeaway order"
            : "New takeaway order " + orderNumber;
        String summary = createTakeawayOrderSummary(order);
        int notificationId = getTakeawayOrderNotificationId(orderId);

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = null;
        if (launchIntent != null) {
            launchIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            launchIntent.putExtra(EXTRA_OPEN_TAKEAWAY_ORDERS, true);
            launchIntent.putExtra(EXTRA_TAKEAWAY_ORDER_ID, orderId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            contentIntent = PendingIntent.getActivity(this, orderId.hashCode(), launchIntent, flags);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, TAKEAWAY_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(summary))
            .setSmallIcon(R.drawable.ic_stat_forwarder)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(sound)
            .setDefaults(Notification.DEFAULT_ALL);
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }

        if (!notificationActionToken.isEmpty()) {
            builder.addAction(
                0,
                "Accept",
                createTakeawayOrderActionIntent(
                    TakeawayNotificationActionReceiver.ACTION_ACCEPT,
                    orderId,
                    notificationActionToken,
                    notificationId
                )
            );
            builder.addAction(
                0,
                "Cancel",
                createTakeawayOrderActionIntent(
                    TakeawayNotificationActionReceiver.ACTION_CANCEL,
                    orderId,
                    notificationActionToken,
                    notificationId
                )
            );
        }
        if (contentIntent != null) {
            builder.addAction(0, "Show order", contentIntent);
        }

        manager.notify(notificationId, builder.build());
    }

    private PendingIntent createTakeawayOrderActionIntent(
        String action,
        String orderId,
        String notificationActionToken,
        int notificationId
    ) {
        Intent intent = new Intent(this, TakeawayNotificationActionReceiver.class);
        intent.setAction(action);
        intent.putExtra(TakeawayNotificationActionReceiver.EXTRA_BASE_URL, baseUrl);
        intent.putExtra(TakeawayNotificationActionReceiver.EXTRA_ORDER_ID, orderId);
        intent.putExtra(TakeawayNotificationActionReceiver.EXTRA_ACTION_TOKEN, notificationActionToken);
        intent.putExtra(TakeawayNotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId);
        int requestCode = 31 * orderId.hashCode() + action.hashCode();
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void cancelTakeawayOrderNotification(String orderId) {
        if (orderId.isEmpty()) {
            return;
        }
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(getTakeawayOrderNotificationId(orderId));
        }
    }

    private int getTakeawayOrderNotificationId(String orderId) {
        return TAKEAWAY_NOTIFICATION_ID_BASE + Math.abs(orderId.hashCode() % 10_000);
    }

    private String createTakeawayOrderSummary(JSONObject order) {
        JSONObject billing = order.optJSONObject("billing");
        String customer = billing == null ? "" : billing.optString("name", "");
        String fulfillment = "delivery".equals(order.optString("deliveryPickupType", ""))
            ? "Delivery"
            : "Pickup";
        String scheduledTime = formatTakeawayScheduledTime(order.optString("scheduledAt", ""));
        String total = formatTakeawayTotal(order);
        JSONArray items = order.optJSONArray("items");
        double itemCount = 0;
        if (items != null) {
            for (int index = 0; index < items.length(); index++) {
                itemCount += items.optJSONObject(index) == null
                    ? 0
                    : items.optJSONObject(index).optDouble("quantity", 0);
            }
        }

        List<String> details = new ArrayList<>();
        if (!customer.isEmpty()) {
            details.add(customer);
        }
        details.add(scheduledTime.isEmpty() ? fulfillment : fulfillment + " " + scheduledTime);
        details.add(NumberFormat.getNumberInstance().format(itemCount) + " items");
        if (!total.isEmpty()) {
            details.add(total);
        }
        return TextUtils.join(" · ", details);
    }

    private String formatTakeawayScheduledTime(String timestamp) {
        if (timestamp.isEmpty()) {
            return "";
        }
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US);
            Date scheduledAt = parser.parse(timestamp);
            return scheduledAt == null ? "" : DateFormat.getTimeInstance(DateFormat.SHORT).format(scheduledAt);
        } catch (Exception e) {
            return "";
        }
    }

    private String formatTakeawayTotal(JSONObject order) {
        String currencyCode = order.optString("currency", "");
        if (currencyCode.isEmpty()) {
            return "";
        }
        try {
            NumberFormat formatter = NumberFormat.getCurrencyInstance();
            formatter.setCurrency(Currency.getInstance(currencyCode));
            return formatter.format(order.optLong("totalAmount", 0) / 100.0);
        } catch (Exception e) {
            return "";
        }
    }

    // ========================================================================
    // Table booking long-poll loop
    // ========================================================================

    private void startTableBookingLoop() {
        tableBookingThread = new Thread(this::runTableBookingLoop, "ForwarderTableBookings");
        tableBookingThread.setDaemon(true);
        tableBookingThread.start();
    }

    private void runTableBookingLoop() {
        while (running.get()) {
            if (!tableBookingEnabled.get()) {
                waitForTableBookingSignal();
                continue;
            }

            try {
                if (!longPollTableBooking()) {
                    sleepQuietly(RETRY_AFTER_ERROR_MS);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Table booking loop iteration failed", t);
                sleepQuietly(RETRY_AFTER_ERROR_MS);
            }
        }
        Log.i(TAG, "Table booking loop exited");
    }

    private void waitForTableBookingSignal() {
        synchronized (tableBookingSignal) {
            if (!running.get() || tableBookingEnabled.get()) {
                return;
            }
            try {
                tableBookingSignal.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void signalTableBookingLoop() {
        synchronized (tableBookingSignal) {
            tableBookingSignal.notifyAll();
        }
    }

    private boolean longPollTableBooking() {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            String cursor = tableBookingCursor;
            if (cursor != null && !cursor.isEmpty()) {
                body.put("cursor", cursor);
            } else {
                body.put("startedAt", tableBookingStartedAt);
            }

            final String usedToken = authToken;
            URL url = new URL(baseUrl + "/api/_internal/table-bookings");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(LONG_POLL_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LONG_POLL_READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            applyCookies(conn);

            synchronized (tableBookingLongPollLock) {
                currentTableBookingLongPoll = conn;
            }

            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (handledUnauthorized(status, usedToken)) {
                return false;
            }
            if (status < 200 || status >= 300) {
                Log.w(TAG, "table-bookings HTTP " + status);
                return false;
            }

            JSONObject response = new JSONObject(readAll(conn.getInputStream()));
            String responseCursor = response.optString("cursor", "");
            if (!responseCursor.isEmpty()) {
                tableBookingCursor = responseCursor;
            }

            JSONObject event = response.optJSONObject("event");
            if (event != null) {
                handleTableBookingEvent(event);
            }
            return true;
        } catch (Exception e) {
            if (running.get() && tableBookingEnabled.get()) {
                Log.w(TAG, "Table booking long poll failed", e);
            }
            return false;
        } finally {
            synchronized (tableBookingLongPollLock) {
                if (currentTableBookingLongPoll == conn) {
                    currentTableBookingLongPoll = null;
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

    private void handleTableBookingEvent(JSONObject event) {
        String type = event.optString("type", "");
        if ("deleted".equals(type)) {
            cancelTableBookingNotification(event.optString("bookingId", ""));
            notifyTableBooking(event);
            return;
        }

        JSONObject booking = event.optJSONObject("booking");
        if (booking == null) {
            notifyTableBooking(event);
            return;
        }

        String bookingId = booking.optString("id", "");
        boolean unconfirmedCreated = "created".equals(type)
            && event.optBoolean("notifyUnconfirmed", false)
            && "pending".equals(booking.optString("status", ""));
        if (!unconfirmedCreated) {
            if (!"pending".equals(booking.optString("status", ""))) {
                cancelTableBookingNotification(bookingId);
            }
            notifyTableBooking(event);
            return;
        }

        boolean showAndroidNotification = shouldShowAndroidNotification();
        try {
            event.put("notificationMode", showAndroidNotification ? "android" : "web");
        } catch (Exception e) {
            Log.w(TAG, "Could not attach table booking notification mode", e);
        }
        notifyTableBooking(event);

        if (showAndroidNotification) {
            showTableBookingNotification(booking);
        } else {
            playNotificationSound();
        }
    }

    private boolean shouldShowAndroidNotification() {
        return !screenOn.get() || !appFocused.get();
    }

    private void showTableBookingNotification(JSONObject booking) {
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                TABLE_BOOKING_CHANNEL_ID,
                "Table bookings",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("New table bookings awaiting confirmation in NemBestil POS.");
            channel.enableVibration(true);
            channel.setSound(sound, null);
            manager.createNotificationChannel(channel);
        }

        String bookingId = booking.optString("id", UUID.randomUUID().toString());
        String summary = createTableBookingSummary(booking);
        int notificationId = getTableBookingNotificationId(bookingId);
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = null;
        if (launchIntent != null) {
            launchIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            launchIntent.putExtra(EXTRA_OPEN_TABLE_BOOKINGS, true);
            launchIntent.putExtra(EXTRA_TABLE_BOOKING_ID, bookingId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            contentIntent = PendingIntent.getActivity(this, bookingId.hashCode(), launchIntent, flags);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, TABLE_BOOKING_CHANNEL_ID)
            .setContentTitle("Table booking awaiting confirmation")
            .setContentText(summary)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(summary))
            .setSmallIcon(R.drawable.ic_stat_forwarder)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(sound)
            .setDefaults(Notification.DEFAULT_ALL);
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
            builder.addAction(0, "Review booking", contentIntent);
        }
        manager.notify(notificationId, builder.build());
    }

    private void cancelTableBookingNotification(String bookingId) {
        if (bookingId.isEmpty()) {
            return;
        }
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(getTableBookingNotificationId(bookingId));
        }
    }

    private int getTableBookingNotificationId(String bookingId) {
        return TABLE_BOOKING_NOTIFICATION_ID_BASE + Math.abs(bookingId.hashCode() % 10_000);
    }

    private String createTableBookingSummary(JSONObject booking) {
        String customer = "";
        JSONArray details = booking.optJSONArray("details");
        if (details != null) {
            for (int index = 0; index < details.length(); index++) {
                JSONObject detail = details.optJSONObject(index);
                if (detail != null && "name".equals(detail.optString("type", ""))) {
                    customer = detail.optString("value", "");
                    break;
                }
            }
        }

        List<String> summary = new ArrayList<>();
        if (!customer.isEmpty()) {
            summary.add(customer);
        }
        String date = booking.optString("date", "");
        String time = booking.optString("timeFrom", "");
        String dateTime = (date + " " + time).trim();
        if (!dateTime.isEmpty()) {
            summary.add(dateTime);
        }
        summary.add(NumberFormat.getIntegerInstance().format(booking.optInt("guests", 0)) + " guests");
        return TextUtils.join(" · ", summary);
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

    // ========================================================================
    // Token rejection handling
    // ========================================================================
    //
    // Every forwarder call authenticates with the androidsync Bearer token. Once
    // the server rejects it (401 "Invalid or expired forwarder token") the token
    // is dead for good — only an authenticated WebView can mint a new one. Rather
    // than hammer every endpoint with a doomed token forever, the first loop to
    // see a 401 clears the token, warns the operator, tells the WebView, and
    // stops the service.

    /**
     * @return true when the status was a 401 for {@code usedToken} and the token
     *     was invalidated — callers should bail out of their current request.
     */
    private boolean handledUnauthorized(int status, String usedToken) {
        if (status != 401) {
            return false;
        }
        handleUnauthorized(usedToken);
        return true;
    }

    private void handleUnauthorized(String usedToken) {
        String current = authToken;
        if (usedToken == null || current == null || !usedToken.equals(current)) {
            // The token was rotated between this request going out and the 401
            // coming back (a refresh raced with us). The in-flight token is
            // already gone, so don't punish the new one.
            return;
        }
        if (!tokenInvalidated.compareAndSet(false, true)) {
            // Another loop already noticed and is tearing things down.
            return;
        }
        Log.w(TAG, "Forwarder token rejected by server (401); clearing it and stopping");

        // Remove the token from the device so a sticky restart can't revive it.
        authToken = null;
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREFS_TOKEN).apply();

        showTokenRejectedNotification();
        notifyTokenRejected();

        // Nothing left to do without a token — stop the loops and the service.
        stopForwarder();
        new Handler(Looper.getMainLooper()).post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void showTokenRejectedNotification() {
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                ALERT_CHANNEL_ID,
                "NemBestil forwarder alerts",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Warns when this tablet can no longer forward printer and payment terminal traffic.");
            manager.createNotificationChannel(channel);
        }

        String message = "This tablet can no longer forward printer and payment terminal traffic. "
            + "Open NemBestil POS and sign in to restore it.";

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = null;
        if (launchIntent != null) {
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            contentIntent = PendingIntent.getActivity(this, 1, launchIntent, flags);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("NemBestil POS – action needed")
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_stat_forwarder)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR);
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }
        manager.notify(ALERT_NOTIFICATION_ID, builder.build());
    }

    public interface TokenListener {
        void onTokenRejected();
    }

    public static void registerTokenListener(TokenListener listener) {
        tokenListeners.addIfAbsent(listener);
    }

    public static void unregisterTokenListener(TokenListener listener) {
        tokenListeners.remove(listener);
    }

    private void notifyTokenRejected() {
        for (TokenListener listener : tokenListeners) {
            try {
                listener.onTokenRejected();
            } catch (Exception e) {
                Log.w(TAG, "Token listener threw", e);
            }
        }
    }

    public interface TakeawayOrderListener {
        void onTakeawayOrder(JSONObject event);
    }

    public static void registerTakeawayOrderListener(TakeawayOrderListener listener) {
        takeawayOrderListeners.addIfAbsent(listener);
    }

    public static void unregisterTakeawayOrderListener(TakeawayOrderListener listener) {
        takeawayOrderListeners.remove(listener);
    }

    private void notifyTakeawayOrder(JSONObject event) {
        for (TakeawayOrderListener listener : takeawayOrderListeners) {
            try {
                listener.onTakeawayOrder(event);
            } catch (Exception e) {
                Log.w(TAG, "Takeaway order listener threw", e);
            }
        }
    }

    public interface TableBookingListener {
        void onTableBooking(JSONObject event);
    }

    public static void registerTableBookingListener(TableBookingListener listener) {
        tableBookingListeners.addIfAbsent(listener);
    }

    public static void unregisterTableBookingListener(TableBookingListener listener) {
        tableBookingListeners.remove(listener);
    }

    private void notifyTableBooking(JSONObject event) {
        for (TableBookingListener listener : tableBookingListeners) {
            try {
                listener.onTableBooking(event);
            } catch (Exception e) {
                Log.w(TAG, "Table booking listener threw", e);
            }
        }
    }

    public static void setAppFocused(boolean focused) {
        appFocused.set(focused);
    }

    public static String getConfiguredBaseUrl(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_BASE_URL, null);
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

    /** Snapshot of terminals announced on the current Wi-Fi network. */
    public static List<DiscoveredTerminal> getDiscoveredTerminals() {
        List<DiscoveredTerminal> snapshot = new ArrayList<>(discoveredTerminals.values());
        snapshot.sort(Comparator.comparing(t -> t.terminalId));
        return snapshot;
    }

    private void startDiscoveryListener() {
        if (!discoveryRunning.compareAndSet(false, true)) {
            return;
        }
        discoveredTerminals.clear();
        acquireMulticastLock();
        registerNetworkCallback();
        startDiscoveryThreadIfNeeded();
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
        Thread t;
        synchronized (discoveryThreadLock) {
            t = discoveryThread;
            discoveryThread = null;
        }
        if (t != null) {
            t.interrupt();
        }
        unregisterNetworkCallback();
        releaseMulticastLock();
        discoveredTerminals.clear();
        signalPaymentTerminalLoop();
    }

    private void startDiscoveryThreadIfNeeded() {
        synchronized (discoveryThreadLock) {
            Thread current = discoveryThread;
            if (!discoveryRunning.get() || (current != null && current.isAlive())) {
                return;
            }
            Thread next = new Thread(this::runDiscoveryListener, "ForwarderDiscovery");
            next.setDaemon(true);
            discoveryThread = next;
            next.start();
        }
    }

    private void resetDiscoveryForNetworkChange(String reason) {
        Log.i(TAG, reason + "; resetting terminal discovery");
        discoveredTerminals.clear();
        interruptPaymentTerminalLongPoll();
        signalPaymentTerminalLoop();
        restartDiscoveryTransport();
    }

    private void restartDiscoveryTransport() {
        releaseMulticastLock();
        acquireMulticastLock();
        DatagramSocket socket = discoverySocket;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        startDiscoveryThreadIfNeeded();
    }

    private void registerNetworkCallback() {
        synchronized (networkCallbackLock) {
            if (!discoveryRunning.get() || networkCallback != null) {
                return;
            }
            try {
                ConnectivityManager cm =
                    (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) {
                    return;
                }
                NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
                ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        Network previous = currentNetwork;
                        currentNetwork = network;
                        if (previous == null) {
                            resetDiscoveryForNetworkChange("Wi-Fi network became available");
                        } else if (!previous.equals(network)) {
                            resetDiscoveryForNetworkChange("Wi-Fi network changed");
                        }
                    }

                    @Override
                    public void onLost(Network network) {
                        if (network.equals(currentNetwork)) {
                            currentNetwork = null;
                            resetDiscoveryForNetworkChange("Wi-Fi network was lost");
                        }
                    }
                };
                cm.registerNetworkCallback(request, callback);
                networkCallback = callback;
            } catch (Exception e) {
                Log.w(TAG, "Failed to register network callback", e);
                networkCallback = null;
            }
        }
    }

    private void unregisterNetworkCallback() {
        synchronized (networkCallbackLock) {
            ConnectivityManager.NetworkCallback callback = networkCallback;
            networkCallback = null;
            currentNetwork = null;
            if (callback == null) {
                return;
            }
            try {
                ConnectivityManager cm =
                    (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(callback);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister network callback", e);
            }
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

    private synchronized void acquireMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                return;
            }
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

    private synchronized void releaseMulticastLock() {
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
            while (discoveryRunning.get()) {
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket(null);
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    Network wifiNetwork = currentNetwork;
                    if (wifiNetwork != null) {
                        wifiNetwork.bindSocket(socket);
                    }
                    socket.bind(new InetSocketAddress(DISCOVERY_PORT));
                    discoverySocket = socket;
                    Log.i(
                        TAG,
                        "Worldline discovery listening on Wi-Fi at 0.0.0.0:" + DISCOVERY_PORT
                    );

                    byte[] buffer = new byte[DISCOVERY_BUFFER_SIZE];
                    while (discoveryRunning.get() && !socket.isClosed()) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        handleDiscoveryPacket(packet.getData(), packet.getLength());
                    }
                } catch (Exception e) {
                    if (!discoveryRunning.get()) {
                        return;
                    }
                    Log.w(TAG, "Worldline discovery transport failed; restarting", e);
                } finally {
                    if (discoverySocket == socket) {
                        discoverySocket = null;
                    }
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                }
                if (discoveryRunning.get()) {
                    sleepQuietly(DISCOVERY_RESTART_DELAY_MS);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Discovery listener crashed", e);
        } finally {
            synchronized (discoveryThreadLock) {
                if (discoveryThread == Thread.currentThread()) {
                    discoveryThread = null;
                }
            }
        }
    }

    private void handleDiscoveryPacket(byte[] data, int length) {
        DiscoveredTerminal terminal = parseDiscoveryPayload(data, length);
        if (terminal == null) {
            return;
        }
        DiscoveredTerminal previous = discoveredTerminals.get(terminal.terminalId);
        boolean isNew = previous == null;
        boolean endpointChanged = previous != null
            && (!previous.ipAddress.equals(terminal.ipAddress) || previous.port != terminal.port);
        discoveredTerminals.put(terminal.terminalId, terminal);
        if (isNew) {
            Log.i(TAG, "Discovered Worldline terminal: "
                + terminal.terminalId + " (" + terminal.ipAddress + ":" + terminal.port + ")");
        } else if (endpointChanged) {
            Log.i(TAG, "Worldline terminal endpoint changed: "
                + terminal.terminalId + " (" + previous.ipAddress + ":" + previous.port
                + " -> " + terminal.ipAddress + ":" + terminal.port + ")");
        }
        if (isNew || endpointChanged) {
            signalPaymentTerminalLoop();
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_BASE_URL, stripTrailingSlash(baseUrl))
            .putString(PREFS_TOKEN, token)
            .apply();
        startService(context, baseUrl, token);
    }

    public static void requestStartIfConfigured(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String baseUrl = prefs.getString(PREFS_BASE_URL, null);
        String token = prefs.getString(PREFS_TOKEN, null);
        if (baseUrl == null || baseUrl.isEmpty() || token == null || token.isEmpty()) {
            return;
        }
        startService(context, baseUrl, token);
    }

    private static void startService(Context context, String baseUrl, String token) {
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREFS_BASE_URL)
            .remove(PREFS_TOKEN)
            .apply();
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

    public static void requestUpdateTakeawayState(
        Context context,
        boolean enabled
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFS_TAKEAWAY_ENABLED, enabled)
            .apply();
        if (!isRunning()) {
            return;
        }

        Intent intent = new Intent(context, ForwarderService.class);
        intent.setAction(ACTION_UPDATE_TAKEAWAY_STATE);
        intent.putExtra(EXTRA_TAKEAWAY_ENABLED, enabled);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignored) {
        }
    }

    public static void requestUpdateTableBookingState(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFS_TABLE_BOOKING_ENABLED, enabled)
            .apply();
        if (!isRunning()) {
            return;
        }

        Intent intent = new Intent(context, ForwarderService.class);
        intent.setAction(ACTION_UPDATE_TABLE_BOOKING_STATE);
        intent.putExtra(EXTRA_TABLE_BOOKING_ENABLED, enabled);
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
