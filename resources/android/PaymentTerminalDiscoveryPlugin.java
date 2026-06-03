package com.nembestil.pos3.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

import java.util.List;

/**
 * Thin Capacitor bridge over {@link ForwarderService}'s discovery state.
 *
 * The UDP listener and the multicast lock live in the foreground service so
 * discovery stays alive whenever the service is running (and stops when the
 * user disables it). This plugin only exists to give the webview a way to
 * query the current snapshot and subscribe to {@code terminalSeen} events.
 */
@CapacitorPlugin(name = "PaymentTerminalDiscovery")
public class PaymentTerminalDiscoveryPlugin extends Plugin {

    private final ForwarderService.DiscoveryListener listener = (terminal, isNew) -> {
        JSObject payload = new JSObject();
        payload.put("terminal", terminalToJson(terminal));
        payload.put("isNew", isNew);
        notifyListeners("terminalSeen", payload);
    };

    @Override
    public void load() {
        ForwarderService.registerDiscoveryListener(listener);
    }

    @Override
    protected void handleOnDestroy() {
        ForwarderService.unregisterDiscoveryListener(listener);
        super.handleOnDestroy();
    }

    @PluginMethod
    public void list(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("terminals", buildTerminalsArray());
        call.resolve(ret);
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("listening", ForwarderService.isDiscoveryRunning());
        ret.put("count", ForwarderService.getDiscoveredTerminalCount());
        call.resolve(ret);
    }

    private static JSONArray buildTerminalsArray() {
        List<ForwarderService.DiscoveredTerminal> terminals = ForwarderService.getDiscoveredTerminals();
        JSONArray arr = new JSONArray();
        for (ForwarderService.DiscoveredTerminal terminal : terminals) {
            arr.put(terminalToJson(terminal));
        }
        return arr;
    }

    private static JSObject terminalToJson(ForwarderService.DiscoveredTerminal t) {
        JSObject o = new JSObject();
        o.put("terminalId", t.terminalId);
        o.put("identity", t.identity);
        o.put("ipAddress", t.ipAddress);
        o.put("port", t.port);
        o.put("protocolType", t.protocolType);
        o.put("protocolVersion", t.protocolVersion);
        o.put("lastSeenAt", ForwarderService.formatIsoUtc(t.lastSeenAtMs));
        return o;
    }
}
