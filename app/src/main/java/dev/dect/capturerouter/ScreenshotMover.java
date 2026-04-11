package dev.dect.capturerouter;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

final class ScreenshotMover {
    private ScreenshotMover() {
    }

    static MoveResult move(Context context, String sourcePath, String destination, boolean nomedia) {
        File source = new File(sourcePath);
        File destinationDir = new File(destination);
        if (!source.exists()) {
            return new MoveResult(false, sourcePath, "Source file no longer exists");
        }
        if (!destinationDir.exists() && !destinationDir.mkdirs()) {
            return new MoveResult(false, sourcePath, "Could not create destination folder: " + destination);
        }
        File target = uniqueTarget(destinationDir, source.getName());
        try {
            if (nomedia) {
                File marker = new File(destinationDir, ".nomedia");
                if (!marker.exists()) {
                    marker.createNewFile();
                }
            }
            if (!source.renameTo(target)) {
                copyThenDelete(source, target);
            }
            cleanupMediaStore(context, source.getAbsolutePath(), target.getAbsolutePath());
            return new MoveResult(true, target.getAbsolutePath(), "");
        } catch (IOException e) {
            return new MoveResult(false, target.getAbsolutePath(), e.getMessage());
        }
    }

    private static File uniqueTarget(File destinationDir, String fileName) {
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

    private static void copyThenDelete(File source, File target) throws IOException {
        byte[] buffer = new byte[1024 * 64];
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        if (!source.delete()) {
            target.delete();
            throw new IOException("Copied but could not delete original file");
        }
    }

    private static void cleanupMediaStore(Context context, String sourcePath, String targetPath) {
        try {
            ContentResolver resolver = context.getContentResolver();
            String where = MediaStore.MediaColumns.DATA + "=?";
            resolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, where, new String[]{sourcePath});
            resolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, where, new String[]{targetPath});
        } catch (Exception ignored) {
        }
        try {
            MediaScannerConnection.scanFile(context, new String[]{sourcePath}, null, null);
        } catch (Exception ignored) {
        }
    }

    static final class MoveResult {
        final boolean ok;
        final String targetPath;
        final String error;

        MoveResult(boolean ok, String targetPath, String error) {
            this.ok = ok;
            this.targetPath = targetPath;
            this.error = error == null ? "" : error;
        }
    }
}
