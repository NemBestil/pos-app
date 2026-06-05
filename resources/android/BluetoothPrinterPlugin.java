package com.nembestil.pos3.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;

import java.util.Set;

/**
 * Lists the Bluetooth printers paired (bonded) with this device so the webview
 * can register them as receipt printers. ESC/POS printers (e.g. Xprinter) use
 * Bluetooth Classic SPP and must be paired in the Android Bluetooth settings
 * first; from there {@link ForwarderService} delivers the actual print jobs over
 * an RFCOMM socket.
 */
@CapacitorPlugin(
    name = "BluetoothPrinter",
    permissions = {
        @Permission(alias = "bluetooth", strings = { Manifest.permission.BLUETOOTH_CONNECT })
    }
)
public class BluetoothPrinterPlugin extends Plugin {

    private static final String BLUETOOTH_ALIAS = "bluetooth";

    @PluginMethod
    public void list(PluginCall call) {
        // BLUETOOTH_CONNECT only exists (and is only enforced) from Android 12.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && getPermissionState(BLUETOOTH_ALIAS) != PermissionState.GRANTED) {
            requestPermissionForAlias(BLUETOOTH_ALIAS, call, "listPermissionCallback");
            return;
        }
        resolveBondedPrinters(call);
    }

    @PermissionCallback
    private void listPermissionCallback(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && getPermissionState(BLUETOOTH_ALIAS) != PermissionState.GRANTED) {
            call.reject("Bluetooth permission is required to list paired printers.");
            return;
        }
        resolveBondedPrinters(call);
    }

    private void resolveBondedPrinters(PluginCall call) {
        BluetoothAdapter adapter = resolveAdapter();
        JSONArray printers = new JSONArray();

        if (adapter == null || !adapter.isEnabled()) {
            JSObject ret = new JSObject();
            ret.put("printers", printers);
            call.resolve(ret);
            return;
        }

        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice device : bonded) {
                    String address = device.getAddress();
                    if (address == null || address.isEmpty()) {
                        continue;
                    }
                    String name = device.getName();
                    JSObject printer = new JSObject();
                    printer.put("name", name == null ? "" : name);
                    printer.put("address", address.toUpperCase());
                    printers.put(printer);
                }
            }
        } catch (SecurityException e) {
            call.reject("Bluetooth permission is required to list paired printers.");
            return;
        }

        JSObject ret = new JSObject();
        ret.put("printers", printers);
        call.resolve(ret);
    }

    private BluetoothAdapter resolveAdapter() {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : null;
    }
}
