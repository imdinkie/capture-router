package dev.dect.capturerouter;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.provider.MediaStore;

import java.io.File;

final class ScreenshotMover {
    private ScreenshotMover() {
    }

    static MoveResult move(Context context, String sourcePath, String destination, boolean nomedia) {
        File source = new File(sourcePath);
        File destinationDir = new File(destination);
        File target = uniqueTarget(destinationDir, source.getName());
        StringBuilder command = new StringBuilder();
        command.append("mkdir -p ").append(RootShell.quote(destinationDir.getAbsolutePath()));
        if (nomedia) {
            command.append(" && touch ").append(RootShell.quote(new File(destinationDir, ".nomedia").getAbsolutePath()));
        }
        command.append(" && mv ").append(RootShell.quote(source.getAbsolutePath()))
                .append(" ").append(RootShell.quote(target.getAbsolutePath()))
                .append(" && chmod 0660 ").append(RootShell.quote(target.getAbsolutePath()));
        RootShell.Result result = RootShell.run(command.toString(), 8000);
        if (result.ok()) {
            cleanupMediaStore(context, source.getAbsolutePath(), target.getAbsolutePath());
            return new MoveResult(true, target.getAbsolutePath(), "");
        }
        return new MoveResult(false, target.getAbsolutePath(), result.output);
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
