package com.termux.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Classic HSV picker parts: a hue ring (the "circle with all the colours")
 * plus a saturation/brightness square. Pure platform code — no dependencies.
 */
public final class HsvColorPicker {

    private HsvColorPicker() {}

    /** Circular hue wheel: drag on the ring to select hue (0..360). */
    public static class HueRingView extends View {

        public interface OnHueChanged { void onHueChanged(float hue); }

        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mKnob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mKnobStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mHue = 250f;
        private OnHueChanged mListener;
        private float mCenter;
        private float mRadius;
        private float mStroke;

        public HueRingView(Context context) { super(context); init(); }
        public HueRingView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

        private void init() {
            mRing.setStyle(Paint.Style.STROKE);
            mKnob.setStyle(Paint.Style.FILL);
            mKnobStroke.setStyle(Paint.Style.STROKE);
            mKnobStroke.setColor(0xFFFFFFFF);
            mKnobStroke.setStrokeWidth(dp(2));
        }

        public void setOnHueChanged(OnHueChanged listener) { mListener = listener; }

        public float getHue() { return mHue; }

        public void setHue(float hue) {
            mHue = ((hue % 360f) + 360f) % 360f;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            mCenter = Math.min(w, h) / 2f;
            mStroke = dp(26);
            mRadius = mCenter - mStroke / 2f - dp(2);
            int[] colors = new int[13];
            for (int i = 0; i < 12; i++) colors[i] = Color.HSVToColor(new float[]{i * 30f, 1f, 1f});
            colors[12] = colors[0];
            mRing.setShader(new SweepGradient(mCenter, mCenter, colors, null));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawCircle(mCenter, mCenter, mRadius, mRing);
            // SweepGradient starts hue 0 at 3 o'clock and sweeps clockwise,
            // matching screen-space atan2 angles (y grows downward).
            float angle = (float) Math.toRadians(mHue);
            float x = mCenter + mRadius * (float) Math.cos(angle);
            float y = mCenter + mRadius * (float) Math.sin(angle);
            mKnob.setColor(Color.HSVToColor(new float[]{mHue, 1f, 1f}));
            canvas.drawCircle(x, y, mStroke / 2f, mKnob);
            canvas.drawCircle(x, y, mStroke / 2f, mKnobStroke);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float dx = event.getX() - mCenter;
            float dy = event.getY() - mCenter;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            float max = mRadius + mStroke;
            if (event.getAction() == MotionEvent.ACTION_DOWN && distance > max) return false;
            if (distance < mRadius * 0.2f) return false;
            float hue = (float) Math.toDegrees(Math.atan2(dy, dx));
            setHue(hue);
            if (mListener != null) mListener.onHueChanged(mHue);
            if (event.getAction() == MotionEvent.ACTION_UP) performClick();
            return true;
        }

        @Override
        public boolean performClick() { return super.performClick(); }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }

    /** Saturation (x) / brightness (y) square for a fixed hue. */
    public static class SvSquareView extends View {

        public interface OnSvChanged { void onSvChanged(float sat, float value); }

        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mKnob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mKnobStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private float mHue = 250f;
        private float mSat = 1f;
        private float mVal = 1f;
        private OnSvChanged mListener;

        public SvSquareView(Context context) { super(context); init(); }
        public SvSquareView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

        private void init() {
            mKnob.setStyle(Paint.Style.FILL);
            mKnobStroke.setStyle(Paint.Style.STROKE);
            mKnobStroke.setColor(0xFFFFFFFF);
            mKnobStroke.setStrokeWidth(dp(2));
        }

        public void setOnSvChanged(OnSvChanged listener) { mListener = listener; }

        public void setHue(float hue) {
            mHue = hue;
            updateShader();
            invalidate();
        }

        public void setColor(int color) {
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            mHue = hsv[0];
            mSat = hsv[1];
            mVal = hsv[2];
            updateShader();
            invalidate();
        }

        public int getColor() {
            return Color.HSVToColor(new float[]{mHue, mSat, mVal});
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            mRect.set(dp(8), dp(8), w - dp(8), h - dp(8));
            updateShader();
        }

        private void updateShader() {
            int hueColor = Color.HSVToColor(new float[]{mHue, 1f, 1f});
            Shader saturation = new LinearGradient(mRect.left, 0, mRect.right, 0,
                0xFFFFFFFF, hueColor, Shader.TileMode.CLAMP);
            Shader brightness = new LinearGradient(0, mRect.top, 0, mRect.bottom,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            mFill.setShader(new ComposeShader(saturation, brightness, PorterDuff.Mode.SRC_OVER));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawRoundRect(mRect, dp(10), dp(10), mFill);
            float x = mRect.left + mSat * mRect.width();
            float y = mRect.top + (1f - mVal) * mRect.height();
            mKnob.setColor(getColor());
            canvas.drawCircle(x, y, dp(9), mKnob);
            canvas.drawCircle(x, y, dp(9), mKnobStroke);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!mRect.contains(event.getX(), event.getY()) && event.getAction() == MotionEvent.ACTION_DOWN)
                return false;
            float x = Math.max(mRect.left, Math.min(mRect.right, event.getX()));
            float y = Math.max(mRect.top, Math.min(mRect.bottom, event.getY()));
            mSat = (x - mRect.left) / mRect.width();
            mVal = 1f - (y - mRect.top) / mRect.height();
            invalidate();
            if (mListener != null) mListener.onSvChanged(mSat, mVal);
            if (event.getAction() == MotionEvent.ACTION_UP) performClick();
            return true;
        }

        @Override
        public boolean performClick() { return super.performClick(); }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }
}
