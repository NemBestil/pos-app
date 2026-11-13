package com.nembestil.pos3.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;

/** Only the active installation may initiate its locally prepared card payment. */
@CapacitorPlugin(name = "DirectCardPayment")
public class DirectCardPaymentPlugin extends Plugin {
    private final DirectCardPayment.OutcomeListener listener = (checkoutId, outcome) -> {
        JSObject result = new JSObject();
        result.put("checkoutId", checkoutId);
        result.put("outcome", outcome);
        notifyListeners("outcome", result, true);
    };

    @Override public void load() { DirectCardPayment.addListener(listener); }
    @Override protected void handleOnDestroy() { DirectCardPayment.removeListener(listener); super.handleOnDestroy(); }

    private ForwarderService service() throws Exception {
        ForwarderService service = ForwarderService.directPaymentService();
        if (service == null || service.directPaymentBaseUrl() == null) throw new Exception("The forwarder is not running.");
        URI page = new URI(getBridge().getWebView().getUrl());
        URI origin = new URI(service.directPaymentBaseUrl());
        if (!origin.getScheme().equals(page.getScheme()) || !origin.getAuthority().equals(page.getAuthority())) {
            throw new Exception("The WebView does not belong to the active installation.");
        }
        return service;
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                ForwarderService service = service();
                JSObject result = new JSObject();
                result.put("supported", true);
                result.put("forwarderId", service.directPaymentForwarderId());
                result.put("terminalIds", new JSONArray(service.directPaymentTerminalIds()));
                result.put("pendingTerminalIds", new JSONArray(DirectCardPayment.unresolvedTerminalIds(service)));
                DirectCardPayment.recover(service);
                call.resolve(result);
            } catch (Exception exception) {
                JSObject result = new JSObject();
                result.put("supported", false);
                call.resolve(result);
            }
        });
    }

    @PluginMethod
    public void start(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            ForwarderService service = null;
            JSONObject prepared = call.getObject("prepared");
            try {
                service = service();
                if (prepared == null) throw new Exception("A prepared payment is required.");
                DirectCardPayment.start(service, prepared);
                JSObject result = new JSObject();
                result.put("accepted", true);
                call.resolve(result);
            } catch (Exception exception) {
                if (service != null && prepared != null) DirectCardPayment.rejectBeforeSubmission(service, prepared);
                call.reject(exception.getMessage());
            }
        });
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                ForwarderService service = service();
                String checkoutId = call.getString("checkoutId");
                if (checkoutId == null) throw new Exception("A checkout ID is required.");
                DirectCardPayment.cancel(service, checkoutId);
                call.resolve();
            } catch (Exception exception) {
                call.reject(exception.getMessage());
            }
        });
    }
}
