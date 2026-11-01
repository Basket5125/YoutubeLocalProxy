package com.viitube.proxy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The settings of the proxy. The first screen is the list of categories, and each of them opens
 * a page of its own with the options under it, so no screen is a wall of text. The views are
 * built in code because the app runs on Android 2.3, where the preference screens of later
 * versions are not available.
 */
public class SettingsActivity extends Activity {

    /** Which page to show; without it the screen is the list of categories. */
    static final String EXTRA_CATEGORY = "com.viitube.proxy.CATEGORY";

    private static final String PLAYBACK = "playback";
    private static final String WATCH = "watch";
    private static final String DATA = "data";
    private static final String STORAGE = "storage";

    /** Ids of the playback formats, as the radio buttons use them. */
    private static final int MODE_360P = 101;
    private static final int MODE_1080P = 102;
    private static final int MODE_AUDIO = 103;
    private static final int MODE_MUX720 = 104;
    private static final int MODE_SELECT = 105;

    /** Ids of the audio-quality radio buttons. */
    private static final int AUDIO_HIGH = 201;
    private static final int AUDIO_NORMAL = 202;
    private static final int AUDIO_LOW = 203;

    /**
     * Keeps "Select in app" available only while there is something to play the qualities above
     * 360p from: an Invidious instance, or the token this phone mints for YouTube's own streams.
     */
    private static final class InstanceWatcher implements TextWatcher {
        private final RadioButton select;
        private final RadioGroup modes;
        private final CheckBox token;

        InstanceWatcher(RadioButton select, RadioGroup modes, CheckBox token) {
            this.select = select;
            this.modes = modes;
            this.token = token;
        }

        boolean possible(String instance) {
            return instance.trim().length() > 0;
        }

        void update(String instance) {
            boolean possible = possible(instance);
            select.setEnabled(possible);
            if (!possible && select.isChecked()) modes.check(MODE_360P);
        }

        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) { }

