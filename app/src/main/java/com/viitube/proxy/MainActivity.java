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

    /** Keeps "Select in app" available only while an Invidious instance is entered. */
    private static final class InstanceWatcher implements android.text.TextWatcher {
        private final RadioButton select;
        private final RadioGroup modes;

        InstanceWatcher(RadioButton select, RadioGroup modes) {
            this.select = select;
            this.modes = modes;
        }

        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) { }

        @Override
        public void afterTextChanged(android.text.Editable text) {
            boolean hasInstance = text.toString().trim().length() > 0;
            select.setEnabled(hasInstance);
            if (!hasInstance && select.isChecked()) modes.check(101);
        }
    }

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

        TextView invidiousLabel = new TextView(this);
        invidiousLabel.setText("Invidious instance");
        layout.addView(invidiousLabel);

        TextView invidiousHint = new TextView(this);
        invidiousHint.setText("Optional. With an instance such as inv.nadeko.net, playback links "
                + "come from it, which gives 720p with sound in one stream. Leave empty to use "
                + "YouTube directly.");
        layout.addView(invidiousHint);

        final EditText invidious = new EditText(this);
        invidious.setSingleLine(true);
        invidious.setHint("https://instance.example (optional)");
        invidious.setText(config.getString("invidious_instance", ""));
        layout.addView(invidious);

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
        final RadioButton playbackSelect = new RadioButton(this);
        playbackSelect.setId(105);
        playbackSelect.setText("Select in app (every quality the Invidious instance has)");
        playbackModes.addView(playbackSelect);
        String configuredMode = config.getString("playback_mode", "360p");
        int configuredId = "video1080".equals(configuredMode) ? 102
                : "audio".equals(configuredMode) ? 103 : "mux720".equals(configuredMode) ? 104
                : "select".equals(configuredMode) ? 105 : 101;
        // Choosing in the app needs the instance: the qualities above 360p are muxed from it.
        playbackSelect.setEnabled(!invidious.getText().toString().trim().isEmpty());
        invidious.addTextChangedListener(new InstanceWatcher(playbackSelect, playbackModes));
        playbackModes.check(configuredId);
        final boolean[] restoringPlaybackMode = new boolean[]{false};
        playbackModes.setOnCheckedChangeListener((group, checkedId) -> {
            if (restoringPlaybackMode[0]) return;
            String previousMode = config.getString("playback_mode", "360p");
            String mode = checkedId == 102 ? "video1080" : checkedId == 103 ? "audio"
                    : checkedId == 104 ? "mux720" : checkedId == 105 ? "select" : "360p";
            config.setString("playback_mode", mode);
            try {
                config.save();
            } catch (java.io.IOException e) {
                config.setString("playback_mode", previousMode);
                int previousId = "video1080".equals(previousMode) ? 102
                        : "audio".equals(previousMode) ? 103 : "mux720".equals(previousMode) ? 104
                        : "select".equals(previousMode) ? 105 : 101;
                restoringPlaybackMode[0] = true;
                playbackModes.check(previousId);
                restoringPlaybackMode[0] = false;
                android.widget.Toast.makeText(this, "Could not save playback format",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(playbackModes);

        final android.widget.CheckBox comments = new android.widget.CheckBox(this);
        comments.setText("Show comments under the video (apps from version 12)");
        comments.setChecked(config.getBool("show_comments", false));
        layout.addView(comments);

        TextView commentsHint = new TextView(this);
        commentsHint.setText("The 2017 apps have no place of their own for comments, so they are "
                + "listed under the video. Loading them also makes a watch page a little slower.");
        layout.addView(commentsHint);

        Button save = new Button(this);
        save.setText("Save settings");
        save.setOnClickListener(v -> {
            config.setYouTubeApiKey(apiKey.getText().toString());
            config.setBool("show_comments", comments.isChecked());
            String instance = invidious.getText().toString().trim();
            config.setString("invidious_instance", instance);
            if (instance.isEmpty() && "select".equals(config.getString("playback_mode", "360p"))) {
                config.setString("playback_mode", "360p");
                playbackModes.check(101);
            }
            try {
                config.save();
                android.widget.Toast.makeText(this, "Settings saved", android.widget.Toast.LENGTH_SHORT).show();
            } catch (java.io.IOException e) {
                android.widget.Toast.makeText(this, "Could not save settings", android.widget.Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(save);

        final Button clearCache = new Button(this);
        clearCache.setText("Clear cache");
        clearCache.setOnClickListener(v -> {
            clearCache.setEnabled(false);
            clearCache.setText("Clearing cache...");
            // Deleting the files can take a moment on an old phone, so not on the UI thread.
            new Thread(() -> {
                final CacheReset.Result result = CacheReset.clear(getApplicationContext());
                runOnUiThread(() -> {
                    clearCache.setText("Clear cache");
                    clearCache.setEnabled(true);
                    android.widget.Toast.makeText(this, result.describe(),
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }, "clear-cache").start();
        });
        layout.addView(clearCache);

        TextView clearCacheHint = new TextView(this);
        clearCacheHint.setText("Forgets the feeds, videos and search results kept on this phone. "
                + "Use it when something shows outdated or broken data; settings are kept.");
        layout.addView(clearCacheHint);

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

        // The settings do not fit on a small screen, so they scroll; without this the buttons at
        // the bottom cannot be reached.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
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
