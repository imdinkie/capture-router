package dev.dect.capturerouter;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(247, 249, 248);
    private static final int SURFACE = Color.WHITE;
    private static final int INK = Color.rgb(15, 23, 32);
    private static final int MUTED = Color.rgb(92, 105, 113);
    private static final int LINE = Color.rgb(221, 227, 224);
    private static final int ACCENT = Color.rgb(10, 124, 102);
    private static final int DANGER = Color.rgb(158, 46, 46);

    private final Handler refreshHandler = new Handler();
    private LinearLayout root;
    private TextView accessibilityStatus;
    private TextView storageStatus;
    private TextView watcherStatus;
    private TextView serviceStatus;
    private TextView batteryStatus;
    private TextView notificationStatus;
    private TextView sourceText;
    private LinearLayout rulesList;
    private LinearLayout pendingList;
    private LinearLayout logsList;
    private Button monitorButton;
    private Button addRuleButton;
    private Button moveSelectedButton;
    private AlertDialog appLoadingDialog;
    private AlertDialog appPickerDialog;
    private ArrayList<AppInfo> appCache;
    private boolean appPickerLoading;
    private int appPickerRequestToken;
    private final HashSet<String> selectedPendingIds = new HashSet<>();

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            refreshHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        buildUi();
        requestRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (AppStore.isMonitoringEnabled(this)) {
            WatchdogReceiver.schedule(this);
            ScreenshotWatcherService.start(this);
        }
        refreshUi();
        refreshHandler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        appPickerRequestToken++;
        dismissAppLoadingDialog();
        if (appPickerDialog != null && appPickerDialog.isShowing()) {
            appPickerDialog.dismiss();
        }
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setContentView(scrollView);

        LinearLayout hero = heroCard();
        TextView eyebrow = text("PRIVATE SCREENSHOT ROUTING", 12, Color.rgb(180, 229, 213), Typeface.BOLD);
        hero.addView(eyebrow);
        TextView title = text("CaptureRouter", 30, Color.WHITE, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, 0);
        hero.addView(title);
        TextView subtitle = text("Move sensitive screenshots into hidden folders the moment they are captured.", 15, Color.rgb(214, 231, 226), Typeface.NORMAL);
        subtitle.setPadding(0, dp(6), 0, dp(4));
        hero.addView(subtitle);
        root.addView(hero);

        LinearLayout statusCard = card();
        statusCard.addView(sectionTitle("Always-on status"));
        LinearLayout statusRow = row();
        statusRow.setPadding(0, 0, 0, dp(8));
        accessibilityStatus = statusPill("Accessibility: needed", MUTED);
        storageStatus = statusPill("Storage: checking", MUTED);
        watcherStatus = statusPill("Watcher: stopped", MUTED);
        statusRow.addView(accessibilityStatus, weightParams());
        statusRow.addView(space(dp(8), 1));
        statusRow.addView(storageStatus, weightParams());
        statusRow.addView(space(dp(8), 1));
        statusRow.addView(watcherStatus, weightParams());
        statusCard.addView(statusRow);
        LinearLayout serviceRow = row();
        serviceRow.setPadding(0, 0, 0, dp(8));
        serviceStatus = statusPill("Service: checking", MUTED);
        batteryStatus = statusPill("Battery: checking", MUTED);
        notificationStatus = statusPill("Notifications: checking", MUTED);
        serviceRow.addView(serviceStatus, weightParams());
        serviceRow.addView(space(dp(8), 1));
        serviceRow.addView(batteryStatus, weightParams());
        serviceRow.addView(space(dp(8), 1));
        serviceRow.addView(notificationStatus, weightParams());
        statusCard.addView(serviceRow);
        sourceText = text("", 13, MUTED, Typeface.NORMAL);
        sourceText.setPadding(0, dp(6), 0, 0);
        statusCard.addView(sourceText);
        LinearLayout statusButtons = row();
        monitorButton = primaryButton("Start monitoring");
        monitorButton.setOnClickListener(view -> toggleMonitoring());
        Button allFiles = secondaryButton("All files access");
        allFiles.setOnClickListener(view -> openAllFilesSettings());
        Button access = secondaryButton("Accessibility");
        access.setOnClickListener(view -> openAccessibilitySettings());
        statusButtons.addView(monitorButton, weightParams());
        statusButtons.addView(space(dp(8), 1));
        statusButtons.addView(allFiles, weightParams());
        statusButtons.addView(space(dp(8), 1));
        statusButtons.addView(access, weightParams());
        statusCard.addView(statusButtons);
        Button battery = secondaryButton("Battery settings");
        battery.setOnClickListener(view -> openBatterySettings());
        statusCard.addView(battery);
        root.addView(statusCard);

        LinearLayout settingsCard = card();
        settingsCard.addView(sectionTitle("Filename settings"));
        TextView filenameHelp = text("Every new screenshot is renamed before rules run. Rules then match the app part of the filename.", 14, MUTED, Typeface.NORMAL);
        filenameHelp.setPadding(0, 0, 0, dp(10));
        settingsCard.addView(filenameHelp);
        TextView preview = text("Preview: " + ScreenshotNamer.preview(AppStore.getFilenameTemplate(this), "ChatGPT", "com.openai.chatgpt"), 13, ACCENT, Typeface.BOLD);
        preview.setPadding(0, 0, 0, dp(10));
        settingsCard.addView(preview);
        Button filenameButton = secondaryButton("Change filename format");
        filenameButton.setOnClickListener(view -> showFilenameSettings());
        settingsCard.addView(filenameButton);
        TextView sourceHelp = text("Source folder: " + AppStore.getSourceDir(this), 13, MUTED, Typeface.NORMAL);
        sourceHelp.setPadding(0, dp(10), 0, dp(8));
        settingsCard.addView(sourceHelp);
        Button sourceButton = secondaryButton("Change source folder");
        sourceButton.setOnClickListener(view -> showSourceFolderSettings());
        settingsCard.addView(sourceButton);
        root.addView(settingsCard);

        LinearLayout rulesCard = card();
        rulesCard.addView(sectionTitle("Routing rules"));
        TextView rulesHelp = text("Create one rule for one app or a bundle. Auto rules move immediately; review rules queue screenshots until you move them.", 14, MUTED, Typeface.NORMAL);
        rulesHelp.setPadding(0, 0, 0, dp(12));
        rulesCard.addView(rulesHelp);
        addRuleButton = primaryButton("Add routing rule");
        addRuleButton.setOnClickListener(view -> chooseAppForRule());
        rulesCard.addView(addRuleButton);
        rulesList = new LinearLayout(this);
        rulesList.setOrientation(LinearLayout.VERTICAL);
        rulesList.setPadding(0, dp(12), 0, 0);
        rulesCard.addView(rulesList);
        root.addView(rulesCard);

        LinearLayout pendingCard = card();
        LinearLayout pendingHeader = row();
        pendingHeader.setGravity(Gravity.CENTER_VERTICAL);
        pendingHeader.addView(sectionTitle("Review queue"), weightParams());
        moveSelectedButton = secondaryButton("Move selected");
        moveSelectedButton.setOnClickListener(view -> moveSelectedPending());
        pendingHeader.addView(moveSelectedButton);
        pendingHeader.addView(space(dp(8), 1));
        Button moveAll = primaryButton("Move all");
        moveAll.setOnClickListener(view -> moveAllPending());
        pendingHeader.addView(moveAll);
        pendingCard.addView(pendingHeader);
        TextView pendingHelp = text("Review-mode screenshots stay visible in Photos until you move them here.", 14, MUTED, Typeface.NORMAL);
        pendingHelp.setPadding(0, 0, 0, dp(10));
        pendingCard.addView(pendingHelp);
        pendingList = new LinearLayout(this);
        pendingList.setOrientation(LinearLayout.VERTICAL);
        pendingCard.addView(pendingList);
        root.addView(pendingCard);

        LinearLayout logsCard = card();
        LinearLayout logHeader = row();
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.addView(sectionTitle("Activity log"), weightParams());
        Button clear = secondaryButton("Clear");
        clear.setOnClickListener(view -> {
            AppStore.clearLogs(this);
            refreshUi();
        });
        logHeader.addView(clear);
        logHeader.addView(space(dp(8), 1));
        Button export = secondaryButton("Export");
        export.setOnClickListener(view -> exportDiagnostics());
        logHeader.addView(export);
        logsCard.addView(logHeader);
        logsList = new LinearLayout(this);
        logsList.setOrientation(LinearLayout.VERTICAL);
        logsList.setPadding(0, dp(8), 0, 0);
        logsCard.addView(logsList);
        root.addView(logsCard);
    }

    private void refreshUi() {
        boolean allFilesOk = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        boolean accessibilityOk = isAccessibilityEnabled();
        boolean monitoring = AppStore.isMonitoringEnabled(this);
        boolean batteryOk = isIgnoringBatteryOptimizations();
        boolean notificationsOk = areNotificationsAllowed();
        boolean serviceRunning = isWatcherServiceRunning();
        accessibilityStatus.setText(accessibilityOk ? "Accessibility: ready" : "Accessibility: needed");
        accessibilityStatus.setTextColor(accessibilityOk ? ACCENT : DANGER);
        storageStatus.setText(allFilesOk ? "Storage: ready" : "Storage: needed");
        storageStatus.setTextColor(allFilesOk ? ACCENT : DANGER);
        watcherStatus.setText(monitoring ? "Watcher: enabled" : "Watcher: stopped");
        watcherStatus.setTextColor(monitoring ? ACCENT : MUTED);
        serviceStatus.setText(serviceRunning ? "Service: running" : "Service: standby");
        serviceStatus.setTextColor(serviceRunning ? ACCENT : (monitoring ? DANGER : MUTED));
        batteryStatus.setText(batteryOk ? "Battery: unrestricted" : "Battery: optimized");
        batteryStatus.setTextColor(batteryOk ? ACCENT : DANGER);
        notificationStatus.setText(notificationsOk ? "Notification: allowed" : "Notification: hidden");
        notificationStatus.setTextColor(notificationsOk ? ACCENT : MUTED);
        sourceText.setText("Source folder: " + AppStore.getSourceDir(this)
                + "\nSwiping CaptureRouter away does not intentionally stop monitoring. Force stop does."
                + "\nAndroid requires a foreground-service notification while monitoring; hiding notifications may still leave an active-service notice in system UI.");
        monitorButton.setText(monitoring ? "Stop monitoring" : "Start monitoring");
        monitorButton.setBackground(buttonBg(monitoring ? DANGER : ACCENT));
        updateAppPickerButton();
        renderRules();
        renderPending();
        renderLogs();
    }

    private void renderRules() {
        rulesList.removeAllViews();
        List<AppStore.Rule> rules = AppStore.getRules(this);
        if (rules.isEmpty()) {
            TextView empty = text("No rules yet.", 14, MUTED, Typeface.NORMAL);
            rulesList.addView(empty);
            return;
        }
        for (AppStore.Rule rule : rules) {
            LinearLayout item = itemBox();
            LinearLayout titleRow = row();
            titleRow.setPadding(0, 0, 0, dp(5));
            TextView name = text(rule.name, 16, rule.enabled ? INK : MUTED, Typeface.BOLD);
            titleRow.addView(name, weightParams());
            CheckBox enabled = new CheckBox(this);
            enabled.setText("Enabled");
            enabled.setTextColor(rule.enabled ? ACCENT : MUTED);
            enabled.setChecked(rule.enabled);
            enabled.setOnCheckedChangeListener((buttonView, checked) -> {
                AppStore.setRuleEnabled(this, rule.id, checked);
                AppStore.log(this, "INFO", (checked ? "Enabled " : "Disabled ") + rule.name);
                refreshUi();
            });
            titleRow.addView(enabled);
            item.addView(titleRow);
            item.addView(text((rule.enabled ? "" : "Disabled • ") + rule.modeLabel() + " • " + rule.apps.size() + " app" + (rule.apps.size() == 1 ? "" : "s"), 13, rule.enabled ? ACCENT : MUTED, Typeface.BOLD));
            item.addView(text(appSummary(rule.apps), 13, MUTED, Typeface.NORMAL));
            item.addView(text("Filename match: " + slugSummary(rule.apps), 13, MUTED, Typeface.NORMAL));
            item.addView(text(rule.destination, 13, MUTED, Typeface.NORMAL));
            LinearLayout actions = row();
            actions.setPadding(0, dp(8), 0, 0);
            Button edit = secondaryButton("Edit");
            edit.setOnClickListener(view -> showRuleEditor(rule.id, toAppInfo(rule.apps), rule.name, rule.destination, rule.mode, rule.nomedia));
            Button remove = secondaryButton("Remove");
            remove.setTextColor(DANGER);
            remove.setOnClickListener(view -> {
                AppStore.removeRule(this, rule.id);
                AppStore.log(this, "INFO", "Removed rule " + rule.name);
                refreshUi();
            });
            actions.addView(edit);
            actions.addView(space(dp(8), 1));
            actions.addView(remove);
            item.addView(actions);
            rulesList.addView(item);
        }
    }

    private void renderLogs() {
        logsList.removeAllViews();
        List<AppStore.LogEntry> logs = AppStore.getLogs(this);
        if (logs.isEmpty()) {
            logsList.addView(text("No log entries yet.", 14, MUTED, Typeface.NORMAL));
            return;
        }
        int count = Math.min(logs.size(), 30);
        for (int i = 0; i < count; i++) {
            AppStore.LogEntry entry = logs.get(i);
            TextView line = text(AppStore.formatTime(entry.time) + "  " + entry.level + "  " + entry.category + "\n" + entry.message,
                    13,
                    "ERROR".equals(entry.level) ? DANGER : INK,
                    Typeface.NORMAL);
            line.setPadding(0, dp(6), 0, dp(8));
            logsList.addView(line);
        }
    }

    private void renderPending() {
        if (pendingList == null) {
            return;
        }
        AppStore.pruneMissingPending(this);
        pendingList.removeAllViews();
        List<AppStore.PendingShot> pending = AppStore.getPending(this);
        if (pending.isEmpty()) {
            selectedPendingIds.clear();
            if (moveSelectedButton != null) {
                moveSelectedButton.setEnabled(false);
                moveSelectedButton.setText("Move selected");
            }
            pendingList.addView(text("No screenshots waiting for review.", 14, MUTED, Typeface.NORMAL));
            return;
        }
        HashSet<String> existing = new HashSet<>();
        LinkedHashMap<String, Integer> grouped = new LinkedHashMap<>();
        for (AppStore.PendingShot shot : pending) {
            existing.add(shot.id);
            Integer count = grouped.get(shot.ruleName);
            grouped.put(shot.ruleName, count == null ? 1 : count + 1);
        }
        selectedPendingIds.retainAll(existing);
        updateMoveSelectedButton();
        TextView summary = text(queueSummary(pending.size(), grouped), 13, MUTED, Typeface.BOLD);
        summary.setPadding(0, 0, 0, dp(8));
        pendingList.addView(summary);
        for (AppStore.PendingShot shot : pending) {
            LinearLayout item = itemBox();
            LinearLayout titleRow = row();
            titleRow.setPadding(0, 0, 0, 0);
            CheckBox selected = new CheckBox(this);
            selected.setChecked(selectedPendingIds.contains(shot.id));
            selected.setOnCheckedChangeListener((buttonView, checked) -> {
                if (checked) {
                    selectedPendingIds.add(shot.id);
                } else {
                    selectedPendingIds.remove(shot.id);
                }
                updateMoveSelectedButton();
            });
            titleRow.addView(selected);
            titleRow.addView(text(shot.label, 15, INK, Typeface.BOLD), weightParams());
            item.addView(titleRow);
            item.addView(text(shot.ruleName + " • " + AppStore.formatTime(shot.time), 13, ACCENT, Typeface.BOLD));
            item.addView(text(new java.io.File(shot.path).getName(), 13, MUTED, Typeface.NORMAL));
            LinearLayout actions = row();
            actions.setPadding(0, dp(8), 0, 0);
            Button move = primaryButton("Move now");
            move.setOnClickListener(view -> movePending(shot));
            Button forget = secondaryButton("Forget");
            forget.setOnClickListener(view -> {
                AppStore.removePending(this, shot.id);
                AppStore.log(this, "INFO", "Forgot queued screenshot " + new java.io.File(shot.path).getName());
                refreshUi();
            });
            actions.addView(move);
            actions.addView(space(dp(8), 1));
            actions.addView(forget);
            item.addView(actions);
            pendingList.addView(item);
        }
    }

    private String queueSummary(int total, LinkedHashMap<String, Integer> grouped) {
        ArrayList<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : grouped.entrySet()) {
            parts.add(entry.getKey() + " (" + entry.getValue() + ")");
            if (parts.size() == 3) {
                break;
            }
        }
        String suffix = grouped.size() > 3 ? " +" + (grouped.size() - 3) + " more" : "";
        return total + " queued across " + grouped.size() + " rule" + (grouped.size() == 1 ? "" : "s")
                + ": " + join(parts, ", ") + suffix;
    }

    private void updateMoveSelectedButton() {
        if (moveSelectedButton == null) {
            return;
        }
        int count = selectedPendingIds.size();
        moveSelectedButton.setText(count == 0 ? "Move selected" : "Move selected (" + count + ")");
        moveSelectedButton.setEnabled(count > 0);
    }

    private void moveAllPending() {
        List<AppStore.PendingShot> pending = AppStore.getPending(this);
        if (pending.isEmpty()) {
            showMessage("Nothing queued", "There are no review-mode screenshots waiting to move.");
            return;
        }
        new Thread(() -> {
            int moved = 0;
            int failed = 0;
            for (AppStore.PendingShot shot : pending) {
                if (!new java.io.File(shot.path).exists()) {
                    AppStore.removePending(this, shot.id);
                    continue;
                }
                ScreenshotMover.MoveResult result = ScreenshotMover.move(this, shot.path, shot.destination, shot.nomedia);
                if (result.ok) {
                    moved++;
                    AppStore.removePending(this, shot.id);
                    AppStore.log(this, "MOVED", shot.label + ": " + new java.io.File(shot.path).getName() + " -> " + shot.destination);
                } else {
                    failed++;
                    AppStore.log(this, "ERROR", "Queued move failed for " + new java.io.File(shot.path).getName() + ": " + result.error);
                }
            }
            int finalMoved = moved;
            int finalFailed = failed;
            refreshHandler.post(() -> {
                refreshUi();
                showMessage("Queue processed", finalMoved + " moved, " + finalFailed + " failed.");
            });
        }, "move-pending").start();
    }

    private void moveSelectedPending() {
        if (selectedPendingIds.isEmpty()) {
            showMessage("Nothing selected", "Select one or more queued screenshots first.");
            return;
        }
        List<AppStore.PendingShot> pending = AppStore.getPending(this);
        ArrayList<AppStore.PendingShot> selected = new ArrayList<>();
        for (AppStore.PendingShot shot : pending) {
            if (selectedPendingIds.contains(shot.id)) {
                selected.add(shot);
            }
        }
        if (selected.isEmpty()) {
            selectedPendingIds.clear();
            refreshUi();
            return;
        }
        new Thread(() -> {
            int moved = 0;
            int failed = 0;
            for (AppStore.PendingShot shot : selected) {
                if (!new java.io.File(shot.path).exists()) {
                    AppStore.removePending(this, shot.id);
                    continue;
                }
                ScreenshotMover.MoveResult result = ScreenshotMover.move(this, shot.path, shot.destination, shot.nomedia);
                if (result.ok) {
                    moved++;
                    AppStore.removePending(this, shot.id);
                    AppStore.log(this, "MOVED", shot.label + ": " + new java.io.File(shot.path).getName() + " -> " + shot.destination);
                } else {
                    failed++;
                    AppStore.log(this, "ERROR", "Queued move failed for " + new java.io.File(shot.path).getName() + ": " + result.error);
                }
            }
            int finalMoved = moved;
            int finalFailed = failed;
            refreshHandler.post(() -> {
                selectedPendingIds.clear();
                refreshUi();
                showMessage("Selection processed", finalMoved + " moved, " + finalFailed + " failed.");
            });
        }, "move-selected-pending").start();
    }

    private void movePending(AppStore.PendingShot shot) {
        new Thread(() -> {
            ScreenshotMover.MoveResult result = ScreenshotMover.move(this, shot.path, shot.destination, shot.nomedia);
            refreshHandler.post(() -> {
                if (result.ok) {
                    AppStore.removePending(this, shot.id);
                    AppStore.log(this, "MOVED", shot.label + ": " + new java.io.File(shot.path).getName() + " -> " + shot.destination);
                } else {
                    AppStore.log(this, "ERROR", "Queued move failed for " + new java.io.File(shot.path).getName() + ": " + result.error);
                    showMessage("Move failed", result.error.isEmpty() ? "The destination is not writable." : result.error);
                }
                refreshUi();
            });
        }, "move-one-pending").start();
    }

    private void toggleMonitoring() {
        boolean next = !AppStore.isMonitoringEnabled(this);
        if (next) {
            if (!isAccessibilityEnabled()) {
                AppStore.log(this, "WARN", "Accessibility is required for app-name screenshot renaming");
                openAccessibilitySettings();
                return;
            }
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                AppStore.log(this, "WARN", "All files access is required before monitoring");
                openAllFilesSettings();
                return;
            }
            AppStore.setMonitoringEnabled(this, true);
            WatchdogReceiver.schedule(this);
            ScreenshotWatcherService.start(this);
        } else {
            WatchdogReceiver.cancel(this);
            ScreenshotWatcherService.stop(this);
        }
        refreshUi();
    }

    private void chooseAppForRule() {
        openAppPicker(AppStore.Rule.MODE_AUTO, new String[]{});
    }

    private void openAppPicker(String defaultMode, String[] preselectedPackages) {
        if (appPickerLoading || (appPickerDialog != null && appPickerDialog.isShowing())) {
            return;
        }
        if (appCache != null && !appCache.isEmpty()) {
            showAppPicker(new ArrayList<>(appCache), preselectedApps(appCache, preselectedPackages), defaultMode);
            return;
        }
        appPickerLoading = true;
        int token = ++appPickerRequestToken;
        updateAppPickerButton();
        showAppLoadingDialog(token);
        new Thread(() -> {
            ArrayList<AppInfo> loadedApps;
            String error = "";
            try {
                loadedApps = loadApps();
            } catch (RuntimeException e) {
                loadedApps = new ArrayList<>();
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final ArrayList<AppInfo> apps = loadedApps;
            String finalError = error;
            refreshHandler.post(() -> {
                if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) {
                    return;
                }
                if (token != appPickerRequestToken) {
                    return;
                }
                appPickerLoading = false;
                appCache = apps;
                dismissAppLoadingDialog();
                updateAppPickerButton();
                if (apps.isEmpty()) {
                    String detail = finalError.isEmpty()
                            ? "The package picker could not load installed applications."
                            : "The package picker could not load installed applications: " + finalError;
                    showMessage("No apps found", detail);
                    return;
                }
                showAppPicker(new ArrayList<>(apps), preselectedApps(apps, preselectedPackages), defaultMode);
            });
        }, "load-app-picker").start();
    }

    private void showAppLoadingDialog(int token) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        ProgressBar progress = new ProgressBar(this);
        box.addView(progress, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView message = text("Loading installed apps. This only happens once per session.", 14, MUTED, Typeface.NORMAL);
        message.setPadding(dp(14), 0, 0, 0);
        box.addView(message, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        appLoadingDialog = new AlertDialog.Builder(this)
                .setTitle("Preparing app picker")
                .setView(box)
                .setNegativeButton("Cancel", (dialog, which) -> cancelAppPickerRequest(token))
                .create();
        appLoadingDialog.setOnCancelListener(dialog -> cancelAppPickerRequest(token));
        appLoadingDialog.show();
    }

    private void cancelAppPickerRequest(int token) {
        if (token != appPickerRequestToken) {
            return;
        }
        appPickerRequestToken++;
        appPickerLoading = false;
        updateAppPickerButton();
    }

    private void dismissAppLoadingDialog() {
        if (appLoadingDialog != null && appLoadingDialog.isShowing()) {
            appLoadingDialog.dismiss();
        }
        appLoadingDialog = null;
    }

    private void updateAppPickerButton() {
        if (addRuleButton == null) {
            return;
        }
        addRuleButton.setEnabled(!appPickerLoading);
        addRuleButton.setText(appPickerLoading ? "Loading apps..." : "Add routing rule");
    }

    private ArrayList<AppInfo> preselectedApps(List<AppInfo> apps, String[] packageNames) {
        ArrayList<AppInfo> selected = new ArrayList<>();
        if (packageNames == null || packageNames.length == 0) {
            return selected;
        }
        HashSet<String> packages = new HashSet<>();
        Collections.addAll(packages, packageNames);
        for (AppInfo app : apps) {
            if (packages.contains(app.packageName)) {
                selected.add(app);
            }
        }
        return selected;
    }

    private void showAppPicker(ArrayList<AppInfo> apps, List<AppInfo> initiallySelected, String defaultMode) {
        if (appPickerDialog != null && appPickerDialog.isShowing()) {
            appPickerDialog.dismiss();
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, 0);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search by app or package");
        box.addView(search);
        TextView selectedCount = text("No apps selected", 13, MUTED, Typeface.BOLD);
        selectedCount.setPadding(0, dp(8), 0, dp(8));
        box.addView(selectedCount);
        ListView listView = new ListView(this);
        box.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(420)
        ));
        ArrayList<AppInfo> filtered = new ArrayList<>(apps);
        HashSet<String> selectedPackages = new HashSet<>();
        for (AppInfo app : initiallySelected) {
            selectedPackages.add(app.packageName);
        }
        AppPickerAdapter adapter = new AppPickerAdapter(this, filtered, selectedPackages);
        listView.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Select apps")
                .setView(box)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Refresh", null)
                .create();
        appPickerDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (appPickerDialog == dialog) {
                appPickerDialog = null;
            }
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppInfo app = filtered.get(position);
            if (selectedPackages.contains(app.packageName)) {
                selectedPackages.remove(app.packageName);
            } else {
                selectedPackages.add(app.packageName);
            }
            updateSelectionLabel(selectedCount, selectedPackages.size());
            adapter.notifyDataSetChanged();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase(Locale.US).trim();
                filtered.clear();
                for (AppInfo app : apps) {
                    if (app.label.toLowerCase(Locale.US).contains(query)
                            || app.packageName.toLowerCase(Locale.US).contains(query)) {
                        filtered.add(app);
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        dialog.setOnShowListener(d -> {
            updateSelectionLabel(selectedCount, selectedPackages.size());
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setTextColor(ACCENT);
            positive.setOnClickListener(view -> {
                ArrayList<AppInfo> selectedApps = new ArrayList<>();
                for (AppInfo app : apps) {
                    if (selectedPackages.contains(app.packageName)) {
                        selectedApps.add(app);
                    }
                }
                if (selectedApps.isEmpty()) {
                    selectedCount.setTextColor(DANGER);
                    selectedCount.setText("Select at least one app");
                    return;
                }
                dialog.dismiss();
                String defaultName = selectedApps.size() == 1
                        ? selectedApps.get(0).label
                        : selectedApps.get(0).label + " +" + (selectedApps.size() - 1);
                showRuleEditor(AppStore.newRuleId(), selectedApps, defaultName,
                        AppStore.defaultDestination(defaultName, selectedApps.get(0).packageName),
                        defaultMode,
                        true);
            });
            Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            neutral.setTextColor(MUTED);
            neutral.setOnClickListener(view -> {
                appCache = null;
                dialog.dismiss();
                openAppPicker(defaultMode, selectedPackagesFromApps(apps, selectedPackages));
            });
            search.requestFocus();
            search.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 200);
        });
        dialog.show();
    }

    private void updateSelectionLabel(TextView selectedCount, int count) {
        selectedCount.setText(selectionLabel(count));
        selectedCount.setTextColor(count == 0 ? MUTED : ACCENT);
    }

    private String[] selectedPackagesFromApps(List<AppInfo> apps, Set<String> selectedPackages) {
        ArrayList<String> packages = new ArrayList<>();
        for (AppInfo app : apps) {
            if (selectedPackages.contains(app.packageName)) {
                packages.add(app.packageName);
            }
        }
        return packages.toArray(new String[0]);
    }

    private void showRuleEditor(String ruleId, List<AppInfo> apps, String ruleName, String destination, String mode, boolean nomedia) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, 0);
        TextView appsLine = text(appSummaryFromInfo(apps), 14, MUTED, Typeface.NORMAL);
        box.addView(appsLine);
        TextView matchPreview = text("Filename match: " + slugSummaryFromInfo(apps), 13, ACCENT, Typeface.BOLD);
        matchPreview.setPadding(0, dp(4), 0, dp(8));
        box.addView(matchPreview);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Rule name");
        name.setText(ruleName);
        box.addView(name);
        EditText folder = new EditText(this);
        folder.setSingleLine(false);
        folder.setMinLines(2);
        folder.setText(destination);
        folder.setSelectAllOnFocus(false);
        folder.setHint("/sdcard/Pictures/.CaptureRouter/App");
        box.addView(folder);
        RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton auto = new RadioButton(this);
        auto.setText("Auto move immediately");
        auto.setTextColor(INK);
        RadioButton manual = new RadioButton(this);
        manual.setText("Queue for review");
        manual.setTextColor(INK);
        auto.setId(101);
        manual.setId(102);
        modeGroup.addView(auto);
        modeGroup.addView(manual);
        modeGroup.check(AppStore.Rule.MODE_MANUAL.equals(mode) ? 102 : 101);
        box.addView(modeGroup);
        TextView modeHelp = text("Review mode keeps screenshots visible until you move them from CaptureRouter.", 13, MUTED, Typeface.NORMAL);
        modeHelp.setPadding(0, 0, 0, dp(8));
        box.addView(modeHelp);
        CheckBox nomediaBox = new CheckBox(this);
        nomediaBox.setText("Create .nomedia in this folder");
        nomediaBox.setTextColor(INK);
        nomediaBox.setChecked(nomedia);
        box.addView(nomediaBox);
        new AlertDialog.Builder(this)
                .setTitle("Rule target")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    String path = folder.getText().toString().trim();
                    String cleanName = name.getText().toString().trim();
                    if (path.isEmpty()) {
                        showMessage("Folder required", "Enter an absolute folder path.");
                        return;
                    }
                    if (cleanName.isEmpty()) {
                        cleanName = apps.size() == 1 ? apps.get(0).label : "App bundle";
                    }
                    ArrayList<AppStore.AppRef> refs = new ArrayList<>();
                    for (AppInfo app : apps) {
                        refs.add(new AppStore.AppRef(app.packageName, app.label));
                    }
                    String selectedMode = modeGroup.getCheckedRadioButtonId() == 102
                            ? AppStore.Rule.MODE_MANUAL
                            : AppStore.Rule.MODE_AUTO;
                    AppStore.addOrReplaceRule(this, new AppStore.Rule(ruleId, cleanName, refs, path, selectedMode, nomediaBox.isChecked(), true));
                    WatchdogReceiver.schedule(this);
                    AppStore.log(this, "INFO", "Saved " + cleanName + " as " + (AppStore.Rule.MODE_MANUAL.equals(selectedMode) ? "review queue" : "auto move"));
                    refreshUi();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private ArrayList<AppInfo> loadApps() {
        PackageManager packageManager = getPackageManager();
        List<ApplicationInfo> installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
        ArrayList<AppInfo> apps = new ArrayList<>();
        for (ApplicationInfo info : installed) {
            if (info.packageName.equals(getPackageName())) {
                continue;
            }
            Intent launch = packageManager.getLaunchIntentForPackage(info.packageName);
            if (launch == null && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }
            String label = packageManager.getApplicationLabel(info).toString();
            apps.add(new AppInfo(label, info.packageName, packageManager.getApplicationIcon(info)));
        }
        Collections.sort(apps, Comparator.comparing(app -> app.label.toLowerCase(Locale.US)));
        return apps;
    }

    private String selectionLabel(int count) {
        if (count == 0) {
            return "No apps selected";
        }
        if (count == 1) {
            return "1 app selected";
        }
        return count + " apps selected";
    }

    private String appSummary(List<AppStore.AppRef> apps) {
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < apps.size() && i < 3; i++) {
            names.add(apps.get(i).label);
        }
        String summary = join(names, ", ");
        if (apps.size() > 3) {
            summary += " +" + (apps.size() - 3);
        }
        return summary;
    }

    private String slugSummary(List<AppStore.AppRef> apps) {
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < apps.size() && i < 3; i++) {
            names.add(apps.get(i).slug());
        }
        String summary = join(names, ", ");
        if (apps.size() > 3) {
            summary += " +" + (apps.size() - 3);
        }
        return summary;
    }

    private String appSummaryFromInfo(List<AppInfo> apps) {
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < apps.size() && i < 4; i++) {
            names.add(apps.get(i).label);
        }
        String summary = join(names, ", ");
        if (apps.size() > 4) {
            summary += " +" + (apps.size() - 4);
        }
        return summary;
    }

    private String slugSummaryFromInfo(List<AppInfo> apps) {
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < apps.size() && i < 4; i++) {
            names.add(ScreenshotNamer.sanitizeLabel(apps.get(i).label));
        }
        String summary = join(names, ", ");
        if (apps.size() > 4) {
            summary += " +" + (apps.size() - 4);
        }
        return summary;
    }

    private String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private ArrayList<AppInfo> toAppInfo(List<AppStore.AppRef> refs) {
        PackageManager packageManager = getPackageManager();
        ArrayList<AppInfo> apps = new ArrayList<>();
        for (AppStore.AppRef ref : refs) {
            Drawable icon;
            try {
                icon = packageManager.getApplicationIcon(ref.packageName);
            } catch (PackageManager.NameNotFoundException e) {
                icon = getDrawable(R.drawable.ic_launcher);
            }
            apps.add(new AppInfo(ref.label, ref.packageName, icon));
        }
        return apps;
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_IMAGES
            }, 9);
        }
    }

    private void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) {
            showMessage("Not needed", "This Android version does not use All files access.");
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openBatterySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean areNotificationsAllowed() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isWatcherServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (ScreenshotWatcherService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName expected = new ComponentName(this, ForegroundAppAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName component = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(component)) {
                return true;
            }
        }
        return false;
    }

    private void showFilenameSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, 0);
        TextView help = text("Use tokens: {date}, {time}, {app}, {package}, {original}", 13, MUTED, Typeface.NORMAL);
        box.addView(help);
        EditText template = new EditText(this);
        template.setSingleLine(true);
        template.setText(AppStore.getFilenameTemplate(this));
        box.addView(template);
        TextView preview = text("", 13, ACCENT, Typeface.BOLD);
        preview.setPadding(0, dp(8), 0, dp(8));
        box.addView(preview);
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String value = s.toString();
                preview.setText(ScreenshotNamer.isValidTemplate(value)
                        ? "Preview: " + ScreenshotNamer.preview(value, "ChatGPT", "com.openai.chatgpt")
                        : "Template must include {app} or {package} and no slashes.");
                preview.setTextColor(ScreenshotNamer.isValidTemplate(value) ? ACCENT : DANGER);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        template.addTextChangedListener(watcher);
        watcher.onTextChanged(template.getText(), 0, 0, template.length());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Filename format")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = template.getText().toString().trim();
                if (!ScreenshotNamer.isValidTemplate(value)) {
                    preview.setTextColor(DANGER);
                    preview.setText("Template must include {app} or {package} and no slashes.");
                    return;
                }
                AppStore.setFilenameTemplate(this, value);
                AppStore.log(this, "INFO", "Filename format set to " + value);
                dialog.dismiss();
                rebuildUi();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                template.setText(AppStore.DEFAULT_FILENAME_TEMPLATE);
            });
        });
        dialog.show();
    }

    private void showSourceFolderSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, 0);
        TextView help = text("Folder to watch for new screenshots. Use an absolute path.", 13, MUTED, Typeface.NORMAL);
        box.addView(help);
        EditText source = new EditText(this);
        source.setSingleLine(false);
        source.setMinLines(2);
        source.setText(AppStore.getSourceDir(this));
        source.setHint(AppStore.DEFAULT_SCREENSHOT_DIR);
        box.addView(source);
        TextView status = text("", 13, ACCENT, Typeface.BOLD);
        status.setPadding(0, dp(8), 0, dp(8));
        box.addView(status);
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateSourceFolderStatus(status, s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        source.addTextChangedListener(watcher);
        watcher.onTextChanged(source.getText(), 0, 0, source.length());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Source folder")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String path = source.getText().toString().trim();
                if (!isValidSourceFolder(path)) {
                    status.setTextColor(DANGER);
                    status.setText("Use an absolute path, for example " + AppStore.DEFAULT_SCREENSHOT_DIR);
                    return;
                }
                AppStore.setSourceDir(this, path);
                AppStore.log(this, "INFO", "Source folder set to " + AppStore.getSourceDir(this));
                if (AppStore.isMonitoringEnabled(this)) {
                    restartWatcherForSourceChange();
                }
                dialog.dismiss();
                rebuildUi();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                source.setText(AppStore.DEFAULT_SCREENSHOT_DIR);
            });
        });
        dialog.show();
    }

    private boolean isValidSourceFolder(String path) {
        return path != null && path.trim().startsWith("/") && !path.trim().contains("\n");
    }

    private void updateSourceFolderStatus(TextView status, String path) {
        String clean = path == null ? "" : path.trim();
        if (!isValidSourceFolder(clean)) {
            status.setTextColor(DANGER);
            status.setText("Use an absolute path with no line breaks.");
            return;
        }
        java.io.File folder = new java.io.File(clean);
        status.setTextColor(folder.exists() && folder.isDirectory() ? ACCENT : MUTED);
        status.setText(folder.exists() && folder.isDirectory()
                ? "Folder exists and will be watched."
                : "Folder does not exist yet. CaptureRouter will try to create it.");
    }

    private void restartWatcherForSourceChange() {
        stopService(new Intent(this, ScreenshotWatcherService.class));
        ScreenshotWatcherService.start(this);
        WatchdogReceiver.schedule(this);
    }

    private void rebuildUi() {
        root.removeAllViews();
        buildUi();
        refreshUi();
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void exportDiagnostics() {
        try {
            File file = AppStore.writeDiagnostics(this);
            AppStore.log(this, "INFO", "DIAGNOSTICS", "Diagnostics exported to " + file.getAbsolutePath());
            showMessage("Diagnostics exported", file.getAbsolutePath());
        } catch (IOException e) {
            AppStore.log(this, "ERROR", "DIAGNOSTICS", "Diagnostics export failed: " + e.getMessage());
            showMessage("Export failed", e.getMessage() == null ? "Could not write diagnostics." : e.getMessage());
        }
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setBackground(cardBg());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(14));
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout heroCard() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(18), dp(18), dp(18), dp(18));
        view.setBackground(heroBg());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(14));
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout itemBox() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackground(itemBg());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(10));
        view.setLayoutParams(params);
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView text = text(value, 18, INK, Typeface.BOLD);
        text.setPadding(0, 0, 0, dp(10));
        return text;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setTypeface(Typeface.DEFAULT, style);
        text.setLineSpacing(0, 1.08f);
        return text;
    }

    private TextView statusPill(String value, int color) {
        TextView text = text(value, 12, color, Typeface.BOLD);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(8), dp(8), dp(8), dp(8));
        text.setBackground(pillBg());
        return text;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setBackground(buttonBg(ACCENT));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(INK);
        button.setTextSize(14);
        button.setBackground(outlineBg());
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        return row;
    }

    private View space(int width, int height) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        return view;
    }

    private LinearLayout.LayoutParams weightParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private GradientDrawable cardBg() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(SURFACE);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(1, LINE);
        return drawable;
    }

    private GradientDrawable heroBg() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(8, 61, 53), Color.rgb(15, 90, 78)});
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private GradientDrawable pillBg() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(243, 247, 245));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(1, LINE);
        return drawable;
    }

    private GradientDrawable itemBg() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(250, 252, 251));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(1, LINE);
        return drawable;
    }

    private GradientDrawable buttonBg(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private GradientDrawable outlineBg() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(1, LINE);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AppInfo {
        final String label;
        final String packageName;
        final Drawable icon;

        AppInfo(String label, String packageName, Drawable icon) {
            this.label = label == null ? packageName : label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }

    private final class AppPickerAdapter extends ArrayAdapter<AppInfo> {
        private final List<AppInfo> apps;
        private final Set<String> selectedPackages;

        AppPickerAdapter(Context context, List<AppInfo> apps, Set<String> selectedPackages) {
            super(context, 0, apps);
            this.apps = apps;
            this.selectedPackages = selectedPackages;
        }

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public AppInfo getItem(int position) {
            return apps.get(position);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppInfo app = getItem(position);
            AppRowHolder holder;
            LinearLayout row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof AppRowHolder) {
                row = (LinearLayout) convertView;
                holder = (AppRowHolder) convertView.getTag();
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                holder = new AppRowHolder();
                holder.icon = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
                row.addView(holder.icon, iconParams);
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(12), 0, dp(8), 0);
                holder.label = text("", 15, INK, Typeface.BOLD);
                holder.packageName = text("", 12, MUTED, Typeface.NORMAL);
                texts.addView(holder.label);
                texts.addView(holder.packageName);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                holder.checkBox = new CheckBox(MainActivity.this);
                holder.checkBox.setClickable(false);
                holder.checkBox.setFocusable(false);
                row.addView(holder.checkBox);
                row.setTag(holder);
            }
            holder.icon.setImageDrawable(app.icon);
            holder.label.setText(app.label);
            holder.packageName.setText(app.packageName);
            holder.checkBox.setChecked(selectedPackages.contains(app.packageName));
            return row;
        }
    }

    private static final class AppRowHolder {
        ImageView icon;
        TextView label;
        TextView packageName;
        CheckBox checkBox;
    }
}
