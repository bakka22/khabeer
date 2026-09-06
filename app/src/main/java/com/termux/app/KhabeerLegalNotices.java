package com.termux.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.termux.shared.activities.ReportActivity;
import com.termux.shared.models.ReportInfo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Offline notices for the modified application and its identified components. */
public final class KhabeerLegalNotices {
    private static final String[] TITLES = {
        "Copyright & acknowledgements", "GNU GPL version 3", "Hermes Agent — MIT",
        "Apache License 2.0", "GNU GPL version 2", "Classpath exception 2.0", "MIT license",
        "Bundled skill authors", "Hermes document skills — MIT", "Humanizer — MIT"
    };
    private static final String[] ASSETS = {
        "NOTICE.md", "GPL-3.0-only.txt", "Hermes-Agent-MIT.txt",
        "Apache-2.0.txt", "GPL-2.0-only.txt", "Classpath-exception-2.0.txt", "MIT.txt",
        "Hermes-Skills-Attribution.md", "skills/productivity/docx/LICENSE", "skills/creative/humanizer/LICENSE"
    };

    private KhabeerLegalNotices() {}

    public static void show(Activity activity) {
        new AlertDialog.Builder(activity)
            .setTitle("Licenses & acknowledgements")
            .setItems(TITLES, (dialog, index) -> open(activity, index))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void open(Activity activity, int index) {
        new Thread(() -> {
            try (InputStream input = activity.getAssets().open(ASSETS[index]);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
                ReportInfo report = new ReportInfo("licenses", KhabeerLegalNotices.class.getName(), TITLES[index]);
                report.setReportString(text);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        ReportActivity.startReportActivity(activity, report);
                    }
                });
            } catch (Exception exception) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        Toast.makeText(activity, "Unable to open the bundled license text.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "khabeer-licenses").start();
    }
}
