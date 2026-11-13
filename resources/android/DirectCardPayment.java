package com.nembestil.pos3.app;

import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Durable local once-only payment submission; result retries never repeat a charge. */
final class DirectCardPayment {
    interface OutcomeListener { void onOutcome(String checkoutId, String outcome); }
    private static final java.util.List<OutcomeListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    static void addListener(OutcomeListener listener) { listeners.add(listener); }
    static void removeListener(OutcomeListener listener) { listeners.remove(listener); }
    private static final String PREFS = "direct-card-payments";
    private static final Set<String> running = ConcurrentHashMap.newKeySet();
    private static final Set<String> submittedPayments = ConcurrentHashMap.newKeySet();
    private static final Set<String> reporting = ConcurrentHashMap.newKeySet();
    private static final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> reportAttempts = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ScheduledExecutorService reportRetries = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(operation -> {
        Thread thread = new Thread(operation, "DirectCardPaymentRetry");
        thread.setDaemon(true);
        return thread;
    });
    private static SharedPreferences store(ForwarderService service) { return service.getSharedPreferences(PREFS, 0); }
    private static String key(ForwarderService service, String id) { return service.directPaymentBaseUrl() + ":" + id; }

    static Set<String> unresolvedTerminalIds(ForwarderService service) {
        Set<String> ids = new java.util.HashSet<>();
        for (Map.Entry<String, ?> entry : store(service).getAll().entrySet()) {
            if (!entry.getKey().startsWith(service.directPaymentBaseUrl() + ":")) continue;
            try {
                JSONObject record = new JSONObject((String) entry.getValue());
                if (!record.optBoolean("acknowledged")) ids.add(record.getJSONObject("prepared").getString("terminalId"));
            } catch (Exception ignored) { }
        }
        return ids;
    }

    static synchronized void start(ForwarderService service, JSONObject prepared) throws Exception {
        String id = prepared.getString("checkoutId");
        java.util.UUID.fromString(id);
        if (!service.directPaymentForwarderId().equals(prepared.getString("forwarderId"))) throw new Exception("Payment belongs to another tablet.");
        if (service.directPaymentTarget(prepared.getString("terminalId")) == null) throw new Exception("This tablet cannot reach the selected terminal.");
        String key = key(service, id);
        if (store(service).contains(key)) { recover(service); return; }
        if (prepared.getLong("expiresAt") <= System.currentTimeMillis()) throw new Exception("The payment preparation expired.");
        int api = prepared.getInt("apiVersion");
        String operation = id.replace("-", "");
        validateStep(prepared.getJSONObject("preflight"), "GET", "/api/v" + api + "/DeviceInformation");
        validateStep(prepared.getJSONObject("payment"), "POST", "/api/v" + api + (api == 1 ? "/PaymentsAsync" : "/Payments"));
        validateStep(prepared.getJSONObject("abort"), "POST", "/api/v" + api + "/Abort" + (api == 1 ? "/" + operation : ""));
        if (api == 1) validateStep(prepared.getJSONObject("status"), "GET", "/api/v1/PaymentsAsync/" + operation + "/status");
        else if (api != 2) throw new Exception("Unsupported terminal API.");
        JSONObject body = new JSONObject(prepared.getJSONObject("payment").getString("body"));
        if (!operation.equals(body.getJSONObject("customData").getString("saleAttemptId"))) throw new Exception("Payment identity mismatch.");
        JSONObject record = new JSONObject().put("prepared", prepared).put("state", "started").put("origin", service.directPaymentBaseUrl());
        // Commit before scheduling/network I/O. A process death from here on is
        // ambiguous and recovery only reports it; it never replays payment POST.
        if (!store(service).edit().putString(key, record.toString()).commit()) throw new Exception("Could not persist payment identity.");
        running.add(key);
        service.enqueueDirectPayment(prepared.getString("terminalId"), () -> execute(service, key, record));
    }

    private static void validateStep(JSONObject step, String method, String path) throws Exception {
        if (!method.equals(step.getString("method")) || !path.equals(step.getString("path"))) throw new Exception("Invalid prepared terminal operation.");
    }

