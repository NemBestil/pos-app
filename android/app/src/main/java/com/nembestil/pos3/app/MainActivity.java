package com.nembestil.pos3.app;

import android.media.AudioManager;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AndroidFullscreenPlugin.class);
        registerPlugin(ApkUpdaterPlugin.class);
        registerPlugin(BluetoothPrinterPlugin.class);
        registerPlugin(ForwarderServicePlugin.class);
        registerPlugin(NetworkPrinterPlugin.class);
        registerPlugin(PaymentTerminalDiscoveryPlugin.class);
        registerPlugin(SecondaryDisplayPlugin.class);
        registerPlugin(UsbPrinterPlugin.class);
        super.onCreate(savedInstanceState);
        if (BuildConfig.BUILD_TYPE.equals("profile")) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        }
        setVolumeControlStream(AudioManager.STREAM_NOTIFICATION);
        WebViewSentrySupport.install(bridge);
        BridgeReinjector.install(bridge);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        ForwarderService.setAppFocused(hasFocus);
        if (hasFocus) {
            AndroidFullscreenPlugin.applyCurrentState(this);
        }
    }
}
