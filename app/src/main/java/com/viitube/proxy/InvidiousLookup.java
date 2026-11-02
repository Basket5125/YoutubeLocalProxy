package com.viitube.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stream links from an Invidious instance. YouTube itself only offers 720p as separate video and
 * audio streams, which the 2010s apps cannot play; an Invidious instance answers with the
 * progressive files (itag 22 is 720p with sound), so one URL is enough and nothing has to be
 * muxed on the phone. The instance is entered in the app and is used for playback whenever it is
 * set; without it nothing changes.
 */
final class InvidiousLookup {

    private static final String TAG = "Invidious";
    private static final long MAX_AGE_MS = 10L * 60 * 1000;
    /** Progressive formats, best first: 720p, 360p, 240p, 144p. */
    private static final int[] WANTED_ITAGS = {22, 18, 59, 133};

    /** The list of instances is fetched from here by default; a different URL can be set. */
    static final String DEFAULT_LIST_URL = "http://144.31.189.129/notPipe.json";
    private static final long LIST_MAX_AGE_MS = 60L * 60 * 1000;
    /** Short per-instance timeouts, so a dead instance is given up on quickly and the next tried. */
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 7000;

    /** The instance that last answered, tried first next time so the dead ones are not re-probed. */
    private static volatile String lastGood = "";
    /** {url, List<String> instances, timestamp} - the parsed instance list, cached. */
    private static Object[] listCache;

    private InvidiousLookup() { }

    /** A host as "scheme://host" with no trailing slash; "https" is assumed when none is given. */
    private static String normalize(String host) {
        String value = host == null ? "" : host.trim();
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** The first instance to try, or "" when none is configured or listed. */
    static String instance(Config config) {
        List<String> all = instances(config);
        return all.isEmpty() ? "" : all.get(0);
    }

    /** True when there is somewhere to get streams from: a manual instance or a list URL. */
    static boolean hasInstance(Config config) {
        return !config.getString("invidious_instance", "").trim().isEmpty()
                || !config.getString("invidious_list_url", DEFAULT_LIST_URL).trim().isEmpty();
    }

    /**
     * The instances to try, in order: the one that last worked, then the manually entered one,
     * then the fetched list. Duplicates are dropped, the order kept.
     */
    static List<String> instances(Config config) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        String good = lastGood;
        if (!good.isEmpty()) set.add(good);
        String manual = normalize(config.getString("invidious_instance", ""));
        if (!manual.isEmpty()) set.add(manual);
        for (String url : fetchList(config)) {
            String n = normalize(url);
            if (!n.isEmpty()) set.add(n);
        }
        return new ArrayList<String>(set);
    }

