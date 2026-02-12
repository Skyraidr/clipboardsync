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
    private Button floatingBtn;
    private Button permissionBtn;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);

        // --- HEADLESS SHARE HANDLING ---
        // If the app was opened via the Android Share Menu, process the data and close instantly.
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            handleIncomingIntent(intent);
            finish(); // Close immediately to keep the user in the Gallery/Source app
            return;   // Skip drawing the UI completely
        }

        // --- NORMAL UI SETUP ---
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        ipInput = new EditText(this);
        ipInput.setHint("Enter PC IP Address");
        ipInput.setText(prefs.getString("ServerIP", ""));

        saveBtn = new Button(this);
        saveBtn.setText("Save IP");

        manualSendBtn = new Button(this);
        manualSendBtn.setText("📤 SEND CLIPBOARD TO PC NOW");

        floatingBtn = new Button(this);
        floatingBtn.setText("🔵 Enable Floating Button");

        permissionBtn = new Button(this);
        permissionBtn.setText("Allow Display Over Apps (Required)");

        layout.addView(ipInput);
        layout.addView(saveBtn);
        layout.addView(manualSendBtn);
        layout.addView(floatingBtn);
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
                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(settingsIntent);
                }
            }
        });

        floatingBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permission Required!", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent serviceIntent = new Intent(MainActivity.this, FloatingButtonService.class);
            startService(serviceIntent);
            Toast.makeText(this, "Floating Button Started!", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
        });

        // Handle Floating Button intents if the UI did load
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
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

        // If the app was already open in the background and a share intent comes in,
        // process it and push the app back out of the way.
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            handleIncomingIntent(intent);
            moveTaskToBack(true);
            return;
        }

        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        String type = intent.getType();
        String ip = prefs.getString("ServerIP", "");

        // --- 1. HANDLE FLOATING BUTTON AUTO-SEND ---
        if (intent.getBooleanExtra("AUTO_SEND", false)) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!ip.isEmpty()) {
                    performManualSync(ip);
                } else {
                    Toast.makeText(getApplicationContext(), "IP not set!", Toast.LENGTH_SHORT).show();
                }
            }, 500);
            return;
        }

        // --- 2. HANDLE NATIVE "SHARE TO APP" MENU ---
        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if (ip.isEmpty()) {
                Toast.makeText(getApplicationContext(), "Please save PC IP in the app first!", Toast.LENGTH_LONG).show();
                return;
            }

            if ("text/plain".equals(type)) {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    Toast.makeText(getApplicationContext(), "Sending shared text...", Toast.LENGTH_SHORT).show();
                    sendToPcNetwork(ip, 1, sharedText.getBytes());
                }
            }
            else if (type.startsWith("image/") || type.startsWith("video/")) {
                Uri fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (fileUri != null) {
                    int sendType = type.startsWith("video/") ? 3 : 2;
                    Toast.makeText(getApplicationContext(), "Sending shared media...", Toast.LENGTH_SHORT).show();

                    // Read and send in background thread
                    new Thread(() -> {
                        byte[] data = getBytesFromUri(fileUri);
                        if (data != null) {
                            sendToPcNetwork(ip, sendType, data);
                        } else {
                            new Handler(Looper.getMainLooper()).post(() ->
                                    Toast.makeText(getApplicationContext(), "Failed to read file", Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                }
            }
        }
    }

    // --- READS THE CLIPBOARD MANUALLY ---
    private void performManualSync(String ip) {
        if (ip.isEmpty()) {
            Toast.makeText(getApplicationContext(), "Save IP first", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(getApplicationContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show();
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
                    Uri uri = item.getUri();
                    String mimeType = getContentResolver().getType(uri);

                    if (mimeType != null && mimeType.startsWith("video/")) {
                        type = 3; // Video
                    } else {
                        type = 2; // Default to Image
                    }
                    data = getBytesFromUri(uri);
                }

                if (data != null) {
                    sendToPcNetwork(ip, type, data);
                }

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(getApplicationContext(), "Read Failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    // --- REUSABLE NETWORK SENDER ---
    private void sendToPcNetwork(String ip, int type, byte[] data) {
        new Thread(() -> {
            try {
                Socket socket = new Socket(ip, 8888);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                out.writeInt(type);
                out.writeInt(data.length);
                out.write(data);
                out.flush();
                socket.close();

                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(getApplicationContext(), "Sent to PC!", Toast.LENGTH_SHORT).show()
                );
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(getApplicationContext(), "Network Failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
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