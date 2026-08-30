package com.termux.app;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.termux.R;

/**
 * Full-spectrum color chooser: hue ring + saturation/brightness square, a hex
 * field, and a row of ready-to-tap common colors. Long-press a swatch to save
 * the currently picked color into that slot; slots persist in the theme prefs.
 */
public final class ColorPickerDialog {

    public interface OnColorPicked { void onColorPicked(int color); }

    private ColorPickerDialog() {}

    public static void show(@NonNull android.app.Activity activity, @NonNull String title, int initialColor,
                     boolean allowSwatchSave, @NonNull OnColorPicked callback) {
        AiTheme.State state = AiTheme.state(activity);
        final int[] picked = {initialColor};

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 18);
        root.setPadding(pad, dp(activity, 8), pad, 0);

        HsvColorPicker.HueRingView ring = new HsvColorPicker.HueRingView(activity);
        root.addView(ring, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 168)));

        HsvColorPicker.SvSquareView square = new HsvColorPicker.SvSquareView(activity);
        root.addView(square, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 150)));

        LinearLayout hexRow = new LinearLayout(activity);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hexRowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hexRowLp.setMargins(0, dp(activity, 12), 0, 0);
        hexRow.setLayoutParams(hexRowLp);

        ImageView preview = new ImageView(activity);
        preview.setLayoutParams(new LinearLayout.LayoutParams((int) dp(activity, 44), (int) dp(activity, 44)));
        preview.setBackgroundResource(R.drawable.bg_ai_harness_badge);
        updatePreview(preview, initialColor);

        EditText hex = new EditText(activity);
        hex.setSingleLine(true);
        hex.setText(colorToHex(initialColor));
        hex.setTextColor(AiTheme.resolve(activity, R.color.ai_text));
        hex.setHintTextColor(AiTheme.resolve(activity, R.color.ai_text_muted));
        hex.setTextSize(14);
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hexLp.setMargins(dp(activity, 12), 0, 0, 0);
        hex.setLayoutParams(hexLp);
        hexRow.addView(preview);
        hexRow.addView(hex);
        root.addView(hexRow);

        TextView swatchHint = new TextView(activity);
        swatchHint.setText(R.string.ai_theme_swatch_hint);
        swatchHint.setTextColor(AiTheme.resolve(activity, R.color.ai_text_muted));
        swatchHint.setTextSize(11);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(activity, 12), 0, dp(activity, 6));
        swatchHint.setLayoutParams(hintLp);
        root.addView(swatchHint);

        HorizontalScrollView swatchScroll = new HorizontalScrollView(activity);
        swatchScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout swatchRow = new LinearLayout(activity);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchScroll.addView(swatchRow);
        root.addView(swatchScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final boolean[] suppressHex = {false};
        Runnable syncFromSquare = () -> {
            picked[0] = square.getColor();
            updatePreview(preview, picked[0]);
            suppressHex[0] = true;
            hex.setText(colorToHex(picked[0]));
            suppressHex[0] = false;
        };

        ring.setOnHueChanged(square::setHue);
        ring.setHue(hueOf(initialColor));
        square.setColor(initialColor);
        square.setOnSvChanged((sat, value) -> syncFromSquare.run());

        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (suppressHex[0]) return;
                try {
                    int color = Color.parseColor(normalizeHex(s.toString()));
                    picked[0] = color;
                    updatePreview(preview, color);
                    square.setColor(color);
                } catch (Exception ignored) {
                }
            }
        });

        Runnable rebuildSwatches = new Runnable() {
            @Override
            public void run() {
                swatchRow.removeAllViews();
                AiTheme.State current = AiTheme.state(activity);
                for (int i = 0; i < current.swatches.size(); i++) {
                    int color = current.swatches.get(i);
                    View swatch = makeSwatch(activity, color, dp(activity, 34));
                    swatch.setTag(i);
                    swatch.setOnClickListener(v -> {
                        picked[0] = color;
                        updatePreview(preview, color);
                        suppressHex[0] = true;
                        hex.setText(colorToHex(color));
                        suppressHex[0] = false;
                        square.setColor(color);
                    });
                    if (allowSwatchSave) {
                        swatch.setOnLongClickListener(v -> {
                            int slot = (int) v.getTag();
                            current.swatches.set(slot, picked[0]);
                            AiTheme.save(activity);
                            Toast.makeText(activity, R.string.ai_theme_swatch_saved, Toast.LENGTH_SHORT).show();
                            this.run();
                            return true;
                        });
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        dp(activity, 34), dp(activity, 34));
                    lp.setMargins(dp(activity, 4), 0, dp(activity, 4), 0);
                    swatch.setLayoutParams(lp);
                    swatchRow.addView(swatch);
                }
            }
        };
        rebuildSwatches.run();

        AiTheme.themedBuilder(activity)
            .setTitle(title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> callback.onColorPicked(picked[0]))
            .show();
    }

    private static View makeSwatch(android.app.Activity activity, int color, int sizeDp) {
        View swatch = new View(activity);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setShape(GradientDrawable.OVAL);
        d.setStroke((int) dp(activity, 1), AiTheme.resolve(activity, R.color.ai_border));
        swatch.setBackground(d);
        swatch.setClickable(true);
        swatch.setFocusable(true);
        return swatch;
    }

    private static void updatePreview(ImageView preview, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setShape(GradientDrawable.OVAL);
        preview.setImageDrawable(d);
    }

    private static float hueOf(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0];
    }

    private static String colorToHex(int color) {
        return String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color));
    }

    private static String normalizeHex(String value) {
        String clean = value.trim().replace("#", "").replace("0x", "").replace("0X", "");
        if (clean.length() == 3) {
            StringBuilder expanded = new StringBuilder();
            for (char c : clean.toCharArray()) expanded.append(c).append(c);
            clean = expanded.toString();
        }
        if (clean.length() == 8) clean = clean.substring(2);
        return "#" + clean;
    }

    private static int dp(android.app.Activity activity, float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