        @Override
        public void afterTextChanged(Editable text) {
            update(text.toString());
        }
    }

    /** Turning the token on is the other way to make "Select in app" available. */
    private static final class TokenWatcher
            implements android.widget.CompoundButton.OnCheckedChangeListener {
        private final InstanceWatcher watcher;
        private final EditText instance;

        TokenWatcher(InstanceWatcher watcher, EditText instance) {
            this.watcher = watcher;
            this.instance = instance;
        }

        @Override
        public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
            watcher.update(instance.getText().toString());
        }
    }

    /**
     * The listeners and the thread are named classes on purpose: D8 4.0.52, the dexer of this
     * build, fails with a NullPointerException on an anonymous class that holds on to the
     * activity around it.
     */
    private static final class OpenCategory implements View.OnClickListener {
        private final Activity screen;
        private final String category;

        OpenCategory(Activity screen, String category) {
            this.screen = screen;
            this.category = category;
        }

        @Override
        public void onClick(View view) {
            Intent intent = new Intent(screen, SettingsActivity.class);
            intent.putExtra(EXTRA_CATEGORY, category);
            screen.startActivity(intent);
        }
    }

    private static final class SaveAndClose implements View.OnClickListener {
        private final SettingsActivity screen;

        SaveAndClose(SettingsActivity screen) {
            this.screen = screen;
        }

        @Override
        public void onClick(View view) {
            if (screen.apply()) {
                Toast.makeText(screen, "Settings saved", Toast.LENGTH_SHORT).show();
                screen.finish();
            }
        }
    }

    private static final class ClearCache implements View.OnClickListener {
        private final SettingsActivity screen;
        private final Button button;

        ClearCache(SettingsActivity screen, Button button) {
            this.screen = screen;
            this.button = button;
        }

        @Override
        public void onClick(View view) {
            screen.clearCache(button);
        }
    }

    /** Deleting the files can take a moment on an old phone, so not on the interface thread. */
    private static final class ClearCacheTask implements Runnable {
        private final SettingsActivity screen;
        private final Button button;

        ClearCacheTask(SettingsActivity screen, Button button) {
            this.screen = screen;
            this.button = button;
        }

        @Override
        public void run() {
            CacheReset.Result result = CacheReset.clear(screen.getApplicationContext());
            screen.runOnUiThread(new ClearCacheDone(screen, button, result));
        }
    }

    private static final class ClearCacheDone implements Runnable {
        private final SettingsActivity screen;
        private final Button button;
        private final CacheReset.Result result;

        ClearCacheDone(SettingsActivity screen, Button button, CacheReset.Result result) {
            this.screen = screen;
            this.button = button;
            this.result = result;
        }

        @Override
        public void run() {
            button.setText("Clear cache");
            button.setEnabled(true);
            Toast.makeText(screen, result.describe(), Toast.LENGTH_SHORT).show();
        }
    }

    private Config config;
    private float density;
    private String category;
    private EditText apiKey;
    private EditText invidious;
    private RadioGroup playbackModes;
    private RadioGroup audioQuality;
    private CheckBox comments;
    private CheckBox captions;
    private CheckBox poToken;
    private CheckBox preload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = Config.get(this);
        density = getResources().getDisplayMetrics().density;
        category = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_CATEGORY);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        screen.setPadding(padding, padding, padding, padding);

        if (category == null) {
            setTitle("Settings");
            categories(screen);
        } else if (PLAYBACK.equals(category)) {
            setTitle("Playback");
            playback(screen);
            saveButton(screen);
        } else if (WATCH.equals(category)) {
            setTitle("Watch page");
            watchPage(screen);
            saveButton(screen);
        } else if (DATA.equals(category)) {
            setTitle("Data");
            data(screen);
            saveButton(screen);
        } else {
            setTitle("Storage");
            storage(screen);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(screen);
        setContentView(scroll);
    }

    /** What is typed in is kept when the page is left by the back key as well. */
    @Override
    protected void onPause() {
        super.onPause();
        if (category != null && !STORAGE.equals(category) && !isFinishing()) apply();
    }

    // ------------------------------------------------------------------ the first screen

    private void categories(LinearLayout screen) {
        TextView hint = hint("What the proxy serves to the YouTube apps, and what it keeps on "
                + "this phone.");
        hint.setPadding(0, 0, 0, dp(6));
        screen.addView(hint);

        row(screen, PLAYBACK, "Playback", android.R.drawable.ic_media_play,
                "Which file the apps play, and where it comes from.");
        row(screen, WATCH, "Watch page", android.R.drawable.ic_menu_agenda,
                "What the apps from version 12 get under a video.");
        row(screen, DATA, "Data", android.R.drawable.ic_menu_upload,
                "Where the feeds and the video details come from.");
        row(screen, STORAGE, "Storage", android.R.drawable.ic_menu_delete,
                "What the proxy keeps on this phone.");
    }

    /** One category of the first screen: its icon, its name and what is inside it. */
    private void row(LinearLayout screen, String id, String name, int icon, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(14), dp(4), dp(14));
        row.setClickable(true);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setOnClickListener(new OpenCategory(this, id));

        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        LinearLayout.LayoutParams imageSize = new LinearLayout.LayoutParams(dp(28), dp(28));
        imageSize.rightMargin = dp(14);
        imageSize.topMargin = dp(2);
        row.addView(image, imageSize);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(17);
        texts.addView(title);
        texts.addView(hint(description));
        row.addView(texts);
        screen.addView(row);

        View line = new View(this);
        line.setBackgroundColor(0x33888888);
        screen.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
    }

    private void saveButton(LinearLayout screen) {
        Button save = new Button(this);
        save.setText("Save settings");
        save.setOnClickListener(new SaveAndClose(this));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        size.topMargin = dp(18);
        screen.addView(save, size);
    }

    // ------------------------------------------------------------------ the category pages

    private void playback(LinearLayout screen) {
        playbackModes = new RadioGroup(this);
        playbackModes.setOrientation(LinearLayout.VERTICAL);
        playbackModes.addView(radio(MODE_360P, "360p video with audio"));
        playbackModes.addView(radio(MODE_1080P, "1080p video only (no audio)"));
        playbackModes.addView(radio(MODE_AUDIO, "Audio only (highest available quality)"));
        playbackModes.addView(radio(MODE_MUX720, "720p video with audio (muxed on this phone)"));
        RadioButton select = radio(MODE_SELECT, "Select in app (every quality there is)");
        playbackModes.addView(select);
        option(screen, "Playback format", playbackModes,
                "One file straight from YouTube is what the old apps play best. The qualities "
                        + "above 360p are put together on this phone out of an Invidious "
                        + "instance's streams, so they need one below; \"Select in app\" lists "
                        + "them all and leaves the choice to the app's own quality menu.");

        invidious = new EditText(this);
        invidious.setSingleLine(true);
        invidious.setHint("https://instance.example (optional)");
        invidious.setText(config.getString("invidious_instance", ""));
        option(screen, "Invidious instance", invidious,
                "With an instance set, the playback links come from it, which is what makes 720p "
                        + "with sound in one file possible. Its links also keep working past the "
                        + "first minute, which YouTube's own no longer do. Public instances often "
                        + "refuse requests from outside their network, so one of your own is the "
                        + "reliable choice. Empty means YouTube directly.");

        boolean canMint = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M;
        poToken = new CheckBox(this);
        poToken.setText(canMint ? "Mint a proof-of-origin token"
                : "Mint a proof-of-origin token (needs Android 6)");
        poToken.setChecked(canMint && config.getBool("po_token", false));
        poToken.setEnabled(canMint);
        option(screen, null, poToken,
                "YouTube serves only the first minute of a video to a client it cannot place. "
                        + "The token that vouches for one is made by a program of YouTube's own "
                        + "that runs in a browser, so the proxy runs it in a hidden WebView and "
                        + "sends it with the requests of the browser client. Android 6 and newer "
                        + "only. Off to begin with: the links that token unlocks are signed in a "
                        + "way this proxy cannot undo yet, so it changes nothing for playback "
                        + "today, and everything else works the same without it.");

        String mode = config.getString("playback_mode", "360p");
        // Choosing in the app needs a source for the qualities above 360p: an instance, or the
        // token, with which YouTube's own streams can be read to the end again.
        InstanceWatcher watcher = new InstanceWatcher(select, playbackModes, poToken);
        select.setEnabled(watcher.possible(invidious.getText().toString()));
        invidious.addTextChangedListener(watcher);
        poToken.setOnCheckedChangeListener(new TokenWatcher(watcher, invidious));
        playbackModes.check(idOfMode(mode));

        audioQuality = new RadioGroup(this);
        audioQuality.setOrientation(LinearLayout.VERTICAL);
        audioQuality.addView(radio(AUDIO_HIGH, "High (best available)"));
        audioQuality.addView(radio(AUDIO_NORMAL, "Normal (~128 kbps)"));
        audioQuality.addView(radio(AUDIO_LOW, "Low (~48 kbps, saves data)"));
        audioQuality.check(idOfQuality(config.getString("audio_quality", "high")));
        option(screen, "Audio quality", audioQuality,
                "The bitrate of the audio the proxy serves for music and for background play "
                        + "(Play Music and audio-only). Higher sounds better; lower uses less "
                        + "data and starts a touch faster.");
    }

    private void watchPage(LinearLayout screen) {
        comments = new CheckBox(this);
        comments.setText("Show comments under the video");
        comments.setChecked(config.getBool("show_comments", true));
        option(screen, null, comments,
                "The comments follow the related videos, under a line of their own: the related "
                        + "videos then end after one page and scrolling on goes through the "
                        + "comments instead. Loading them makes a watch page a little slower.");

        captions = new CheckBox(this);
        captions.setText("Offer subtitles");
        captions.setChecked(config.getBool("show_captions", true));
        option(screen, null, captions,
                "The subtitle tracks of a video are named in the answer and served by this "
                        + "proxy, so the apps can turn them on from the player. Off means the "
                        + "player is told the video has none.");

        preload = new CheckBox(this);
        preload.setText("Read ahead what comes next");
        preload.setChecked(config.getBool("preload", false));
        option(screen, null, preload,
                "Guesses what will be opened next and reads it before it is asked for: the page "
                        + "and the stream of the video that follows - the next entry of a "
                        + "playlist, or the first related video - and the newest uploads of the "
                        + "channel of the video being watched. It costs a few requests in the "
                        + "background and makes the next video open at once.");

        TextView autoplay = hint("Playing the next video by itself is not a setting here: it is "
                + "the Autoplay switch of the app itself, in the \"Up next\" line above the "
                + "related videos.");
        autoplay.setPadding(0, dp(14), 0, 0);
        screen.addView(autoplay);
    }

    private void data(LinearLayout screen) {
        apiKey = new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setHint("API key (optional)");
        apiKey.setText(config.getYouTubeApiKey());
        option(screen, "YouTube Data API v3 key", apiKey,
                "Optional. With a key of your own the popular feeds come from the Data API, and "
                        + "upload descriptions and exact view counts are filled in. Without one "
                        + "everything is read from the same pages the apps would read.");
    }

    private void storage(LinearLayout screen) {
        Button clear = new Button(this);
        clear.setText("Clear cache");
        clear.setOnClickListener(new ClearCache(this, clear));
        option(screen, null, clear,
                "Forgets the feeds, videos, search results, comments and playback links kept "
                        + "here, and what was searched for, which the home feed is built out of. "
                        + "Use it when something shows outdated or broken data; the settings are "
                        + "kept and the next request simply asks YouTube again.");
    }

    // ------------------------------------------------------------------ saving

    /** Writes the settings of the page that is open; false when the file could not be written. */
    boolean apply() {
        if (DATA.equals(category)) config.setYouTubeApiKey(apiKey.getText().toString());
        if (WATCH.equals(category)) {
            config.setBool("show_comments", comments.isChecked());
            config.setBool("show_captions", captions.isChecked());
            config.setBool("preload", preload.isChecked());
        }
        if (PLAYBACK.equals(category)) {
            config.setBool("po_token", poToken.isChecked());
            String instance = invidious.getText().toString().trim();
            config.setString("invidious_instance", instance);
            String mode = modeOfId(playbackModes.getCheckedRadioButtonId());
            // Choosing in the app needs an instance or the token; without either there is
            // nothing to play the qualities above 360p from.
            if (instance.isEmpty() && !poToken.isChecked() && "select".equals(mode)) {
                mode = "360p";
                playbackModes.check(MODE_360P);
            }
            config.setString("playback_mode", mode);
            config.setString("audio_quality", qualityOfId(audioQuality.getCheckedRadioButtonId()));
        }
        try {
            config.save();
            return true;
        } catch (java.io.IOException e) {
            Toast.makeText(this, "Could not save the settings", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    void clearCache(Button button) {
        button.setEnabled(false);
        button.setText("Clearing cache...");
        new Thread(new ClearCacheTask(this, button), "clear-cache").start();
    }

    private static String modeOfId(int id) {
        switch (id) {
            case MODE_1080P: return "video1080";
            case MODE_AUDIO: return "audio";
            case MODE_MUX720: return "mux720";
            case MODE_SELECT: return "select";
            default: return "360p";
        }
    }

    private static int idOfMode(String mode) {
        if ("video1080".equals(mode)) return MODE_1080P;
        if ("audio".equals(mode)) return MODE_AUDIO;
        if ("mux720".equals(mode)) return MODE_MUX720;
        if ("select".equals(mode)) return MODE_SELECT;
        return MODE_360P;
    }

    private static String qualityOfId(int id) {
        if (id == AUDIO_NORMAL) return "normal";
        if (id == AUDIO_LOW) return "low";
        return "high";
    }

    private static int idOfQuality(String quality) {
        if ("normal".equals(quality)) return AUDIO_NORMAL;
        if ("low".equals(quality)) return AUDIO_LOW;
        return AUDIO_HIGH;
    }

    // ------------------------------------------------------------------ the pieces of a page

    /** One option: its name, the thing itself, and a line under it saying what it does. */
    private void option(LinearLayout screen, String name, View control, String description) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(6));
        if (name != null) {
            TextView label = new TextView(this);
            label.setText(name);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            box.addView(label);
        }
        box.addView(control);
        box.addView(hint(description));
        screen.addView(box);
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        // Grey enough to read as a note on a light and on a dark screen alike.
        view.setTextColor(0xFF888888);
        return view;
    }

    private RadioButton radio(int id, String text) {
        RadioButton button = new RadioButton(this);
        button.setId(id);
        button.setText(text);
        return button;
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }
}
