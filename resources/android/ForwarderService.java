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
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent foreground service that owns one authenticated, bidirectional
 * WebSocket for print jobs, terminal traffic, notifications, device presence,
 * configuration changes, and liveness.
 *
 * Lifecycle: started/stopped through ForwarderServicePlugin. While alive it
 * shows a persistent low-priority notification so Android won't kill the
 * process; the socket keeps working even when the WebView is backgrounded.
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
    public static final String ACTION_RECONNECT =
        "com.nembestil.pos3.app.action.RECONNECT_FORWARDER";
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
    private static final String PREFS_FORWARDER_ID = "forwarderId";

    private static final int LAN_PRINTER_PORT = 9100;
    private static final int LAN_PING_TIMEOUT_MS = 1_500;
    private static final int LAN_PRINT_TIMEOUT_MS = 8_000;

    // Bluetooth Classic SPP (Serial Port Profile) — the channel ESC/POS printers expose.
    private static final UUID BLUETOOTH_SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int BLUETOOTH_PRINT_TIMEOUT_MS = 8_000;
    private static final int TERMINAL_REQUEST_DEFAULT_TIMEOUT_MS = 5 * 60_000;

    private static final int ANDROID_SOCKET_PROTOCOL = 3;
    private static final long DEVICE_REFRESH_INTERVAL_MS = 15_000;
    private static final long IDLE_DEVICE_REFRESH_INTERVAL_MS = 2 * 60_000;
    private static final long TERMINAL_STALE_AFTER_MS = 2 * 60_000;
    private static final long SOCKET_REQUEST_TIMEOUT_MS = 8_000;
    private static final long SOCKET_RECONNECT_MAX_MS = 15_000;
    private static final long SOCKET_SERVER_PING_INTERVAL_MS = 5_000;
    private static final long SOCKET_SERVER_PING_TIMEOUT_MS = SOCKET_SERVER_PING_INTERVAL_MS * 2;
    private static final long SOCKET_WATCHDOG_INTERVAL_MS = 1_000;
    private static final String WHOAMI_URL = "https://whoami.nemkasse.com";

    // Worldline UDP discovery
    private static final int DISCOVERY_PORT = 8000;
    private static final int DISCOVERY_BUFFER_SIZE = 64 * 1024;
    private static final int DISCOVERY_RESTART_DELAY_MS = 1_000;
    private static final String MULTICAST_LOCK_TAG = "ForwarderServiceDiscovery";

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicReference<String> activeBaseUrl = new AtomicReference<>(null);
    private static final AtomicBoolean appFocused = new AtomicBoolean(false);
    private static final AtomicReference<ForwarderService> activeService = new AtomicReference<>(null);

    // Discovery state lives in static fields so the Capacitor plugin (running
    // in the same process) can subscribe and query without holding a reference
    // to the service instance.
    private static final ConcurrentMap<String, DiscoveredTerminal> discoveredTerminals = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<DiscoveryListener> discoveryListeners = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean discoveryRunning = new AtomicBoolean(false);

    // Listeners (the Capacitor plugin) that want to know when the server rejected
    // our token, so the WebView can drop its own copy and re-mint after login.
    private static final CopyOnWriteArrayList<TokenListener> tokenListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<StatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<TakeawayOrderListener> takeawayOrderListeners =
        new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<TableBookingListener> tableBookingListeners =
        new CopyOnWriteArrayList<>();

    private volatile String forwarderId;
    // Latches the first time the server rejects our token so only one socket
    // tears things down; cleared again whenever fresh credentials arrive.
    private final AtomicBoolean tokenInvalidated = new AtomicBoolean(false);
    private final AtomicBoolean takeawayEnabled = new AtomicBoolean(false);
    private final AtomicBoolean tableBookingEnabled = new AtomicBoolean(false);
    private final ConcurrentMap<String, ExecutorService> paymentTerminalExecutors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TerminalJobExecution> activeTerminalJobs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> cancelledTerminalJobs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<JSONObject>> pendingSocketRequests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, JSONObject> pendingPrintResults = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, JSONObject> pendingTerminalResults = new ConcurrentHashMap<>();
    private final ExecutorService socketWorkExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService socketScheduler = Executors.newSingleThreadScheduledExecutor();
    private final OkHttpClient socketClient = new OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build();
    private final AtomicBoolean socketConnecting = new AtomicBoolean(false);
    private final AtomicBoolean socketReconnectScheduled = new AtomicBoolean(false);
    private final AtomicBoolean socketRegistered = new AtomicBoolean(false);
    private final AtomicBoolean pendingResultFlushRunning = new AtomicBoolean(false);
    private final AtomicInteger socketReconnectAttempt = new AtomicInteger(0);
    private final AtomicLong socketGeneration = new AtomicLong(0);
    private final AtomicInteger availableDeviceCount = new AtomicInteger(0);
    private final Object deviceMonitorSignal = new Object();
    private final Object deviceConfigurationLock = new Object();
    private final List<LanPrinter> configuredLanPrinters = new ArrayList<>();
    private final List<LocalPrinter> configuredLocalPrinters = new ArrayList<>();
    private final List<ConfiguredTerminal> configuredPaymentTerminals = new ArrayList<>();
    private volatile boolean terminalProbePending = false;
    private volatile WebSocket webSocket;
    private volatile long socketConnectStartedElapsedAt = 0;
    private volatile long lastServerPingElapsedAt = 0;
    private volatile String lastAdvertisedDevices = "";

    private volatile String baseUrl;
    private volatile String authToken;

    private Thread discoveryThread;
    private Thread deviceMonitorThread;
    private volatile DatagramSocket discoverySocket;
    private WifiManager.MulticastLock multicastLock;
    private volatile ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network currentNetwork;
    private final Object networkLocationCallbackLock = new Object();
    private volatile ConnectivityManager.NetworkCallback networkLocationCallback;
    private volatile Network networkLocationNetwork;
    private volatile String networkAttestationToken;
    private volatile long networkAttestationExpiresAt;

    /** True when the device is currently interactive. State changes are pushed
     *  immediately so the server can prefer screen-on tablets for dispatch. */
    private final AtomicBoolean screenOn = new AtomicBoolean(true);
    private BroadcastReceiver screenStateReceiver;
    private BroadcastReceiver attachedDeviceReceiver;

    private final Object discoveryThreadLock = new Object();
    private final Object networkCallbackLock = new Object();
    private volatile String takeawayOrderCursor;
    private final String takeawayOrderStartedAt = formatIsoUtc(System.currentTimeMillis());
    private volatile String tableBookingCursor;
    private final String tableBookingStartedAt = formatIsoUtc(System.currentTimeMillis());

    public static boolean isRunning() {
        return running.get();
    }

    public static boolean isConnected() {
        ForwarderService service = activeService.get();
        if (service == null || !service.socketRegistered.get()) {
            return false;
        }
        long lastPing = service.lastServerPingElapsedAt;
        return lastPing > 0
            && SystemClock.elapsedRealtime() - lastPing < SOCKET_SERVER_PING_TIMEOUT_MS;
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
            Log.i(TAG, "Config change notified; requesting an immediate socket configuration snapshot");
            sendSocketMessage("configuration.request", new JSONObject());
            wakeDeviceMonitor();
            return START_STICKY;
        }

        if (ACTION_RECONNECT.equals(action)) {
            Log.i(TAG, "Immediate reconnect requested");
            reconnectWebSocketNow();
            return running.get() ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_UPDATE_TAKEAWAY_STATE.equals(action)) {
            boolean enabled = intent != null && intent.getBooleanExtra(EXTRA_TAKEAWAY_ENABLED, false);
            takeawayEnabled.set(enabled);
            prefs.edit().putBoolean(PREFS_TAKEAWAY_ENABLED, enabled).apply();
            sendClientState();
            return running.get() ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_UPDATE_TABLE_BOOKING_STATE.equals(action)) {
            boolean enabled = intent != null && intent.getBooleanExtra(EXTRA_TABLE_BOOKING_ENABLED, false);
            tableBookingEnabled.set(enabled);
            prefs.edit().putBoolean(PREFS_TABLE_BOOKING_ENABLED, enabled).apply();
            sendClientState();
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

        String nextBaseUrl = stripTrailingSlash(requestedBaseUrl);
        boolean credentialsChanged = !nextBaseUrl.equals(baseUrl) || !requestedToken.equals(authToken);
        baseUrl = nextBaseUrl;
        authToken = requestedToken;
        forwarderId = getOrCreateForwarderId(prefs);
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
            Log.i(TAG, "Starting WebSocket forwarder baseUrl=" + baseUrl + " forwarderId=" + forwarderId);
            activeService.set(this);
            notifyStatusChanged();
            registerScreenStateReceiver();
            registerAttachedDeviceReceiver();
            startDiscoveryListener();
            startNetworkLocationTracking();
            startDeviceMonitor();
            startSocketWatchdog();
            connectWebSocket();
        } else {
            Log.i(TAG, "Forwarder already running; credentials refreshed");
            sendClientState();
            sendSocketMessage("configuration.request", new JSONObject());
            refreshNetworkLocation(networkLocationNetwork);
            wakeDeviceMonitor();
            if (credentialsChanged || webSocket == null) {
                reconnectWebSocketNow();
            }
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
        activeService.compareAndSet(this, null);
        activeBaseUrl.set(null);
        socketRegistered.set(false);
        notifyStatusChanged();
        socketGeneration.incrementAndGet();
        socketConnectStartedElapsedAt = 0;
        lastServerPingElapsedAt = 0;
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            socket.close(1000, "Forwarder stopped");
        }
        for (CompletableFuture<JSONObject> future : pendingSocketRequests.values()) {
            future.completeExceptionally(new IOException("The Android socket stopped."));
        }
        pendingSocketRequests.clear();
        pendingPrintResults.clear();
        pendingTerminalResults.clear();
        wakeDeviceMonitor();
        unregisterScreenStateReceiver();
        unregisterAttachedDeviceReceiver();
        stopDiscoveryListener();
        stopNetworkLocationTracking();
        Thread deviceMonitor = deviceMonitorThread;
        deviceMonitorThread = null;
        if (deviceMonitor != null) {
            deviceMonitor.interrupt();
        }
        for (TerminalJobExecution execution : activeTerminalJobs.values()) {
            execution.requestCancellation();
        }
        activeTerminalJobs.clear();
        for (ExecutorService executor : paymentTerminalExecutors.values()) {
            executor.shutdownNow();
        }
        paymentTerminalExecutors.clear();
        cancelledTerminalJobs.clear();
        socketWorkExecutor.shutdownNow();
        socketScheduler.shutdownNow();
        socketClient.dispatcher().executorService().shutdownNow();
        socketClient.connectionPool().evictAll();
        // Unexpected shutdowns keep the stored credentials so Android can
        // restore the service. An explicit stop removes them before this runs.
    }

    // ========================================================================
    // Bidirectional server connection
    // ========================================================================

    private String getOrCreateForwarderId(SharedPreferences prefs) {
        String stored = prefs.getString(PREFS_FORWARDER_ID, null);
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(PREFS_FORWARDER_ID, created).apply();
        return created;
    }

    private String getSocketUrl() {
        if (baseUrl.startsWith("https://")) {
            return "wss://" + baseUrl.substring("https://".length()) + "/api/_internal/android-forwarder";
        }
        if (baseUrl.startsWith("http://")) {
            return "ws://" + baseUrl.substring("http://".length()) + "/api/_internal/android-forwarder";
        }
        throw new IllegalStateException("The configured POS URL must use HTTP or HTTPS.");
    }

    private void connectWebSocket() {
        if (!running.get() || authToken == null || !socketConnecting.compareAndSet(false, true)) {
            return;
        }
        final String usedToken = authToken;
        final Request request;
        try {
            request = new Request.Builder()
                .url(getSocketUrl())
                .header("Authorization", "Bearer " + usedToken)
                .build();
        } catch (Exception e) {
            socketConnecting.set(false);
            Log.e(TAG, "Could not construct Android WebSocket request", e);
            scheduleSocketReconnect();
            return;
        }

        Log.i(TAG, "Connecting Android WebSocket to " + request.url());
        final long generation = socketGeneration.incrementAndGet();
        socketConnectStartedElapsedAt = SystemClock.elapsedRealtime();
        final WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                if (!running.get() || generation != socketGeneration.get() || !usedToken.equals(authToken)) {
                    socket.close(1000, "Credentials changed");
                    return;
                }
                webSocket = socket;
                socketConnectStartedElapsedAt = 0;
                lastServerPingElapsedAt = SystemClock.elapsedRealtime();
                socketConnecting.set(false);
                socketReconnectScheduled.set(false);
                socketReconnectAttempt.set(0);
                setSocketRegistered(false);
                Log.i(TAG, "Android WebSocket connected");
                sendSocketRegistration();
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                if (generation != socketGeneration.get() || socket != webSocket) {
                    return;
                }
                handleSocketMessage(text);
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                handleSocketDisconnected(socket, generation, "closed code=" + code + " reason=" + reason, null);
            }

            @Override
            public void onFailure(WebSocket socket, Throwable error, Response response) {
                if (generation != socketGeneration.get()) {
                    return;
                }
                if (response != null && response.code() == 401) {
                    socketConnectStartedElapsedAt = 0;
                    socketConnecting.set(false);
                    handledUnauthorized(401, usedToken);
                    return;
                }
                handleSocketDisconnected(socket, generation, "connection failure", error);
            }
        };

        try {
            WebSocket connectingSocket = socketClient.newWebSocket(request, listener);
            if (generation == socketGeneration.get()) {
                webSocket = connectingSocket;
            } else {
                connectingSocket.cancel();
            }
        } catch (Exception error) {
            if (generation == socketGeneration.get()) {
                webSocket = null;
                socketConnectStartedElapsedAt = 0;
                socketConnecting.set(false);
                Log.w(TAG, "Could not start Android WebSocket connection", error);
                scheduleSocketReconnect();
            }
        }
    }

    private void handleSocketDisconnected(WebSocket socket, long generation, String reason, Throwable error) {
        if (generation != socketGeneration.get()) {
            return;
        }
        if (webSocket == socket) {
            webSocket = null;
        }
        socketConnectStartedElapsedAt = 0;
        lastServerPingElapsedAt = 0;
        socketConnecting.set(false);
        setSocketRegistered(false);
        for (CompletableFuture<JSONObject> future : pendingSocketRequests.values()) {
            future.completeExceptionally(new IOException("The Android WebSocket disconnected."));
        }
        pendingSocketRequests.clear();
        if (error == null) {
            Log.w(TAG, "Android WebSocket " + reason);
        } else {
            Log.w(TAG, "Android WebSocket " + reason, error);
        }
        if (running.get()) {
            scheduleSocketReconnect();
        }
    }

    private void scheduleSocketReconnect() {
        if (!running.get() || !socketReconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        int attempt = socketReconnectAttempt.getAndIncrement();
        long delayMs = attempt == 0 ? 0 : Math.min(SOCKET_RECONNECT_MAX_MS, 1_000L << Math.min(attempt - 1, 4));
        socketScheduler.schedule(() -> {
            socketReconnectScheduled.set(false);
            connectWebSocket();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void reconnectWebSocketNow() {
        socketGeneration.incrementAndGet();
        WebSocket current = webSocket;
        webSocket = null;
        socketConnectStartedElapsedAt = 0;
        lastServerPingElapsedAt = 0;
        setSocketRegistered(false);
        socketConnecting.set(false);
        socketReconnectAttempt.set(0);
        if (current != null) {
            current.cancel();
        }
        scheduleSocketReconnect();
    }

    private void startSocketWatchdog() {
        socketScheduler.scheduleWithFixedDelay(() -> {
            if (!running.get()) {
                return;
            }

            long now = SystemClock.elapsedRealtime();
            if (socketConnecting.get()) {
                long connectStarted = socketConnectStartedElapsedAt;
                if (connectStarted > 0 && now - connectStarted >= SOCKET_SERVER_PING_TIMEOUT_MS) {
                    Log.w(TAG, "Android WebSocket handshake timed out; reconnecting");
                    reconnectWebSocketNow();
                }
                return;
            }

            WebSocket socket = webSocket;
            if (socket == null) {
                scheduleSocketReconnect();
                return;
            }

            long lastPing = lastServerPingElapsedAt;
            if (lastPing <= 0) {
                return;
            }

            long silenceMs = now - lastPing;
            if (silenceMs >= SOCKET_SERVER_PING_TIMEOUT_MS) {
                Log.w(TAG, "No server WebSocket ping received for " + silenceMs + " ms; reconnecting");
                reconnectWebSocketNow();
            }
        }, SOCKET_WATCHDOG_INTERVAL_MS, SOCKET_WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private boolean sendSocketMessage(String type, JSONObject payload) {
        return sendSocketMessage(type, payload, null);
    }

    private boolean sendSocketMessage(String type, JSONObject payload, String requestId) {
        WebSocket socket = webSocket;
        if (socket == null) {
            return false;
        }
        if (!socketRegistered.get()
            && !"connection.register".equals(type)
            && !"connection.pong".equals(type)) {
            return false;
        }
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("type", type);
            if (requestId != null) {
                envelope.put("requestId", requestId);
            }
            envelope.put("payload", payload);
            boolean accepted = socket.send(envelope.toString());
            if (!accepted) {
                Log.w(TAG, "Android WebSocket rejected outgoing message type=" + type);
                reconnectWebSocketNow();
            }
            return accepted;
        } catch (Exception e) {
            Log.w(TAG, "Could not encode Android WebSocket message type=" + type, e);
            return false;
        }
    }

    private JSONObject sendSocketRequest(String type, JSONObject payload) throws Exception {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pendingSocketRequests.put(requestId, future);
        if (!sendSocketMessage(type, payload, requestId)) {
            pendingSocketRequests.remove(requestId);
            throw new IOException("The Android WebSocket is not connected.");
        }
        try {
            return future.get(SOCKET_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("The server did not answer the Android WebSocket request in time.", e);
        } finally {
            pendingSocketRequests.remove(requestId);
        }
    }

    private void sendSocketRegistration() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("forwarderId", forwarderId);
            payload.put("deviceName", (Build.MANUFACTURER + " " + Build.MODEL).trim());
            payload.put("appVersion", BuildConfig.VERSION_NAME);
            payload.put("protocolVersion", ANDROID_SOCKET_PROTOCOL);
            payload.put("screenOn", screenOn.get());
            payload.put("appFocused", appFocused.get());
            payload.put("takeawayEnabled", takeawayEnabled.get());
            payload.put("tableBookingEnabled", tableBookingEnabled.get());
            if (takeawayOrderCursor != null && !takeawayOrderCursor.isEmpty()) {
                payload.put("takeawayCursor", takeawayOrderCursor);
            }
            payload.put("takeawayStartedAt", takeawayOrderStartedAt);
            if (tableBookingCursor != null && !tableBookingCursor.isEmpty()) {
                payload.put("tableBookingCursor", tableBookingCursor);
            }
            payload.put("tableBookingStartedAt", tableBookingStartedAt);
            payload.put("devices", lastAdvertisedDevices.isEmpty()
                ? new JSONArray()
                : new JSONArray(lastAdvertisedDevices));
            sendSocketMessage("connection.register", payload);
        } catch (Exception e) {
            Log.e(TAG, "Could not register Android WebSocket", e);
            reconnectWebSocketNow();
        }
    }

    private void handleSocketMessage(String text) {
        try {
            JSONObject envelope = new JSONObject(text);
            String requestId = envelope.optString("requestId", "");
            JSONObject payload = envelope.optJSONObject("payload");
            if (!requestId.isEmpty()) {
                CompletableFuture<JSONObject> future = pendingSocketRequests.remove(requestId);
                if (future != null) {
                    future.complete(payload == null ? new JSONObject() : payload);
                    return;
                }
            }

            String type = envelope.optString("type", "");
            if ("connection.ping".equals(type)) {
                lastServerPingElapsedAt = SystemClock.elapsedRealtime();
                sendSocketMessage("connection.pong", payload == null ? new JSONObject() : payload);
                return;
            }
            if ("connection.ready".equals(type)) {
                setSocketRegistered(true);
                // Force one post-registration snapshot so changes discovered
                // during the handshake cannot be lost behind the ready gate.
                lastAdvertisedDevices = "";
                socketWorkExecutor.execute(this::flushPendingSocketResults);
                if (!sendNetworkLocation()) {
                    refreshNetworkLocation(networkLocationNetwork);
                }
                wakeDeviceMonitor();
                return;
            }
            if ("configuration.snapshot".equals(type) && payload != null) {
                applyConfigurationSnapshot(payload);
                return;
            }
            if ("print.job".equals(type) && payload != null) {
                socketWorkExecutor.execute(() -> handleSocketPrintJob(payload));
                return;
            }
            if ("terminal.request".equals(type) && payload != null) {
                socketWorkExecutor.execute(() -> handleTerminalRequest(payload.toString()));
                return;
            }
            if ("terminal.cancel".equals(type) && payload != null) {
                handleTerminalCancellation(payload);
                return;
            }
            if ("takeaway.event".equals(type) && payload != null) {
                takeawayOrderCursor = payload.optString("cursor", takeawayOrderCursor);
                handleTakeawayOrderEvent(payload);
                return;
            }
            if ("table-booking.event".equals(type) && payload != null) {
                tableBookingCursor = payload.optString("cursor", tableBookingCursor);
                handleTableBookingEvent(payload);
                return;
            }
            if ("connection.error".equals(type)) {
                Log.w(TAG, "Android WebSocket server error: " + (payload == null ? text : payload.toString()));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not handle Android WebSocket message", e);
        }
    }

    private void sendClientState() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("screenOn", screenOn.get());
            payload.put("appFocused", appFocused.get());
            payload.put("takeawayEnabled", takeawayEnabled.get());
            payload.put("tableBookingEnabled", tableBookingEnabled.get());
            sendSocketMessage("client.state", payload);
        } catch (Exception e) {
            Log.w(TAG, "Could not send Android client state", e);
        }
    }

    private boolean sendNetworkLocation() {
        String token = networkAttestationToken;
        long now = System.currentTimeMillis() / 1_000L;
        if (token == null || networkAttestationExpiresAt <= now || !socketRegistered.get()) {
            return false;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("token", token);
            return sendSocketMessage("network.location", payload);
        } catch (Exception e) {
            Log.w(TAG, "Could not send network location", e);
            return false;
        }
    }

    private void refreshNetworkLocation(Network network) {
        if (!running.get()) {
            return;
        }
        socketWorkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(WHOAMI_URL);
                connection = (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(5_000);
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IOException("Whoami returned HTTP " + status + ".");
                }
                JSONObject response;
                try (InputStream stream = connection.getInputStream()) {
                    response = new JSONObject(readAll(stream));
                }
                String token = response.optString("token", "");
                long expiresAt = response.optLong("expiresAt", 0);
                if (token.isEmpty() || expiresAt <= System.currentTimeMillis() / 1_000L) {
                    throw new IOException("Whoami returned an invalid network attestation.");
                }
                if (network != null && !network.equals(networkLocationNetwork)) {
                    return;
                }
                networkAttestationToken = token;
                networkAttestationExpiresAt = expiresAt;
                sendNetworkLocation();
                Log.i(TAG, "Network location refreshed from whoami");
            } catch (Exception e) {
                Log.w(TAG, "Could not refresh network location", e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void flushPendingSocketResults() {
        if (!pendingResultFlushRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            for (JSONObject payload : pendingPrintResults.values()) {
                submitPrintResultPayload(payload);
            }
            for (JSONObject payload : pendingTerminalResults.values()) {
                submitTerminalResultPayload(payload);
            }
        } finally {
            pendingResultFlushRunning.set(false);
        }
    }

    private void applyConfigurationSnapshot(JSONObject payload) {
        List<LanPrinter> nextLanPrinters = new ArrayList<>();
        List<LocalPrinter> nextLocalPrinters = new ArrayList<>();
        List<ConfiguredTerminal> nextTerminals = new ArrayList<>();
        JSONArray printers = payload.optJSONArray("printers");
        if (printers != null) {
            for (int index = 0; index < printers.length(); index++) {
                JSONObject printer = printers.optJSONObject(index);
                if (printer == null) continue;
                String transport = printer.optString("transport", "");
                if ("lan".equals(transport)) {
                    LanPrinter configured = new LanPrinter();
                    configured.printerId = printer.optString("printerId", "");
                    configured.name = printer.optString("name", configured.printerId);
                    configured.ip = printer.optString("target", "");
                    if (!configured.printerId.isEmpty() && !configured.ip.isEmpty()) {
                        nextLanPrinters.add(configured);
                    }
                    continue;
                }
                if (!"bluetooth".equals(transport) && !"usb".equals(transport)) continue;
                LocalPrinter configured = new LocalPrinter();
                configured.printerId = printer.optString("printerId", "");
                configured.name = printer.optString("name", configured.printerId);
                configured.transport = transport;
                configured.target = printer.optString("target", "");
                configured.usbDeviceName = printer.optString("usbDeviceName", null);
                configured.usbVendorId = printer.optInt("usbVendorId", -1);
                configured.usbProductId = printer.optInt("usbProductId", -1);
                configured.usbSerialNumber = printer.isNull("usbSerialNumber")
                    ? null
                    : printer.optString("usbSerialNumber", null);
                if (!configured.printerId.isEmpty() && !configured.target.isEmpty()) {
                    nextLocalPrinters.add(configured);
                }
            }
        }

        JSONArray terminals = payload.optJSONArray("terminals");
        if (terminals != null) {
            for (int index = 0; index < terminals.length(); index++) {
                JSONObject terminal = terminals.optJSONObject(index);
                if (terminal == null) continue;
                ConfiguredTerminal configured = ConfiguredTerminal.fromJson(terminal);
                if (configured != null) {
                    nextTerminals.add(configured);
                }
            }
        }

        synchronized (deviceConfigurationLock) {
            configuredLanPrinters.clear();
            configuredLanPrinters.addAll(nextLanPrinters);
            configuredLocalPrinters.clear();
            configuredLocalPrinters.addAll(nextLocalPrinters);
            configuredPaymentTerminals.clear();
            configuredPaymentTerminals.addAll(nextTerminals);
            terminalProbePending = true;
        }
        wakeDeviceMonitor();
    }

    private void handleSocketPrintJob(JSONObject job) {
        String transport = job.optString("transport", "");
        if ("lan".equals(transport)) {
            Map<String, String> ipByPrinterId = new java.util.HashMap<>();
            synchronized (deviceConfigurationLock) {
                for (LanPrinter printer : configuredLanPrinters) {
                    ipByPrinterId.put(printer.printerId, printer.ip);
                }
            }
            handleLanPrintJob(job.toString(), ipByPrinterId);
            return;
        }

        Map<String, LocalPrinter> printerById = new java.util.HashMap<>();
        List<LocalPrinter> configured;
        synchronized (deviceConfigurationLock) {
            configured = new ArrayList<>(configuredLocalPrinters);
        }
        for (LocalPrinter printer : filterAvailableLocalPrinters(configured)) {
            printerById.put(printer.printerId, printer);
        }
        handleLocalPrintJob(job.toString(), printerById);
    }

    private void handleTerminalCancellation(JSONObject payload) {
        String jobId = payload.optString("jobId", "");
        String deliveryToken = payload.optString("deliveryToken", "");
        if (jobId.isEmpty() || deliveryToken.isEmpty()) {
            return;
        }
        cancelledTerminalJobs.put(jobId, deliveryToken);
        TerminalJobExecution execution = activeTerminalJobs.get(jobId);
        if (execution != null) {
            execution.requestCancellation();
        }
        socketScheduler.schedule(
            () -> cancelledTerminalJobs.remove(jobId, deliveryToken),
            60,
            TimeUnit.SECONDS
        );
    }

    // ========================================================================
    // Reactive device availability
    // ========================================================================

    private void startDeviceMonitor() {
        deviceMonitorThread = new Thread(this::runDeviceMonitor, "ForwarderDeviceMonitor");
        deviceMonitorThread.setDaemon(true);
        deviceMonitorThread.start();
    }

    private void runDeviceMonitor() {
        while (running.get()) {
            try {
                refreshAndAdvertiseDevices();
            } catch (Throwable error) {
                Log.w(TAG, "Device availability refresh failed", error);
            }
            long waitMs = availableDeviceCount.get() == 0
                ? IDLE_DEVICE_REFRESH_INTERVAL_MS
                : DEVICE_REFRESH_INTERVAL_MS;
            synchronized (deviceMonitorSignal) {
                if (!running.get()) break;
                try {
                    deviceMonitorSignal.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (running.get() && availableDeviceCount.get() == 0) {
                sendSocketMessage("configuration.request", new JSONObject());
            }
        }
    }

    private void wakeDeviceMonitor() {
        synchronized (deviceMonitorSignal) {
            deviceMonitorSignal.notifyAll();
        }
    }

    private void refreshAndAdvertiseDevices() throws Exception {
        List<LanPrinter> lanPrinters;
        List<LocalPrinter> localPrinters;
        List<ConfiguredTerminal> terminals;
        boolean shouldProbeTerminals;
        synchronized (deviceConfigurationLock) {
            lanPrinters = new ArrayList<>(configuredLanPrinters);
            localPrinters = new ArrayList<>(configuredLocalPrinters);
            terminals = new ArrayList<>(configuredPaymentTerminals);
            shouldProbeTerminals = terminalProbePending;
            terminalProbePending = false;
        }

        List<LanPrinter> reachableLan = filterReachableLanPrinters(lanPrinters);
        List<LocalPrinter> reachableLocal = filterAvailableLocalPrinters(localPrinters);
        if (shouldProbeTerminals) {
            for (ConfiguredTerminal terminal : terminals) {
                if (probeConfiguredTerminal(terminal)) {
                    discoveredTerminals.put(
                        terminal.terminalId,
                        new DiscoveredTerminal(
                            terminal.terminalId,
                            terminal.identity,
                            terminal.ipAddress,
                            terminal.port,
                            terminal.protocolType,
                            terminal.protocolVersion,
                            System.currentTimeMillis()
                        )
                    );
                }
            }
        }

        long staleBefore = System.currentTimeMillis() - TERMINAL_STALE_AFTER_MS;
        for (Map.Entry<String, DiscoveredTerminal> entry : discoveredTerminals.entrySet()) {
            if (entry.getValue().lastSeenAtMs < staleBefore) {
                discoveredTerminals.remove(entry.getKey(), entry.getValue());
            }
        }

        JSONArray devices = new JSONArray();
        for (LanPrinter printer : reachableLan) {
            JSONObject device = new JSONObject();
            device.put("kind", "printer");
            device.put("id", printer.printerId);
            device.put("name", printer.name);
            device.put("transport", "lan");
            device.put("target", printer.ip);
            devices.put(device);
        }
        for (LocalPrinter printer : reachableLocal) {
            JSONObject device = new JSONObject();
            device.put("kind", "printer");
            device.put("id", printer.printerId);
            device.put("name", printer.name);
            device.put("transport", printer.transport);
            device.put("target", printer.target);
            devices.put(device);
        }
        Map<String, String> terminalNames = new java.util.HashMap<>();
        for (ConfiguredTerminal terminal : terminals) {
            terminalNames.put(terminal.terminalId, terminal.name);
        }
        for (DiscoveredTerminal terminal : getDiscoveredTerminals()) {
            JSONObject device = new JSONObject();
            device.put("kind", "payment_terminal");
            device.put("id", terminal.terminalId);
            device.put("name", terminalNames.getOrDefault(terminal.terminalId, terminal.terminalId));
            device.put("transport", "network");
            device.put("target", terminal.ipAddress + ":" + terminal.port);
            device.put("ipAddress", terminal.ipAddress);
            device.put("port", terminal.port);
            device.put("lastSeenAt", formatIsoUtc(terminal.lastSeenAtMs));
            devices.put(device);
        }

        availableDeviceCount.set(devices.length());
        String serialized = devices.toString();
        boolean changed = !serialized.equals(lastAdvertisedDevices);
        lastAdvertisedDevices = serialized;
        if ((changed || !socketRegistered.get()) && webSocket != null) {
            JSONObject payload = new JSONObject();
            payload.put("screenOn", screenOn.get());
            payload.put("devices", devices);
            sendSocketMessage("devices.snapshot", payload);
        }
    }

    private boolean probeConfiguredTerminal(ConfiguredTerminal terminal) {
        if (terminal.probePath == null || terminal.probeHeaders == null) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http", terminal.ipAddress, terminal.port, terminal.probePath);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(3_000);
            Iterator<String> names = terminal.probeHeaders.keys();
            while (names.hasNext()) {
                String name = names.next();
                connection.setRequestProperty(name, terminal.probeHeaders.optString(name, ""));
            }
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                InputStream stream = connection.getInputStream();
                if (stream != null) stream.close();
                Log.i(TAG, "Payment terminal startup probe succeeded terminal=" + terminal.terminalId);
                return true;
            }
            Log.w(TAG, "Payment terminal startup probe returned HTTP " + status
                + " terminal=" + terminal.terminalId);
        } catch (Exception e) {
            Log.i(TAG, "Payment terminal startup probe failed terminal=" + terminal.terminalId);
        } finally {
            if (connection != null) connection.disconnect();
        }
        return false;
    }

    // ========================================================================
    // Printer access and delivery
    // ========================================================================

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
        String name;
        String transport;
        String target;
        String usbDeviceName;
        int usbVendorId;
        int usbProductId;
        String usbSerialNumber;
    }

    private void handleTerminalRequest(String data) {
        try {
            JSONObject job = new JSONObject(data);
            String jobId = job.optString("jobId", "");
            String paymentTerminalId = job.optString("paymentTerminalId", "");
            String path = job.optString("path", null);
            String method = job.optString("method", "POST");
            int timeoutMs = job.optInt("timeoutMs", TERMINAL_REQUEST_DEFAULT_TIMEOUT_MS);
            String deliveryToken = job.optString("deliveryToken", "");
            String operationMode = job.optString("operationMode", "exclusive");
            String responseDeliveryToken = deliveryToken;
            JSONObject headers = job.optJSONObject("headers");
            String requestBody = job.isNull("body") ? null : job.optString("body", null);
            if (paymentTerminalId.isEmpty()) {
                postTerminalError(jobId, responseDeliveryToken, "Forwarded terminal request missing paymentTerminalId.");
                return;
            }
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                postTerminalError(jobId, responseDeliveryToken, "Forwarded terminal request has an invalid path.");
                return;
            }
            if (deliveryToken.isEmpty()) {
                postTerminalError(jobId, "Forwarded terminal request is missing its delivery token.");
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

            if (deliveryToken.equals(cancelledTerminalJobs.remove(finalJobId))) {
                postTerminalCancellation(finalJobId, deliveryToken, false);
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
            if ("modeless".equals(operationMode) || "concurrent".equals(operationMode)) {
                Thread worker = new Thread(operation, "ForwarderTerminalConcurrentJob-" + jobId);
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
        if (deliveryToken.equals(cancelledTerminalJobs.remove(jobId))) {
            postTerminalCancellation(jobId, deliveryToken, false);
            return;
        }
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
        TerminalJobExecution execution = new TerminalJobExecution();
        try {
            activeTerminalJobs.put(jobId, execution);
            if (deliveryToken.equals(cancelledTerminalJobs.remove(jobId))) {
                execution.requestCancellation();
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
            execution.setConnection(conn);
            if (execution.isCancellationRequested()) {
                throw new IOException("Forwarded terminal request was cancelled before dispatch.");
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
            if (execution.isCancellationRequested()) {
                postTerminalCancellation(jobId, deliveryToken, true);
            } else {
                postTerminalError(jobId, deliveryToken, e.getMessage() == null
                    ? "Forwarded terminal request failed." : e.getMessage());
            }
        } finally {
            execution.markFinished();
            activeTerminalJobs.remove(jobId, execution);
            cancelledTerminalJobs.remove(jobId);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean updateTerminalJobLifecycle(String jobId, String deliveryToken, String action) {
        try {
            JSONObject body = new JSONObject();
            body.put("jobId", jobId);
            body.put("deliveryToken", deliveryToken);
            body.put("action", action);
            JSONObject response = sendSocketRequest("terminal.lifecycle", body);
            return response.optBoolean("accepted", false) && !response.optBoolean("cancelRequested", false);
        } catch (Exception e) {
            Log.w(TAG, "terminal job lifecycle failed jobId=" + jobId + " action=" + action, e);
            return false;
        }
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
        try {
            JSONObject payload = new JSONObject();
            payload.put("jobId", jobId);
            payload.put("deliveryToken", deliveryToken);
            payload.put("status", status);
            if (errorMessage != null && !errorMessage.isEmpty()) {
                payload.put("errorMessage", errorMessage.substring(0, Math.min(errorMessage.length(), 1_000)));
            }
            if (!"accepted".equals(status)) {
                pendingPrintResults.put(jobId, payload);
            }
            return submitPrintResultPayload(payload);
        } catch (Exception e) {
            Log.w(TAG, "Could not submit print result jobId=" + jobId, e);
            return false;
        }
    }

    private boolean submitPrintResultPayload(JSONObject payload) {
        String jobId = payload.optString("jobId", "");
        try {
            JSONObject response = sendSocketRequest("print.result", payload);
            boolean accepted = response.optBoolean("accepted", false);
            if (accepted) {
                pendingPrintResults.remove(jobId, payload);
            }
            return accepted;
        } catch (Exception e) {
            Log.w(TAG, "Print result will be retried after reconnect jobId=" + jobId, e);
            return false;
        }
    }

    private void postTerminalResult(String jobId, String deliveryToken, JSONObject result, String errorMessage) {
        if (jobId == null || jobId.isEmpty() || deliveryToken == null || deliveryToken.isEmpty()) {
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("jobId", jobId);
            payload.put("deliveryToken", deliveryToken);
            if (errorMessage != null) {
                JSONObject error = new JSONObject();
                error.put("message", errorMessage);
                payload.put("result", error);
            } else {
                payload.put("result", result);
            }
            pendingTerminalResults.put(jobId, payload);
            submitTerminalResultPayload(payload);
        } catch (Exception e) {
            Log.w(TAG, "Could not encode terminal response jobId=" + jobId, e);
        }
    }

    private boolean submitTerminalResultPayload(JSONObject payload) {
        String jobId = payload.optString("jobId", "");
        try {
            JSONObject response = sendSocketRequest("terminal.result", payload);
            boolean accepted = response.optBoolean("accepted", false);
            if (accepted) {
                pendingTerminalResults.remove(jobId, payload);
            }
            return accepted;
        } catch (Exception e) {
            Log.w(TAG, "Terminal response will be retried after reconnect jobId=" + jobId, e);
            return false;
        }
    }

    private void postTerminalError(String jobId, String message) {
        Log.w(TAG, "Cannot submit terminal error without a delivery token jobId=" + jobId + ": " + message);
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
    // Token rejection handling
    // ========================================================================
    //
    // The socket authenticates with the androidsync Bearer token. Once the
    // server rejects it, only an authenticated WebView can mint a new one.

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

    public interface StatusListener {
        void onStatusChanged(boolean isRunning, boolean isConnected);
    }

    public static void registerStatusListener(StatusListener listener) {
        statusListeners.addIfAbsent(listener);
    }

    public static void unregisterStatusListener(StatusListener listener) {
        statusListeners.remove(listener);
    }

    private static void notifyStatusChanged() {
        boolean isRunning = isRunning();
        boolean isConnected = isConnected();
        for (StatusListener listener : statusListeners) {
            try {
                listener.onStatusChanged(isRunning, isConnected);
            } catch (Exception e) {
                Log.w(TAG, "Status listener threw", e);
            }
        }
    }

    private void setSocketRegistered(boolean registered) {
        if (socketRegistered.getAndSet(registered) != registered) {
            notifyStatusChanged();
        }
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
        ForwarderService service = activeService.get();
        if (service != null) {
            service.sendClientState();
            service.wakeDeviceMonitor();
        }
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
        String name;
        String ip;
    }

    private static class ConfiguredTerminal {
        String terminalId;
        String name;
        String identity;
        String ipAddress;
        int port;
        String protocolType;
        String protocolVersion;
        String probePath;
        JSONObject probeHeaders;

        static ConfiguredTerminal fromJson(JSONObject value) {
            String terminalId = value.optString("terminalId", "");
            String ipAddress = value.optString("ipAddress", "");
            int port = value.optInt("port", 0);
            if (terminalId.isEmpty() || ipAddress.isEmpty() || port < 1) {
                return null;
            }
            ConfiguredTerminal terminal = new ConfiguredTerminal();
            terminal.terminalId = terminalId;
            terminal.name = value.optString("name", terminalId);
            terminal.identity = value.optString("identity", null);
            terminal.ipAddress = ipAddress;
            terminal.port = port;
            terminal.protocolType = value.optString("protocolType", null);
            terminal.protocolVersion = value.optString("protocolVersion", null);
            JSONObject probe = value.optJSONObject("probe");
            if (probe != null) {
                terminal.probePath = probe.optString("path", null);
                terminal.probeHeaders = probe.optJSONObject("headers");
            }
            return terminal;
        }
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
        wakeDeviceMonitor();
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
        synchronized (deviceConfigurationLock) {
            terminalProbePending = true;
        }
        wakeDeviceMonitor();
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

    private void startNetworkLocationTracking() {
        synchronized (networkLocationCallbackLock) {
            if (networkLocationCallback != null) {
                return;
            }
            try {
                ConnectivityManager cm =
                    (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) {
                    return;
                }
                Network activeNetwork = cm.getActiveNetwork();
                networkLocationNetwork = activeNetwork;
                ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        Network previous = networkLocationNetwork;
                        networkLocationNetwork = network;
                        if (previous == null || !previous.equals(network)) {
                            networkAttestationToken = null;
                            networkAttestationExpiresAt = 0;
                            refreshNetworkLocation(network);
                        }
                    }

                    @Override
                    public void onLost(Network network) {
                        if (network.equals(networkLocationNetwork)) {
                            networkLocationNetwork = null;
                            networkAttestationToken = null;
                            networkAttestationExpiresAt = 0;
                        }
                    }
                };
                cm.registerDefaultNetworkCallback(callback);
                networkLocationCallback = callback;
                if (activeNetwork != null) {
                    refreshNetworkLocation(activeNetwork);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to track network location changes", e);
                networkLocationCallback = null;
            }
        }
    }

    private void stopNetworkLocationTracking() {
        synchronized (networkLocationCallbackLock) {
            ConnectivityManager.NetworkCallback callback = networkLocationCallback;
            networkLocationCallback = null;
            networkLocationNetwork = null;
            networkAttestationToken = null;
            networkAttestationExpiresAt = 0;
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
                Log.w(TAG, "Failed to stop network location tracking", e);
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
                sendClientState();
                wakeDeviceMonitor();
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

    private void registerAttachedDeviceReceiver() {
        attachedDeviceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.i(TAG, "Attached device state changed: " + action);
                wakeDeviceMonitor();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getApplicationContext().registerReceiver(
                    attachedDeviceReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                getApplicationContext().registerReceiver(attachedDeviceReceiver, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to register attached device receiver", e);
            attachedDeviceReceiver = null;
        }
    }

    private void unregisterAttachedDeviceReceiver() {
        BroadcastReceiver receiver = attachedDeviceReceiver;
        attachedDeviceReceiver = null;
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
            wakeDeviceMonitor();
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

    public static void requestReconnect(Context context) {
        if (!isRunning()) {
            return;
        }
        Intent intent = new Intent(context, ForwarderService.class);
        intent.setAction(ACTION_RECONNECT);
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
