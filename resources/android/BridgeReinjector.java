package com.nembestil.pos3.app;

import android.util.Log;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.Bridge;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Extends Capacitor's document-start bridge injection to every origin listed in
 * server.allowNavigation.
 *
 * Why this exists: Capacitor 8's Bridge.loadWebView() registers the bridge JS
 * via WebViewCompat.addDocumentStartJavaScript with Collections.singleton(allowedOrigin),
 * where allowedOrigin is derived only from the initial appUrl. Pages we navigate
 * to via window.location.assign — even those in allowNavigation — never receive
 * window.Capacitor.PluginHeaders / nativeCallback / handleWindowError, so every
 * registerPlugin proxy call throws "plugin is not implemented on android".
 *
 * Reflection is required because Bridge.getJSInjector() is private and JSInjector
 * is package-private to com.getcapacitor. The public surface area is intentionally
 * narrow; we accept the fragility in exchange for not forking Capacitor.
 */
public final class BridgeReinjector {
    private static final String TAG = "BridgeReinjector";

    private BridgeReinjector() {}

    public static void install(Bridge bridge) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "DOCUMENT_START_SCRIPT not supported; bridge will not reach allowNavigation origins");
            return;
        }
        String[] hosts = bridge.getConfig().getAllowNavigation();
        if (hosts == null || hosts.length == 0) {
            return;
        }

        String script;
        try {
            Method getInjector = Bridge.class.getDeclaredMethod("getJSInjector");
            getInjector.setAccessible(true);
            Object injector = getInjector.invoke(bridge);
            if (injector == null) {
                Log.w(TAG, "Bridge.getJSInjector() returned null");
                return;
            }
            Method getScript = injector.getClass().getDeclaredMethod("getScriptString");
            getScript.setAccessible(true);
            script = (String) getScript.invoke(injector);
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "Failed to access Capacitor's JSInjector via reflection", e);
            return;
        }
        if (script == null || script.isEmpty()) {
            Log.w(TAG, "JSInjector.getScriptString() returned empty");
            return;
        }

        Set<String> origins = new HashSet<>();
        for (String h : hosts) {
            origins.add(h.startsWith("http") ? h : "https://" + h);
        }
        try {
            WebViewCompat.addDocumentStartJavaScript(bridge.getWebView(), script, origins);
            Log.i(TAG, "Capacitor bridge document-start script installed for origins: " + origins);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "addDocumentStartJavaScript rejected origin set " + origins, e);
        }
    }
}
