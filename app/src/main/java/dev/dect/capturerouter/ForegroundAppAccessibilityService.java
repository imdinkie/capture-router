package dev.dect.capturerouter;

import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

public class ForegroundAppAccessibilityService extends AccessibilityService {
    private final Set<String> ignoredPackages = new HashSet<>();

    @Override
    protected void onServiceConnected() {
        ignoredPackages.add(getPackageName());
        ignoredPackages.add("android");
        ignoredPackages.add("com.android.systemui");
        ignoredPackages.add("com.google.android.apps.nexuslauncher");
        ignoredPackages.add("com.google.android.permissioncontroller");
        ignoredPackages.add("com.android.settings");
        AppStore.log(this, "INFO", "Accessibility foreground tracking enabled");
        if (AppStore.isMonitoringEnabled(this)) {
            ScreenshotWatcherService.start(this);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }
        String packageName = event.getPackageName().toString();
        if (packageName.isEmpty() || ignoredPackages.contains(packageName)) {
            return;
        }
        AppStore.setForegroundApp(this, packageName, labelFor(packageName), System.currentTimeMillis());
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        AppStore.log(this, "WARN", "Accessibility foreground tracking stopped");
        super.onDestroy();
    }

    private String labelFor(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
