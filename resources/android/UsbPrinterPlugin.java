package com.nembestil.pos3.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Enumerates attached USB devices and obtains access to the one selected in the POS UI. */
@CapacitorPlugin(name = "UsbPrinter")
public class UsbPrinterPlugin extends Plugin {

    private static final String ACTION_USB_PERMISSION =
        "com.nembestil.pos3.app.action.USB_PRINTER_PERMISSION";

    private String pendingCallId;
    private String pendingDeviceName;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }

            PluginCall call = pendingCallId == null ? null : bridge.getSavedCall(pendingCallId);
            String deviceName = pendingDeviceName;
            pendingCallId = null;
            pendingDeviceName = null;
            if (call == null) {
                return;
            }

            UsbManager manager = resolveUsbManager();
            UsbDevice device = manager == null ? null : manager.getDeviceList().get(deviceName);
            boolean granted = device != null && (
                manager.hasPermission(device)
                    || intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            );
            if (!granted) {
                call.reject("USB permission is required to use this printer.");
                bridge.releaseCall(call);
                return;
            }

            call.resolve(serializeDevice(manager, device));
            ForwarderService.requestNotifyConfigChanged(getContext().getApplicationContext());
            bridge.releaseCall(call);
        }
    };

    @Override
    public void load() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(permissionReceiver, filter);
        }
    }

    @Override
    protected void handleOnDestroy() {
        getContext().unregisterReceiver(permissionReceiver);
        super.handleOnDestroy();
    }

    @PluginMethod
    public void list(PluginCall call) {
        UsbManager manager = resolveUsbManager();
        JSONArray devices = new JSONArray();
        if (manager != null) {
            List<UsbDevice> attached = new ArrayList<>(manager.getDeviceList().values());
            attached.sort(Comparator.comparing(UsbDevice::getDeviceName));
            for (UsbDevice device : attached) {
                devices.put(serializeDevice(manager, device));
            }
        }

        JSObject result = new JSObject();
        result.put("devices", devices);
        call.resolve(result);
    }

    @PluginMethod
    public void requestAccess(PluginCall call) {
        String deviceName = call.getString("deviceName");
        if (deviceName == null || deviceName.isEmpty()) {
            call.reject("deviceName is required.");
            return;
        }

        UsbManager manager = resolveUsbManager();
        UsbDevice device = manager == null ? null : manager.getDeviceList().get(deviceName);
        if (manager == null || device == null) {
            call.reject("The USB device is no longer attached.");
            return;
        }

        if (manager.hasPermission(device)) {
            call.resolve(serializeDevice(manager, device));
            return;
        }

        if (pendingCallId != null) {
            call.reject("Another USB permission request is already active.");
            return;
        }

        bridge.saveCall(call);
        pendingCallId = call.getCallbackId();
        pendingDeviceName = deviceName;
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
            getContext(),
            0,
            new Intent(ACTION_USB_PERMISSION).setPackage(getContext().getPackageName()),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        manager.requestPermission(device, permissionIntent);
    }

    private UsbManager resolveUsbManager() {
        return (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
    }

    private JSObject serializeDevice(UsbManager manager, UsbDevice device) {
        JSObject result = new JSObject();
        result.put("deviceName", device.getDeviceName());
        result.put("deviceId", device.getDeviceId());
        result.put("vendorId", device.getVendorId());
        result.put("productId", device.getProductId());
        result.put("deviceClass", device.getDeviceClass());
        result.put("deviceSubclass", device.getDeviceSubclass());
        result.put("deviceProtocol", device.getDeviceProtocol());
        result.put("configurationCount", device.getConfigurationCount());
        result.put("hasPermission", manager != null && manager.hasPermission(device));
        result.put("manufacturerName", readDeviceText(device, "manufacturer"));
        result.put("productName", readDeviceText(device, "product"));
        result.put("version", device.getVersion());
        result.put("serialNumber", readDeviceText(device, "serial"));

        JSONArray interfaces = new JSONArray();
        for (int interfaceIndex = 0; interfaceIndex < device.getInterfaceCount(); interfaceIndex++) {
            UsbInterface usbInterface = device.getInterface(interfaceIndex);
            JSObject interfaceInfo = new JSObject();
            interfaceInfo.put("id", usbInterface.getId());
            interfaceInfo.put("alternateSetting", usbInterface.getAlternateSetting());
            interfaceInfo.put("name", usbInterface.getName());
            interfaceInfo.put("interfaceClass", usbInterface.getInterfaceClass());
            interfaceInfo.put("interfaceSubclass", usbInterface.getInterfaceSubclass());
            interfaceInfo.put("interfaceProtocol", usbInterface.getInterfaceProtocol());

            JSONArray endpoints = new JSONArray();
            for (int endpointIndex = 0; endpointIndex < usbInterface.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                JSObject endpointInfo = new JSObject();
                endpointInfo.put("address", endpoint.getAddress());
                endpointInfo.put("direction", endpoint.getDirection());
                endpointInfo.put("type", endpoint.getType());
                endpointInfo.put("maxPacketSize", endpoint.getMaxPacketSize());
                endpointInfo.put("interval", endpoint.getInterval());
                endpoints.put(endpointInfo);
            }
            interfaceInfo.put("endpoints", endpoints);
            interfaces.put(interfaceInfo);
        }
        result.put("interfaces", interfaces);
        return result;
    }

    private String readDeviceText(UsbDevice device, String field) {
        try {
            if ("manufacturer".equals(field)) return device.getManufacturerName();
            if ("product".equals(field)) return device.getProductName();
            return device.getSerialNumber();
        } catch (SecurityException ignored) {
            return null;
        }
    }

}
