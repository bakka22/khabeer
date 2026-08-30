package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.termux.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime color palette for the Hermes-style UI (everything except the terminal).
 *
 * Six semantic roles group every themeable resource, mirroring how the design
 * tokens were used: changing "accent" recolors all buttons/highlights at once.
 * Overrides live in SharedPreferences and are applied through
 * {@link #resolve(Context, int)} for code-built views and through the
 * LayoutInflater factory ({@link #install(Activity)}) for XML-inflated views,
 * so layouts keep referencing plain @color resources.
 */
public final class AiTheme {

    public enum Role { ACCENT, BACKGROUND, SURFACE, TEXT_PRIMARY, TEXT_SECONDARY, BORDER }

    public static final class State {
        public final Map<Role, Integer> overrides = new HashMap<>();
        public final List<Integer> swatches = new ArrayList<>();
        public int version = 0;
    }

    private static final String PREFS = "ai_theme";
    private static final String KEY_VERSION = "version";
    private static final String KEY_SWATCH_COUNT = "swatch_count";

    private static State sState;

    private static final int[] DEFAULT_SWATCHES = {
        0xFFFFFFFF, 0xFF000000, 0xFF7B61FF, 0xFF2196F3,
        0xFF2ECC71, 0xFFF44336, 0xFFFFC107, 0xFFE91E63,
    };

    private static final Map<String, Role> RESOURCE_ROLES = new HashMap<>();
    static {
        for (String name : new String[]{"ai_accent", "ai_accent_dark", "ai_accent_muted",
            "ai_accent_glow", "ai_bubble_user", "ai_nav_selected"}) RESOURCE_ROLES.put(name, Role.ACCENT);
        for (String name : new String[]{"ai_background", "ai_background_gradient_start",
            "ai_background_gradient_end", "ai_drawer_background", "ai_nav_background"}) RESOURCE_ROLES.put(name, Role.BACKGROUND);
        for (String name : new String[]{"ai_surface", "ai_surface_muted", "ai_surface_elevated",
            "ai_bubble_agent", "ai_chip"}) RESOURCE_ROLES.put(name, Role.SURFACE);
        RESOURCE_ROLES.put("ai_text", Role.TEXT_PRIMARY);
        for (String name : new String[]{"ai_text_muted", "ai_text_dim"}) RESOURCE_ROLES.put(name, Role.TEXT_SECONDARY);
        for (String name : new String[]{"ai_border", "ai_border_subtle", "ai_divider"}) RESOURCE_ROLES.put(name, Role.BORDER);
    }

    /** View attributes the factory re-colors when they reference a themed resource. */
    private static final int[] READ_ATTRS = {
        android.R.attr.background,
        android.R.attr.backgroundTint,
        android.R.attr.textColor,
        android.R.attr.textColorHint,
        androidx.appcompat.R.attr.tint,
        com.google.android.material.R.attr.strokeColor,
        com.google.android.material.R.attr.iconTint,
        com.google.android.material.R.attr.boxStrokeColor,
    };

    private AiTheme() {}

    public static synchronized State state(Context context) {
        if (sState == null) load(context.getApplicationContext());
        return sState;
    }

    public static synchronized void load(Context context) {
        State state = new State();
        SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        state.version = prefs.getInt(KEY_VERSION, 0);
        for (Role role : Role.values()) {
            String value = prefs.getString(role.name(), null);
            if (value != null) {
                try { state.overrides.put(role, Color.parseColor(value)); } catch (Exception ignored) {}
            }
        }
        int count = prefs.getInt(KEY_SWATCH_COUNT, DEFAULT_SWATCHES.length);
        for (int i = 0; i < count; i++) {
            String value = prefs.getString("swatch_" + i, null);
            if (value == null && i < DEFAULT_SWATCHES.length) value = String.format("#%08X", DEFAULT_SWATCHES[i]);
            if (value != null) {
                try { state.swatches.add(Color.parseColor(value)); } catch (Exception ignored) {}
            }
        }
        if (state.swatches.isEmpty()) for (int c : DEFAULT_SWATCHES) state.swatches.add(c);
        sState = state;
    }

    public static synchronized void save(Context context) {
        State state = state(context);
        state.version++;
        SharedPreferences.Editor editor = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putInt(KEY_VERSION, state.version);
        for (Role role : Role.values()) {
            Integer color = state.overrides.get(role);
            if (color == null) editor.remove(role.name());
            else editor.putString(role.name(), String.format("#%06X", 0xFFFFFF & color));
        }
        editor.putInt(KEY_SWATCH_COUNT, state.swatches.size());
        for (int i = 0; i < state.swatches.size(); i++)
            editor.putString("swatch_" + i, String.format("#%08X", state.swatches.get(i)));
        editor.apply();
    }

    public static void reset(Context context) {
        state(context).overrides.clear();
        save(context);
    }

    public static void setOverride(Context context, Role role, int color) {
        state(context).overrides.put(role, color);
        save(context);
    }

    /**
     * Material dialog builder whose show() tints buttons to follow the
     * palette — dialog theme overlays are static resources the factory
     * cannot reach.
     */
    public static com.google.android.material.dialog.MaterialAlertDialogBuilder themedBuilder(final Context context) {
        return new com.google.android.material.dialog.MaterialAlertDialogBuilder(context) {
            @Override
            public androidx.appcompat.app.AlertDialog show() {
                return showThemed(this, context);
            }
        };
    }

    public static androidx.appcompat.app.AlertDialog showThemed(
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder, Context context) {
        androidx.appcompat.app.AlertDialog dialog = builder.show();
        Integer accent = state(context).overrides.get(Role.ACCENT);
        if (accent != null) {
            android.widget.Button positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            if (positive != null) positive.setTextColor(accent);
        }
        Integer muted = state(context).overrides.get(Role.TEXT_SECONDARY);
        if (muted != null) {
            android.widget.Button negative = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
            if (negative != null) negative.setTextColor(muted);
        }
        return dialog;
    }

    public static Integer override(Context context, Role role) {
        return state(context).overrides.get(role);
    }

    /** True when the given resource entry name belongs to a themeable group. */
    public static Role roleForResource(Context context, int resId) {
        try {
            return RESOURCE_ROLES.get(context.getResources().getResourceEntryName(resId));
        } catch (Exception e) {
            return null;
        }
    }

    /** Color for a themed resource: user override when present, stock otherwise. */
    public static int resolve(Context context, int resId) {
        State state = state(context);
        Role role = roleForResource(context, resId);
        if (role != null && state.overrides.containsKey(role))
            return derive(context, roleForResource(context, resId), resId, state.overrides.get(role));
        return ContextCompat.getColor(context, resId);
    }

    /** Recreate activities whose palette version is stale (call in onResume). */
    public static void checkRecreate(Activity activity) {
        if (activity.getIntent() == null) return;
        int applied = activity.getIntent().getIntExtra("ai_theme_version", -1);
        if (applied >= 0 && applied != state(activity).version) {
            activity.getIntent().putExtra("ai_theme_version", state(activity).version);
            activity.recreate();
        }
    }

    /**
     * Shade variants derive from the base override so one knob retints the
     * whole family: ai_accent_dark follows accent, ai_divider follows border...
     */
    private static int derive(Context context, Role role, int resId, int base) {
        Role baseRole = roleForResource(context, resId);
        if (baseRole != role) return base;
        String entry = context.getResources().getResourceEntryName(resId);
        int surface = state(context).overrides.containsKey(Role.SURFACE)
            ? state(context).overrides.get(Role.SURFACE) : ContextCompat.getColor(context, R.color.ai_surface);
        int accent = state(context).overrides.containsKey(Role.ACCENT)
            ? state(context).overrides.get(Role.ACCENT) : ContextCompat.getColor(context, R.color.ai_accent);
        switch (entry) {
            case "ai_accent_dark": return darken(base, 0.78f);
            case "ai_accent_muted": return blend(base, surface, 0.80f);
            case "ai_accent_glow": return blend(base, surface, 0.62f);
            case "ai_bubble_user": return base;
            case "ai_nav_selected": return blend(base, surface, 0.72f);
            case "ai_background_gradient_end": return blend(base, accent, 0.82f);
            case "ai_background_gradient_start": return base;
            case "ai_drawer_background": return darken(base, 0.92f);
            case "ai_nav_background": return base;
            case "ai_surface_muted": return lighten(base, 1.09f);
            case "ai_surface_elevated": return lighten(base, 1.13f);
            case "ai_bubble_agent": return base;
            case "ai_chip": return lighten(base, 1.09f);
            case "ai_border_subtle": return darken(base, 0.88f);
            case "ai_divider": return darken(base, 0.80f);
            default: return base;
        }
    }

    // ---------------------------------------------------------------- color math

    public static int darken(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= factor;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    public static int lighten(int color, float factor) {
        return darken(color, 1f / factor);
    }

    public static int blend(int color, int onto, float ontoRatio) {
        float ratio = Math.max(0f, Math.min(1f, ontoRatio));
        int a = (int) (Color.alpha(color) + (Color.alpha(onto) - Color.alpha(color)) * ratio);
        int r = (int) (Color.red(color) + (Color.red(onto) - Color.red(color)) * ratio);
        int g = (int) (Color.green(color) + (Color.green(onto) - Color.green(color)) * ratio);
        int b = (int) (Color.blue(color) + (Color.blue(onto) - Color.blue(color)) * ratio);
        return Color.argb(a, r, g, b);
    }

    public static int withAlpha(int color, float alphaRatio) {
        return Color.argb((int) (255 * alphaRatio), Color.red(color), Color.green(color), Color.blue(color));
    }

    // ---------------------------------------------------------------- drawables

    /** Rebuild a named drawable from the live palette, or null to keep stock. */
    public static Drawable drawableFor(Context context, String entry) {
        if (entry == null) return null;
        int bg = resolve(context, R.color.ai_background);
        int surface = resolve(context, R.color.ai_surface);
        int border = resolve(context, R.color.ai_border);
        int accent = resolve(context, R.color.ai_accent);
        int text = resolve(context, R.color.ai_text);
        switch (entry) {
            case "bg_gradient_main": {
                GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{bg, blend(bg, accent, 0.85f)});
                return d;
            }
            case "bg_provider_card":
                return rounded(context, surface, 1f, border, 16f);
            case "bg_bottom_nav":
                return rounded(context, surface, 1f, border, 22f);
            case "bg_ai_user_bubble":
                return rounded(context, accent, 0.92f, 0, 18f);
            case "bg_ai_agent_bubble":
                return rounded(context, surface, 1f, 0, 18f);
            case "bg_ai_harness_badge":
                return circle(context, blend(surface, text, 0.92f));
            case "bg_ai_chip":
                return rounded(context, resolve(context, R.color.ai_chip), 1f, 0, 20f);
            case "bg_ai_suggestion":
                return rounded(context, surface, 1f, border, 12f);
            default:
                return null;
        }
    }

    private static GradientDrawable rounded(Context context, int fill, float alpha, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(withAlpha(fill, alpha));
        d.setCornerRadius(dp(context, radiusDp));
        if (stroke != 0) {
            d.setStroke(dp(context, 1), withAlpha(stroke, 0.9f));
        }
        return d;
    }

    private static GradientDrawable circle(Context context, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setShape(GradientDrawable.OVAL);
        return d;
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- factory

    /**
     * Install the theming LayoutInflater factory. Call AFTER super.onCreate()
     * (so AppCompat's factory is already installed and can be delegated to)
     * and BEFORE setContentView(). Also sets the window background from the
     * palette, which covers themes that reference windowBackground.
     */
    public static void install(final Activity activity) {
        State state = state(activity);
        if (activity.getIntent() != null) activity.getIntent().putExtra("ai_theme_version", state.version);
        Integer bg = state.overrides.get(Role.BACKGROUND);
        if (bg != null) activity.getWindow().setBackgroundDrawable(new ColorDrawable(bg));

        final LayoutInflater.Factory2 delegate = activity.getLayoutInflater().getFactory2();
        activity.getLayoutInflater().setFactory2(new LayoutInflater.Factory2() {
            @Override
            public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
                View view = delegate.onCreateView(parent, name, context, attrs);
                applyTheme(view, context, attrs);
                return view;
            }

            @Override
            public View onCreateView(String name, Context context, AttributeSet attrs) {
                return delegate.onCreateView(null, name, context, attrs);
            }
        });
    }

    private static void applyTheme(View view, Context context, AttributeSet attrs) {
        if (view == null) return;
        TypedArray a = context.obtainStyledAttributes(attrs, READ_ATTRS);
        try {
            for (int i = 0; i < READ_ATTRS.length; i++) {
                if (!a.hasValue(i)) continue;
                TypedValue tv = new TypedValue();
                if (!a.getValue(i, tv)) continue;
                int attr = READ_ATTRS[i];
                if (attr == android.R.attr.background) {
                    applyBackground(view, context, tv);
                } else {
                    Integer color = themedColor(context, tv);
                    if (color == null) continue;
                    if (attr == android.R.attr.textColor) {
                        if (view instanceof TextView) ((TextView) view).setTextColor(color);
                    } else if (attr == android.R.attr.textColorHint) {
                        if (view instanceof TextView) ((TextView) view).setHintTextColor(color);
                    } else if (attr == android.R.attr.backgroundTint) {
                        view.setBackgroundTintList(ColorStateList.valueOf(color));
                    } else if (attr == androidx.appcompat.R.attr.tint
                        || attr == com.google.android.material.R.attr.iconTint) {
                        applyTint(view, color);
                    } else if (attr == com.google.android.material.R.attr.strokeColor) {
                        if (view instanceof com.google.android.material.card.MaterialCardView)
                            ((com.google.android.material.card.MaterialCardView) view).setStrokeColor(color);
                        else if (view instanceof com.google.android.material.button.MaterialButton)
                            ((com.google.android.material.button.MaterialButton) view)
                                .setStrokeColor(ColorStateList.valueOf(color));
                    } else if (attr == com.google.android.material.R.attr.boxStrokeColor) {
                        if (view instanceof com.google.android.material.textfield.TextInputLayout)
                            ((com.google.android.material.textfield.TextInputLayout) view)
                                .setBoxStrokeColor(color);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            a.recycle();
        }
        applyButtonStyle(view, context, attrs);
    }

    /** Filled MaterialButtons pull their fill from the static theme
     * (colorPrimary), which the factory cannot change — retint them and
     * their outlined siblings explicitly. */
    private static void applyButtonStyle(View view, Context context, AttributeSet attrs) {
        if (!(view instanceof com.google.android.material.button.MaterialButton)) return;
        State state = state(context);
        Integer accent = state.overrides.get(Role.ACCENT);
        com.google.android.material.button.MaterialButton button =
            (com.google.android.material.button.MaterialButton) view;
        String styleEntry = styleEntryName(context, attrs);
        boolean outlined = styleEntry != null && styleEntry.contains("OutlinedButton");
        boolean textStyle = styleEntry != null && styleEntry.contains("TextButton");
        if (!outlined && !textStyle) {
            if (accent != null) {
                button.setBackgroundTintList(ColorStateList.valueOf(accent));
                button.setTextColor(0xFFFFFFFF);
            }
        } else if (outlined && accent != null) {
            button.setTextColor(accent);
            if (button.getIcon() != null) button.setIconTint(ColorStateList.valueOf(accent));
        }
    }

    private static String styleEntryName(Context context, AttributeSet attrs) {
        if (!(attrs instanceof org.xmlpull.v1.XmlPullParser)) return null;
        // style="@style/..." carries no namespace prefix; the raw string is enough.
        return ((org.xmlpull.v1.XmlPullParser) attrs).getAttributeValue(null, "style");
    }

    private static void applyTint(View view, int color) {
        if (view instanceof android.widget.ImageView) {
            ((android.widget.ImageView) view).setColorFilter(color);
        } else {
            tintDrawable(view, color);
        }
    }

    private static void applyBackground(View view, Context context, TypedValue tv) {
        if (tv.type == TypedValue.TYPE_REFERENCE) {
            try {
                Resources res = context.getResources();
                String entry = res.getResourceEntryName(tv.data);
                String typeName = res.getResourceTypeName(tv.data);
                if ("color".equals(typeName)) {
                    Role role = RESOURCE_ROLES.get(entry);
                    State state = state(context);
                    if (role != null && state.overrides.containsKey(role)) {
                        view.setBackgroundColor(resolve(context, tv.data));
                    }
                } else if ("drawable".equals(typeName)) {
                    Drawable rebuilt = drawableFor(context, entry);
                    if (rebuilt != null) view.setBackground(rebuilt);
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Map an attribute TypedValue to a palette color. Handles @color resource
     * references and the framework theme attributes used by the settings
     * screens (textColorPrimary etc.); literal colors are left untouched.
     */
    private static Integer themedColor(Context context, TypedValue tv) {
        State state = state(context);
        if (state.overrides.isEmpty()) return null;
        if (tv.type == TypedValue.TYPE_REFERENCE) {
            try {
                String entry = context.getResources().getResourceEntryName(tv.data);
                Role role = RESOURCE_ROLES.get(entry);
                if (role != null && state.overrides.containsKey(role))
                    return resolve(context, tv.data);
            } catch (Exception ignored) {}
            return null;
        }
        if (tv.type == TypedValue.TYPE_ATTRIBUTE) {
            switch (tv.data) {
                case android.R.attr.textColorPrimary: return themed(context, Role.TEXT_PRIMARY, R.color.ai_text);
                case android.R.attr.textColorSecondary: return themed(context, Role.TEXT_SECONDARY, R.color.ai_text_muted);
                case android.R.attr.textColorTertiary: return themed(context, Role.TEXT_SECONDARY, R.color.ai_text_dim);
                default: return null;
            }
        }
        return null;
    }

    private static int themed(Context context, Role role, int fallbackRes) {
        State state = state(context);
        Integer override = state.overrides.get(role);
        return override != null ? override : ContextCompat.getColor(context, fallbackRes);
    }

    private static void tintDrawable(View view, int color) {
        Drawable background = view.getBackground();
        if (background == null) return;
        Drawable mutated = background.mutate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mutated.setColorFilter(new BlendModeColorFilter(color, BlendMode.SRC_IN));
        } else {
            mutated.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        view.setBackground(mutated);
    }
}
