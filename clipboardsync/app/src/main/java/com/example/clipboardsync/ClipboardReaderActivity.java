package com.example.clipboardsync;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * This transparent activity is launched by the Service to legally access the clipboard.
 * It uses the "Input Focus" hack (SOFT_INPUT_STATE_ALWAYS_VISIBLE) to force Android
 * to recognize it as the active foreground app for the split second needed to read data.
 */
public class ClipboardReaderActivity extends Activity {

    private boolean hasAttemptedRead = false;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // FIX 1: The "Input Focus" Hack.
        // Requesting the keyboard (SOFT_INPUT_STATE_ALWAYS_VISIBLE) forces the WindowManager
        // to treat this window as the primary input target, unlocking clipboard access.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        // Standard flags to keep app alive/visible to system
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        // FIX 2: Standard Layout Hierarchy
        // Using a FrameLayout helps ensure the window is attached correctly.
        FrameLayout layout = new FrameLayout(this);

        // 1x1 Pixel View (Technically visible, practically invisible)
        View view = new View(this);
        view.setBackgroundColor(Color.argb(1, 0, 0, 0));
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
        params.gravity = Gravity.TOP | Gravity.START;
        layout.addView(view, params);

        setContentView(layout);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // FIX 3: Trigger read on Resume (Foreground State)
        // We use a handler to allow the window 'focus' animation to settle for 1 frame.
        if (!hasAttemptedRead) {
            hasAttemptedRead = true;
            new Handler(Looper.getMainLooper()).postDelayed(this::attemptRead, 100);
        }
    }

    private void attemptRead() {
        if (performRead()) {
            finishAndRemoveTask();
        } else {
            if (retryCount < MAX_RETRIES) {
                retryCount++;
                // Retry every 100ms
                new Handler(Looper.getMainLooper()).postDelayed(this::attemptRead, 100);
            } else {
                // If we fail after 1 second, give up to avoid stuck window
                finishAndRemoveTask();
            }
        }
    }

    private boolean performRead() {
        // Note: We skip the hasWindowFocus() check here because on some devices,
        // the soft-input hack grants permission before the focus flag updates.

        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) return false;

        try {
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                ClipData.Item item = clip.getItemAt(0);

                Intent serviceIntent = new Intent(this, ClipboardAccessibilityService.class);
                serviceIntent.setAction("SYNC_CLIPBOARD");

                if (item.getText() != null) {
                    serviceIntent.putExtra("TYPE", 1);
                    serviceIntent.putExtra("DATA_TEXT", item.getText().toString());
                    startService(serviceIntent);
                    return true;
                } else if (item.getUri() != null) {
                    byte[] imageBytes = getBytesFromUri(item.getUri());
                    if (imageBytes != null && imageBytes.length < 500000) {
                        serviceIntent.putExtra("TYPE", 2);
                        serviceIntent.putExtra("DATA_BYTES", imageBytes);
                        startService(serviceIntent);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // SecurityException is expected if we are still too fast. Retry loop handles it.
            e.printStackTrace();
        }
        return false;
    }

    private byte[] getBytesFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream()) {
            int bufferSize = 1024;
            byte[] buffer = new byte[bufferSize];
            int len;
            while ((len = is.read(buffer)) != -1) {
                byteBuffer.write(buffer, 0, len);
            }
            return byteBuffer.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}