    static synchronized void rejectBeforeSubmission(ForwarderService service, JSONObject prepared) {
        try {
            if (!service.directPaymentForwarderId().equals(prepared.getString("forwarderId"))) return;
            String key = key(service, prepared.getString("checkoutId"));
            if (store(service).contains(key)) return;
            JSONObject record = new JSONObject().put("prepared", prepared).put("state", "not_started").put("origin", service.directPaymentBaseUrl());
            if (store(service).edit().putString(key, record.toString()).commit()) reportAsync(service, key, record);
        } catch (Exception ignored) { }
    }

    private static JSONObject request(ForwarderService service, JSONObject record, JSONObject step, int timeout) throws Exception {
        if (!record.getString("origin").equals(service.directPaymentBaseUrl())) throw new Exception("The active installation changed.");
        JSONObject prepared = record.getJSONObject("prepared");
        PaymentTerminalReachability.Target target = service.directPaymentTarget(prepared.getString("terminalId"));
        if (target == null) throw new Exception("The selected terminal is no longer locally available.");
        HttpURLConnection connection = SamportTerminalConnection.open(service, target.ipAddress, target.port, step.getString("path"));
        try {
            connection.setRequestMethod(step.getString("method"));
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(timeout);
            JSONObject headers = step.getJSONObject("headers");
            for (Iterator<String> keys = headers.keys(); keys.hasNext();) { String name = keys.next(); connection.setRequestProperty(name, headers.getString(name)); }
            if (!step.isNull("body")) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) { output.write(step.getString("body").getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            JSONObject responseHeaders = new JSONObject();
            for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) responseHeaders.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue().get(0));
            }
            return new JSONObject().put("status", status).put("headers", responseHeaders).put("body", read(connection, status));
        } finally { connection.disconnect(); }
    }

    private static String read(HttpURLConnection connection, int status) throws Exception {
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) { if (output.size() + count > 512 * 1024) throw new Exception("Terminal response too large."); output.write(buffer, 0, count); }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void execute(ForwarderService service, String key, JSONObject record) {
        boolean submitted = false;
        JSONObject prepared = record.optJSONObject("prepared");
        try {
            reportAsync(service, key, record);
            JSONObject preflight = request(service, record, prepared.getJSONObject("preflight"), 5_000);
            record.put("preflight", preflight);
            JSONObject information = new JSONObject(preflight.getString("body"));
            if (preflight.getInt("status") != 200 || !prepared.getString("terminalId").equals(information.getJSONObject("terminal").getString("id"))) throw new Exception("Terminal identity could not be checked.");
            JSONObject ongoing = information.optJSONObject("ongoingOperation");
            String type = ongoing == null ? "None" : ongoing.optString("type", ongoing.optString("transactionType", "None"));
            boolean idleV1 = prepared.getInt("apiVersion") == 1 && ongoing != null && ongoing.has("operationId") && ongoing.optString("operationId").isEmpty();
            if (!"None".equals(type) && !type.isEmpty() && !idleV1) throw new Exception("The terminal is busy.");
            synchronized (DirectCardPayment.class) {
                if (cancelled.contains(key) || prepared.getLong("expiresAt") <= System.currentTimeMillis()) throw new Exception("Payment cancelled before submission.");
                submittedPayments.add(key);
                submitted = true;
            }
            JSONObject response = request(service, record, prepared.getJSONObject("payment"), 5 * 60_000);
            if (prepared.getInt("apiVersion") == 1 && response.getInt("status") == 200) {
                String operation = prepared.getString("checkoutId").replace("-", "");
                if (!operation.equals(new JSONObject(response.getString("body")).optString("operationId"))) throw new Exception("Async payment identity mismatch.");
                long deadline = System.currentTimeMillis() + 5 * 60_000;
                do {
                    if (cancelled.contains(key)) request(service, record, prepared.getJSONObject("abort"), 5_000);
                    response = request(service, record, prepared.getJSONObject("status"), 5_000);
                    if (response.getInt("status") == 200) break;
                    if (response.getInt("status") != 204) throw new Exception("Async payment outcome unavailable.");
                    Thread.sleep(500);
                } while (System.currentTimeMillis() < deadline);
            }
            if (response.getInt("status") != 200) throw new Exception("Payment outcome unavailable.");
            record.put("response", response).put("state", "resolved");
        } catch (Exception exception) {
            try { record.put("state", submitted ? "unknown" : "not_started"); } catch (Exception ignored) { }
        } finally {
            store(service).edit().putString(key, record.toString()).commit();
            synchronized (DirectCardPayment.class) {
                submittedPayments.remove(key);
                running.remove(key);
                cancelled.remove(key);
            }
            reportAsync(service, key, record);
        }
    }

    static synchronized void cancel(ForwarderService service, String id) throws Exception {
        String key = key(service, id);
        if (!running.contains(key)) return;
        cancelled.add(key);
        String value = store(service).getString(key, null);
        if (value == null || !submittedPayments.contains(key)) return;
        JSONObject record = new JSONObject(value);
        Thread worker = new Thread(() -> {
            try {
                JSONObject prepared = record.getJSONObject("prepared");
                for (int attempt = 0; attempt < 3 && submittedPayments.contains(key); attempt++) {
                    request(service, record, prepared.getJSONObject("abort"), 5_000);
                    Thread.sleep(250);
                }
            }
            catch (Exception ignored) { /* The final payment result remains authoritative. */ }
        }, "DirectCardPaymentAbort");
        worker.setDaemon(true); worker.start();
    }

    static synchronized void recover(ForwarderService service) {
        for (Map.Entry<String, ?> entry : store(service).getAll().entrySet()) {
            if (!entry.getKey().startsWith(service.directPaymentBaseUrl() + ":") || running.contains(entry.getKey())) continue;
            try {
                JSONObject record = new JSONObject((String) entry.getValue());
                if (record.optBoolean("acknowledged")) {
                    if (record.optLong("expiresAt", Long.MAX_VALUE) < System.currentTimeMillis() - 24 * 60 * 60_000L) store(service).edit().remove(entry.getKey()).commit();
                    continue;
                }
                if ("started".equals(record.optString("state"))) { record.put("state", "unknown"); store(service).edit().putString(entry.getKey(), record.toString()).commit(); }
                reportAsync(service, entry.getKey(), record);
            } catch (Exception ignored) { }
        }
    }

    private static void reportAsync(ForwarderService service, String key, JSONObject record) {
        if (!record.optString("origin").equals(service.directPaymentBaseUrl())) return;
        final String origin = service.directPaymentBaseUrl();
        final String token = service.directPaymentToken();
        final String body = record.toString();
        if (!reporting.add(key)) return;
        Thread worker = new Thread(() -> {
            boolean accepted = false;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(origin + "/api/_internal/direct-card-payment").openConnection();
                try {
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestMethod("POST"); connection.setConnectTimeout(5_000); connection.setReadTimeout(10_000);
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                    connection.setRequestProperty("Content-Type", "application/json"); connection.setDoOutput(true);
                    try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
                    accepted = connection.getResponseCode() == 200;
                    if (accepted && !"started".equals(new JSONObject(body).getString("state"))) {
                        if ("resolved".equals(new JSONObject(body).getString("state"))) {
                            String outcome = new JSONObject(record.getJSONObject("response").getString("body")).optString("transactionOutcome");
                            for (OutcomeListener listener : listeners) {
                                try { listener.onOutcome(record.getJSONObject("prepared").getString("checkoutId"), outcome); }
                                catch (RuntimeException ignored) { /* UI delivery cannot block durable result acknowledgement. */ }
                            }
                        }
                        // Keep only the once-only tombstone after authoritative acknowledgement.
                        store(service).edit().putString(key, new JSONObject().put("acknowledged", true).put("expiresAt", record.getJSONObject("prepared").getLong("expiresAt")).toString()).commit();
                        reportAttempts.remove(key);
                    }
                } finally { connection.disconnect(); }
            } catch (Exception ignored) { /* getStatus/reconnection retries the saved result, never payment. */ }
            finally {
                reporting.remove(key);
                String latest = store(service).getString(key, null);
                if (latest != null && !latest.equals(body) && !latest.contains("\"acknowledged\":true")) {
                    try { reportAsync(service, key, new JSONObject(latest)); } catch (Exception ignored) { }
                } else if (!accepted && latest != null && !latest.contains("\"acknowledged\":true")) {
                    int attempt = reportAttempts.merge(key, 1, Integer::sum);
                    if (attempt <= 5) reportRetries.schedule(() -> {
                        String current = store(service).getString(key, null);
                        if (current != null && !current.contains("\"acknowledged\":true")) {
                            try { reportAsync(service, key, new JSONObject(current)); } catch (Exception ignored) { }
                        }
                    }, 1_000L * attempt, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            }
        }, "DirectCardPaymentResult");
        worker.setDaemon(true); worker.start();
    }
}
