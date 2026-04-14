package dev.dect.capturerouter;

import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ForegroundAppAccessibilityService extends AccessibilityService {
    private final Set<String> ignoredPackages = new HashSet<>();
    private long ignoredPackagesRefreshed;

    @Override
    protected void onServiceConnected() {
        refreshIgnoredPackages(true);
        AppStore.log(this, "INFO", "ATTRIBUTION", "Accessibility foreground tracking enabled");
        if (AppStore.isMonitoringEnabled(this)) {
            ScreenshotWatcherService.start(this);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }
        refreshIgnoredPackages(false);
        AppRef windowApp = currentApplicationWindow();
        if (windowApp != null) {
            AppStore.setForegroundApp(this, windowApp.packageName, windowApp.label, System.currentTimeMillis(),
                    AppStore.ForegroundApp.CONFIDENCE_HIGH, "window");
            return;
        }
        String packageName = event.getPackageName().toString();
        if (packageName.isEmpty() || ignoredPackages.contains(packageName)) {
            return;
        }
        AppStore.setForegroundApp(this, packageName, labelFor(packageName), System.currentTimeMillis(),
                AppStore.ForegroundApp.CONFIDENCE_MEDIUM, "event:" + AccessibilityEvent.eventTypeToString(event.getEventType()));
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        AppStore.log(this, "WARN", "ATTRIBUTION", "Accessibility foreground tracking stopped");
        super.onDestroy();
    }

    private void refreshIgnoredPackages(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - ignoredPackagesRefreshed < 60_000) {
            return;
        }
        ignoredPackagesRefreshed = now;
        ignoredPackages.clear();
        ignoredPackages.add(getPackageName());
        ignoredPackages.add("android");
        ignoredPackages.add("com.android.systemui");
        ignoredPackages.add("com.google.android.apps.nexuslauncher");
        ignoredPackages.add("com.google.android.permissioncontroller");
        ignoredPackages.add("com.android.settings");
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            try {
                List<InputMethodInfo> methods = manager.getInputMethodList();
                for (InputMethodInfo method : methods) {
                    if (method != null && method.getPackageName() != null) {
                        ignoredPackages.add(method.getPackageName());
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private AppRef currentApplicationWindow() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException e) {
            return null;
        }
        if (windows == null || windows.isEmpty()) {
            return null;
        }
        AppRef fallback = null;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            AppRef ref = appFromWindow(window);
            if (ref == null || ignoredPackages.contains(ref.packageName)) {
                continue;
            }
            if (window.isActive() || window.isFocused()) {
                return ref;
            }
            if (fallback == null) {
                fallback = ref;
            }
        }
        return fallback;
    }

    private AppRef appFromWindow(AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = null;
        try {
            root = window.getRoot();
            if (root == null || root.getPackageName() == null) {
                return null;
            }
            String packageName = root.getPackageName().toString();
            if (packageName.isEmpty()) {
                return null;
            }
            return new AppRef(packageName, labelFor(packageName));
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (root != null) {
                root.recycle();
            }
        }
    }

    private String labelFor(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private static final class AppRef {
        final String packageName;
        final String label;

        AppRef(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }
}
