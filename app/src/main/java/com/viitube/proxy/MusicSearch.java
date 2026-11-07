package com.viitube.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Search against YouTube Music (the WEB_REMIX Innertube client), so results come back as songs with
 * a title, artists, an album and album art, the way the old Play Music app wants them — not as plain
 * YouTube videos with "official video" titles and video thumbnails.
 *
 * <p>The request shape follows the InnerTune client kept in this repo
 * ({@code InnerTune/innertube/.../YouTube.kt}, {@code YouTubeClient.kt}, {@code SearchPage.kt}):
 * POST {@code music.youtube.com/youtubei/v1/search} with the WEB_REMIX context and the "songs only"
 * filter, then read the {@code musicResponsiveListItemRenderer} cards.
 */
final class MusicSearch {

    private static final String TAG = "Gpm";
    private static final String CLIENT_VERSION = "1.20260720.01.00";
    /** The WEB_REMIX api key InnerTune uses; music.youtube.com expects it on the search call. */
    private static final String API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
    /** InnerTune's FILTER_SONG params (decoded from its URL-encoded form), so only songs come back. */
    private static final String FILTER_SONG = "EgWKAQIIAWoKEAkQBRAKEAMQBA==";

    private MusicSearch() { }

    /** One song from YouTube Music; the id is the YouTube video id, as the stream resolves from it. */
    static final class Track {
        String videoId = "";
        String title = "";
        String artist = "";
        String album = "";
        /** The YouTube Music thumbnail URL (square album art), proxied to the app via /art. */
        String thumbnailUrl = "";
        long durationSeconds;
    }

    private static final long CACHE_MAX_AGE_MS = 30L * 60 * 1000;

