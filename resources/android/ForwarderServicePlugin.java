package com.nembestil.pos3.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Thin Capacitor bridge for {@link ForwarderService}. The webview only ever
 * needs to ask "turn it on/off" and "what's the state" — everything else lives
 * in the service itself.
 */
@CapacitorPlugin(name = "ForwarderService")
public class ForwarderServicePlugin extends Plugin {

    private static final int NOTIFICATION_PERMISSION_REQUEST = 4711;

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
        Context context = getContext();
        if (context == null) {
            call.reject("No Android context");
            return;
        }
        maybeRequestNotificationPermission();
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

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        int granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.POST_NOTIFICATIONS
        );
        if (granted == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(
            activity,
            new String[]{Manifest.permission.POST_NOTIFICATIONS},
            NOTIFICATION_PERMISSION_REQUEST
        );
    }
}
