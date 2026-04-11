package dev.dect.capturerouter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootShell {
    private RootShell() {
    }

    static Result run(String command, long timeoutMs) {
        Process process = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            Process finalProcess = process;
            Thread reader = new Thread(() -> copy(finalProcess.getInputStream(), output), "root-output-reader");
            reader.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(250);
                return new Result(124, output.toString(StandardCharsets.UTF_8.name()).trim());
            }
            reader.join(250);
            return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8.name()).trim());
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new Result(1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static boolean isAvailable() {
        Result result = run("id", 3000);
        return result.exitCode == 0 && result.output.contains("uid=0");
    }

    static String quote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[4096];
        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
        }
    }

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        boolean ok() {
            return exitCode == 0;
        }
    }
}
