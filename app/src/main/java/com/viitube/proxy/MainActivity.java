package com.viitube.proxy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

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

        final Switch proxySwitch = new Switch(this);
        proxySwitch.setText("Proxy enabled");
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
    }
}
