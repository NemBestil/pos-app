package com.nembestil.pos3.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns notification-channel audio for both foreground previews and background
 * POS events. Custom sounds are cached in app-private storage and invalidated
 * when their versioned source URL changes.
 */
public final class NotificationSoundManager {

    public interface PlaybackCallback {
        void onComplete();
        void onError(Exception exception);
    }

    private static final String TAG = "NotificationSound";
    private static final String PREFS_NAME = "notification_sound_prefs";
    private static final String PREFS_CONFIGURATION = "configuration";
    private static final String PREFS_SOURCE_PREFIX = "source.";
    private static final String STANDARD_SOUND_ID = "standard";
    private static final long REPEAT_DELAY_MS = 1_000;
    private static final int MAX_SOUND_BYTES = 10 * 1024 * 1024;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicLong NEXT_PLAYBACK_ID = new AtomicLong(0);

    private static PlaybackSession activeSession;
    private static MediaPlayer activePlayer;
    private static Runnable pendingRepeat;

    private NotificationSoundManager() {}

    public static void configure(Context context, JSONObject rawConfiguration, String baseUrl) {
        Context appContext = context.getApplicationContext();
        JSONObject configuration = new JSONObject();
        try {
            configuration.put("takeawayPreOrderHours", rawConfiguration.optInt("takeawayPreOrderHours", 12));
            for (String eventKey : new String[] { "takeawayOrder", "takeawayPreOrder", "tableBooking" }) {
                JSONObject rawSelection = rawConfiguration.optJSONObject(eventKey);
                if (rawSelection == null) {
                    continue;
                }
                JSONObject selection = new JSONObject(rawSelection.toString());
                String sourcePath = selection.optString("sourcePath", "");
                selection.remove("sourcePath");
                if (!sourcePath.isEmpty()) {
                    selection.put("sourceUrl", new URL(new URL(ensureTrailingSlash(baseUrl)), sourcePath).toString());
                }
                configuration.put(eventKey, selection);
                prefetch(appContext, selection);
            }
        } catch (Exception exception) {
            Log.w(TAG, "Could not apply notification sound configuration", exception);
            return;
        }

        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_CONFIGURATION, configuration.toString())
            .apply();
    }

    public static void playConfigured(Context context, String eventKey) {
        JSONObject configuration = readConfiguration(context);
        JSONObject selection = configuration.optJSONObject(eventKey);
        if (selection == null) {
            play(context, STANDARD_SOUND_ID, 2, null, null);
            return;
        }
        play(
            context,
            selection.optString("soundId", STANDARD_SOUND_ID),
            selection.optInt("repeatCount", 2),
            selection.optString("sourceUrl", null),
            null
        );
    }

    public static int getTakeawayPreOrderHours(Context context) {
        return Math.max(0, readConfiguration(context).optInt("takeawayPreOrderHours", 12));
    }

    public static void play(
        Context context,
        String soundId,
        int repeatCount,
        String sourceUrl,
        PlaybackCallback callback
    ) {
        Context appContext = context.getApplicationContext();
        String normalizedSoundId = normalizeSoundId(soundId);
        int normalizedRepeatCount = Math.max(1, Math.min(10, repeatCount));
        long playbackId = NEXT_PLAYBACK_ID.incrementAndGet();
        MAIN_HANDLER.post(() -> {
            finishActivePlayback();
            PlaybackSession session = new PlaybackSession(
                playbackId,
                normalizedSoundId,
                normalizedRepeatCount,
                sourceUrl,
                callback
            );
            activeSession = session;
            resolveSource(appContext, session);
        });
    }

    public static void stop() {
        MAIN_HANDLER.post(NotificationSoundManager::finishActivePlayback);
    }

    public static void cancelRepeats() {
        MAIN_HANDLER.post(() -> {
            PlaybackSession session = activeSession;
            if (session == null) {
                return;
            }
            session.cancelRemaining = true;
            if (pendingRepeat != null) {
                MAIN_HANDLER.removeCallbacks(pendingRepeat);
                pendingRepeat = null;
                finishActivePlayback();
            }
        });
    }

    private static void prefetch(Context context, JSONObject selection) {
        String soundId = selection.optString("soundId", STANDARD_SOUND_ID);
        String sourceUrl = selection.optString("sourceUrl", "");
        if (STANDARD_SOUND_ID.equals(soundId) || sourceUrl.isEmpty()) {
            return;
        }
        DOWNLOAD_EXECUTOR.execute(() -> {
            try {
                ensureCachedFile(context, normalizeSoundId(soundId), sourceUrl);
            } catch (Exception exception) {
                Log.w(TAG, "Could not prefetch notification sound " + soundId, exception);
            }
        });
    }

    private static JSONObject readConfiguration(Context context) {
        String raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_CONFIGURATION, "{}");
        try {
            return new JSONObject(raw == null ? "{}" : raw);
        } catch (Exception exception) {
            return new JSONObject();
        }
    }

    private static void resolveSource(Context context, PlaybackSession session) {
        if (STANDARD_SOUND_ID.equals(session.soundId)) {
            Uri defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (defaultSound == null) {
                fail(session, new IOException("Android has no default notification sound configured."));
                return;
            }
            session.sourceUri = defaultSound;
            startCurrentPlay(context, session);
            return;
        }
        if (session.sourceUrl == null || session.sourceUrl.isEmpty()) {
            fail(session, new IOException("A custom notification sound requires a source URL."));
            return;
        }

        DOWNLOAD_EXECUTOR.execute(() -> {
            try {
                File file = ensureCachedFile(context, session.soundId, session.sourceUrl);
                MAIN_HANDLER.post(() -> {
                    if (activeSession != session) {
                        return;
                    }
                    session.sourceFile = file;
                    startCurrentPlay(context, session);
                });
            } catch (Exception exception) {
                MAIN_HANDLER.post(() -> fail(session, exception));
            }
        });
    }

    private static File ensureCachedFile(Context context, String soundId, String sourceUrl) throws IOException {
        URL url = new URL(sourceUrl);
        String protocol = url.getProtocol();
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new IOException("Notification sound URLs must use HTTP or HTTPS.");
        }

        File cacheDirectory = new File(context.getFilesDir(), "notification-sounds");
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw new IOException("Could not create the notification sound cache.");
        }
        File cachedFile = new File(cacheDirectory, soundId + ".mp3");
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (cachedFile.isFile() && sourceUrl.equals(prefs.getString(PREFS_SOURCE_PREFIX + soundId, null))) {
            return cachedFile;
        }

        File temporaryFile = new File(cacheDirectory, soundId + ".download");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.1");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Notification sound download returned HTTP " + status + ".");
            }
            int totalBytes = 0;
            byte[] buffer = new byte[16 * 1024];
            try (
                InputStream input = connection.getInputStream();
                FileOutputStream output = new FileOutputStream(temporaryFile, false)
            ) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > MAX_SOUND_BYTES) {
                        throw new IOException("Notification sound exceeds the 10 MB limit.");
                    }
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            } catch (IOException exception) {
                temporaryFile.delete();
                throw exception;
            }
            if (totalBytes == 0) {
                temporaryFile.delete();
                throw new IOException("Notification sound download was empty.");
            }
            if (cachedFile.exists() && !cachedFile.delete()) {
                temporaryFile.delete();
                throw new IOException("Could not replace the cached notification sound.");
            }
            if (!temporaryFile.renameTo(cachedFile)) {
                temporaryFile.delete();
                throw new IOException("Could not finalize the cached notification sound.");
            }
            prefs.edit().putString(PREFS_SOURCE_PREFIX + soundId, sourceUrl).apply();
            return cachedFile;
        } finally {
            connection.disconnect();
        }
    }

    private static void startCurrentPlay(Context context, PlaybackSession session) {
        if (activeSession != session) {
            return;
        }
        try {
            MediaPlayer player = new MediaPlayer();
            activePlayer = player;
            player.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            );
            player.setOnCompletionListener(completedPlayer -> {
                completedPlayer.release();
                if (activePlayer == completedPlayer) {
                    activePlayer = null;
                }
                onCurrentPlayFinished(context, session);
            });
            player.setOnErrorListener((failedPlayer, what, extra) -> {
                failedPlayer.release();
                if (activePlayer == failedPlayer) {
                    activePlayer = null;
                }
                fail(session, new IOException("Android MediaPlayer failed (" + what + ", " + extra + ")."));
                return true;
            });
            player.setOnPreparedListener(MediaPlayer::start);
            if (session.sourceFile != null) {
                player.setDataSource(session.sourceFile.getAbsolutePath());
            } else {
                player.setDataSource(context, session.sourceUri);
            }
            player.prepareAsync();
        } catch (Exception exception) {
            fail(session, exception);
        }
    }

    private static void onCurrentPlayFinished(Context context, PlaybackSession session) {
        if (activeSession != session) {
            return;
        }
        session.playsCompleted += 1;
        if (session.cancelRemaining || session.playsCompleted >= session.repeatCount) {
            finishActivePlayback();
            return;
        }
        pendingRepeat = () -> {
            pendingRepeat = null;
            startCurrentPlay(context, session);
        };
        MAIN_HANDLER.postDelayed(pendingRepeat, REPEAT_DELAY_MS);
    }

    private static void fail(PlaybackSession session, Exception exception) {
        if (activeSession != session) {
            return;
        }
        PlaybackCallback callback = session.callback;
        releasePlayer();
        activeSession = null;
        pendingRepeat = null;
        if (callback != null) {
            callback.onError(exception);
        } else {
            Log.w(TAG, "Notification sound playback failed", exception);
        }
    }

    private static void finishActivePlayback() {
        if (pendingRepeat != null) {
            MAIN_HANDLER.removeCallbacks(pendingRepeat);
            pendingRepeat = null;
        }
        releasePlayer();
        PlaybackSession session = activeSession;
        activeSession = null;
        if (session != null && session.callback != null) {
            session.callback.onComplete();
        }
    }

    private static void releasePlayer() {
        MediaPlayer player = activePlayer;
        activePlayer = null;
        if (player == null) {
            return;
        }
        try {
            player.stop();
        } catch (IllegalStateException ignored) {
        }
        player.release();
    }

    private static String normalizeSoundId(String soundId) {
        if (soundId == null || !soundId.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid notification sound ID.");
        }
        return soundId;
    }

    private static String ensureTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    private static final class PlaybackSession {
        final long id;
        final String soundId;
        final int repeatCount;
        final String sourceUrl;
        final PlaybackCallback callback;
        int playsCompleted = 0;
        boolean cancelRemaining = false;
        Uri sourceUri;
        File sourceFile;

        PlaybackSession(long id, String soundId, int repeatCount, String sourceUrl, PlaybackCallback callback) {
            this.id = id;
            this.soundId = soundId;
            this.repeatCount = repeatCount;
            this.sourceUrl = sourceUrl;
            this.callback = callback;
        }
    }
}
