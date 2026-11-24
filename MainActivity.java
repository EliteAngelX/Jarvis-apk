package com.jarvis.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView logView;
    private CommandProcessor testProcessor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        testProcessor = new CommandProcessor(this);
        final SharedPreferences prefs = getSharedPreferences("jarvis", MODE_PRIVATE);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Jarvis");
        title.setTextSize(28);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(4), 0, dp(12));
        root.addView(status);

        root.addView(button("Start Jarvis", v -> startJarvis()));
        root.addView(button("Stop Jarvis", v -> {
            stopService(new Intent(this, JarvisService.class));
            v.postDelayed(this::refresh, 300);
        }));

        final Switch rootSwitch = new Switch(this);
        rootSwitch.setText("Use root (su) for system commands");
        rootSwitch.setPadding(0, dp(12), 0, dp(12));
        rootSwitch.setChecked(prefs.getBoolean("root", false));
        rootSwitch.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("root", checked).apply();
            if (checked) {
                io.execute(() -> {
                    RootShell.reset();
                    final boolean ok = RootShell.hasRoot();
                    runOnUiThread(() -> {
                        if (ok) {
                            Toast.makeText(this, "Root access granted", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Root not available or denied", Toast.LENGTH_LONG).show();
                            rootSwitch.setChecked(false);
                        }
                    });
                });
            }
        });
        root.addView(rootSwitch);

        TextView permTitle = new TextView(this);
        permTitle.setText("Permissions (grant once)");
        permTitle.setTextSize(16);
        root.addView(permTitle);
        root.addView(button("Allow display over other apps (needed to open apps)", v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));
        root.addView(button("Allow modify system settings (brightness, rotation)", v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName())))));
        root.addView(button("Allow Do Not Disturb access", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))));
        root.addView(button("Battery: set Jarvis to unrestricted", v ->
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));

        TextView testTitle = new TextView(this);
        testTitle.setText("Try a command without speaking");
        testTitle.setTextSize(16);
        testTitle.setPadding(0, dp(12), 0, 0);
        root.addView(testTitle);

        final EditText input = new EditText(this);
        input.setHint("e.g. open youtube");
        input.setSingleLine(true);
        root.addView(input);
        root.addView(button("Run command", v -> {
            final String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            io.execute(() -> {
                String reply;
                try {
                    reply = testProcessor.execute(text);
                } catch (Exception e) {
                    reply = "Something went wrong.";
                }
                JarvisLog.add("> " + text + "\n" + reply);
            });
        }));

        TextView examples = new TextView(this);
        examples.setText("Examples:\n"
                + "Jarvis, open WhatsApp\n"
                + "Jarvis, play Kesariya\n"
                + "Jarvis, play shape of you on spotify\n"
                + "Jarvis, pause / next song / volume 60\n"
                + "Jarvis, turn on flashlight\n"
                + "Jarvis, turn off wifi / bluetooth / mobile data\n"
                + "Jarvis, airplane mode on\n"
                + "Jarvis, brightness 30\n"
                + "Jarvis, take a screenshot (root)\n"
                + "Jarvis, reboot (root, asks to confirm)");
        examples.setPadding(0, dp(12), 0, dp(12));
        root.addView(examples);

        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        requestBasePermissions();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        JarvisLog.listener = () -> runOnUiThread(this::refresh);
        refresh();
    }

    @Override
    protected void onPause() {
        JarvisLog.listener = null;
        super.onPause();
    }

    private void startJarvis() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestBasePermissions();
            Toast.makeText(this, "Allow the microphone, then tap Start again", Toast.LENGTH_LONG).show();
            return;
        }
        startForegroundService(new Intent(this, JarvisService.class));
        status.postDelayed(this::refresh, 500);
    }

    private void requestBasePermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    private void refresh() {
        status.setText(JarvisService.running
                ? "Running. Say \"Jarvis\" and then a command."
                : "Stopped. Tap Start Jarvis.");
        logView.setText(JarvisLog.text());
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
