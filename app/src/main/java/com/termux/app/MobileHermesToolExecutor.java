package com.termux.app;

import android.content.Context;

import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Native Hermes-mobile tool executor backed by the bundled Termux environment. */
public final class MobileHermesToolExecutor {

    private static final int MAX_CAPTURE_BYTES = 64 * 1024;
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_TIMEOUT_SECONDS = 20 * 60;

    private final Context mContext;

    public MobileHermesToolExecutor(Context context) {
        mContext = context.getApplicationContext();
    }

    @Nullable
    public static String normalizeWorkspace(String workspace) {
        if (workspace == null) return null;

        String path = workspace.trim();
        if (path.isEmpty() || "~".equals(path)) {
            path = TermuxConstants.TERMUX_HOME_DIR_PATH;
        } else if (path.startsWith("~/")) {
            path = TermuxConstants.TERMUX_HOME_DIR_PATH + path.substring(1);
        } else if (path.startsWith("$HOME/")) {
            path = TermuxConstants.TERMUX_HOME_DIR_PATH + path.substring(5);
        }

        try {
            File directory = new File(path).getCanonicalFile();
            String canonicalPath = directory.getAbsolutePath();
            String home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH).getCanonicalPath();
            boolean underHome = canonicalPath.equals(home) || canonicalPath.startsWith(home + "/");
            boolean underSharedStorage = canonicalPath.equals("/storage/emulated/0")
                || canonicalPath.startsWith("/storage/emulated/0/");
            if (!underHome && !underSharedStorage) return null;
            if (!directory.exists()) {
                try { directory.mkdirs(); } catch (Exception ignored) {}
            }
            if (!directory.isDirectory() || !directory.canRead()) {
                File parent = directory.getParentFile();
                if (parent != null && parent.isDirectory() && parent.canRead()) return canonicalPath;
                return null;
            }
            return canonicalPath;
        } catch (Exception e) {
            return null;
        }
    }

    public String runCommand(String workspace, String command, int timeoutSeconds) {
        JSONObject result = new JSONObject();
        String directory = normalizeWorkspace(workspace);
        if (directory == null) return error("Choose a valid project folder first.");
        if (command == null || command.trim().isEmpty()) return error("Command is empty.");

        int timeout = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
        Process process = null;
        try {
            TermuxShellEnvironment.init(mContext);
            ProcessBuilder builder = new ProcessBuilder(
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
                "-lc",
                "export PATH=\"$HOME/.local/bin:$PREFIX/bin:$PATH\"; " + command);
            builder.directory(new File(directory));
            Map<String, String> env = builder.environment();
            env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            env.put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
            env.put("TERM", "xterm-256color");
            env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":"
                + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/bin:"
                + env.getOrDefault("PATH", ""));

            process = builder.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            CountDownLatch readersDone = new CountDownLatch(2);
            Process finalProcess = process;
            new Thread(() -> drain(finalProcess.getInputStream(), stdout, readersDone),
                "mobile-hermes-stdout").start();
            new Thread(() -> drain(finalProcess.getErrorStream(), stderr, readersDone),
                "mobile-hermes-stderr").start();

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                result.put("timed_out", true);
                result.put("exit_code", 124);
            } else {
                result.put("timed_out", false);
                result.put("exit_code", process.exitValue());
            }
            readersDone.await(2, TimeUnit.SECONDS);
            result.put("stdout", new String(stdout.toByteArray(), StandardCharsets.UTF_8));
            result.put("stderr", new String(stderr.toByteArray(), StandardCharsets.UTF_8));
            result.put("cwd", directory);
            return result.toString();
        } catch (Exception e) {
            if (process != null) process.destroy();
            return error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void drain(InputStream input, ByteArrayOutputStream output, CountDownLatch latch) {
        try (InputStream stream = input) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (output.size() < MAX_CAPTURE_BYTES) {
                    int remaining = Math.min(read, MAX_CAPTURE_BYTES - output.size());
                    output.write(buffer, 0, remaining);
                }
            }
        } catch (Exception ignored) {
        } finally {
            latch.countDown();
        }
    }

    private String error(String message) {
        try {
            return new JSONObject().put("error", message == null ? "Unknown tool error." : message).toString();
        } catch (Exception e) {
            return "{\"error\":\"Unknown tool error.\"}";
        }
    }
}
