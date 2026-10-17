package com.viitube.proxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.ToggleButton;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Config config = Config.get(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        TextView label = new TextView(this);
        label.setText("YouTube Proxy");
        layout.addView(label);

        final ToggleButton proxySwitch = new ToggleButton(this);
        proxySwitch.setTextOn("Proxy enabled");
        proxySwitch.setTextOff("Proxy disabled");
        proxySwitch.setChecked(config.getBool("proxy_enabled", true));
        layout.addView(proxySwitch);

        TextView apiKeyLabel = new TextView(this);
        apiKeyLabel.setText("YouTube Data API v3 key");
        layout.addView(apiKeyLabel);

        TextView apiKeyHint = new TextView(this);
        apiKeyHint.setText("Optional. Used to fetch upload descriptions and accurate view counts.");
        layout.addView(apiKeyHint);

        final EditText apiKey = new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setHint("API key (optional)");
        apiKey.setText(config.getYouTubeApiKey());
        layout.addView(apiKey);

        TextView playbackLabel = new TextView(this);
        playbackLabel.setText("Playback format");
        layout.addView(playbackLabel);

        RadioGroup playbackModes = new RadioGroup(this);
        playbackModes.setOrientation(LinearLayout.VERTICAL);
        RadioButton playback360 = new RadioButton(this);
        playback360.setId(101);
        playback360.setText("360p video with audio");
        playbackModes.addView(playback360);
        RadioButton playback1080 = new RadioButton(this);
        playback1080.setId(102);
        playback1080.setText("1080p video only (no audio)");
        playbackModes.addView(playback1080);
        RadioButton playbackAudio = new RadioButton(this);
        playbackAudio.setId(103);
        playbackAudio.setText("Audio only (highest available quality)");
        playbackModes.addView(playbackAudio);
        RadioButton playbackMux = new RadioButton(this);
        playbackMux.setId(104);
        playbackMux.setText("720p video with audio (experimental, muxed on this phone)");
        playbackModes.addView(playbackMux);
        String configuredMode = config.getString("playback_mode", "360p");
        int configuredId = "video1080".equals(configuredMode) ? 102
                : "audio".equals(configuredMode) ? 103 : "mux720".equals(configuredMode) ? 104 : 101;
        playbackModes.check(configuredId);
        final boolean[] restoringPlaybackMode = new boolean[]{false};
        playbackModes.setOnCheckedChangeListener((group, checkedId) -> {
            if (restoringPlaybackMode[0]) return;
            String previousMode = config.getString("playback_mode", "360p");
            String mode = checkedId == 102 ? "video1080" : checkedId == 103 ? "audio"
                    : checkedId == 104 ? "mux720" : "360p";
            config.setString("playback_mode", mode);
            try {
                config.save();
            } catch (java.io.IOException e) {
                config.setString("playback_mode", previousMode);
                int previousId = "video1080".equals(previousMode) ? 102
                        : "audio".equals(previousMode) ? 103 : "mux720".equals(previousMode) ? 104 : 101;
                restoringPlaybackMode[0] = true;
                playbackModes.check(previousId);
                restoringPlaybackMode[0] = false;
                android.widget.Toast.makeText(this, "Could not save playback format",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(playbackModes);

        Button save = new Button(this);
        save.setText("Save settings");
        save.setOnClickListener(v -> {
            config.setYouTubeApiKey(apiKey.getText().toString());
            try {
                config.save();
                android.widget.Toast.makeText(this, "Settings saved", android.widget.Toast.LENGTH_SHORT).show();
            } catch (java.io.IOException e) {
                android.widget.Toast.makeText(this, "Could not save settings", android.widget.Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(save);

        proxySwitch.setOnCheckedChangeListener((button, enabled) -> {
            config.setBool("proxy_enabled", enabled);
            try {
                config.save();
                if (enabled) startService(new Intent(MainActivity.this, ProxyService.class));
                else stopService(new Intent(MainActivity.this, ProxyService.class));
            } catch (java.io.IOException e) {
                proxySwitch.setChecked(!enabled);
                android.widget.Toast.makeText(this, "Could not update proxy state",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });

        if (proxySwitch.isChecked()) {
            startService(new Intent(MainActivity.this, ProxyService.class));
        }

        setContentView(layout);
        if (CrashReporter.hasPendingCrash(this)) {
            new AlertDialog.Builder(this)
                .setTitle("The app encountered a problem")
                .setMessage("Would you like to send an anonymous crash report? It includes crash details, YouTube request and response logs, your device model, and Android version. The hosting provider may see your IP address when the report is sent. No report is sent unless you tap Send logs.")
                .setPositiveButton("Send logs", (dialog, which) -> {
                android.widget.Toast.makeText(this, "Sending crash report...",
                    android.widget.Toast.LENGTH_SHORT).show();
                new Thread(() -> CrashReporter.upload(this, error -> runOnUiThread(() -> {
                    String message = error == null ? "Crash report sent. Thank you."
                        : "Could not send crash report: " + error.getClass().getSimpleName()
                                + (error.getMessage() == null ? "" : " (" + error.getMessage() + ")");
                    android.widget.Toast.makeText(this, message,
                            android.widget.Toast.LENGTH_LONG).show();
                }))).start();
                })
                .setNegativeButton("Not now", null)
                .show();
        }
    }
}
