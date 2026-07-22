package com.nembestil.pos3.app;

import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Presents the POS customer display page on an attached secondary monitor via
 * Android's Presentation API. The web app calls setUrl(...) once; from then on
 * the plugin keeps a fullscreen WebView presentation in sync as displays are
 * plugged and unplugged. The presented WebView shares the app's WebView profile
 * with the main Capacitor WebView, so same-origin pages on both screens can
 * communicate over a BroadcastChannel.
 */
@CapacitorPlugin(name = "SecondaryDisplay")
public class SecondaryDisplayPlugin extends Plugin {

    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private CustomerDisplayPresentation presentation;
    private String url;

    @Override
    public void load() {
        displayManager = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                syncPresentation();
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                syncPresentation();
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }
        };
        displayManager.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
    }

    @PluginMethod
    public void setUrl(PluginCall call) {
        String nextUrl = call.getString("url");
        if (nextUrl == null) {
            call.reject("Missing url");
            return;
        }

        url = nextUrl;
        getActivity().runOnUiThread(() -> {
            syncPresentation();

            JSObject result = new JSObject();
            result.put("presenting", presentation != null);
            call.resolve(result);
        });
    }

    @Override
    protected void handleOnDestroy() {
        if (displayListener != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayListener = null;
        }
        dismissPresentation();
    }

    private Display findPresentationDisplay() {
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return displays.length > 0 ? displays[0] : null;
    }

    private void syncPresentation() {
        Display display = findPresentationDisplay();

        if (url == null || display == null) {
            dismissPresentation();
            return;
        }

        if (presentation != null && presentation.getDisplay().getDisplayId() == display.getDisplayId()) {
            presentation.loadUrl(url);
            return;
        }

        dismissPresentation();
        presentation = new CustomerDisplayPresentation(getActivity(), display, url);
        presentation.show();
    }

    private void dismissPresentation() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
    }

    private static class CustomerDisplayPresentation extends Presentation {

        private final String initialUrl;
        private WebView webView;

        CustomerDisplayPresentation(Context outerContext, Display display, String initialUrl) {
            super(outerContext, display);
            this.initialUrl = initialUrl;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            webView = new WebView(getContext());
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            webView.setWebViewClient(new WebViewClient());
            webView.loadUrl(initialUrl);

            setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void loadUrl(String nextUrl) {
            if (webView != null && !nextUrl.equals(webView.getUrl())) {
                webView.loadUrl(nextUrl);
            }
        }

        @Override
        protected void onStop() {
            super.onStop();
            if (webView != null) {
                webView.destroy();
                webView = null;
            }
        }
    }
}