    private static final java.util.Map<String, Cached> CACHE =
            new java.util.LinkedHashMap<String, Cached>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Cached> eldest) {
                    return size() > MemoryBudget.entries(16);
                }
            };

    private static final class Cached {
        final List<Track> tracks;
        final long at;

        Cached(List<Track> tracks) {
            this.tracks = tracks;
            this.at = System.currentTimeMillis();
        }
    }

    /** Songs matching {@code query} from YouTube Music, at most {@code limit}; cached for 30 minutes. */
    static List<Track> songs(String query, int limit, Config config) {
        if (query == null || query.trim().isEmpty()) return new ArrayList<Track>();
        String key = query.trim().toLowerCase(java.util.Locale.US);
        synchronized (CACHE) {
            Cached cached = CACHE.get(key);
            if (cached != null && System.currentTimeMillis() - cached.at < CACHE_MAX_AGE_MS) {
                return trim(cached.tracks, limit);
            }
        }
        List<Track> tracks;
        try {
            tracks = fetch(query, config);
        } catch (Exception e) {
            android.util.Log.w(TAG, "music search for " + query + " failed: " + e);
            return new ArrayList<Track>();
        }
        synchronized (CACHE) {
            CACHE.put(key, new Cached(tracks));
        }
        return trim(tracks, limit);
    }

    private static List<Track> trim(List<Track> tracks, int limit) {
        if (limit <= 0 || tracks.size() <= limit) return new ArrayList<Track>(tracks);
        return new ArrayList<Track>(tracks.subList(0, limit));
    }

    private static List<Track> fetch(String query, Config config) throws Exception {
        String language = config.getString("music_language", "pl");
        String region = config.getString("music_region", "PL");
        JSONObject payload = new JSONObject()
                .put("context", new JSONObject().put("client", new JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", language)
                        .put("gl", region)))
                .put("query", query)
                .put("params", FILTER_SONG);

        java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
        headers.put("Origin", "https://music.youtube.com");
        headers.put("Referer", "https://music.youtube.com/");
        headers.put("X-Origin", "https://music.youtube.com");
        headers.put("X-YouTube-Client-Name", "67");
        headers.put("X-YouTube-Client-Version", CLIENT_VERSION);

        String url = "https://music.youtube.com/youtubei/v1/search?alt=json&prettyPrint=false&key="
                + API_KEY;
        JSONObject response = new JSONObject(HttpClient.postJson(
                url, payload.toString(), DEFAULT_USER_AGENT, headers, 6000, 10000));

        List<Track> tracks = new ArrayList<Track>();
        for (JSONObject item : collectListItems(response)) {
            Track track = toTrack(item);
            if (track != null) tracks.add(track);
        }
        return tracks;
    }

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** Every {@code musicResponsiveListItemRenderer} in the response, however it is nested. */
    private static List<JSONObject> collectListItems(Object node) {
        List<JSONObject> items = new ArrayList<JSONObject>();
        collectListItems(node, items);
        return items;
    }

    private static void collectListItems(Object node, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject item = object.optJSONObject("musicResponsiveListItemRenderer");
            if (item != null) {
                out.add(item);
                return;
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    collectListItems(object.opt(names.optString(i)), out);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collectListItems(array.opt(i), out);
        }
    }

    private static Track toTrack(JSONObject renderer) {
        Track track = new Track();
        track.videoId = findVideoId(renderer);
        if (track.videoId.isEmpty()) return null;

        JSONArray flexColumns = renderer.optJSONArray("flexColumns");
        if (flexColumns == null || flexColumns.length() == 0) return null;
        track.title = firstRunText(column(flexColumns, 0));
        if (track.title.isEmpty()) return null;

        // The second line reads "Artist • Album • Duration" (any part may be missing); the runs are
        // split on the " • " separators, artists sit in the first group (every other run is a comma).
        List<List<JSONObject>> groups = splitBySeparator(runsOf(column(flexColumns, 1)));
        if (!groups.isEmpty()) {
            track.artist = joinNames(groups.get(0));
            List<JSONObject> last = groups.get(groups.size() - 1);
            if (!last.isEmpty()) {
                long seconds = parseTime(text(last.get(0)));
                if (seconds > 0) track.durationSeconds = seconds;
            }
            if (groups.size() >= 3) {
                JSONObject albumRun = groups.get(1).isEmpty() ? null : groups.get(1).get(0);
                if (albumRun != null && hasBrowseEndpoint(albumRun)) track.album = text(albumRun);
            }
        }

        track.thumbnailUrl = thumbnailUrl(renderer);
        return track;
    }

    private static JSONObject column(JSONArray flexColumns, int index) {
        JSONObject column = flexColumns.optJSONObject(index);
        return column == null ? null : column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
    }

    private static JSONArray runsOf(JSONObject column) {
        if (column == null) return new JSONArray();
        JSONObject text = column.optJSONObject("text");
        JSONArray runs = text == null ? null : text.optJSONArray("runs");
        return runs == null ? new JSONArray() : runs;
    }

    private static String firstRunText(JSONObject column) {
        JSONArray runs = runsOf(column);
        return runs.length() == 0 ? "" : runs.optJSONObject(0).optString("text", "");
    }

    /** Splits a run list on the " • " separator runs, dropping the separators. */
    private static List<List<JSONObject>> splitBySeparator(JSONArray runs) {
        List<List<JSONObject>> groups = new ArrayList<List<JSONObject>>();
        List<JSONObject> current = new ArrayList<JSONObject>();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null) continue;
            if ("•".equals(run.optString("text", "").trim())) {
                groups.add(current);
                current = new ArrayList<JSONObject>();
            } else {
                current.add(run);
            }
        }
        groups.add(current);
        return groups;
    }

    /** Names from a run group, skipping the ", " separator runs between them. */
    private static String joinNames(List<JSONObject> runs) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < runs.size(); i += 2) {
            String name = text(runs.get(i)).trim();
            if (name.isEmpty()) continue;
            if (names.length() > 0) names.append(", ");
            names.append(name);
        }
        return names.toString();
    }

    private static boolean hasBrowseEndpoint(JSONObject run) {
        JSONObject navigation = run.optJSONObject("navigationEndpoint");
        return navigation != null && navigation.optJSONObject("browseEndpoint") != null;
    }

    private static String text(JSONObject run) {
        return run == null ? "" : run.optString("text", "");
    }

    private static String findVideoId(JSONObject renderer) {
        JSONObject data = renderer.optJSONObject("playlistItemData");
        String id = data == null ? "" : data.optString("videoId", "");
        if (!id.isEmpty()) return id;
        // Fallback: a watch endpoint somewhere in the overlay / menu.
        return findFirst(renderer, "videoId");
    }

    private static String findFirst(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has(key)) {
                String value = object.optString(key, "");
                if (!value.isEmpty()) return value;
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String found = findFirst(object.opt(names.optString(i)), key);
                    if (!found.isEmpty()) return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findFirst(array.opt(i), key);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    /** The largest thumbnail from the card's musicThumbnailRenderer. */
    private static String thumbnailUrl(JSONObject renderer) {
        JSONObject thumbnail = renderer.optJSONObject("thumbnail");
        JSONObject musicThumbnail = thumbnail == null ? null
                : thumbnail.optJSONObject("musicThumbnailRenderer");
        JSONObject inner = musicThumbnail == null ? null : musicThumbnail.optJSONObject("thumbnail");
        JSONArray thumbnails = inner == null ? null : inner.optJSONArray("thumbnails");
        if (thumbnails == null || thumbnails.length() == 0) return "";
        JSONObject best = thumbnails.optJSONObject(thumbnails.length() - 1);
        return upscale(best == null ? "" : best.optString("url", ""));
    }

    /**
     * YouTube Music hands back a small square (often 60–120 px); its googleusercontent links take
     * a size in the "=wNN-hNN" suffix, so ask for 544 px to get crisp album art.
     */
    private static String upscale(String url) {
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            return url.replaceAll("=w\\d+-h\\d+[^/]*$", "=w544-h544-l90-rj");
        }
        return url;
    }

    private static long parseTime(String value) {
        if (value == null || value.isEmpty()) return 0;
        String[] parts = value.trim().split(":");
        long seconds = 0;
        try {
            for (String part : parts) seconds = seconds * 60 + Long.parseLong(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
        return seconds;
    }

    static void clearMemory() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
