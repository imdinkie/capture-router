package dev.dect.capturerouter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScreenshotWatcherService extends Service {
    static final String ACTION_STOP = "dev.dect.capturerouter.STOP";
    private static final String CHANNEL_ID = "watcher";
    private static final int NOTIFICATION_ID = 7;
    private static final long FOREGROUND_POLL_MS = 700;
    private static final long SCREENSHOT_POLL_MS = 1500;
    private static final long ATTRIBUTION_WINDOW_MS = 12_000;
    private static final Pattern ACTIVITY_PACKAGE = Pattern.compile("\\su\\d+\\s+([A-Za-z0-9_.$]+)(?:/|\\s)");

    private final Set<String> ignoredForegroundPackages = new HashSet<>();
    private final Map<String, Long> knownFiles = new HashMap<>();
    private final ArrayDeque<ForegroundSample> foregroundSamples = new ArrayDeque<>();
    private HandlerThread workerThread;
    private Handler worker;
    private boolean running;

    public static void start(Context context) {
        Intent intent = new Intent(context, ScreenshotWatcherService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, ScreenshotWatcherService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ignoredForegroundPackages.add(getPackageName());
        ignoredForegroundPackages.add("android");
        ignoredForegroundPackages.add("com.android.systemui");
        ignoredForegroundPackages.add("com.google.android.apps.nexuslauncher");
        ignoredForegroundPackages.add("com.google.android.permissioncontroller");
        workerThread = new HandlerThread("screenshot-watcher");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AppStore.setMonitoringEnabled(this, false);
            AppStore.log(this, "INFO", "Monitoring stopped");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("Monitoring screenshots"));
        if (!running) {
            running = true;
            AppStore.log(this, "INFO", "Monitoring started");
            worker.post(this::primeKnownFiles);
            worker.post(this::pollForeground);
            worker.postDelayed(this::pollScreenshots, 700);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
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

    private void pollForeground() {
        if (!running) {
            return;
        }
        String packageName = getForegroundPackage();
        long now = System.currentTimeMillis();
        if (packageName != null && !ignoredForegroundPackages.contains(packageName)) {
            foregroundSamples.addFirst(new ForegroundSample(packageName, now));
        }
        while (!foregroundSamples.isEmpty() && now - foregroundSamples.getLast().time > ATTRIBUTION_WINDOW_MS) {
            foregroundSamples.removeLast();
        }
        worker.postDelayed(this::pollForeground, FOREGROUND_POLL_MS);
    }

    private void primeKnownFiles() {
        knownFiles.clear();
        for (File file : listScreenshots()) {
            knownFiles.put(file.getAbsolutePath(), file.lastModified());
        }
    }

    private void pollScreenshots() {
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
        worker.postDelayed(this::pollScreenshots, SCREENSHOT_POLL_MS);
    }

    private void handleCandidate(File file) {
        if (!waitUntilStable(file)) {
            AppStore.log(this, "WARN", "Skipped unstable screenshot: " + file.getName());
            return;
        }
        String packageName = recentForegroundPackage();
        if (packageName == null) {
            AppStore.log(this, "INFO", "No recent foreground app for " + file.getName());
            return;
        }
        AppStore.Rule rule = AppStore.findRule(this, packageName);
        if (rule == null) {
            AppStore.log(this, "INFO", "No rule for " + packageName + " (" + file.getName() + ")");
            return;
        }
        moveScreenshot(file, rule, packageName);
    }

    private void moveScreenshot(File source, AppStore.Rule rule, String packageName) {
        File destinationDir = new File(rule.destination);
        File target = uniqueTarget(destinationDir, source.getName());
        StringBuilder command = new StringBuilder();
        command.append("mkdir -p ").append(RootShell.quote(destinationDir.getAbsolutePath()));
        if (rule.nomedia) {
            command.append(" && touch ").append(RootShell.quote(new File(destinationDir, ".nomedia").getAbsolutePath()));
        }
        command.append(" && mv ").append(RootShell.quote(source.getAbsolutePath()))
                .append(" ").append(RootShell.quote(target.getAbsolutePath()))
                .append(" && chmod 0660 ").append(RootShell.quote(target.getAbsolutePath()));
        RootShell.Result result = RootShell.run(command.toString(), 8000);
        if (result.ok()) {
            cleanupMediaStore(source.getAbsolutePath(), target.getAbsolutePath());
            String label = rule.label == null || rule.label.isEmpty() ? packageName : rule.label;
            AppStore.log(this, "MOVED", label + ": " + source.getName() + " -> " + rule.destination);
            updateNotification("Last moved: " + label);
        } else {
            AppStore.log(this, "ERROR", "Move failed for " + source.getName() + ": " + result.output);
        }
    }

    private List<File> listScreenshots() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots");
        File[] files = dir.listFiles(file -> file.isFile() && isScreenshotImage(file.getName()));
        if (files == null) {
            return Collections.emptyList();
        }
        ArrayList<File> result = new ArrayList<>();
        Collections.addAll(result, files);
        return result;
    }

    private boolean isScreenshotImage(String name) {
        String lower = name.toLowerCase(Locale.US);
        return (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp"))
                && (lower.contains("screenshot") || lower.contains("screen_shot"));
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

    private File uniqueTarget(File destinationDir, String fileName) {
        File target = new File(destinationDir, fileName);
        if (!target.exists()) {
            return target;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            target = new File(destinationDir, base + "-" + i + ext);
            if (!target.exists()) {
                return target;
            }
        }
        return new File(destinationDir, base + "-" + System.currentTimeMillis() + ext);
    }

    private String recentForegroundPackage() {
        long now = System.currentTimeMillis();
        for (ForegroundSample sample : foregroundSamples) {
            if (now - sample.time <= ATTRIBUTION_WINDOW_MS) {
                return sample.packageName;
            }
        }
        return null;
    }

    private String getForegroundPackage() {
        RootShell.Result result = RootShell.run(
                "dumpsys activity activities | grep -m 6 -E 'topResumedActivity|mResumedActivity|ResumedActivity'",
                3000
        );
        if (!result.ok() || result.output.isEmpty()) {
            return null;
        }
        for (String line : result.output.split("\\n")) {
            Matcher matcher = ACTIVITY_PACKAGE.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private void cleanupMediaStore(String sourcePath, String targetPath) {
        try {
            ContentResolver resolver = getContentResolver();
            String where = MediaStore.MediaColumns.DATA + "=?";
            resolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, where, new String[]{sourcePath});
            resolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, where, new String[]{targetPath});
        } catch (Exception ignored) {
        }
        try {
            MediaScannerConnection.scanFile(this, new String[]{sourcePath}, null, null);
        } catch (Exception ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screenshot monitoring",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Moves screenshots into app-specific folders");
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
                .setOngoing(true)
                .addAction(R.drawable.ic_launcher, "Stop", stopIntent)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification(text));
        }
    }

    private static final class ForegroundSample {
        final String packageName;
        final long time;

        ForegroundSample(String packageName, long time) {
            this.packageName = packageName;
            this.time = time;
        }
    }
}
