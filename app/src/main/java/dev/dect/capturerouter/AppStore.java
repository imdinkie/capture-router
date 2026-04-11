package dev.dect.capturerouter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class AppStore {
    static final String PREFS = "capture_router";
    static final String KEY_RULES = "rules";
    static final String KEY_LOGS = "logs";
    static final String KEY_MONITORING = "monitoring";
    static final String KEY_PENDING = "pending";
    static final String KEY_FOREGROUND_PACKAGE = "foreground_package";
    static final String KEY_FOREGROUND_LABEL = "foreground_label";
    static final String KEY_FOREGROUND_TIME = "foreground_time";
    static final String KEY_FILENAME_TEMPLATE = "filename_template";
    static final String DEFAULT_FILENAME_TEMPLATE = "Screenshot_{date}_{time}_{app}";
    static final String DEFAULT_SCREENSHOT_DIR = "/sdcard/Pictures/Screenshots";
    private static final int MAX_LOGS = 200;
    private static final int MAX_PENDING = 300;

    private AppStore() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isMonitoringEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MONITORING, false);
    }

    static void setMonitoringEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MONITORING, enabled).apply();
    }

    static String getFilenameTemplate(Context context) {
        String value = prefs(context).getString(KEY_FILENAME_TEMPLATE, DEFAULT_FILENAME_TEMPLATE);
        return ScreenshotNamer.isValidTemplate(value) ? value : DEFAULT_FILENAME_TEMPLATE;
    }

    static void setFilenameTemplate(Context context, String template) {
        prefs(context).edit().putString(KEY_FILENAME_TEMPLATE, template).apply();
    }

    static void setForegroundApp(Context context, String packageName, String label, long time) {
        prefs(context).edit()
                .putString(KEY_FOREGROUND_PACKAGE, packageName)
                .putString(KEY_FOREGROUND_LABEL, label)
                .putLong(KEY_FOREGROUND_TIME, time)
                .apply();
    }

    static ForegroundApp getRecentForegroundApp(Context context, long maxAgeMs) {
        SharedPreferences prefs = prefs(context);
        long time = prefs.getLong(KEY_FOREGROUND_TIME, 0);
        if (time <= 0 || System.currentTimeMillis() - time > maxAgeMs) {
            return null;
        }
        String packageName = prefs.getString(KEY_FOREGROUND_PACKAGE, "");
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        String label = prefs.getString(KEY_FOREGROUND_LABEL, packageName);
        return new ForegroundApp(packageName, label, time);
    }

    static List<Rule> getRules(Context context) {
        ArrayList<Rule> rules = new ArrayList<>();
        String raw = prefs(context).getString(KEY_RULES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                ArrayList<AppRef> apps = new ArrayList<>();
                JSONArray appArray = obj.optJSONArray("apps");
                if (appArray != null) {
                    for (int j = 0; j < appArray.length(); j++) {
                        JSONObject appObj = appArray.getJSONObject(j);
                        String packageName = appObj.optString("packageName");
                        if (!packageName.isEmpty()) {
                            apps.add(new AppRef(packageName, appObj.optString("label", packageName)));
                        }
                    }
                } else {
                    String legacyPackage = obj.optString("packageName");
                    if (!legacyPackage.isEmpty()) {
                        apps.add(new AppRef(legacyPackage, obj.optString("label", legacyPackage)));
                    }
                }
                Rule rule = new Rule(
                        obj.optString("id", obj.optString("packageName", "rule-" + i)),
                        obj.optString("name", obj.optString("label", "Untitled rule")),
                        apps,
                        obj.optString("destination"),
                        obj.optString("mode", Rule.MODE_AUTO),
                        obj.optBoolean("nomedia", true),
                        obj.optBoolean("enabled", true)
                );
                if (!rule.apps.isEmpty() && !rule.destination.isEmpty()) {
                    rules.add(rule);
                }
            }
        } catch (JSONException ignored) {
        }
        return rules;
    }

    static void saveRules(Context context, List<Rule> rules) {
        JSONArray array = new JSONArray();
        for (Rule rule : rules) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", rule.id);
                obj.put("name", rule.name);
                obj.put("destination", rule.destination);
                obj.put("mode", rule.mode);
                obj.put("nomedia", rule.nomedia);
                obj.put("enabled", rule.enabled);
                JSONArray appArray = new JSONArray();
                for (AppRef app : rule.apps) {
                    JSONObject appObj = new JSONObject();
                    appObj.put("packageName", app.packageName);
                    appObj.put("label", app.label);
                    appArray.put(appObj);
                }
                obj.put("apps", appArray);
                array.put(obj);
            } catch (JSONException ignored) {
            }
        }
        prefs(context).edit().putString(KEY_RULES, array.toString()).apply();
    }

    static Rule findRule(Context context, String packageName) {
        if (packageName == null) {
            return null;
        }
        for (Rule rule : getRules(context)) {
            if (rule.enabled && rule.contains(packageName)) {
                return rule;
            }
        }
        return null;
    }

    static void addOrReplaceRule(Context context, Rule newRule) {
        List<Rule> rules = getRules(context);
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id.equals(newRule.id)) {
                rules.set(i, newRule);
                saveRules(context, rules);
                return;
            }
        }
        rules.add(newRule);
        saveRules(context, rules);
    }

    static void removeRule(Context context, String ruleId) {
        List<Rule> rules = getRules(context);
        ArrayList<Rule> kept = new ArrayList<>();
        for (Rule rule : rules) {
            if (!rule.id.equals(ruleId)) {
                kept.add(rule);
            }
        }
        saveRules(context, kept);
    }

    static Rule findRuleById(Context context, String ruleId) {
        for (Rule rule : getRules(context)) {
            if (rule.id.equals(ruleId)) {
                return rule;
            }
        }
        return null;
    }

    static Rule findRuleForFilename(Context context, String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.US);
        for (Rule rule : getRules(context)) {
            if (!rule.enabled) {
                continue;
            }
            for (AppRef app : rule.apps) {
                if (lower.contains(app.slug().toLowerCase(Locale.US))) {
                    return rule;
                }
            }
        }
        return null;
    }

    static List<PendingShot> getPending(Context context) {
        ArrayList<PendingShot> pending = new ArrayList<>();
        String raw = prefs(context).getString(KEY_PENDING, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                PendingShot shot = new PendingShot(
                        obj.optString("id"),
                        obj.optString("path"),
                        obj.optString("packageName"),
                        obj.optString("label"),
                        obj.optString("ruleId"),
                        obj.optString("ruleName"),
                        obj.optString("destination"),
                        obj.optBoolean("nomedia", true),
                        obj.optLong("time")
                );
                if (!shot.id.isEmpty() && !shot.path.isEmpty()) {
                    pending.add(shot);
                }
            }
        } catch (JSONException ignored) {
        }
        return pending;
    }

    static void savePending(Context context, List<PendingShot> pending) {
        JSONArray array = new JSONArray();
        int count = 0;
        for (PendingShot shot : pending) {
            if (count >= MAX_PENDING) {
                break;
            }
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", shot.id);
                obj.put("path", shot.path);
                obj.put("packageName", shot.packageName);
                obj.put("label", shot.label);
                obj.put("ruleId", shot.ruleId);
                obj.put("ruleName", shot.ruleName);
                obj.put("destination", shot.destination);
                obj.put("nomedia", shot.nomedia);
                obj.put("time", shot.time);
                array.put(obj);
                count++;
            } catch (JSONException ignored) {
            }
        }
        prefs(context).edit().putString(KEY_PENDING, array.toString()).apply();
    }

    static void addPending(Context context, PendingShot newShot) {
        List<PendingShot> pending = getPending(context);
        ArrayList<PendingShot> kept = new ArrayList<>();
        kept.add(newShot);
        for (PendingShot shot : pending) {
            if (!shot.path.equals(newShot.path) && new File(shot.path).exists()) {
                kept.add(shot);
            }
        }
        savePending(context, kept);
    }

    static void removePending(Context context, String pendingId) {
        ArrayList<PendingShot> kept = new ArrayList<>();
        for (PendingShot shot : getPending(context)) {
            if (!shot.id.equals(pendingId)) {
                kept.add(shot);
            }
        }
        savePending(context, kept);
    }

    static int pruneMissingPending(Context context) {
        List<PendingShot> pending = getPending(context);
        ArrayList<PendingShot> kept = new ArrayList<>();
        for (PendingShot shot : pending) {
            if (new File(shot.path).exists()) {
                kept.add(shot);
            }
        }
        if (kept.size() != pending.size()) {
            savePending(context, kept);
        }
        return kept.size();
    }

    static List<LogEntry> getLogs(Context context) {
        ArrayList<LogEntry> logs = new ArrayList<>();
        String raw = prefs(context).getString(KEY_LOGS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                logs.add(new LogEntry(
                        obj.optLong("time"),
                        obj.optString("level"),
                        obj.optString("message")
                ));
            }
        } catch (JSONException ignored) {
        }
        return logs;
    }

    static void log(Context context, String level, String message) {
        List<LogEntry> logs = getLogs(context);
        logs.add(0, new LogEntry(System.currentTimeMillis(), level, message));
        while (logs.size() > MAX_LOGS) {
            logs.remove(logs.size() - 1);
        }
        JSONArray array = new JSONArray();
        for (LogEntry entry : logs) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("time", entry.time);
                obj.put("level", entry.level);
                obj.put("message", entry.message);
                array.put(obj);
            } catch (JSONException ignored) {
            }
        }
        prefs(context).edit().putString(KEY_LOGS, array.toString()).apply();
    }

    static String defaultDestination(String label, String packageName) {
        String base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ".CaptureRouter").getAbsolutePath();
        String name = label == null || label.trim().isEmpty() ? packageName : label;
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.isEmpty()) {
            name = packageName;
        }
        return base + "/" + name;
    }

    static String newRuleId() {
        return "rule-" + System.currentTimeMillis();
    }

    static String formatTime(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date(time));
    }

    static final class Rule {
        static final String MODE_AUTO = "auto";
        static final String MODE_MANUAL = "manual";

        final String id;
        final String name;
        final List<AppRef> apps;
        final String destination;
        final String mode;
        final boolean nomedia;
        final boolean enabled;

        Rule(String id, String name, List<AppRef> apps, String destination, String mode, boolean nomedia, boolean enabled) {
            this.id = id == null || id.isEmpty() ? newRuleId() : id;
            this.name = name == null || name.isEmpty() ? "Untitled rule" : name;
            this.apps = new ArrayList<>(apps);
            this.destination = destination;
            this.mode = MODE_MANUAL.equals(mode) ? MODE_MANUAL : MODE_AUTO;
            this.nomedia = nomedia;
            this.enabled = enabled;
        }

        boolean contains(String packageName) {
            for (AppRef app : apps) {
                if (app.packageName.equals(packageName)) {
                    return true;
                }
            }
            return false;
        }

        String labelFor(String packageName) {
            for (AppRef app : apps) {
                if (app.packageName.equals(packageName)) {
                    return app.label;
                }
            }
            return packageName;
        }

        String labelForFilename(String filename) {
            String lower = filename == null ? "" : filename.toLowerCase(Locale.US);
            for (AppRef app : apps) {
                if (lower.contains(app.slug().toLowerCase(Locale.US))) {
                    return app.label;
                }
            }
            return name;
        }

        String modeLabel() {
            return MODE_MANUAL.equals(mode) ? "Queue for review" : "Auto move";
        }
    }

    static final class AppRef {
        final String packageName;
        final String label;

        AppRef(String packageName, String label) {
            this.packageName = packageName;
            this.label = label == null || label.isEmpty() ? packageName : label;
        }

        String slug() {
            return ScreenshotNamer.sanitizeLabel(label);
        }
    }

    static final class ForegroundApp {
        final String packageName;
        final String label;
        final long time;

        ForegroundApp(String packageName, String label, long time) {
            this.packageName = packageName;
            this.label = label == null || label.isEmpty() ? packageName : label;
            this.time = time;
        }
    }

    static final class PendingShot {
        final String id;
        final String path;
        final String packageName;
        final String label;
        final String ruleId;
        final String ruleName;
        final String destination;
        final boolean nomedia;
        final long time;

        PendingShot(String id, String path, String packageName, String label, String ruleId,
                    String ruleName, String destination, boolean nomedia, long time) {
            this.id = id == null || id.isEmpty() ? "pending-" + System.currentTimeMillis() : id;
            this.path = path;
            this.packageName = packageName;
            this.label = label == null || label.isEmpty() ? packageName : label;
            this.ruleId = ruleId;
            this.ruleName = ruleName;
            this.destination = destination;
            this.nomedia = nomedia;
            this.time = time;
        }
    }

    static final class LogEntry {
        final long time;
        final String level;
        final String message;

        LogEntry(long time, String level, String message) {
            this.time = time;
            this.level = level;
            this.message = message;
        }
    }
}
