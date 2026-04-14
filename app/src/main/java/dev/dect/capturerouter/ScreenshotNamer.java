package dev.dect.capturerouter;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ScreenshotNamer {
    private static final Pattern PIXEL_TIMESTAMP = Pattern.compile("Screenshot[_-](\\d{8})[-_](\\d{6})$");
    private static final Pattern EXTENSION = Pattern.compile("(?i)\\.(png|jpg|jpeg|webp)$");

    private ScreenshotNamer() {
    }

    static RenameResult ensureNamed(Context context, File source, AppStore.ForegroundApp foreground) {
        return ensureNamed(context, source, foreground, null);
    }

    static RenameResult ensureNamed(Context context, File source, AppStore.ForegroundApp foreground, Uri mediaUri) {
        String originalName = source.getName();
        String ext = extension(originalName);
        String originalBase = originalName.substring(0, originalName.length() - ext.length());
        String appLabel = foreground == null ? "Unknown App" : foreground.label;
        String packageName = foreground == null ? "unknown.app" : foreground.packageName;
        String appSlug = sanitizeLabel(appLabel);
        if (!isOriginalSystemScreenshot(originalBase)) {
            return new RenameResult(source, appLabel, packageName, appSlug, false, "");
        }

        DateParts parts = dateParts(originalBase, source.lastModified());
        String targetBase = applyTemplate(AppStore.getFilenameTemplate(context), parts.date, parts.time,
                appSlug, sanitizePackage(packageName), sanitizeLabel(originalBase));
        if (targetBase.isEmpty()) {
            targetBase = applyTemplate(AppStore.DEFAULT_FILENAME_TEMPLATE, parts.date, parts.time,
                    appSlug, sanitizePackage(packageName), sanitizeLabel(originalBase));
        }
        File target = uniqueSibling(source.getParentFile(), targetBase, ext);
        if (source.equals(target)) {
            return new RenameResult(source, appLabel, packageName, appSlug, false, "");
        }
        if (renameWithMediaStore(context, mediaUri, source, target) || source.renameTo(target)) {
            scan(context, source.getAbsolutePath(), target.getAbsolutePath());
            return new RenameResult(target, appLabel, packageName, appSlug, true, "");
        }
        return new RenameResult(source, appLabel, packageName, appSlug, false, "Rename failed for " + originalName);
    }

    static String sanitizeLabel(String value) {
        if (value == null) {
            return "Unknown_App";
        }
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
        return cleaned.isEmpty() ? "Unknown_App" : cleaned;
    }

    static boolean isValidTemplate(String template) {
        if (template == null || template.trim().isEmpty()) {
            return false;
        }
        if (template.contains("/") || template.contains("\\")) {
            return false;
        }
        return template.contains("{app}") || template.contains("{package}");
    }

    static String preview(String template, String appLabel, String packageName) {
        String safe = isValidTemplate(template) ? template : AppStore.DEFAULT_FILENAME_TEMPLATE;
        return applyTemplate(safe, "20260411", "194318", sanitizeLabel(appLabel),
                sanitizePackage(packageName), "Screenshot_20260411_194318") + ".png";
    }

    private static boolean isOriginalSystemScreenshot(String base) {
        return PIXEL_TIMESTAMP.matcher(base).matches();
    }

    private static DateParts dateParts(String base, long modified) {
        Matcher matcher = PIXEL_TIMESTAMP.matcher(base);
        if (matcher.matches()) {
            return new DateParts(matcher.group(1), matcher.group(2));
        }
        Date date = new Date(modified);
        return new DateParts(
                new SimpleDateFormat("yyyyMMdd", Locale.US).format(date),
                new SimpleDateFormat("HHmmss", Locale.US).format(date)
        );
    }

    private static String applyTemplate(String template, String date, String time, String app, String packageName, String original) {
        return template.replace("{date}", date)
                .replace("{time}", time)
                .replace("{app}", app)
                .replace("{package}", packageName)
                .replace("{original}", original)
                .replaceAll("[/\\\\]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
    }

    private static String sanitizePackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return "unknown_app";
        }
        return packageName.trim().replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
    }

    private static File uniqueSibling(File dir, String base, String ext) {
        File target = new File(dir, base + ext);
        if (!target.exists()) {
            return target;
        }
        for (int i = 2; i < 1000; i++) {
            target = new File(dir, base + "_" + i + ext);
            if (!target.exists()) {
                return target;
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    private static boolean renameWithMediaStore(Context context, Uri mediaUri, File source, File target) {
        if (mediaUri == null || android.os.Build.VERSION.SDK_INT < 29) {
            return false;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, target.getName());
            int rows = context.getContentResolver().update(mediaUri, values, null, null);
            if (rows < 1) {
                return false;
            }
            if (target.exists()) {
                return true;
            }
            return !source.exists();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String extension(String name) {
        Matcher matcher = EXTENSION.matcher(name);
        return matcher.find() ? matcher.group(0) : "";
    }

    private static void scan(Context context, String oldPath, String newPath) {
        try {
            MediaScannerConnection.scanFile(context, new String[]{oldPath, newPath}, null, null);
        } catch (Exception ignored) {
        }
    }

    private static final class DateParts {
        final String date;
        final String time;

        DateParts(String date, String time) {
            this.date = date;
            this.time = time;
        }
    }

    static final class RenameResult {
        final File file;
        final String appLabel;
        final String packageName;
        final String appSlug;
        final boolean renamed;
        final String error;

        RenameResult(File file, String appLabel, String packageName, String appSlug, boolean renamed, String error) {
            this.file = file;
            this.appLabel = appLabel;
            this.packageName = packageName;
            this.appSlug = appSlug;
            this.renamed = renamed;
            this.error = error == null ? "" : error;
        }
    }
}
