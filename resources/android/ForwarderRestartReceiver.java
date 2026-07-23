package com.nembestil.pos3.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restores the forwarder after a device reboot or an app replacement when
 * forwarding credentials are still configured.
 */
public class ForwarderRestartReceiver extends BroadcastReceiver {

    private static final String TAG = "ForwarderRestart";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Log.i(TAG, "Restoring configured forwarder after " + action);
        ForwarderService.requestStartIfConfigured(context.getApplicationContext());
    }
}
