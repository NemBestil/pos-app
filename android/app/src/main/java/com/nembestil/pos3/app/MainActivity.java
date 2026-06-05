package com.nembestil.pos3.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BluetoothPrinterPlugin.class);
        registerPlugin(ForwarderServicePlugin.class);
        registerPlugin(NetworkPrinterPlugin.class);
        registerPlugin(PaymentTerminalDiscoveryPlugin.class);
        super.onCreate(savedInstanceState);
        WebViewSentrySupport.install(bridge);
        BridgeReinjector.install(bridge);
    }
}
