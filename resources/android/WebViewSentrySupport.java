package com.nembestil.pos3.app;

import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.BridgeWebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLevel;

public final class WebViewSentrySupport {
    private static final String JAVASCRIPT_INTERFACE_NAME = "NemBestilSentry";

    private WebViewSentrySupport() {}

    public static void install(Bridge bridge) {
        WebView webView = bridge.getWebView();

        webView.addJavascriptInterface(new SentryJavascriptBridge(), JAVASCRIPT_INTERFACE_NAME);
        bridge.setWebViewClient(new SentryBridgeWebViewClient(bridge));
        webView.setWebChromeClient(new SentryBridgeWebChromeClient(bridge));
        installDocumentStartScript(webView, bridge.getConfig().getAllowNavigation());

        addBreadcrumb(
            "webview.lifecycle",
            "WebView Sentry support installed",
            SentryLevel.INFO,
            null
        );
    }

    private static void installDocumentStartScript(WebView webView, String[] hosts) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            addBreadcrumb(
                "webview.lifecycle",
                "Document-start script is not supported",
                SentryLevel.WARNING,
                null
            );
            return;
        }
        if (hosts == null || hosts.length == 0) {
            return;
        }

        Set<String> origins = new HashSet<>();
        for (String host : hosts) {
            origins.add(host.startsWith("http") ? host : "https://" + host);
        }

        try {
            WebViewCompat.addDocumentStartJavaScript(webView, getDocumentStartScript(), origins);
        } catch (IllegalArgumentException e) {
            Sentry.captureException(e);
        }
    }

    private static String getDocumentStartScript() {
        return "(function(){"
            + "if(window.__nemBestilSentryInstalled){return;}"
            + "window.__nemBestilSentryInstalled=true;"
            + "function url(){try{return location.origin+location.pathname;}catch(e){return String(location.href||'');}}"
            + "function send(method,category,message,level,data){try{"
            + "if(!window.NemBestilSentry||!window.NemBestilSentry[method]){return;}"
            + "window.NemBestilSentry[method](category,message,level||'info',JSON.stringify(Object.assign({url:url()},data||{})));"
            + "}catch(e){}}"
            + "function breadcrumb(category,message,data,level){send('addBreadcrumb',category,message,level,data);}"
            + "function capture(message,data,level){send('captureMessage','webview.javascript',message,level||'error',data);}"
            + "breadcrumb('navigation','Document initialized',{readyState:document.readyState});"
            + "window.addEventListener('error',function(event){capture('Unhandled JavaScript error',{"
            + "message:event.message,filename:event.filename,lineno:event.lineno,colno:event.colno,"
            + "error:event.error&&event.error.stack?String(event.error.stack):String(event.error||'')"
            + "});});"
            + "window.addEventListener('unhandledrejection',function(event){var reason=event.reason;"
            + "capture('Unhandled promise rejection',{reason:reason&&reason.stack?String(reason.stack):String(reason)});});"
            + "window.addEventListener('hashchange',function(){breadcrumb('navigation','Hash changed');});"
            + "window.addEventListener('popstate',function(){breadcrumb('navigation','History popped');});"
            + "var pushState=history.pushState;"
            + "history.pushState=function(){var from=url();var result=pushState.apply(this,arguments);"
            + "breadcrumb('navigation','History pushed',{from:from,to:url()});return result;};"
            + "var replaceState=history.replaceState;"
            + "history.replaceState=function(){var from=url();var result=replaceState.apply(this,arguments);"
            + "breadcrumb('navigation','History replaced',{from:from,to:url()});return result;};"
            + "document.addEventListener('visibilitychange',function(){breadcrumb('lifecycle','Visibility changed',{state:document.visibilityState});});"
            + "})();";
    }

    private static final class SentryBridgeWebViewClient extends BridgeWebViewClient {
        SentryBridgeWebViewClient(Bridge bridge) {
            super(bridge);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            addWebViewBreadcrumb(
                "webview.navigation",
                "Navigation requested",
                SentryLevel.INFO,
                request.getUrl().toString()
            );

            return super.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            addWebViewBreadcrumb("webview.navigation", "Page started", SentryLevel.INFO, url);
            Sentry.setTag("webview.url", stripUrlDetails(url));
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Map<String, Object> data = new HashMap<>();
            data.put("url", stripUrlDetails(url));
            data.put("progress", view.getProgress());
            addBreadcrumb("webview.navigation", "Page finished", SentryLevel.INFO, data);
            super.onPageFinished(view, url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            Map<String, Object> data = new HashMap<>();
            data.put("url", stripUrlDetails(request.getUrl().toString()));
            data.put("mainFrame", request.isForMainFrame());
            data.put("code", error.getErrorCode());
            data.put("description", String.valueOf(error.getDescription()));
            addBreadcrumb("webview.error", "WebView resource error", SentryLevel.WARNING, data);

            if (request.isForMainFrame()) {
                captureMessage("WebView main frame load error", SentryLevel.ERROR, data);
            }

            super.onReceivedError(view, request, error);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            Map<String, Object> data = new HashMap<>();
            data.put("url", stripUrlDetails(request.getUrl().toString()));
            data.put("mainFrame", request.isForMainFrame());
            data.put("statusCode", errorResponse.getStatusCode());
            data.put("reasonPhrase", errorResponse.getReasonPhrase());
            addBreadcrumb("webview.error", "WebView HTTP error", SentryLevel.WARNING, data);

            if (request.isForMainFrame() || errorResponse.getStatusCode() >= 500) {
                captureMessage("WebView HTTP error", SentryLevel.ERROR, data);
            }

            super.onReceivedHttpError(view, request, errorResponse);
        }
    }

    private static final class SentryBridgeWebChromeClient extends BridgeWebChromeClient {
        SentryBridgeWebChromeClient(Bridge bridge) {
            super(bridge);
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            Map<String, Object> data = new HashMap<>();
            data.put("source", stripUrlDetails(consoleMessage.sourceId()));
            data.put("line", consoleMessage.lineNumber());
            data.put("level", consoleMessage.messageLevel().name());

            SentryLevel level = mapConsoleLevel(consoleMessage.messageLevel());
            addBreadcrumb("webview.console", consoleMessage.message(), level, data);

            if (level == SentryLevel.ERROR || level == SentryLevel.FATAL) {
                captureMessage("WebView console error", level, data);
            }

            return super.onConsoleMessage(consoleMessage);
        }
    }

    private static final class SentryJavascriptBridge {
        @JavascriptInterface
        public void addBreadcrumb(String category, String message, String level, String dataJson) {
            WebViewSentrySupport.addBreadcrumb(category, message, parseLevel(level), parseJsonData(dataJson));
        }

        @JavascriptInterface
        public void captureMessage(String category, String message, String level, String dataJson) {
            Map<String, Object> data = parseJsonData(dataJson);
            data.put("category", category);
            WebViewSentrySupport.captureMessage(message, parseLevel(level), data);
        }
    }

    private static void addWebViewBreadcrumb(
        String category,
        String message,
        SentryLevel level,
        String url
    ) {
        Map<String, Object> data = new HashMap<>();
        data.put("url", stripUrlDetails(url));
        addBreadcrumb(category, message, level, data);
    }

    private static void addBreadcrumb(
        String category,
        String message,
        SentryLevel level,
        Map<String, Object> data
    ) {
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setCategory(category);
        breadcrumb.setMessage(message);
        breadcrumb.setLevel(level);

        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                breadcrumb.setData(entry.getKey(), entry.getValue());
            }
        }

        Sentry.addBreadcrumb(breadcrumb);
    }

    private static void captureMessage(String message, SentryLevel level, Map<String, Object> data) {
        Sentry.withScope(scope -> {
            scope.setTag("event.origin", "webview");
            if (data != null) {
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    scope.setExtra(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            Sentry.captureMessage(message, level);
        });
    }

    private static Map<String, Object> parseJsonData(String dataJson) {
        Map<String, Object> data = new HashMap<>();

        if (dataJson == null || dataJson.isEmpty()) {
            return data;
        }

        try {
            JSONObject jsonObject = new JSONObject(dataJson);
            Iterator<String> keys = jsonObject.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                Object value = jsonObject.get(key);
                data.put(key, "url".equals(key) && value instanceof String ? stripUrlDetails((String) value) : value);
            }
        } catch (JSONException e) {
            data.put("parseError", e.getMessage());
        }

        return data;
    }

    private static SentryLevel parseLevel(String level) {
        if ("debug".equals(level)) {
            return SentryLevel.DEBUG;
        }
        if ("warning".equals(level) || "warn".equals(level)) {
            return SentryLevel.WARNING;
        }
        if ("error".equals(level)) {
            return SentryLevel.ERROR;
        }
        if ("fatal".equals(level)) {
            return SentryLevel.FATAL;
        }

        return SentryLevel.INFO;
    }

    private static SentryLevel mapConsoleLevel(ConsoleMessage.MessageLevel level) {
        if (level == ConsoleMessage.MessageLevel.ERROR) {
            return SentryLevel.ERROR;
        }
        if (level == ConsoleMessage.MessageLevel.WARNING) {
            return SentryLevel.WARNING;
        }
        if (level == ConsoleMessage.MessageLevel.DEBUG) {
            return SentryLevel.DEBUG;
        }

        return SentryLevel.INFO;
    }

    private static String stripUrlDetails(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        try {
            Uri uri = Uri.parse(url);
            Uri.Builder builder = uri.buildUpon();
            builder.clearQuery();
            builder.fragment(null);
            return builder.build().toString();
        } catch (Exception e) {
            return url;
        }
    }
}
