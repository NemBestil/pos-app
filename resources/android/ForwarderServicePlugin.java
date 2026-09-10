package com.nembestil.pos3.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONException;

/**
 * Thin Capacitor bridge for {@link ForwarderService}. The webview only ever
 * needs to ask "turn it on/off" and "what's the state" — everything else lives
 * in the service itself.
 *
 * Notification permission and the battery-optimization exemption are requested
 * explicitly by the hosted POS after the user has authenticated, never while
 * the app shell is starting.
 */
@CapacitorPlugin(
    name = "ForwarderService",
    permissions = {
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class ForwarderServicePlugin extends Plugin {

    private static final String NOTIFICATIONS_ALIAS = "notifications";
    private static final String BACKGROUND_EXECUTION_PERMISSION_SUPPORTED =
        "backgroundExecutionPermissionSupported";
    private static final String NOTIFICATION_SOUND_PLAYBACK_SUPPORTED =
        "notificationSoundPlaybackSupported";

    // Forwarded to the WebView so it can drop its own copy of the (now dead)
    // token and re-mint after the next login.
    private final ForwarderService.TokenListener tokenListener =
        () -> notifyListeners("tokenRejected", new JSObject());
    private final ForwarderService.StatusListener statusListener = (running, connected) -> {
        JSObject status = new JSObject();
        status.put("running", running);
        status.put("connected", connected);
        // A WebView reload briefly removes its Capacitor listeners. Retain the
        // latest transition so the replacement listener cannot keep showing a
        // stale green state after the native socket has gone offline.
        notifyListeners("statusChanged", status, true);
    };
    private final ForwarderService.TakeawayOrderListener takeawayOrderListener = event -> {
        try {
            notifyListeners("takeawayOrder", JSObject.fromJSONObject(event), true);
        } catch (JSONException exception) {
            android.util.Log.w("ForwarderServicePlugin", "Could not forward takeaway order to WebView", exception);
        }
    };
    private final ForwarderService.TableBookingListener tableBookingListener = event -> {
        try {
            notifyListeners("tableBooking", JSObject.fromJSONObject(event), true);
        } catch (JSONException exception) {
            android.util.Log.w("ForwarderServicePlugin", "Could not forward table booking to WebView", exception);
        }
    };

    @Override
    public void load() {
        ForwarderService.registerTokenListener(tokenListener);
        ForwarderService.registerStatusListener(statusListener);
        ForwarderService.registerTakeawayOrderListener(takeawayOrderListener);
        ForwarderService.registerTableBookingListener(tableBookingListener);
        handleTakeawayNotificationIntent(getActivity().getIntent());
        handleTableBookingNotificationIntent(getActivity().getIntent());
    }

    @Override
    protected void handleOnDestroy() {
        ForwarderService.setAppFocused(false);
        ForwarderService.unregisterTokenListener(tokenListener);
        ForwarderService.unregisterStatusListener(statusListener);
        ForwarderService.unregisterTakeawayOrderListener(takeawayOrderListener);
        ForwarderService.unregisterTableBookingListener(tableBookingListener);
        super.handleOnDestroy();
    }

    @Override
    protected void handleOnPause() {
        ForwarderService.setAppFocused(false);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        handleTakeawayNotificationIntent(intent);
        handleTableBookingNotificationIntent(intent);
    }

    @PluginMethod
    public void start(PluginCall call) {
        String baseUrl = call.getString("baseUrl");
        String token = call.getString("token");
        if (baseUrl == null || baseUrl.isEmpty()) {
            call.reject("Missing baseUrl");
            return;
        }
        if (token == null || token.isEmpty()) {
            call.reject("Missing token");
            return;
        }
        startForwarder(call);
    }

    private void startForwarder(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No Android context");
            return;
        }
        String baseUrl = call.getString("baseUrl");
        String token = call.getString("token");
        try {
            ForwarderService.requestStart(context.getApplicationContext(), baseUrl, token);
        } catch (RuntimeException exception) {
            call.reject("Android did not allow the foreground forwarder service to start.", exception);
            return;
        }
        JSObject ret = new JSObject();
        ret.put("running", true);
        ret.put("connected", ForwarderService.isConnected());
        ret.put("baseUrl", baseUrl);
        ret.put(BACKGROUND_EXECUTION_PERMISSION_SUPPORTED, true);
        ret.put(NOTIFICATION_SOUND_PLAYBACK_SUPPORTED, true);
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No Android context");
            return;
        }
        ForwarderService.requestStop(context.getApplicationContext());
        JSObject ret = new JSObject();
        ret.put("running", false);
        ret.put("connected", false);
        ret.put(BACKGROUND_EXECUTION_PERMISSION_SUPPORTED, true);
        ret.put(NOTIFICATION_SOUND_PLAYBACK_SUPPORTED, true);
        call.resolve(ret);
    }

    @PluginMethod
    public void reconnect(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No Android context");
            return;
        }
        ForwarderService.requestReconnect(context.getApplicationContext());
        JSObject ret = new JSObject();
        ret.put("running", ForwarderService.isRunning());
        ret.put("connected", ForwarderService.isConnected());
        ret.put(BACKGROUND_EXECUTION_PERMISSION_SUPPORTED, true);
        ret.put(NOTIFICATION_SOUND_PLAYBACK_SUPPORTED, true);
        call.resolve(ret);
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("running", ForwarderService.isRunning());
        ret.put("connected", ForwarderService.isConnected());
        ret.put(BACKGROUND_EXECUTION_PERMISSION_SUPPORTED, true);
        ret.put(NOTIFICATION_SOUND_PLAYBACK_SUPPORTED, true);
        String activeBaseUrl = ForwarderService.getActiveBaseUrl();
        if (activeBaseUrl != null) {
            ret.put("baseUrl", activeBaseUrl);
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void notifyConfigChanged(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No Android context");
            return;
        }
        ForwarderService.requestNotifyConfigChanged(context.getApplicationContext());
        call.resolve();
    }

    @PluginMethod
    public void setTakeawayState(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No Android context");
            return;
        }
        boolean enabled = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        ForwarderService.requestUpdateTakeawayState(
            context.getApplicationContext(),
            enabled
        );
        call.resolve();
    }

    @PluginMethod
    public void setTableBookingState(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No Android context");
            return;
        }
        boolean enabled = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        ForwarderService.requestUpdateTableBookingState(
            context.getApplicationContext(),
            enabled
        );
        call.resolve();
    }

    @PluginMethod
    public void playNotificationSound(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No Android context");
            return;
        }
        String soundId = call.getString("soundId");
        Integer repeatCount = call.getInt("repeatCount");
        String sourceUrl = call.getString("sourceUrl");
        if (soundId == null || !soundId.matches("[a-z0-9-]+")) {
            call.reject("Invalid notification sound ID");
            return;
        }
        NotificationSoundManager.play(
            context,
            soundId,
            repeatCount == null ? 1 : repeatCount,
            sourceUrl,
            new NotificationSoundManager.PlaybackCallback() {
                @Override
                public void onComplete() {
                    call.resolve();
                }

                @Override
                public void onError(Exception exception) {
                    call.reject("Android could not play the notification sound.", exception);
                }
            }
        );
    }

    @PluginMethod
    public void stopNotificationSound(PluginCall call) {
        NotificationSoundManager.stop();
        call.resolve();
    }

    @PluginMethod
    public void cancelNotificationSoundRepeats(PluginCall call) {
        NotificationSoundManager.cancelRepeats();
        call.resolve();
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || getPermissionState(NOTIFICATIONS_ALIAS) == PermissionState.GRANTED
        ) {
            resolveNotificationPermission(call, true);
            return;
        }
        requestPermissionForAlias(
            NOTIFICATIONS_ALIAS,
            call,
            "notificationPermissionCallback"
        );
    }

    @PermissionCallback
    private void notificationPermissionCallback(PluginCall call) {
        resolveNotificationPermission(
            call,
            getPermissionState(NOTIFICATIONS_ALIAS) == PermissionState.GRANTED
        );
    }

    @PluginMethod
    public void checkNotificationPermission(PluginCall call) {
        resolveNotificationPermission(
            call,
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || getPermissionState(NOTIFICATIONS_ALIAS) == PermissionState.GRANTED
        );
    }

    @PluginMethod
    public void checkBackgroundExecutionPermission(PluginCall call) {
        resolveBackgroundExecutionPermission(call);
    }

    @PluginMethod
    public void requestBackgroundExecutionPermission(PluginCall call) {
        if (isIgnoringBatteryOptimizations()) {
            resolveBackgroundExecutionPermission(call);
            return;
        }

        Context context = getContext();
        if (context == null) {
            call.reject("No Android context");
            return;
        }

        Intent intent = new Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + context.getPackageName())
        );
        try {
            startActivityForResult(call, intent, "backgroundExecutionPermissionCallback");
        } catch (RuntimeException exception) {
            call.reject("Android could not open the background execution permission dialog.", exception);
        }
    }

    @ActivityCallback
    private void backgroundExecutionPermissionCallback(PluginCall call, ActivityResult result) {
        if (call != null) {
            resolveBackgroundExecutionPermission(call);
        }
    }

    private void resolveBackgroundExecutionPermission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", isIgnoringBatteryOptimizations());
        call.resolve(ret);
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        Context context = getContext();
        if (context == null) {
            return false;
        }
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private void resolveNotificationPermission(PluginCall call, boolean granted) {
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        call.resolve(ret);
    }

    private void handleTakeawayNotificationIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(ForwarderService.EXTRA_OPEN_TAKEAWAY_ORDERS, false)) {
            return;
        }
        intent.removeExtra(ForwarderService.EXTRA_OPEN_TAKEAWAY_ORDERS);
        String orderId = intent.getStringExtra(ForwarderService.EXTRA_TAKEAWAY_ORDER_ID);
        intent.removeExtra(ForwarderService.EXTRA_TAKEAWAY_ORDER_ID);

        JSObject event = new JSObject();
        if (orderId != null && !orderId.isEmpty()) {
            event.put("orderId", orderId);
        }
        // Capacitor retains the click across WebView startup until the hosted
        // POS has installed its listener and can apply its own readiness gates.
        notifyListeners("takeawayNotificationClick", event, true);
    }

    private void handleTableBookingNotificationIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(ForwarderService.EXTRA_OPEN_TABLE_BOOKINGS, false)) {
            return;
        }
        intent.removeExtra(ForwarderService.EXTRA_OPEN_TABLE_BOOKINGS);
        String bookingId = intent.getStringExtra(ForwarderService.EXTRA_TABLE_BOOKING_ID);
        intent.removeExtra(ForwarderService.EXTRA_TABLE_BOOKING_ID);

        JSObject event = new JSObject();
        if (bookingId != null && !bookingId.isEmpty()) {
            event.put("bookingId", bookingId);
        }
        notifyListeners("tableBookingNotificationClick", event, true);
    }

}
