package com.nembestil.pos3.app;

import android.app.Activity;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "AndroidFullscreen")
public class AndroidFullscreenPlugin extends Plugin {

    private static boolean enabled = false;

    @PluginMethod
    public void setEnabled(PluginCall call) {
        Boolean nextEnabled = call.getBoolean("enabled");
        if (nextEnabled == null) {
            call.reject("Missing enabled");
            return;
        }

        Activity activity = getActivity();
        if (activity == null) {
            call.reject("No Android activity");
            return;
        }

        enabled = nextEnabled;
        activity.runOnUiThread(() -> {
            apply(activity, nextEnabled);

            JSObject result = new JSObject();
            result.put("enabled", nextEnabled);
            call.resolve(result);
        });
    }

    public static void applyCurrentState(Activity activity) {
        apply(activity, enabled);
    }

    private static void apply(Activity activity, boolean fullscreen) {
        Window window = activity.getWindow();
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        WindowCompat.setDecorFitsSystemWindows(window, !fullscreen);

        if (fullscreen) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
            return;
        }

        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
        controller.show(WindowInsetsCompat.Type.systemBars());
    }
}
