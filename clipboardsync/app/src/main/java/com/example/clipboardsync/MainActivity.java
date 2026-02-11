package com.example.clipboardsync;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {

    private EditText ipInput;
    private Button saveBtn;
    private Button manualSendBtn;
    private Button floatingBtn; // New Button to toggle floating icon
    private Button permissionBtn;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        ipInput = new EditText(this);
        ipInput.setHint("Enter PC IP Address");

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        ipInput.setText(prefs.getString("ServerIP", ""));

        saveBtn = new Button(this);
        saveBtn.setText("Save IP");

        manualSendBtn = new Button(this);
        manualSendBtn.setText("📤 SEND TO PC NOW");

        // --- NEW FLOATING BUTTON CONTROLLER ---
        floatingBtn = new Button(this);
        floatingBtn.setText("🔵 Enable Floating Button");

        permissionBtn = new Button(this);
        permissionBtn.setText("Allow Display Over Apps (Required)");

        layout.addView(ipInput);
        layout.addView(saveBtn);
        layout.addView(manualSendBtn);
        layout.addView(floatingBtn); // Add to layout
        layout.addView(permissionBtn);

        setContentView(layout);

        saveBtn.setOnClickListener(v -> {
            String ip = ipInput.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, "Please enter an IP", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("ServerIP", ip).apply();
            Toast.makeText(this, "IP Saved!", Toast.LENGTH_SHORT).show();
        });

        manualSendBtn.setOnClickListener(v -> performManualSync(prefs.getString("ServerIP", "")));

        permissionBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            }
        });

        // Toggle Floating Service
        floatingBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permission Required!", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent serviceIntent = new Intent(MainActivity.this, FloatingButtonService.class);
            // Simple toggle logic (Stop if running, Start if not - roughly)
            // For simplicity, we just start it. To stop, you'd usually add a 'stop' button or persistent notification.
            startService(serviceIntent);
            Toast.makeText(this, "Floating Button Started!", Toast.LENGTH_SHORT).show();

            // Minimize app to show the button
            moveTaskToBack(true);
        });

        // CHECK IF OPENED VIA FLOATING BUTTON
        handleAutoSend(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Check permissions every time the app comes into view
        updatePermissionUI();
    }

    private void updatePermissionUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                permissionBtn.setText("Display Over Apps: GRANTED ✅");
                permissionBtn.setTextColor(Color.GREEN);
            } else {
                permissionBtn.setText("Allow Display Over Apps (Required) ❌");
                permissionBtn.setTextColor(Color.RED);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutoSend(intent);
    }

    private void handleAutoSend(Intent intent) {
        if (intent != null && intent.getBooleanExtra("AUTO_SEND", false)) {
            // FIX: Add a delay!
            // When the floating button launches the app, it takes a few hundred milliseconds
            // for the window to gain focus. If we read too fast, Android denies access
            // and reports "Clipboard Empty".
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                String ip = prefs.getString("ServerIP", "");
                if (!ip.isEmpty()) {
                    performManualSync(ip);
                    // Optional: Minimize back automatically after sending?
                    // moveTaskToBack(true);
                } else {
                    Toast.makeText(this, "IP not set!", Toast.LENGTH_SHORT).show();
                }
            }, 500); // 500ms delay to ensure focus
        }
    }

    private void performManualSync(String ip) {
        if (ip.isEmpty()) {
            Toast.makeText(this, "Save IP first", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "Clipboard is empty (or access denied)", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);

        new Thread(() -> {
            try {
                int type = 0;
                byte[] data = null;

                if (item.getText() != null) {
                    type = 1; // Text
                    data = item.getText().toString().getBytes("UTF-8");
                } else if (item.getUri() != null) {
                    type = 2; // Image
                    data = getBytesFromUri(item.getUri());
                }

                if (data == null) return;

                Socket socket = new Socket(ip, 8888);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                out.writeInt(type);
                out.writeInt(data.length);
                out.write(data);
                out.flush();
                socket.close();

                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(MainActivity.this, "Sent to PC!", Toast.LENGTH_SHORT).show()
                );

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(MainActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
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