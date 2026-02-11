package com.example.clipboardsync;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

public class FloatingButtonService extends Service {

    private WindowManager windowManager;
    private ImageView floatingButton;
    private WindowManager.LayoutParams params;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 1. Create the floating view (A simple Send Icon)
        floatingButton = new ImageView(this);
        floatingButton.setImageResource(android.R.drawable.ic_menu_send);
        floatingButton.setBackgroundColor(Color.parseColor("#DDFFFFFF")); // Semi-transparent white
        floatingButton.setPadding(20, 20, 20, 20);

        // Make it circular (Optional visual tweak)
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shape.setColor(Color.parseColor("#AA000000")); // Dark background
        floatingButton.setBackground(shape);
        floatingButton.setColorFilter(Color.WHITE);

        // 2. Set Layout Parameters (Always on top)
        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                150, 150, // Width, Height
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // Don't steal focus unless clicked
                PixelFormat.TRANSLUCENT);

        // Initial Position
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 300;

        // 3. Add to Window
        windowManager.addView(floatingButton, params);

        // 4. Click Listener -> Launch App & Sync
        floatingButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("AUTO_SEND", true); // Tell Main Activity to sync immediately
            startActivity(intent);
        });

        // 5. Drag Logic (Optional: allows moving the button)
        floatingButton.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        // Detect Click vs Drag
                        if (Math.abs(event.getRawX() - initialTouchX) < 10 &&
                                Math.abs(event.getRawY() - initialTouchY) < 10) {
                            v.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingButton, params);
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingButton != null) windowManager.removeView(floatingButton);
    }
}