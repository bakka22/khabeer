package com.termux.app;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class KhabeerStreamConsumer {
    public interface Sink {
        void onText(String text, boolean isFinal);
        void onToolProgress(String line);
        void onDone();
    }

    private static final String DONE = "__DONE__";
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Sink sink;
    private final long editIntervalMs;
    private final StringBuilder ledger = new StringBuilder();
    private final StringBuilder buffer = new StringBuilder();
    private long lastEmit;
    private volatile boolean closed;
    private int floodStrikes;
    private static final int MAX_FLOOD_STRIKES = 3;

    public KhabeerStreamConsumer(Sink sink, long editIntervalMs) {
        this.sink = sink;
        this.editIntervalMs = editIntervalMs;
    }

    public void onDelta(String text) {
        if (closed || text == null || text.isEmpty()) return;
        queue.offer(text);
        ledger.append(text);
        if (ledger.length() > 20000) ledger.delete(0, ledger.length() - 20000);
    }

    public void start() {
        new Thread(this::loop, "khabeer-stream-consumer").start();
    }

    private void loop() {
        try {
            while (!closed) {
                String chunk = queue.poll(editIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (chunk != null) buffer.append(chunk);
                long now = System.currentTimeMillis();
                boolean timeout = now - lastEmit >= editIntervalMs;
                if (buffer.length() > 0 && (timeout || buffer.length() > 512)) {
                    String text = buffer.toString();
                    buffer.setLength(0);
                    lastEmit = now;
                    handler.post(() -> {
                        try { sink.onText(text, false); }
                        catch (Exception e) { if (++floodStrikes >= MAX_FLOOD_STRIKES) closed = true; }
                    });
                }
                if (chunk == null && queue.isEmpty() && buffer.length() > 0) {
                    String text = buffer.toString();
                    buffer.setLength(0);
                    handler.post(() -> sink.onText(text, false));
                }
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    public void finish(String finalText) {
        closed = true;
        handler.post(() -> {
            if (finalText != null && !finalText.isEmpty() && !ledger.toString().endsWith(finalText))
                sink.onText(finalText, true);
            sink.onDone();
        });
    }

    public String ledger() { return ledger.toString(); }
    public boolean hasDelivered(String text) { return ledger.indexOf(text) >= 0; }
}
