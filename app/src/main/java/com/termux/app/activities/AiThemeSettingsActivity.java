package com.termux.app.activities;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.AiTheme;
import com.termux.app.ColorPickerDialog;

/**
 * Theme customization screen: each color group (buttons, background, cards,
 * text...) gets a row with its current swatch; tapping opens a full HSV color
 * picker with a customizable common-colors row. Terminal colors stay separate.
 */
public class AiThemeSettingsActivity extends AppCompatActivity {

    private LinearLayout mList;
    private TextView mResetButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AiTheme.install(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(AiTheme.resolve(this, R.color.ai_background));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText(R.string.ai_theme_title);
        title.setTextColor(AiTheme.resolve(this, R.color.ai_text));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.ai_theme_subtitle);
        subtitle.setTextColor(AiTheme.resolve(this, R.color.ai_text_muted));
        subtitle.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(6), 0, dp(18));
        subtitle.setLayoutParams(subLp);
        root.addView(subtitle);

        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        root.addView(mList);

        mResetButton = new TextView(this);
        mResetButton.setText(R.string.ai_theme_reset);
        mResetButton.setTextColor(AiTheme.resolve(this, R.color.ai_error));
        mResetButton.setTextSize(14);
        mResetButton.setTypeface(Typeface.DEFAULT_BOLD);
        mResetButton.setGravity(Gravity.CENTER);
        mResetButton.setPadding(dp(12), dp(14), dp(12), dp(14));
        mResetButton.setBackgroundResource(R.drawable.bg_provider_card);
        mResetButton.setClickable(true);
        mResetButton.setFocusable(true);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.setMargins(0, dp(18), 0, 0);
        mResetButton.setLayoutParams(resetLp);
        mResetButton.setOnClickListener(v -> confirmReset());
        root.addView(mResetButton);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private void rebuild() {
        mList.removeAllViews();
        mList.addView(roleRow(R.string.ai_theme_role_accent, AiTheme.Role.ACCENT));
        mList.addView(roleRow(R.string.ai_theme_role_background, AiTheme.Role.BACKGROUND));
        mList.addView(roleRow(R.string.ai_theme_role_surface, AiTheme.Role.SURFACE));
        mList.addView(roleRow(R.string.ai_theme_role_text, AiTheme.Role.TEXT_PRIMARY));
        mList.addView(roleRow(R.string.ai_theme_role_text_secondary, AiTheme.Role.TEXT_SECONDARY));
        mList.addView(roleRow(R.string.ai_theme_role_border, AiTheme.Role.BORDER));
    }

    private View roleRow(int labelRes, AiTheme.Role role) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setBackgroundResource(R.drawable.bg_provider_card);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(lp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(textLp);
        row.addView(text);

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextColor(AiTheme.resolve(this, R.color.ai_text));
        label.setTextSize(15);
        text.addView(label);

        TextView description = new TextView(this);
        description.setText(roleDescription(role));
        description.setTextColor(AiTheme.resolve(this, R.color.ai_text_muted));
        description.setTextSize(11);
        text.addView(description);

        int current = currentColor(role);
        View swatch = swatchView(current, dp(38));
        row.addView(swatch);

        row.setOnClickListener(v -> ColorPickerDialog.show(this, getString(labelRes), current, true, color -> {
            AiTheme.setOverride(this, role, color);
            rebuild();
            refreshBackgrounds();
        }));
        return row;
    }

    private View swatchView(int color, int sizeDp) {
        View swatch = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(dp(1), AiTheme.resolve(this, R.color.ai_border));
        swatch.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizeDp, sizeDp);
        lp.setMargins(dp(10), 0, 0, 0);
        swatch.setLayoutParams(lp);
        return swatch;
    }

    private int currentColor(AiTheme.Role role) {
        Integer override = AiTheme.override(this, role);
        if (override != null) return override;
        switch (role) {
            case ACCENT: return AiTheme.resolve(this, R.color.ai_accent);
            case BACKGROUND: return AiTheme.resolve(this, R.color.ai_background);
            case SURFACE: return AiTheme.resolve(this, R.color.ai_surface);
            case TEXT_PRIMARY: return AiTheme.resolve(this, R.color.ai_text);
            case TEXT_SECONDARY: return AiTheme.resolve(this, R.color.ai_text_muted);
            case BORDER: return AiTheme.resolve(this, R.color.ai_border);
        }
        return 0xFF7B61FF;
    }

    private int roleDescription(AiTheme.Role role) {
        switch (role) {
            case ACCENT: return R.string.ai_theme_role_accent_desc;
            case BACKGROUND: return R.string.ai_theme_role_background_desc;
            case SURFACE: return R.string.ai_theme_role_surface_desc;
            case TEXT_PRIMARY: return R.string.ai_theme_role_text_desc;
            case TEXT_SECONDARY: return R.string.ai_theme_role_text_secondary_desc;
            case BORDER: return R.string.ai_theme_role_border_desc;
        }
        return 0;
    }

    private void refreshBackgrounds() {
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
            AiTheme.resolve(this, R.color.ai_background)));
        findViewById(android.R.id.content).invalidate();
    }

    private void confirmReset() {
        AiTheme.themedBuilder(this)
            .setTitle(R.string.ai_theme_reset)
            .setMessage(R.string.ai_theme_reset_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                AiTheme.reset(this);
                Toast.makeText(this, R.string.ai_theme_reset_done, Toast.LENGTH_SHORT).show();
                rebuild();
            })
            .show();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
