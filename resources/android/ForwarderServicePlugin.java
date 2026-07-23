package com.nembestil.pos3.app;

import android.content.Context;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Thin Capacitor bridge for {@link ForwarderService}. The webview only ever
 * needs to ask "turn it on/off" and "what's the state" — everything else lives
 * in the service itself.
 *
 * Android does not require POST_NOTIFICATIONS permission to start a foreground
 * service. The service therefore starts independently of notification-drawer
 * visibility and remains visible in Android's active-apps UI.
 */
@CapacitorPlugin(name = "ForwarderService")
public class ForwarderServicePlugin extends Plugin {

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

}
