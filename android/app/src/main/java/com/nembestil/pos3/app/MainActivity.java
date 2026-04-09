package com.nembestil.pos3.app;

import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.BridgeActivity;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        patchJSInjection();
    }

    private void patchJSInjection() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                Method getJsInjector = bridge.getClass().getDeclaredMethod("getJSInjector");
                getJsInjector.setAccessible(true);
                Object injector = getJsInjector.invoke(bridge);

                assert injector != null;
                Method getScriptString = injector.getClass().getDeclaredMethod("getScriptString");
                String scriptString = (String) getScriptString.invoke(injector);

                Set<String> allowedOrigins = Arrays.stream(bridge.getConfig().getAllowNavigation())
                        .map(str -> "https://" + str.replaceAll("^https?://|/\\*$", ""))
                        .collect(Collectors.toSet());

                assert scriptString != null;
                WebViewCompat.addDocumentStartJavaScript(bridge.getWebView(), scriptString, allowedOrigins);
            }
        } catch (Exception e) {
            Log.e("Error", e.getMessage() != null ? e.getMessage() : "");
        }
    }
}