package dev.dect.capturerouter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

public class WatchdogReceiver extends BroadcastReceiver {
    static final String ACTION_WATCHDOG = "dev.dect.capturerouter.WATCHDOG";
    private static final int REQUEST_CODE = 31;
    private static final long WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (AppStore.isMonitoringEnabled(context)) {
            ScreenshotWatcherService.start(context);
            schedule(context);
        }
    }

    static void schedule(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        PendingIntent intent = pendingIntent(context);
        long when = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS;
        if (Build.VERSION.SDK_INT >= 23) {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, intent);
        } else {
            manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, intent);
        }
    }

    static void cancel(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {
            manager.cancel(pendingIntent(context));
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, WatchdogReceiver.class);
        intent.setAction(ACTION_WATCHDOG);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
