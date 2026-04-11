package dev.dect.capturerouter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
    private TextView rootStatus;
    private TextView storageStatus;
    private TextView watcherStatus;
    private TextView sourceText;
    private LinearLayout rulesList;
    private LinearLayout logsList;
    private Button monitorButton;
    private boolean rootKnown;
    private boolean rootOk;
    private boolean rootCheckInFlight;

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
        refreshUi();
        checkRootAsync(false);
        refreshHandler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacksAndMessages(null);
        super.onPause();
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
        statusCard.addView(sectionTitle("Device readiness"));
        LinearLayout statusRow = row();
        statusRow.setPadding(0, 0, 0, dp(8));
        rootStatus = statusPill("Root: checking", MUTED);
        storageStatus = statusPill("Storage: checking", MUTED);
        watcherStatus = statusPill("Watcher: stopped", MUTED);
        statusRow.addView(rootStatus, weightParams());
        statusRow.addView(space(dp(8), 1));
        statusRow.addView(storageStatus, weightParams());
        statusRow.addView(space(dp(8), 1));
        statusRow.addView(watcherStatus, weightParams());
        statusCard.addView(statusRow);
        sourceText = text("", 13, MUTED, Typeface.NORMAL);
        sourceText.setPadding(0, dp(6), 0, 0);
        statusCard.addView(sourceText);
        LinearLayout statusButtons = row();
        monitorButton = primaryButton("Start monitoring");
        monitorButton.setOnClickListener(view -> toggleMonitoring());
        Button allFiles = secondaryButton("All files access");
        allFiles.setOnClickListener(view -> openAllFilesSettings());
        Button rootCheck = secondaryButton("Check root");
        rootCheck.setOnClickListener(view -> checkRootNow());
        statusButtons.addView(monitorButton, weightParams());
        statusButtons.addView(space(dp(8), 1));
        statusButtons.addView(allFiles, weightParams());
        statusButtons.addView(space(dp(8), 1));
        statusButtons.addView(rootCheck, weightParams());
        statusCard.addView(statusButtons);
        root.addView(statusCard);

        LinearLayout rulesCard = card();
        rulesCard.addView(sectionTitle("Routing rules"));
        TextView rulesHelp = text("Pick an app, choose a destination, and CaptureRouter will keep matching screenshots out of the camera roll.", 14, MUTED, Typeface.NORMAL);
        rulesHelp.setPadding(0, 0, 0, dp(12));
        rulesCard.addView(rulesHelp);
        Button addRule = primaryButton("Add routing rule");
        addRule.setOnClickListener(view -> chooseAppForRule());
        rulesCard.addView(addRule);
        rulesList = new LinearLayout(this);
        rulesList.setOrientation(LinearLayout.VERTICAL);
        rulesList.setPadding(0, dp(12), 0, 0);
        rulesCard.addView(rulesList);
        root.addView(rulesCard);

        LinearLayout logsCard = card();
        LinearLayout logHeader = row();
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.addView(sectionTitle("Activity log"), weightParams());
        Button clear = secondaryButton("Clear");
        clear.setOnClickListener(view -> {
            AppStore.prefs(this).edit().putString(AppStore.KEY_LOGS, "[]").apply();
            refreshUi();
        });
        logHeader.addView(clear);
        logsCard.addView(logHeader);
        logsList = new LinearLayout(this);
        logsList.setOrientation(LinearLayout.VERTICAL);
        logsList.setPadding(0, dp(8), 0, 0);
        logsCard.addView(logsList);
        root.addView(logsCard);
    }

    private void refreshUi() {
        boolean allFilesOk = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        boolean monitoring = AppStore.isMonitoringEnabled(this);
        rootStatus.setText(rootKnown ? (rootOk ? "Root: ready" : "Root: needed") : "Root: checking");
        rootStatus.setTextColor(rootKnown && !rootOk ? DANGER : (rootOk ? ACCENT : MUTED));
        storageStatus.setText(allFilesOk ? "Storage: ready" : "Storage: needed");
        storageStatus.setTextColor(allFilesOk ? ACCENT : DANGER);
        watcherStatus.setText(monitoring ? "Watcher: enabled" : "Watcher: stopped");
        watcherStatus.setTextColor(monitoring ? ACCENT : MUTED);
        sourceText.setText("Source folder: " + AppStore.DEFAULT_SCREENSHOT_DIR);
        monitorButton.setText(monitoring ? "Stop monitoring" : "Start monitoring");
        monitorButton.setBackground(buttonBg(monitoring ? DANGER : ACCENT));
        renderRules();
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
            TextView name = text(rule.label + "\n" + rule.packageName, 15, INK, Typeface.BOLD);
            name.setPadding(0, 0, 0, dp(5));
            item.addView(name);
            item.addView(text(rule.destination, 13, MUTED, Typeface.NORMAL));
            LinearLayout actions = row();
            actions.setPadding(0, dp(8), 0, 0);
            Button edit = secondaryButton("Edit");
            edit.setOnClickListener(view -> showRuleEditor(rule.packageName, rule.label, rule.destination, rule.nomedia));
            Button remove = secondaryButton("Remove");
            remove.setTextColor(DANGER);
            remove.setOnClickListener(view -> {
                AppStore.removeRule(this, rule.packageName);
                AppStore.log(this, "INFO", "Removed rule for " + rule.packageName);
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
            TextView line = text(AppStore.formatTime(entry.time) + "  " + entry.level + "\n" + entry.message,
                    13,
                    "ERROR".equals(entry.level) ? DANGER : INK,
                    Typeface.NORMAL);
            line.setPadding(0, dp(6), 0, dp(8));
            logsList.addView(line);
        }
    }

    private void toggleMonitoring() {
        boolean next = !AppStore.isMonitoringEnabled(this);
        if (next) {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                AppStore.log(this, "WARN", "All files access is required before monitoring");
                openAllFilesSettings();
                return;
            }
            AppStore.setMonitoringEnabled(this, true);
            ScreenshotWatcherService.start(this);
        } else {
            ScreenshotWatcherService.stop(this);
        }
        refreshUi();
    }

    private void chooseAppForRule() {
        ArrayList<AppInfo> apps = loadApps();
        if (apps.isEmpty()) {
            showMessage("No apps found", "The package picker could not load installed applications.");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, 0);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search apps");
        box.addView(search);
        ListView listView = new ListView(this);
        box.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(420)
        ));
        ArrayList<AppInfo> filtered = new ArrayList<>(apps);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels(filtered));
        listView.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Choose app")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppInfo app = filtered.get(position);
            dialog.dismiss();
            showRuleEditor(app.packageName, app.label, AppStore.defaultDestination(app.label, app.packageName), true);
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
                adapter.clear();
                adapter.addAll(labels(filtered));
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        dialog.setOnShowListener(d -> {
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

    private void showRuleEditor(String packageName, String label, String destination, boolean nomedia) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, 0);
        TextView packageLine = text(label + "\n" + packageName, 14, MUTED, Typeface.NORMAL);
        box.addView(packageLine);
        EditText folder = new EditText(this);
        folder.setSingleLine(false);
        folder.setMinLines(2);
        folder.setText(destination);
        folder.setSelectAllOnFocus(false);
        folder.setHint("/sdcard/Pictures/.CaptureRouter/App");
        box.addView(folder);
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
                    if (path.isEmpty()) {
                        showMessage("Folder required", "Enter an absolute folder path.");
                        return;
                    }
                    AppStore.addOrReplaceRule(this, new AppStore.Rule(packageName, label, path, nomediaBox.isChecked(), true));
                    AppStore.log(this, "INFO", "Saved rule for " + packageName + " -> " + path);
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
            apps.add(new AppInfo(label, info.packageName));
        }
        Collections.sort(apps, Comparator.comparing(app -> app.label.toLowerCase(Locale.US)));
        return apps;
    }

    private ArrayList<String> labels(List<AppInfo> apps) {
        ArrayList<String> labels = new ArrayList<>();
        for (AppInfo app : apps) {
            labels.add(app.label + "\n" + app.packageName);
        }
        return labels;
    }

    private void checkRootNow() {
        checkRootAsync(true);
        refreshUi();
    }

    private void checkRootAsync(boolean showDialog) {
        if (rootCheckInFlight) {
            return;
        }
        rootCheckInFlight = true;
        rootStatus.setText("Root: checking");
        new Thread(() -> {
            RootShell.Result result = RootShell.run("id", 3000);
            boolean ok = result.ok() && result.output.contains("uid=0");
            refreshHandler.post(() -> {
                rootCheckInFlight = false;
                rootKnown = true;
                rootOk = ok;
                if (showDialog) {
                    if (ok) {
                        AppStore.log(this, "INFO", "Root check passed: " + result.output);
                        showMessage("Root ready", result.output);
                    } else {
                        AppStore.log(this, "ERROR", "Root check failed: " + result.output);
                        showMessage("Root not ready", result.output.isEmpty() ? "Magisk did not grant su." : result.output);
                    }
                }
                refreshUi();
            });
        }, "root-check").start();
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

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
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

        AppInfo(String label, String packageName) {
            this.label = label == null ? packageName : label;
            this.packageName = packageName;
        }
    }
}