    /** The instance list at the configured URL (key "invidious"), cached for an hour. */
    @SuppressWarnings("unchecked")
    private static List<String> fetchList(Config config) {
        String url = config.getString("invidious_list_url", DEFAULT_LIST_URL).trim();
        if (url.isEmpty()) return java.util.Collections.emptyList();
        synchronized (InvidiousLookup.class) {
            if (listCache != null && url.equals(listCache[0])
                    && System.currentTimeMillis() - (Long) listCache[2] < LIST_MAX_AGE_MS) {
                return (List<String>) listCache[1];
            }
        }
        List<String> list = new ArrayList<String>();
        try {
            String userAgent = config.getString("user_agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:102.0) Gecko/20100101 Firefox/102.0");
            JSONObject root = new JSONObject(HttpClient.getUrl(url, userAgent,
                    CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS));
            JSONArray array = root.optJSONArray("invidious");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    String host = array.optString(i, "").trim();
                    if (!host.isEmpty()) list.add(host);
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "instance list " + url + " failed: " + e);
        }
        if (!list.isEmpty()) {
            synchronized (InvidiousLookup.class) {
                listCache = new Object[]{url, list, System.currentTimeMillis()};
            }
        }
        return list;
    }

    /**
     * The single file with sound the instance has for the video (itag 22 when it still exists,
     * otherwise 360p), or null. Comes out of the same answer as the 720p streams.
     */
    static String progressiveUrl(String videoId, Config config) {
        Streams streams = streams(videoId, config);
        return streams == null || streams.progressive.isEmpty() ? null : streams.progressive;
    }

    /** The video and audio of one quality, ready for the muxer of this proxy. */
    static final class Pair {
        JSONObject video;
        JSONObject audio;
        String videoUrl = "";
        String audioUrl = "";
    }

    /** What an instance can play for one video. */
    static final class Streams {
        /** One file with sound (itag 22 or 18), as the old apps like it. */
        String progressive = "";
        /** The original audio track, not a dub, for muxing and for background playback. */
        JSONObject audio;
        String audioUrl = "";
        /** Heights of the H.264 streams the instance has, highest first. */
        final java.util.List<Integer> heights = new java.util.ArrayList<Integer>();
        private JSONArray adaptive;

        /** The best video not above {@code maxHeight}, with the audio next to it. */
        Pair pair(int maxHeight) {
            if (adaptive == null || audio == null || audioUrl.isEmpty()) return null;
            JSONObject video = pickAdaptive(adaptive, "video/mp4", "avc1", maxHeight);
            if (video == null) return null;
            Pair pair = new Pair();
            try {
                pair.video = toPlayerFormat(video);
            } catch (Exception e) {
                return null;
            }
            pair.audio = audio;
            pair.videoUrl = video.optString("url", "");
            pair.audioUrl = audioUrl;
            return pair.videoUrl.isEmpty() ? null : pair;
        }

        boolean has720() {
            return pair(720) != null;
        }
    }

    private static final Map<String, Object[]> STREAMS =
            new LinkedHashMap<String, Object[]>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
                    return size() > 8;
                }
            };

    /**
     * Everything the instance offers for a video. The request asks for local links, so the files
     * are served by the instance itself: those do not stop after a minute the way YouTube's own
     * adaptive links do without a PO token, which is what makes 720p work here at all.
     */
    static Streams streams(String videoId, Config config) {
        if (!InnertubeEndpoint.isValidVideoId(videoId)) return null;
        synchronized (STREAMS) {
            Object[] cached = STREAMS.get(videoId);
            if (cached != null && System.currentTimeMillis() - (Long) cached[1] < MAX_AGE_MS) {
                return (Streams) cached[0];
            }
        }
        // Try the instances one after another until one answers in time with usable streams. A
        // dead one - no connection, a timeout, an error, or an empty answer - is skipped, and the
        // one that works is remembered so it is tried first next time.
        for (String instance : instances(config)) {
            Streams streams;
            try {
                streams = fetchFrom(instance, videoId, config);
            } catch (Exception e) {
                android.util.Log.i(TAG, "Streams of " + videoId + " at " + instance + " failed: " + e);
                continue;
            }
            if (streams == null) continue;
            lastGood = instance;
            synchronized (STREAMS) {
                STREAMS.put(videoId, new Object[]{streams, System.currentTimeMillis()});
            }
            return streams;
        }
        return null;
    }

    /**
     * One instance's answer for a video, or null when it has nothing playable (so the caller moves
     * on to the next). Throws on a network error or a timeout, which the caller also treats as a
     * reason to try the next instance.
     */
    private static Streams fetchFrom(String instance, String videoId, Config config) throws Exception {
        String userAgent = config.getString("user_agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:102.0) Gecko/20100101 Firefox/102.0");
        JSONObject answer = new JSONObject(HttpClient.getUrl(
                instance + "/api/v1/videos/" + videoId + "?local=true", userAgent,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS));
        Streams streams = new Streams();
        JSONArray adaptive = answer.optJSONArray("adaptiveFormats");
        // Some instances return the stream links relative to their own host (e.g. "/latest_version"
        // or "/videoplayback..."); make every link absolute so it can be opened and muxed.
        absolutize(answer.optJSONArray("formatStreams"), instance);
        absolutize(adaptive, instance);
        String progressive = bestFormat(answer.optJSONArray("formatStreams"));
        if (progressive != null) streams.progressive = progressive;
        streams.adaptive = adaptive;
        JSONObject audio = pickAudio(adaptive, config.getString("audio_quality", "high"));
        if (audio != null) {
            streams.audio = toPlayerFormat(audio);
            streams.audioUrl = audio.optString("url", "");
        }
        // Which qualities the instance has, so the apps can offer them.
        if (adaptive != null) {
            for (int i = 0; i < adaptive.length(); i++) {
                JSONObject format = adaptive.optJSONObject(i);
                if (format == null) continue;
                String type = format.optString("type", "");
                if (!type.startsWith("video/mp4") || !type.contains("avc1")) continue;
                if (format.optString("url", "").isEmpty()
                        || format.optString("init", "").isEmpty()
                        || format.optString("index", "").isEmpty()) {
                    continue;
                }
                Integer height = Integer.valueOf(heightOf(format));
                if (height.intValue() > 0 && !streams.heights.contains(height)) {
                    streams.heights.add(height);
                }
            }
            java.util.Collections.sort(streams.heights, java.util.Collections.reverseOrder());
        }
        // Nothing here to play or mux - treat the instance as a miss.
        if (streams.progressive.isEmpty() && streams.audioUrl.isEmpty() && streams.heights.isEmpty()) {
            return null;
        }
        return streams;
    }

    /** Rewrites each format's "url" so a link the instance gave relative to itself becomes absolute. */
    private static void absolutize(JSONArray formats, String instance) {
        if (formats == null) return;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null) continue;
            String url = format.optString("url", "");
            if (url.isEmpty()) continue;
            String fixed = absolute(instance, url);
            if (!fixed.equals(url)) {
                try {
                    format.put("url", fixed);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** A link made absolute against the instance host; an already absolute one is left as is. */
    private static String absolute(String instance, String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) {
            int scheme = instance.indexOf("://");
            return (scheme < 0 ? "http:" : instance.substring(0, scheme) + ":") + url;
        }
        return url.startsWith("/") ? instance + url : instance + "/" + url;
    }

    /** The single file with sound, best first: 720p, 360p, then whatever the instance has. */
    private static String bestFormat(JSONArray formats) {
        if (formats == null) return null;
        for (int itag : WANTED_ITAGS) {
            for (int i = 0; i < formats.length(); i++) {
                JSONObject format = formats.optJSONObject(i);
                if (format == null) continue;
                // Invidious writes the itag as a string, forks of it as a number.
                if (String.valueOf(itag).equals(format.optString("itag", "").trim())
                        || itag == format.optInt("itag", -1)) {
                    String url = format.optString("url", "");
                    if (!url.isEmpty()) return url;
                }
            }
        }
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            String url = format == null ? "" : format.optString("url", "");
            if (!url.isEmpty()) return url;
        }
        return null;
    }

    /** The best entry of a kind, not above {@code maxHeight} when one is given. */
    private static JSONObject pickAdaptive(JSONArray formats, String mime, String codec, int maxHeight) {
        if (formats == null) return null;
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null) continue;
            String type = format.optString("type", "");
            if (!type.startsWith(mime) || !type.contains(codec)) continue;
            if (format.optString("url", "").isEmpty()) continue;
            // Ranges of the file header and of its index; without them nothing can be muxed.
            if (format.optString("init", "").isEmpty() || format.optString("index", "").isEmpty()) continue;
            int height = heightOf(format);
            if (maxHeight > 0 && (height <= 0 || height > maxHeight)) continue;
            int score = maxHeight > 0 ? height : audioScore(format);
            if (score > bestScore) {
                bestScore = score;
                best = format;
            }
        }
        return best;
    }

    /**
     * The audio stream to serve, at the quality the settings ask for. The original (non-dubbed,
     * non-loudness-normalised) track always wins; among the originals, "high" takes the highest
     * bitrate (itag 140, ~128 kbps), "normal" the one nearest 128 kbps, "low" the smallest
     * (itag 139, ~48 kbps) to save data.
     */
    private static JSONObject pickAudio(JSONArray formats, String quality) {
        if (formats == null) return null;
        JSONObject best = null;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null) continue;
            String type = format.optString("type", "");
            if (!type.startsWith("audio/mp4") || !type.contains("mp4a")) continue;
            if (format.optString("url", "").isEmpty()) continue;
            if (format.optString("init", "").isEmpty() || format.optString("index", "").isEmpty()) continue;
            String tags = tagsOf(format.optString("url", ""));
            long score = 0;
            if (tags.contains("acont=original")) score += 1000000000L;
            else if (tags.contains("acont=dubbed")) score -= 1000000000L;
            if (tags.contains("drc=1")) score -= 1000000L;
            int kbps = parseInt(format.optString("bitrate", ""), 0) / 1000;
            if ("low".equals(quality)) score += 1000 - kbps;
            else if ("normal".equals(quality)) score += 1000 - Math.abs(kbps - 128);
            else score += kbps;
            if (score > bestScore) {
                bestScore = score;
                best = format;
            }
        }
        return best;
    }

    /**
     * Which audio stream to take. A video with dubbed tracks offers one entry per language and
     * the answer does not name them, but the link does: its "xtags" says acont=original or
     * acont=dubbed:lang=xx, and drc=1 marks the loudness-normalised copy. The original track
     * wins, so a Polish video is not played with its English AI dub.
     */
    private static int audioScore(JSONObject format) {
        String tags = tagsOf(format.optString("url", ""));
        int score = parseInt(format.optString("bitrate", ""), 0) / 1000;
        if (tags.contains("acont=original")) score += 1000000;
        else if (tags.contains("acont=dubbed")) score -= 1000000;
        if (tags.contains("drc=1")) score -= 1000;
        return score;
    }

    /** The "xtags" parameter of a stream link, which carries the track it belongs to. */
    private static String tagsOf(String url) {
        int at = url.indexOf("xtags=");
        if (at < 0) return "";
        int end = url.indexOf('&', at);
        String value = end < 0 ? url.substring(at + 6) : url.substring(at + 6, end);
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static int heightOf(JSONObject format) {
        String size = format.optString("size", "");
        int cross = size.indexOf('x');
        if (cross > 0) return parseInt(size.substring(cross + 1), 0);
        String label = format.optString("qualityLabel", format.optString("resolution", ""));
        return parseInt(label.replaceAll("[^0-9].*$", ""), 0);
    }

    /**
     * An Invidious format written the way the player answers write it, so the muxer of this
     * proxy can use it unchanged: "init": "0-739" becomes initRange {start, end} and so on.
     */
    private static JSONObject toPlayerFormat(JSONObject format) throws org.json.JSONException {
        JSONObject out = new JSONObject()
                .put("itag", parseInt(format.optString("itag", ""), 0))
                .put("mimeType", format.optString("type", ""))
                .put("contentLength", format.optString("clen", ""))
                .put("bitrate", parseInt(format.optString("bitrate", ""), 0))
                .put("initRange", range(format.optString("init", "")))
                .put("indexRange", range(format.optString("index", "")));
        int height = heightOf(format);
        if (height > 0) {
            String size = format.optString("size", "");
            int cross = size.indexOf('x');
            out.put("height", height);
            out.put("width", cross > 0 ? parseInt(size.substring(0, cross), 0) : height * 16 / 9);
            out.put("qualityLabel", format.optString("qualityLabel", height + "p"));
            out.put("fps", parseInt(format.optString("fps", ""), 30));
        }
        return out;
    }

    private static JSONObject range(String value) throws org.json.JSONException {
        int dash = value.indexOf('-');
        if (dash < 0) return new JSONObject().put("start", "0").put("end", "0");
        return new JSONObject()
                .put("start", value.substring(0, dash).trim())
                .put("end", value.substring(dash + 1).trim());
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    static void clearMemory() {
        synchronized (STREAMS) {
            STREAMS.clear();
        }
    }
}
