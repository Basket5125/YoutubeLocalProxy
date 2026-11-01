package com.viitube.proxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

/**
 * The screen of the proxy: the switch that runs it and the way to its settings. Everything that
 * can be set lives in {@link SettingsActivity}, so this one stays what it is needed for.
 */
public class MainActivity extends Activity {

    /**
     * The listeners are named classes on purpose: D8 4.0.52, the dexer of this build, fails with
     * a NullPointerException on an anonymous class that holds on to the activity around it.
     */
    private static final class OpenSettings implements View.OnClickListener {
        private final Activity activity;

        OpenSettings(Activity activity) {
            this.activity = activity;
        }

        @Override
        public void onClick(View view) {
            activity.startActivity(new Intent(activity, SettingsActivity.class));
        }
    }

    private static final class SendCrashReport implements DialogInterface.OnClickListener {
        private final MainActivity activity;

        SendCrashReport(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            activity.sendCrashReport();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Config config = Config.get(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (24 * density);
        layout.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("YouTube Proxy");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(20);
        layout.addView(title);

        final ToggleButton proxySwitch = new ToggleButton(this);
        proxySwitch.setTextOn("Proxy enabled");
        proxySwitch.setTextOff("Proxy disabled");
        proxySwitch.setChecked(config.getBool("proxy_enabled", true));
        LinearLayout.LayoutParams switchSize = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (64 * density));
        switchSize.topMargin = (int) (20 * density);
        layout.addView(proxySwitch, switchSize);

        final ToggleButton gpmSwitch = new ToggleButton(this);
        gpmSwitch.setTextOn("Play Music API enabled");
        gpmSwitch.setTextOff("Play Music API disabled");
        gpmSwitch.setChecked(config.getBool("gpm_enabled", false));
        LinearLayout.LayoutParams gpmSize = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (64 * density));
        gpmSize.topMargin = (int) (12 * density);
        layout.addView(gpmSwitch, gpmSize);

        Button settings = new Button(this);
        settings.setText("Settings");
        settings.setOnClickListener(new OpenSettings(this));
        LinearLayout.LayoutParams settingsSize = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        settingsSize.topMargin = (int) (12 * density);
        layout.addView(settings, settingsSize);

        proxySwitch.setOnCheckedChangeListener((button, enabled) -> {
            config.setBool("proxy_enabled", enabled);
            try {
                config.save();
                if (enabled) startService(new Intent(MainActivity.this, ProxyService.class));
                else stopService(new Intent(MainActivity.this, ProxyService.class));
            } catch (java.io.IOException e) {
                proxySwitch.setChecked(!enabled);
                Toast.makeText(this, "Could not update proxy state", Toast.LENGTH_LONG).show();
            }
        });

        gpmSwitch.setOnCheckedChangeListener((button, enabled) -> {
            config.setBool("gpm_enabled", enabled);
            try {
                config.save();
                // The Play Music endpoints are served by the same HTTP server, so a restart is
                // enough to pick the change up (only while the proxy itself is on).
                if (config.getBool("proxy_enabled", true)) {
                    stopService(new Intent(MainActivity.this, ProxyService.class));
                    startService(new Intent(MainActivity.this, ProxyService.class));
                }
            } catch (java.io.IOException e) {
                gpmSwitch.setChecked(!enabled);
                Toast.makeText(this, "Could not update Play Music state", Toast.LENGTH_LONG).show();
            }
        });

        if (proxySwitch.isChecked()) {
            startService(new Intent(MainActivity.this, ProxyService.class));
        }

        setContentView(layout);
        if (CrashReporter.hasPendingCrash(this)) askAboutCrash();
    }

    private void askAboutCrash() {
        new AlertDialog.Builder(this)
                .setTitle("The app encountered a problem")
                .setMessage("Would you like to send an anonymous crash report? It includes crash"
                        + " details, YouTube request and response logs, your device model, and"
                        + " Android version. The hosting provider may see your IP address when the"
                        + " report is sent. No report is sent unless you tap Send logs.")
                .setPositiveButton("Send logs", new SendCrashReport(this))
                .setNegativeButton("Not now", null)
                .show();
    }

    private void sendCrashReport() {
        Toast.makeText(this, "Sending crash report...", Toast.LENGTH_SHORT).show();
        new Thread(() -> CrashReporter.upload(this, error -> runOnUiThread(() -> {
            String message = error == null ? "Crash report sent. Thank you."
                    : "Could not send crash report: " + error.getClass().getSimpleName()
                            + (error.getMessage() == null ? "" : " (" + error.getMessage() + ")");
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }))).start();
    }
}
