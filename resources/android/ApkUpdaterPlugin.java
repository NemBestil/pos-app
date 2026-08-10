package com.nembestil.pos3.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@CapacitorPlugin(
    name = "ApkUpdater",
    permissions = {
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class ApkUpdaterPlugin extends Plugin {

    private static final String NOTIFICATIONS_ALIAS = "notifications";

    @Override
    public void load() {
        AppReleaseUpdateReceiver.schedule(getContext());
        handleUpdateIntent(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        handleUpdateIntent(intent);
    }

    @PluginMethod
    public void getReleaseInfo(PluginCall call) {
        JSObject result = new JSObject();
        result.put("prerelease", BuildConfig.APP_PRERELEASE);
        call.resolve(result);
    }

    @PluginMethod
    public void schedulePeriodicChecks(PluginCall call) {
        AppReleaseUpdateReceiver.schedule(getContext());
        call.resolve();
    }

    @PluginMethod
    public void requestUpdateNotificationPermission(PluginCall call) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || getPermissionState(NOTIFICATIONS_ALIAS) == PermissionState.GRANTED
        ) {
            resolveNotificationPermission(call, true);
            return;
        }

        requestPermissionForAlias(
            NOTIFICATIONS_ALIAS,
            call,
            "updateNotificationPermissionCallback"
        );
    }

    @PermissionCallback
    private void updateNotificationPermissionCallback(PluginCall call) {
        resolveNotificationPermission(
            call,
            getPermissionState(NOTIFICATIONS_ALIAS) == PermissionState.GRANTED
        );
    }

    @PluginMethod
    public void getPendingUpdateAction(PluginCall call) {
        JSObject action = consumeUpdateIntent(getActivity().getIntent());
        JSObject result = new JSObject();

        if (action != null) {
            result.put("action", action);
        }

        call.resolve(result);
    }

    @PluginMethod
    public void canRequestPackageInstalls(PluginCall call) {
        JSObject result = new JSObject();
        boolean canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getContext().getPackageManager().canRequestPackageInstalls();

        result.put("value", canInstall);
        call.resolve(result);
    }

    @PluginMethod
    public void openInstallPermissionSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            call.resolve();
            return;
        }

        Intent intent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getContext().getPackageName())
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void openExternalUrl(PluginCall call) {
        String url = call.getString("url");

        if (url == null || url.trim().isEmpty()) {
            call.reject("url is required");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void installFromUrl(PluginCall call) {
        String url = call.getString("url");
        String fileName = call.getString("fileName");

        if (url == null || url.trim().isEmpty()) {
            call.reject("url is required");
            return;
        }

        new Thread(() -> downloadAndInstall(call, url, fileName)).start();
    }

    private void handleUpdateIntent(Intent intent) {
        JSObject action = consumeUpdateIntent(intent);

        if (action != null) {
            notifyListeners("updateNotificationAction", action, true);
        }
    }

    private JSObject consumeUpdateIntent(Intent intent) {
        if (
            intent == null
                || !intent.getBooleanExtra(AppReleaseUpdateReceiver.EXTRA_OPEN_UPDATE, false)
        ) {
            return null;
        }

        String version = intent.getStringExtra(AppReleaseUpdateReceiver.EXTRA_VERSION);
        String downloadUrl = intent.getStringExtra(AppReleaseUpdateReceiver.EXTRA_DOWNLOAD_URL);
        String releaseUrl = intent.getStringExtra(AppReleaseUpdateReceiver.EXTRA_RELEASE_URL);
        String fileName = intent.getStringExtra(AppReleaseUpdateReceiver.EXTRA_FILE_NAME);
        boolean prerelease = intent.getBooleanExtra(
            AppReleaseUpdateReceiver.EXTRA_PRERELEASE,
            false
        );
        boolean accept = intent.getBooleanExtra(AppReleaseUpdateReceiver.EXTRA_ACCEPT_UPDATE, false);

        intent.removeExtra(AppReleaseUpdateReceiver.EXTRA_OPEN_UPDATE);
        intent.removeExtra(AppReleaseUpdateReceiver.EXTRA_VERSION);
        intent.removeExtra(AppReleaseUpdateReceiver.EXTRA_DOWNLOAD_URL);
        intent.removeExtra(AppReleaseUpdateReceiver.EXTRA_RELEASE_URL);
        intent.removeExtra(AppReleaseUpdateReceiver.EXTRA_FILE_NAME);
        intent.removeExtra(AppReleaseUpdateReceiver.EXTRA_PRERELEASE);
        intent.removeExtra(AppReleaseUpdateReceiver.EXTRA_ACCEPT_UPDATE);

        if (version == null || downloadUrl == null || releaseUrl == null || fileName == null) {
            return null;
        }

        JSObject release = new JSObject();
        release.put("version", version);
        release.put("prerelease", prerelease);
        release.put("downloadUrl", downloadUrl);
        release.put("releaseUrl", releaseUrl);
        release.put("fileName", fileName);

        JSObject action = new JSObject();
        action.put("release", release);
        action.put("accept", accept);
        return action;
    }

    private void resolveNotificationPermission(PluginCall call, boolean granted) {
        JSObject result = new JSObject();
        result.put("granted", granted);
        call.resolve(result);
    }

    private void downloadAndInstall(PluginCall call, String url, String fileName) {
        HttpURLConnection connection = null;

        try {
            File updatesDirectory = new File(getContext().getCacheDir(), "apk-updates");
            if (!updatesDirectory.exists() && !updatesDirectory.mkdirs()) {
                throw new IllegalStateException("Could not create APK cache directory");
            }

            File apkFile = new File(updatesDirectory, resolveFileName(url, fileName));
            clearStaleApks(updatesDirectory, apkFile);

            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty(
                "Accept",
                "application/vnd.android.package-archive,application/octet-stream"
            );

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("APK download failed with HTTP " + statusCode);
            }

            try (InputStream inputStream = connection.getInputStream();
                 OutputStream outputStream = new FileOutputStream(apkFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            Activity activity = getActivity();
            if (activity == null) {
                throw new IllegalStateException("Activity unavailable");
            }

            activity.runOnUiThread(() -> {
                try {
                    launchInstaller(apkFile);
                    call.resolve();
                } catch (Exception exception) {
                    call.reject("Could not open APK installer", exception);
                }
            });
        } catch (Exception exception) {
            call.reject("Could not download APK", exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void launchInstaller(File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                apkFile
        );

        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        getContext().startActivity(installIntent);
    }

    private void clearStaleApks(File directory, File activeFile) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (!file.equals(activeFile) && file.getName().endsWith(".apk")) {
                file.delete();
            }
        }
    }

    private String resolveFileName(String url, String fileName) {
        String candidate = fileName;

        if (candidate == null || candidate.trim().isEmpty()) {
            int lastSlashIndex = url.lastIndexOf('/');
            candidate = lastSlashIndex >= 0 ? url.substring(lastSlashIndex + 1) : "update.apk";
        }

        String sanitized = candidate.replaceAll("[^A-Za-z0-9._-]", "-");

        if (!sanitized.endsWith(".apk")) {
            sanitized = sanitized + ".apk";
        }

        return sanitized;
    }
}
