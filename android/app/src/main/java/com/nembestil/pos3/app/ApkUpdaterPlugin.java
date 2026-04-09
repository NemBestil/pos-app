package com.nembestil.pos3.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@CapacitorPlugin(name = "ApkUpdater")
public class ApkUpdaterPlugin extends Plugin {
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
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream");

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
