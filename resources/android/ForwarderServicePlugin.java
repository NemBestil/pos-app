package com.nembestil.pos3.app;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Thin Capacitor bridge for {@link ForwarderService}. The webview only ever
 * needs to ask "turn it on/off" and "what's the state" — everything else lives
 * in the service itself.
 *
 * The foreground service can't post its persistent notification without the
 * POST_NOTIFICATIONS permission (Android 13+), so {@link #start} requests it and
 * only brings the service up once it's granted. If the user declines, we resolve
 * with running=false so the webview toggle stays inactive.
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

    @Override
    public void load() {
        ForwarderService.registerTokenListener(tokenListener);
    }

    @Override
    protected void handleOnDestroy() {
        ForwarderService.unregisterTokenListener(tokenListener);
        super.handleOnDestroy();
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
        // POST_NOTIFICATIONS only exists (and is only enforced) from Android 13.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && getPermissionState(NOTIFICATIONS_ALIAS) != PermissionState.GRANTED) {
            requestPermissionForAlias(NOTIFICATIONS_ALIAS, call, "startPermissionCallback");
            return;
        }
        startForwarder(call);
    }

    @PermissionCallback
    private void startPermissionCallback(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && getPermissionState(NOTIFICATIONS_ALIAS) != PermissionState.GRANTED) {
            // Declined: leave the forwarder off so the toggle stays inactive.
            JSObject ret = new JSObject();
            ret.put("running", false);
            call.resolve(ret);
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

    /**
     * Lets the webview decide whether to show its "why we need notifications"
     * explainer before triggering the system prompt via {@link #start}.
     */
    @PluginMethod
    public void checkNotificationPermission(PluginCall call) {
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || getPermissionState(NOTIFICATIONS_ALIAS) == PermissionState.GRANTED;
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        call.resolve(ret);
    }
}
