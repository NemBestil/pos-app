package com.nembestil.pos3.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppReleaseUpdateReceiver extends BroadcastReceiver {

    public static final String EXTRA_OPEN_UPDATE = "openAppReleaseUpdate";
    public static final String EXTRA_ACCEPT_UPDATE = "acceptAppReleaseUpdate";
    public static final String EXTRA_VERSION = "appReleaseVersion";
    public static final String EXTRA_DOWNLOAD_URL = "appReleaseDownloadUrl";
    public static final String EXTRA_RELEASE_URL = "appReleaseUrl";
    public static final String EXTRA_FILE_NAME = "appReleaseFileName";

    private static final String TAG = "AppReleaseUpdate";
    private static final String CHECK_ACTION = "com.nembestil.pos3.app.CHECK_RELEASE_UPDATE";
    private static final String RELEASES_ENDPOINT =
        "https://api.github.com/repos/NemBestil/pos-app/releases?per_page=100";
    private static final String CHANNEL_ID = "app-release-updates";
    private static final String PREFERENCES = "app-release-updates";
    private static final String LAST_NOTIFIED_VERSION = "last-notified-version";
    private static final long CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L;
    private static final int ALARM_REQUEST_CODE = 12001;
    private static final int NOTIFICATION_ID = 12002;
    private static final int OPEN_REQUEST_CODE = 12003;
    private static final int ACCEPT_REQUEST_CODE = 12004;
    private static final Pattern RELEASE_TAG_PATTERN =
        Pattern.compile("^apk-(\\d+\\.\\d+\\.\\d+)(-pre)?$");

    public static void schedule(Context context) {
        Context applicationContext = context.getApplicationContext();
        AlarmManager alarmManager =
            (AlarmManager) applicationContext.getSystemService(Context.ALARM_SERVICE);

        if (alarmManager == null || findCheckIntent(applicationContext, PendingIntent.FLAG_NO_CREATE) != null) {
            return;
        }

        PendingIntent checkIntent = findCheckIntent(applicationContext, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + CHECK_INTERVAL_MILLIS,
            CHECK_INTERVAL_MILLIS,
            checkIntent
        );
        Log.i(TAG, "Scheduled app release checks every 24 hours");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !CHECK_ACTION.equals(intent.getAction())) {
            return;
        }

        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                checkForUpdate(context.getApplicationContext());
            } catch (Exception exception) {
                Log.w(TAG, "Scheduled app release check failed", exception);
            } finally {
                pendingResult.finish();
            }
        }, "app-release-update-check").start();
    }

    private void checkForUpdate(Context context) throws Exception {
        AvailableRelease release = fetchLatestRelease();

        if (release == null || compareVersions(release.version, BuildConfig.VERSION_NAME) <= 0) {
            return;
        }

        String lastNotifiedVersion = context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(LAST_NOTIFIED_VERSION, "");

        if (release.version.equals(lastNotifiedVersion) || !canShowNotifications(context)) {
            return;
        }

        showUpdateNotification(context, release);
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_NOTIFIED_VERSION, release.version)
            .apply();
    }

    private AvailableRelease fetchLatestRelease() throws Exception {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(RELEASES_ENDPOINT).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "NemBestil-POS-Android");

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("GitHub releases returned HTTP " + statusCode);
            }

            JSONArray releases = new JSONArray(readBody(connection.getInputStream()));
            AvailableRelease latestRelease = null;

            for (int index = 0; index < releases.length(); index += 1) {
                AvailableRelease release = extractRelease(releases.getJSONObject(index));
                if (
                    release != null
                        && (
                            latestRelease == null
                                || compareVersions(release.version, latestRelease.version) > 0
                        )
                ) {
                    latestRelease = release;
                }
            }

            return latestRelease;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private AvailableRelease extractRelease(JSONObject release) {
        if (
            release.optBoolean("draft", false)
                || release.optBoolean("prerelease", false) != BuildConfig.APP_PRERELEASE
        ) {
            return null;
        }

        Matcher tagMatcher = RELEASE_TAG_PATTERN.matcher(release.optString("tag_name", ""));
        if (!tagMatcher.matches() || (tagMatcher.group(2) != null) != BuildConfig.APP_PRERELEASE) {
            return null;
        }

        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }

        for (int index = 0; index < assets.length(); index += 1) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null) {
                continue;
            }

            String downloadUrl = asset.optString("browser_download_url", "");
            if (downloadUrl.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                return new AvailableRelease(
                    tagMatcher.group(1),
                    downloadUrl,
                    release.optString("html_url", ""),
                    asset.optString("name", "update.apk")
                );
            }
        }

        return null;
    }

    private void showUpdateNotification(Context context, AvailableRelease release) {
        createNotificationChannel(context);

        PendingIntent openIntent = createUpdateIntent(context, release, false, OPEN_REQUEST_CODE);
        PendingIntent acceptIntent = createUpdateIntent(context, release, true, ACCEPT_REQUEST_CODE);

        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_forwarder)
            .setContentTitle("App update available")
            .setContentText("NemBestil POS " + release.version + " is ready to install.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(0, "Update now", acceptIntent);

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification.build());
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Notifications when a new NemBestil POS app release is available");
        manager.createNotificationChannel(channel);
    }

    private PendingIntent createUpdateIntent(
        Context context,
        AvailableRelease release,
        boolean accept,
        int requestCode
    ) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(
            "nembestil://app-release-update/" + release.version + "?accept=" + accept
        ));
        intent.putExtra(EXTRA_OPEN_UPDATE, true);
        intent.putExtra(EXTRA_ACCEPT_UPDATE, accept);
        intent.putExtra(EXTRA_VERSION, release.version);
        intent.putExtra(EXTRA_DOWNLOAD_URL, release.downloadUrl);
        intent.putExtra(EXTRA_RELEASE_URL, release.releaseUrl);
        intent.putExtra(EXTRA_FILE_NAME, release.fileName);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private boolean canShowNotifications(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent findCheckIntent(Context context, int extraFlags) {
        Intent intent = new Intent(context, AppReleaseUpdateReceiver.class);
        intent.setAction(CHECK_ACTION);
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            extraFlags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static String readBody(InputStream inputStream) throws Exception {
        StringBuilder body = new StringBuilder();

        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
            )
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }

        return body.toString();
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int partCount = Math.max(leftParts.length, rightParts.length);

        for (int index = 0; index < partCount; index += 1) {
            int leftValue = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;

            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }

        return 0;
    }

    private static final class AvailableRelease {
        private final String version;
        private final String downloadUrl;
        private final String releaseUrl;
        private final String fileName;

        private AvailableRelease(
            String version,
            String downloadUrl,
            String releaseUrl,
            String fileName
        ) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.releaseUrl = releaseUrl;
            this.fileName = fileName;
        }
    }
}
