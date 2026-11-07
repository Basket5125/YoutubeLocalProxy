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
        if ("editstation".equals(method)) return editStation(body, config);
        if ("stationfeed".equals(method)) return stationFeed(body, config);
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

    /**
     * "Start radio" on a song: the app creates a station seeded with the track and asks for its
     * first songs in the same call. The songs are YouTube Music's radio of that song.
     */
    private static String editStation(String body, Config config) {
        JSONArray responses = new JSONArray();
        try {
            JSONArray mutations = new JSONObject(body).optJSONArray("mutations");
            for (int i = 0; mutations != null && i < mutations.length(); i++) {
                JSONObject mutation = mutations.optJSONObject(i);
                // The app sends "create" for a new radio; "createOrGet" and "update" also carry it.
                JSONObject station = null;
                for (String kind : new String[]{"create", "createOrGet", "update"}) {
                    if (station == null && mutation != null) station = mutation.optJSONObject(kind);
                }
                if (station == null) continue;
                JSONObject seed = station.optJSONObject("seed");
                String videoId = seedVideoId(seed, "");
                int count = mutation.optInt("numEntries", 25);
                JSONObject out = station(videoId, seed, station.optString("name", ""), count,
                        new java.util.HashSet<String>(), config);
                out.put("clientId", station.optString("clientId", ""));
                JSONObject response = new JSONObject()
                        .put("id", out.optString("id"))
                        .put("client_id", station.optString("clientId", ""))
                        .put("response_code", videoId.isEmpty() ? "NOT_FOUND" : "OK");
                if (mutation.optBoolean("includeFeed", true)) response.put("station", out);
                responses.put(response);
            }
        } catch (Exception e) {
            android.util.Log.w("Gpm", "editstation failed: " + e);
        }
        return "{\"mutate_response\":" + responses + "}";
    }

    /** More songs of a station, leaving out the ones played lately. */
    private static String stationFeed(String body, Config config) {
        JSONArray stations = new JSONArray();
        try {
            JSONArray asked = new JSONObject(body).optJSONArray("stations");
            for (int i = 0; asked != null && i < asked.length(); i++) {
                JSONObject request = asked.optJSONObject(i);
                if (request == null) continue;
                String radioId = request.optString("radioId", "");
                JSONObject seed = request.optJSONObject("seed");
                String videoId = seedVideoId(seed, radioId);
                java.util.Set<String> played = new java.util.HashSet<String>();
                JSONArray recent = request.optJSONArray("recentlyPlayed");
                for (int j = 0; recent != null && j < recent.length(); j++) {
                    JSONObject item = recent.optJSONObject(j);
                    if (item != null) played.add(stripT(item.optString("id", "")));
                }
                stations.put(station(videoId, seed, "", request.optInt("numEntries", 25), played, config));
            }
        } catch (Exception e) {
            android.util.Log.w("Gpm", "stationfeed failed: " + e);
        }
        return "{\"kind\":\"sj#radioFeed\",\"data\":{\"stations\":" + stations + "}}";
    }

    private static JSONObject station(String videoId, JSONObject seed, String name, int count,
                                      java.util.Set<String> played, Config config) throws Exception {
        JSONArray tracks = new JSONArray();
        String title = name;
        if (!videoId.isEmpty()) {
            List<MusicSearch.Track> songs = MusicSearch.radio(videoId, config);
            for (MusicSearch.Track song : songs) {
                if (title.isEmpty() && song.videoId.equals(videoId)) title = song.title;
                if (played.contains(song.videoId)) continue;
                if (tracks.length() >= Math.max(1, Math.min(count, 50))) break;
                if (tracks.length() == 0) GpmStream.warm(song.videoId, config);
                tracks.put(new JSONObject(track(song)));
            }
        }
        JSONObject station = new JSONObject()
                .put("kind", "sj#radioStation")
                .put("id", "rd" + videoId)
                .put("name", title.isEmpty() ? "Radio" : title)
                .put("deleted", false)
                .put("tracks", tracks);
        if (seed != null) station.put("seed", seed);
        return station;
    }

    /** The YouTube video a station grows from: its seed track, or the id this proxy gave it. */
    private static String seedVideoId(JSONObject seed, String radioId) {
        String id = "";
        if (seed != null) {
            id = seed.optString("trackId", "");
            if (id.isEmpty()) id = seed.optString("trackLockerId", "");
        }
        if (id.isEmpty() && radioId.startsWith("rd")) id = radioId.substring(2);
        id = stripT(id);
        return InnertubeEndpoint.isValidVideoId(id) ? id : "";
    }

    private static String stripT(String id) {
        return id != null && id.length() == 12 && id.charAt(0) == 'T' ? id.substring(1) : id == null ? "" : id;
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
