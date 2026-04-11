package dev.dect.capturerouter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

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
    private static final long SCREENSHOT_RESCAN_MS = 15_000;
    private static final long ATTRIBUTION_WINDOW_MS = 20_000;

    private final Map<String, Long> knownFiles = new HashMap<>();
    private HandlerThread workerThread;
    private Handler worker;
    private FileObserver observer;
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
            AppStore.log(context, "ERROR", "Could not start watcher: " + e.getMessage());
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, ScreenshotWatcherService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            AppStore.log(context, "ERROR", "Could not stop watcher: " + e.getMessage());
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
            AppStore.log(this, "INFO", "Monitoring stopped");
            stopSelf();
            return START_NOT_STICKY;
        }
        WatchdogReceiver.schedule(this);
        startForeground(NOTIFICATION_ID, notification("Monitoring screenshots"));
        if (!running) {
            running = true;
            AppStore.log(this, "INFO", "Monitoring started");
            worker.post(this::primeKnownFiles);
            worker.post(this::startFileObserver);
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
            AppStore.log(this, "INFO", "Monitoring remains enabled after CaptureRouter was dismissed from recents");
        }
        super.onTaskRemoved(rootIntent);
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
                if (path == null || !isScreenshotImage(path)) {
                    return;
                }
                File file = new File(observedDir, path);
                worker.postDelayed(() -> handleCandidate(file), 700);
            }
        };
        observer.startWatching();
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
                    handleCandidate(file);
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
            AppStore.log(this, "ERROR", "Watcher error: " + e.getMessage());
        }
        worker.postDelayed(this::rescanScreenshots, SCREENSHOT_RESCAN_MS);
    }

    private void handleCandidate(File file) {
        if (!file.exists() || !isScreenshotImage(file.getName())) {
            return;
        }
        if (!waitUntilStable(file)) {
            AppStore.log(this, "WARN", "Skipped unstable screenshot: " + file.getName());
            return;
        }
        AppStore.ForegroundApp foreground = AppStore.getRecentForegroundApp(this, ATTRIBUTION_WINDOW_MS);
        ScreenshotNamer.RenameResult renamed = ScreenshotNamer.ensureNamed(this, file, foreground);
        if (!renamed.error.isEmpty()) {
            AppStore.log(this, "WARN", renamed.error);
        } else if (renamed.renamed) {
            AppStore.log(this, "RENAMED", file.getName() + " -> " + renamed.file.getName());
        }
        knownFiles.remove(file.getAbsolutePath());
        knownFiles.put(renamed.file.getAbsolutePath(), renamed.file.lastModified());
        if (foreground == null && renamed.renamed) {
            AppStore.log(this, "WARN", "No recent foreground app for " + renamed.file.getName());
        }
        routeByFilename(renamed.file);
    }

    private void routeAlreadyNamed(File file) {
        AppStore.Rule rule = AppStore.findRuleForFilename(this, file.getName());
        if (rule == null || AppStore.Rule.MODE_MANUAL.equals(rule.mode)) {
            return;
        }
        routeByFilename(file);
    }

    private void routeByFilename(File file) {
        AppStore.Rule rule = AppStore.findRuleForFilename(this, file.getName());
        if (rule == null) {
            AppStore.log(this, "INFO", "No filename rule for " + file.getName());
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
            AppStore.log(this, "QUEUED", label + ": " + file.getName() + " queued for " + rule.name);
            return;
        }
        ScreenshotMover.MoveResult result = ScreenshotMover.move(this, file.getAbsolutePath(), rule.destination, rule.nomedia);
        if (result.ok) {
            knownFiles.remove(file.getAbsolutePath());
            AppStore.log(this, "MOVED", label + ": " + file.getName() + " -> " + rule.destination);
        } else {
            AppStore.log(this, "ERROR", "Move failed for " + file.getName() + ": " + result.error);
        }
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
