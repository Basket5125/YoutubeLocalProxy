package com.viitube.proxy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import fi.iki.elonen.NanoHTTPD;

/**
 * The JSON bodies the Play Music app's skyjam API expects, built from YouTube Music. The store id
 * of every track is the YouTube video id, so a later /music/mplay for that id resolves straight
 * to the matching YouTube stream.
 */
final class GpmResponses {

    private GpmResponses() { }

    /** JSON for a /sj/v1/{method} call, or null when this proxy does not answer it yet. */
    static String forMethod(String method, NanoHTTPD.IHTTPSession session, String query,
                            String body, Config config, Context context) {
        if ("config".equals(method)) return config();
        if ("query".equals(method)) return search(session, config, context);
        if ("tabs".equals(method)) return exploreTabs(config, context);
        if ("genres".equals(method)) return genres();
        return null;
    }

    /**
     * The account's configuration. The entries say it is a full subscriber in Poland so nothing
     * is greyed out; the app also reads isNautilusUser from its own config store (set once).
     */
    private static String config() {
        return "{\"kind\":\"sj#configList\",\"data\":{\"entries\":["
                + entry("isNautilusUser", "true")
                + "," + entry("isNautilusAvailable", "true")
                + "," + entry("defaultCountryCode", "PL")
                + "]}}";
    }

    private static String entry(String key, String value) {
        return "{\"kind\":\"sj#configEntry\",\"key\":\"" + key + "\",\"value\":\"" + value + "\"}";
    }

    /** A search: YouTube Music songs dressed up as skyjam tracks. */
    private static String search(NanoHTTPD.IHTTPSession session, Config config, Context context) {
        String q = session.getParms().get("q");
        if (q == null) q = "";
        int max = parseInt(session.getParms().get("max-results"), 20);
        List<MusicSearch.Track> tracks = MusicSearch.songs(q, Math.max(1, Math.min(max, 25)), config);
        if (tracks.isEmpty()) {
            return "{\"kind\":\"sj#searchresults\",\"entries\":[]}";
        }
        // Build the top result's audio ahead of the tap, so the first play does not wait. Only the
        // top one: the phone's bandwidth to Invidious is the limit, so warming more would slow the
        // track the user actually taps.
        GpmStream.warm(tracks.get(0).videoId, config);
        StringBuilder entries = new StringBuilder();
        for (MusicSearch.Track song : tracks) {
            if (song.videoId.isEmpty()) continue;
            if (entries.length() > 0) entries.append(',');
            entries.append("{\"kind\":\"sj#searchResult\",\"type\":\"1\",\"score\":1.0,\"track\":")
                    .append(track(song)).append('}');
        }
        return "{\"kind\":\"sj#searchresults\",\"clusterOrder\":[\"1\"],\"entries\":["
                + entries + "]}";
    }

    /** One YouTube Music song as a skyjam store track; the store id is the video id. */
    static String track(MusicSearch.Track song) {
        long millis = song.durationSeconds * 1000;
        JSONObject art = new JSONObject();
        JSONObject track = new JSONObject();
        try {
            track.put("kind", "sj#track");
            track.put("title", song.title);
            track.put("artist", song.artist);
            track.put("albumArtist", song.artist);
            track.put("album", song.album);
            track.put("trackType", "7");
            track.put("storeId", song.videoId);
            track.put("nid", song.videoId);
            track.put("durationMillis", Long.toString(millis));
            track.put("trackAvailableForSubscription", true);
            track.put("trackAvailableForPurchase", false);
            track.put("albumAvailableForPurchase", false);
            if (!song.videoId.isEmpty()) {
                // The square album art the search found, served by this proxy over HTTP because the
                // app's old TLS cannot fetch the https thumbnail URLs directly.
                GpmEndpoint.rememberArt(song.videoId, song.thumbnailUrl);
                art.put("kind", "sj#imageRef");
                art.put("url", "http://127.0.0.1:8080/art/" + song.videoId);
                art.put("autogen", false);
                track.put("albumArtRef", new JSONArray().put(art));
                track.put("artistArtRef", new JSONArray().put(art));
            }
        } catch (Exception ignored) {
        }
        return track.toString();
    }

    /** The Explore screen: a few tabs, each with one group of YouTube Music tracks. */
    private static String exploreTabs(Config config, Context context) {
        StringBuilder tabs = new StringBuilder();
        tabs.append(tab("RECOMMENDED", "Dla Ciebie", "polecane utwory", config, context));
        tabs.append(',').append(tab("TOP_CHARTS", "Listy przebojów", "top hits 2024", config, context));
        tabs.append(',').append(tab("NEW_RELEASES", "Nowości", "new music this week", config, context));
        return "{\"tabs\":[" + tabs + "]}";
    }

    private static String tab(String type, String title, String query, Config config, Context context) {
        StringBuilder entities = new StringBuilder();
        for (MusicSearch.Track song : MusicSearch.songs(query, 15, config)) {
            if (song.videoId.isEmpty()) continue;
            if (entities.length() > 0) entities.append(',');
            entities.append("{\"track\":").append(track(song)).append('}');
        }
        String group = "{\"title\":\"" + title + "\",\"description\":\"\",\"entities\":["
                + entities + "]}";
        return "{\"tab_type\":\"" + type + "\",\"data_status\":\"OK\",\"groups\":["
                + group + "]}";
    }

    /** A small static genre list, so the Genres tab is not empty. */
    private static String genres() {
        String[][] list = {
                {"POP", "Pop"}, {"HIPHOP", "Hip-Hop/Rap"}, {"ROCK", "Rock"},
                {"ELECTRONIC", "Elektroniczna"}, {"RNB", "R&B/Soul"}, {"JAZZ", "Jazz"},
                {"CLASSICAL", "Klasyczna"}, {"COUNTRY", "Country"}, {"METAL", "Metal"},
                {"DANCE", "Dance"}};
        StringBuilder g = new StringBuilder();
        for (String[] genre : list) {
            if (g.length() > 0) g.append(',');
            g.append("{\"id\":\"").append(genre[0]).append("\",\"name\":\"")
                    .append(genre[1]).append("\"}");
        }
        return "{\"genres\":[" + g + "]}";
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
