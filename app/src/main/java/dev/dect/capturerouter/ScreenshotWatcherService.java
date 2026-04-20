package dev.dect.capturerouter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.content.pm.ServiceInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ScreenshotWatcherService extends Service {
    static final String ACTION_STOP = "dev.dect.capturerouter.STOP";
    private static final String CHANNEL_ID = "watcher";
    private static final int NOTIFICATION_ID = 7;
    private static final long SCREENSHOT_RESCAN_MS = 60_000;
    private static final long ATTRIBUTION_WINDOW_MS = 20_000;
    private static final long MEDIASTORE_LOOKBACK_MS = 30 * 60 * 1000L;

    private final Map<String, Long> knownFiles = new HashMap<>();
    private final HashSet<String> queuedPaths = new HashSet<>();
    private final Runnable mediaStoreScanRunnable = this::scanRecentMediaStore;
    private HandlerThread workerThread;
    private Handler worker;
    private FileObserver observer;
    private ContentObserver mediaObserver;
    private String observedDir;
    private boolean running;

    public static void start(Context context) {
        Intent intent = new Intent(context, ScreenshotWatcherService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            AppStore.log(context, "ERROR", "SERVICE", "Could not start watcher: " + e.getMessage());
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, ScreenshotWatcherService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            AppStore.log(context, "ERROR", "SERVICE", "Could not stop watcher: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("screenshot-watcher");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AppStore.setMonitoringEnabled(this, false);
            WatchdogReceiver.cancel(this);
            AppStore.log(this, "INFO", "SERVICE", "Monitoring stopped");
            stopSelf();
            return START_NOT_STICKY;
        }
        WatchdogReceiver.schedule(this);
        promoteToForeground("Monitoring screenshots");
        if (!running) {
            running = true;
            AppStore.log(this, "INFO", "SERVICE", "Monitoring started source=" + AppStore.getSourceDir(this));
            worker.post(this::primeKnownFiles);
            worker.post(this::startFileObserver);
            worker.post(this::startMediaObserver);
            worker.post(mediaStoreScanRunnable);
            worker.postDelayed(this::rescanScreenshots, 1000);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (observer != null) {
            observer.stopWatching();
            observer = null;
        }
        if (mediaObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(mediaObserver);
            } catch (RuntimeException ignored) {
            }
            mediaObserver = null;
        }
        if (AppStore.isMonitoringEnabled(this)) {
            WatchdogReceiver.schedule(this);
        }
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (AppStore.isMonitoringEnabled(this)) {
            WatchdogReceiver.schedule(this);
            AppStore.log(this, "INFO", "SERVICE", "Monitoring remains enabled after CaptureRouter was dismissed from recents");
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onTimeout(int type, int reason) {
        AppStore.log(this, "ERROR", "SERVICE", "Foreground service timeout type=" + type + " reason=" + reason);
        if (AppStore.isMonitoringEnabled(this)) {
            WatchdogReceiver.schedule(this);
        }
        stopSelf();
    }

    private void startFileObserver() {
        File dir = screenshotDir();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        observedDir = dir.getAbsolutePath();
        observer = new FileObserver(dir.getAbsolutePath(), FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !isScreenshotImage(path) || !ScreenshotNamer.isOriginalSystemScreenshot(ScreenshotWatcherService.this, path)) {
                    return;
                }
                File file = new File(observedDir, path);
                AppStore.log(ScreenshotWatcherService.this, "INFO", "OBSERVER", "Filesystem event=" + event + " file=" + path);
                queueCandidate(file, "file-observer", null);
            }
        };
        observer.startWatching();
        AppStore.log(this, "INFO", "OBSERVER", "FileObserver watching " + observedDir);
    }

    private void startMediaObserver() {
        if (mediaObserver != null) {
            return;
        }
        mediaObserver = new ContentObserver(worker) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                AppStore.log(ScreenshotWatcherService.this, "INFO", "MEDIASTORE", "MediaStore changed uri=" + uri);
                worker.removeCallbacks(mediaStoreScanRunnable);
                worker.postDelayed(mediaStoreScanRunnable, 500);
            }

            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }
        };
        try {
            getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
            AppStore.log(this, "INFO", "MEDIASTORE", "ContentObserver registered");
        } catch (RuntimeException e) {
            AppStore.log(this, "ERROR", "MEDIASTORE", "ContentObserver registration failed: " + e.getMessage());
        }
    }

    private void primeKnownFiles() {
        knownFiles.clear();
        for (File file : listScreenshots()) {
            knownFiles.put(file.getAbsolutePath(), file.lastModified());
        }
    }

    private void rescanScreenshots() {
        if (!running) {
            return;
        }
        try {
            List<File> files = listScreenshots();
            HashSet<String> current = new HashSet<>();
            for (File file : files) {
                String path = file.getAbsolutePath();
                current.add(path);
                Long previousModified = knownFiles.get(path);
                long modified = file.lastModified();
                if (previousModified == null || modified > previousModified) {
                    knownFiles.put(path, modified);
                    queueCandidate(file, "rescan", null);
                } else {
                    routeAlreadyNamed(file);
                }
            }
            ArrayList<String> removed = new ArrayList<>();
            for (String path : knownFiles.keySet()) {
                if (!current.contains(path)) {
                    removed.add(path);
                }
            }
            for (String path : removed) {
                knownFiles.remove(path);
            }
        } catch (Exception e) {
            AppStore.log(this, "ERROR", "OBSERVER", "Watcher error: " + e.getMessage());
        }
        scanRecentMediaStore();
        worker.postDelayed(this::rescanScreenshots, SCREENSHOT_RESCAN_MS);
    }

    private void queueCandidate(File file, String trigger, String processedKey) {
        String path = file.getAbsolutePath();
        if (!queuedPaths.add(path)) {
            return;
        }
        worker.postDelayed(() -> {
            try {
                handleCandidate(file, null, trigger, processedKey);
            } finally {
                queuedPaths.remove(path);
            }
        }, 700);
    }

    private void handleCandidate(File file, Uri mediaUri, String trigger, String processedKey) {
        if (processedKey != null && AppStore.hasProcessedScreenshot(this, processedKey)) {
            return;
        }
        if (!file.exists() || !isScreenshotImage(file.getName())) {
            if (processedKey != null) {
                AppStore.rememberProcessedScreenshot(this, processedKey);
            }
            if (!"mediastore".equals(trigger)) {
                AppStore.log(this, "WARN", "RENAME", "Candidate missing trigger=" + trigger + " file=" + file.getAbsolutePath());
            }
            return;
        }
        if (!waitUntilStable(file)) {
            AppStore.log(this, "WARN", "RENAME", "Skipped unstable screenshot trigger=" + trigger + " file=" + file.getName());
            return;
        }
        if (processedKey == null || processedKey.startsWith("file:")) {
            processedKey = processedKeyForFile(file);
        }
        if (AppStore.hasProcessedScreenshot(this, processedKey)) {
            return;
        }
        if (!ScreenshotNamer.isOriginalSystemScreenshot(this, file.getName())) {
            AppStore.rememberProcessedScreenshot(this, processedKey);
            routeAlreadyNamed(file);
            return;
        }
        AppStore.ForegroundApp foreground = AppStore.getRecentForegroundApp(this, ATTRIBUTION_WINDOW_MS);
        if (foreground == null) {
            AppStore.log(this, "WARN", "ATTRIBUTION", "Left screenshot unchanged because no recent foreground app was available trigger="
                    + trigger + " file=" + file.getName());
            AppStore.rememberProcessedScreenshot(this, processedKey);
            return;
        }
        ScreenshotNamer.RenameResult renamed = ScreenshotNamer.ensureNamed(this, file, foreground, mediaUri);
        if (!renamed.error.isEmpty()) {
            AppStore.log(this, "WARN", "RENAME", renamed.error + " trigger=" + trigger + " fg=" + AppStore.foregroundSummary(this));
        } else if (renamed.renamed) {
            String confidence = foreground == null ? "NONE" : foreground.confidence;
            String source = foreground == null ? "" : foreground.source;
            AppStore.log(this, "RENAMED", "RENAME", file.getName() + " -> " + renamed.file.getName()
                    + " trigger=" + trigger + " app=" + renamed.packageName + " confidence=" + confidence + " source=" + source);
        }
        knownFiles.remove(file.getAbsolutePath());
        knownFiles.put(renamed.file.getAbsolutePath(), renamed.file.lastModified());
        if (processedKey != null) {
            AppStore.rememberProcessedScreenshot(this, processedKey);
        }
        if (foreground == null && renamed.renamed) {
            AppStore.log(this, "WARN", "ATTRIBUTION", "No recent foreground app for " + renamed.file.getName());
        }
        routeByFilename(renamed.file);
    }

    private void routeAlreadyNamed(File file) {
        AppStore.Rule rule = AppStore.findRuleForFilename(this, file.getName());
        if (rule == null) {
            return;
        }
        if (AppStore.Rule.MODE_MANUAL.equals(rule.mode)) {
            addRecoveredSkipped(file, rule);
            return;
        }
        routeByFilename(file);
    }

    private void addRecoveredSkipped(File file, AppStore.Rule rule) {
        if (AppStore.hasPendingOrSkippedPath(this, file.getAbsolutePath())) {
            return;
        }
        String label = rule.labelForFilename(file.getName());
        AppStore.addSkipped(this, new AppStore.SkippedShot(
                "skipped-recovered-" + file.lastModified() + "-" + file.getName(),
                file.getAbsolutePath(),
                "",
                label,
                rule.id,
                rule.name,
                rule.destination,
                rule.nomedia,
                System.currentTimeMillis(),
                AppStore.SkippedShot.REASON_RECOVERED
        ));
        AppStore.log(this, "INFO", "MOVE", label + ": " + file.getName() + " recovered for skipped review");
    }

    private void routeByFilename(File file) {
        AppStore.Rule rule = AppStore.findRuleForFilename(this, file.getName());
        if (rule == null) {
            AppStore.log(this, "INFO", "MOVE", "No filename rule for " + file.getName());
            return;
        }
        String label = rule.labelForFilename(file.getName());
        if (AppStore.Rule.MODE_MANUAL.equals(rule.mode)) {
            AppStore.addPending(this, new AppStore.PendingShot(
                    "pending-" + file.lastModified() + "-" + file.getName(),
                    file.getAbsolutePath(),
                    "",
                    label,
                    rule.id,
                    rule.name,
                    rule.destination,
                    rule.nomedia,
                    System.currentTimeMillis()
            ));
            AppStore.log(this, "QUEUED", "MOVE", label + ": " + file.getName() + " queued for " + rule.name);
            return;
        }
        ScreenshotMover.MoveResult result = ScreenshotMover.move(this, file.getAbsolutePath(), rule.destination, rule.nomedia);
        if (result.ok) {
            knownFiles.remove(file.getAbsolutePath());
            AppStore.log(this, "MOVED", "MOVE", label + ": " + file.getName() + " -> " + rule.destination);
        } else {
            AppStore.log(this, "ERROR", "MOVE", "Move failed for " + file.getName() + ": " + result.error);
        }
    }

    private void scanRecentMediaStore() {
        if (!running) {
            return;
        }
        long afterSeconds = (System.currentTimeMillis() - MEDIASTORE_LOOKBACK_MS) / 1000L;
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.IS_PENDING
        };
        String selection = MediaStore.Images.Media.DATE_ADDED + ">=?";
        String[] args = new String[]{String.valueOf(afterSeconds)};
        String expectedRelative = sourceRelativePath();
        int seen = 0;
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) {
                AppStore.log(this, "WARN", "MEDIASTORE", "Query returned null");
                return;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            int relCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            int modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
            int pendingCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING);
            while (cursor.moveToNext() && seen < 80) {
                seen++;
                String name = cursor.getString(nameCol);
                if (name == null || !isScreenshotImage(name)) {
                    continue;
                }
                if (!ScreenshotNamer.isOriginalSystemScreenshot(this, name)) {
                    continue;
                }
                String relative = cursor.getString(relCol);
                if (!relativeMatches(expectedRelative, relative)) {
                    continue;
                }
                long size = cursor.getLong(sizeCol);
                int pending = cursor.getInt(pendingCol);
                if (pending != 0 || size <= 0) {
                    continue;
                }
                long id = cursor.getLong(idCol);
                String key = "media:" + id;
                if (AppStore.hasProcessedScreenshot(this, key)) {
                    continue;
                }
                File file = fileForMediaRow(cursor.getString(dataCol), relative, name);
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                worker.postDelayed(() -> handleCandidate(file, uri, "mediastore", key), 700);
            }
        } catch (Exception e) {
            AppStore.log(this, "ERROR", "MEDIASTORE", "Query failed: " + e.getMessage());
        }
    }

    private File fileForMediaRow(String data, String relative, String name) {
        if (data != null && !data.isEmpty()) {
            return new File(data);
        }
        String base = AppStore.getSourceDir(this);
        return new File(base, name);
    }

    private String sourceRelativePath() {
        String source = AppStore.getSourceDir(this);
        String external = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        if (source.startsWith(external)) {
            String relative = source.substring(external.length());
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return relative.endsWith("/") ? relative : relative + "/";
        }
        if (source.startsWith("/sdcard/")) {
            String relative = source.substring("/sdcard/".length());
            return relative.endsWith("/") ? relative : relative + "/";
        }
        return "";
    }

    private boolean relativeMatches(String expected, String actual) {
        if (expected.isEmpty()) {
            return true;
        }
        return actual != null && expected.equals(actual);
    }

    private String processedKeyForFile(File file) {
        return "file:" + file.getAbsolutePath() + ":" + file.lastModified() + ":" + file.length();
    }

    private List<File> listScreenshots() {
        File[] files = screenshotDir().listFiles(file -> file.isFile() && isScreenshotImage(file.getName()));
        if (files == null) {
            return Collections.emptyList();
        }
        ArrayList<File> result = new ArrayList<>();
        Collections.addAll(result, files);
        return result;
    }

    private File screenshotDir() {
        return new File(AppStore.getSourceDir(this));
    }

    private boolean isScreenshotImage(String name) {
        if (name == null || name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
    }

    private boolean waitUntilStable(File file) {
        long lastSize = -1;
        for (int i = 0; i < 8; i++) {
            long current = file.length();
            if (current > 0 && current == lastSize) {
                return true;
            }
            lastSize = current;
            try {
                Thread.sleep(350);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return file.exists() && file.length() > 0;
    }

    private void promoteToForeground(String text) {
        Notification notification = notification(text);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            AppStore.log(this, "ERROR", "SERVICE", "startForeground failed: " + e.getMessage());
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screenshot monitoring",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Renames and routes screenshots");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stop = new Intent(this, ScreenshotWatcherService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                1,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("CaptureRouter")
                .setContentText(text)
                .setContentIntent(openIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setLocalOnly(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(R.drawable.ic_launcher, "Stop", stopIntent)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification(text));
        }
    }
}
