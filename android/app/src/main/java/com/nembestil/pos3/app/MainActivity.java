package com.nembestil.pos3.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AndroidFullscreenPlugin.class);
        registerPlugin(BluetoothPrinterPlugin.class);
        registerPlugin(ForwarderServicePlugin.class);
        registerPlugin(NetworkPrinterPlugin.class);
        registerPlugin(PaymentTerminalDiscoveryPlugin.class);
        registerPlugin(SecondaryDisplayPlugin.class);
        super.onCreate(savedInstanceState);
        WebViewSentrySupport.install(bridge);
        BridgeReinjector.install(bridge);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            AndroidFullscreenPlugin.applyCurrentState(this);
        }
    }
}
