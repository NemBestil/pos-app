package com.nembestil.pos3.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONException;

/**
 * Thin Capacitor bridge for {@link ForwarderService}. The webview only ever
 * needs to ask "turn it on/off" and "what's the state" — everything else lives
 * in the service itself.
 *
 * Android does not require POST_NOTIFICATIONS permission to start a foreground
 * service. The service therefore starts independently of notification-drawer
 * visibility and remains visible in Android's active-apps UI.
 */
@CapacitorPlugin(
    name = "ForwarderService",
    permissions = {
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class ForwarderServicePlugin extends Plugin {

    private static final String NOTIFICATIONS_ALIAS = "notifications";

    // Forwarded to the WebView so it can drop its own copy of the (now dead)
    // token and re-mint after the next login.
    private final ForwarderService.TokenListener tokenListener =
        () -> notifyListeners("tokenRejected", new JSObject());
    private final ForwarderService.TakeawayOrderListener takeawayOrderListener = event -> {
        try {
            notifyListeners("takeawayOrder", JSObject.fromJSONObject(event), true);
        } catch (JSONException exception) {
            android.util.Log.w("ForwarderServicePlugin", "Could not forward takeaway order to WebView", exception);
        }
    };

    @Override
    public void load() {
        ForwarderService.registerTokenListener(tokenListener);
        ForwarderService.registerTakeawayOrderListener(takeawayOrderListener);
        handleTakeawayNotificationIntent(getActivity().getIntent());
    }

    @Override
    protected void handleOnDestroy() {
        ForwarderService.setAppFocused(false);
        ForwarderService.unregisterTokenListener(tokenListener);
        ForwarderService.unregisterTakeawayOrderListener(takeawayOrderListener);
        super.handleOnDestroy();
    }

    @Override
    protected void handleOnPause() {
        ForwarderService.setAppFocused(false);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        handleTakeawayNotificationIntent(intent);
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
        ForwarderService.requestStart(context.getApplicationContext(), baseUrl, token);
        JSObject ret = new JSObject();
        ret.put("running", true);
        ret.put("baseUrl", baseUrl);
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
        call.resolve(ret);
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("running", ForwarderService.isRunning());
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
    public void requestTakeawayNotificationPermission(PluginCall call) {
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
            "takeawayNotificationPermissionCallback"
        );
    }

    @PermissionCallback
    private void takeawayNotificationPermissionCallback(PluginCall call) {
        resolveNotificationPermission(
            call,
            getPermissionState(NOTIFICATIONS_ALIAS) == PermissionState.GRANTED
        );
    }

    /**
     * Kept for hosted frontend versions that still treat notification
     * permission as a foreground-service prerequisite. Modern Android does not
     * require that permission to start the service, so compatibility clients
     * should proceed as though the prerequisite is satisfied.
     */
    @PluginMethod
    public void checkNotificationPermission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", true);
        call.resolve(ret);
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

}
