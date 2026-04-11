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
    static final String DEFAULT_SCREENSHOT_DIR = "/sdcard/Pictures/Screenshots";
    private static final int MAX_LOGS = 200;

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

    static List<Rule> getRules(Context context) {
        ArrayList<Rule> rules = new ArrayList<>();
        String raw = prefs(context).getString(KEY_RULES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                Rule rule = new Rule(
                        obj.optString("packageName"),
                        obj.optString("label"),
                        obj.optString("destination"),
                        obj.optBoolean("nomedia", true),
                        obj.optBoolean("enabled", true)
                );
                if (!rule.packageName.isEmpty() && !rule.destination.isEmpty()) {
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
                obj.put("packageName", rule.packageName);
                obj.put("label", rule.label);
                obj.put("destination", rule.destination);
                obj.put("nomedia", rule.nomedia);
                obj.put("enabled", rule.enabled);
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
            if (rule.enabled && packageName.equals(rule.packageName)) {
                return rule;
            }
        }
        return null;
    }

    static void addOrReplaceRule(Context context, Rule newRule) {
        List<Rule> rules = getRules(context);
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).packageName.equals(newRule.packageName)) {
                rules.set(i, newRule);
                saveRules(context, rules);
                return;
            }
        }
        rules.add(newRule);
        saveRules(context, rules);
    }

    static void removeRule(Context context, String packageName) {
        List<Rule> rules = getRules(context);
        ArrayList<Rule> kept = new ArrayList<>();
        for (Rule rule : rules) {
            if (!rule.packageName.equals(packageName)) {
                kept.add(rule);
            }
        }
        saveRules(context, kept);
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

    static String formatTime(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date(time));
    }

    static final class Rule {
        final String packageName;
        final String label;
        final String destination;
        final boolean nomedia;
        final boolean enabled;

        Rule(String packageName, String label, String destination, boolean nomedia, boolean enabled) {
            this.packageName = packageName;
            this.label = label;
            this.destination = destination;
            this.nomedia = nomedia;
            this.enabled = enabled;
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
