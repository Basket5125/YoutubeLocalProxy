package com.viitube.proxy;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What this phone has been looking for. The apps of today fill their home screen with what the
 * account has watched; there is no account here, so the proxy keeps the searches and the videos
 * opened through it and builds the home feed out of those instead.
 *
 * <p>Nothing leaves the phone: the list lives in the app's own files and is only used to decide
 * what to ask YouTube for.
 */
final class TasteProfile {

    private static final String TAG = "Taste";
    /** How many searches are kept, newest first. */
    private static final int MAX_QUERIES = 20;
    /** How many watched videos are kept. */
    private static final int MAX_VIDEOS = 30;
    /** Below this many searches the home feed stays the plain chart. */
    static final int ENOUGH_TO_PROFILE = 3;

    private static TasteProfile instance;

    private final File file;
    private final List<String> queries = new ArrayList<String>();
    private final List<String> channels = new ArrayList<String>();
    private final List<String> videos = new ArrayList<String>();
    private boolean dirty;

    private TasteProfile(Context context) {
        file = new File(context.getFilesDir(), "taste.json");
        read();
    }

    static synchronized TasteProfile get(Context context) {
        if (instance == null) instance = new TasteProfile(context.getApplicationContext());
        return instance;
    }

    /** A search this phone made; the newest one counts the most. */
    synchronized void rememberSearch(String query) {
        if (query == null) return;
        String trimmed = query.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) return;
        queries.remove(trimmed);
        queries.add(0, trimmed);
        while (queries.size() > MAX_QUERIES) queries.remove(queries.size() - 1);
        dirty = true;
        write();
    }

    /** A video that was opened, and whose channel says as much as the video itself. */
    synchronized void rememberWatch(String videoId, String channelId) {
        if (videoId != null && !videoId.isEmpty()) {
            videos.remove(videoId);
            videos.add(0, videoId);
            while (videos.size() > MAX_VIDEOS) videos.remove(videos.size() - 1);
            dirty = true;
        }
        if (channelId != null && channelId.startsWith("UC")) {
            channels.remove(channelId);
            channels.add(0, channelId);
            while (channels.size() > MAX_VIDEOS) channels.remove(channels.size() - 1);
            dirty = true;
        }
        write();
    }

    /** The searches to build a feed out of, newest first. */
    synchronized List<String> searches(int count) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < queries.size() && out.size() < count; i++) out.add(queries.get(i));
        return out;
    }

    /** The channels last watched, newest first: their new videos belong in the feed. */
    synchronized List<String> watchedChannels(int count) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < channels.size() && out.size() < count; i++) out.add(channels.get(i));
        return out;
    }

    /** The videos last opened, whose related videos say what to show next. */
    synchronized List<String> lastVideos(int count) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < videos.size() && out.size() < count; i++) out.add(videos.get(i));
        return out;
    }

    /** The video last opened, whose related videos say what to show next. */
    synchronized String lastVideo() {
        return videos.isEmpty() ? "" : videos.get(0);
    }

    synchronized int searchCount() {
        return queries.size();
    }

    /** Whether there is enough here to make a feed of someone's own. */
    synchronized boolean ready() {
        return queries.size() >= ENOUGH_TO_PROFILE;
    }

    /** Changes with every search, so a feed built from it is not served after the next one. */
    synchronized String signature() {
        int hash = 17;
        for (int i = 0; i < queries.size() && i < 5; i++) hash = hash * 31 + queries.get(i).hashCode();
        hash = hash * 31 + (videos.isEmpty() ? 0 : videos.get(0).hashCode());
        return Integer.toHexString(hash);
    }

    synchronized void forget() {
        queries.clear();
        channels.clear();
        videos.clear();
        dirty = true;
        write();
    }

    // ------------------------------------------------------------------ the file

    private void read() {
        if (!file.exists()) return;
        try {
            JSONObject stored = new JSONObject(HttpClient.readFile(file));
            fill(queries, stored.optJSONArray("queries"));
            fill(channels, stored.optJSONArray("channels"));
            fill(videos, stored.optJSONArray("videos"));
        } catch (Exception e) {
            android.util.Log.w(TAG, "Could not read what was searched for: " + e);
        }
    }

    private static void fill(List<String> out, JSONArray array) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (!value.isEmpty()) out.add(value);
        }
    }

    private void write() {
        if (!dirty) return;
        dirty = false;
        try {
            JSONObject stored = new JSONObject()
                    .put("queries", new JSONArray(queries))
                    .put("channels", new JSONArray(channels))
                    .put("videos", new JSONArray(videos));
            HttpClient.writeFile(file, stored.toString());
        } catch (Exception e) {
            android.util.Log.w(TAG, "Could not keep what was searched for: " + e);
        }
    }
}
