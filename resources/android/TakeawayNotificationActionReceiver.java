package com.nembestil.pos3.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TakeawayNotificationActionReceiver extends BroadcastReceiver {

    private static final String TAG = "TakeawayActionReceiver";

    public static final String ACTION_ACCEPT =
        "com.nembestil.pos3.app.action.ACCEPT_TAKEAWAY_ORDER";
    public static final String ACTION_CANCEL =
        "com.nembestil.pos3.app.action.CANCEL_TAKEAWAY_ORDER";
    public static final String EXTRA_BASE_URL = "baseUrl";
    public static final String EXTRA_ORDER_ID = "orderId";
    public static final String EXTRA_ACTION_TOKEN = "actionToken";
    public static final String EXTRA_NOTIFICATION_ID = "notificationId";

    @Override
    public void onReceive(Context context, Intent intent) {
        String intentAction = intent == null ? null : intent.getAction();
        String action = ACTION_ACCEPT.equals(intentAction)
            ? "accept"
            : ACTION_CANCEL.equals(intentAction)
                ? "cancel"
                : null;
        String baseUrl = intent == null ? null : intent.getStringExtra(EXTRA_BASE_URL);
        String orderId = intent == null ? null : intent.getStringExtra(EXTRA_ORDER_ID);
        String actionToken = intent == null ? null : intent.getStringExtra(EXTRA_ACTION_TOKEN);
        int notificationId = intent == null ? 0 : intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0);
        if (action == null || isEmpty(baseUrl) || isEmpty(orderId) || isEmpty(actionToken)) {
            Log.w(TAG, "Ignoring incomplete takeaway notification action");
            return;
        }

        PendingResult pendingResult = goAsync();
        Context applicationContext = context.getApplicationContext();
        Thread requestThread = new Thread(
            () -> {
                try {
                    int status = submitAction(baseUrl, orderId, actionToken, action);
                    if ((status >= 200 && status < 300) || status == 409) {
                        NotificationManager manager =
                            (NotificationManager) applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
                        if (manager != null && notificationId != 0) {
                            manager.cancel(notificationId);
                        }
                    } else {
                        Log.w(TAG, "Takeaway notification action HTTP " + status);
                    }
                } catch (Exception exception) {
                    Log.w(TAG, "Could not apply takeaway notification action", exception);
                } finally {
                    pendingResult.finish();
                }
            },
            "TakeawayNotificationAction"
        );
        requestThread.start();
    }

    private static int submitAction(String baseUrl, String orderId, String token, String action)
        throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(
                baseUrl.replaceAll("/+$", "")
                    + "/api/takeaway/orders/"
                    + orderId
                    + "/notification-action"
            );
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");

            JSONObject body = new JSONObject();
            body.put("action", action);
            body.put("token", token);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (response != null) {
                try (InputStream ignored = response) {
                    byte[] buffer = new byte[512];
                    while (ignored.read(buffer) != -1) {
                        // Drain the response so the connection can close cleanly.
                    }
                }
            }
            return status;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
