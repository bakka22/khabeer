package com.termux.app;

import android.app.Activity;
import android.view.MotionEvent;

import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.view.KeyboardUtils;
import com.termux.view.TerminalView;

/** Minimal terminal interaction client for the optional technical view. */
public final class AiTerminalViewClient extends TermuxTerminalViewClientBase {

    private final Activity mActivity;
    private final TerminalView mTerminalView;

    public AiTerminalViewClient(Activity activity, TerminalView terminalView) {
        mActivity = activity;
        mTerminalView = terminalView;
    }

    @Override
    public void onSingleTapUp(MotionEvent event) {
        KeyboardUtils.showSoftKeyboard(mActivity, mTerminalView);
    }

    @Override
    public float onScale(float scale) {
        return Math.max(0.8f, Math.min(scale, 1.2f));
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return true;
    }

    @Override
    public void onEmulatorSet() {
        mTerminalView.setTerminalCursorBlinkerState(true, false);
    }
}
