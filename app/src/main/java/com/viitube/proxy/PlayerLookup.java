package com.viitube.proxy;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.net.URLDecoder;
import java.net.URLEncoder;

/** Resolves YouTube player responses and direct stream URLs. */
public class PlayerLookup {

    private static final String ANDROID_USER_AGENT =
        "com.google.android.youtube/21.29.366 (Linux; U; Android 14; en_US; Pixel 7 Build/UP1A.231005.007) gzip";
    private static final String IOS_USER_AGENT =
        "com.google.ios.youtube/20.11.6 (iPhone14,5; U; CPU iOS 18_5 like Mac OS X;)";
    private static final String WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36,gzip(gfe)";

    private static final PlayerProfile[] PLAYBACK_PROFILES = {
        new PlayerProfile("ANDROID", "21.29.366", ANDROID_USER_AGENT, "3", "MOBILE",
            "Android", "14", "Google", "Pixel 7", "", 34),
        new PlayerProfile("MWEB", "2.20260722.01.00", WEB_USER_AGENT, "2", "MOBILE",
            "Android", "16", "", "", "", 0),
        new PlayerProfile("IOS", "20.11.6", IOS_USER_AGENT, "5", "MOBILE",
            "iPhone", "18.5.0.22F76", "Apple", "iPhone14,5", "", 0),
        new PlayerProfile("WEB_EMBEDDED_PLAYER", "2.20260722.01.00", WEB_USER_AGENT,
            "56", "DESKTOP", "Windows", "10.0", "", "", "EMBED", 0),
        new PlayerProfile("TVHTML5", "2.20260722.01.00", WEB_USER_AGENT, "7", "",
            "", "", "", "", "", 0)
    };

    private static final String VR_USER_AGENT_FORMAT =
        "com.google.android.apps.youtube.vr.oculus/%s (Linux; U; Android 12L; "
            + "eureka-user Build/SQ3A.220605.009.A1) gzip";

    /**
     * Clients tried for the experimental 720p muxer. Without a PO token YouTube cuts most
     * adaptive stream URLs off after about the first minute (HTTP 403 beyond that); older
     * ANDROID_VR versions still get complete files for some videos, so they come first.
     */
    private static final PlayerProfile[] MUX_PROFILES = {
        new PlayerProfile("ANDROID_VR", "1.62.27", String.format(VR_USER_AGENT_FORMAT, "1.62.27"),
            "28", "", "Android", "12L", "Oculus", "Quest 3", "", 32),
        new PlayerProfile("ANDROID_VR", "1.65.10", String.format(VR_USER_AGENT_FORMAT, "1.65.10"),
            "28", "", "Android", "12L", "Oculus", "Quest 3", "", 32),
        PLAYBACK_PROFILES[0],
        PLAYBACK_PROFILES[2],
        PLAYBACK_PROFILES[1]
    };

    /** Players of every mux client that answered, in preference order (cached). */
    public static java.util.List<JSONObject> fetchPlaybackCandidatesForMux(String videoId, Config config) {
        java.util.List<JSONObject> players = new java.util.ArrayList<JSONObject>();
        for (PlayerProfile profile : MUX_PROFILES) {
            try {
                JSONObject player = fetchProfilePlayer(videoId, config, profile);
                if (findMuxFormats(player, 720) != null) players.add(player);
            } catch (Exception error) {
                android.util.Log.w("PlayerLookup", profile.name + " " + profile.version
                        + " player failed: " + error.getMessage());
            }
        }
        return players;
    }

    private static String visitorData;
    private static long visitorDataTime;

    /** A fresh visitor id (some clients answer LOGIN_REQUIRED without one); cached for a day. */
    private static synchronized String freshVisitorData(Config config) {
        if (visitorData != null && System.currentTimeMillis() - visitorDataTime < 24L * 3600 * 1000) {
            return visitorData;
        }
        try {
            JSONObject response = InnertubeEndpoint.request("visitor_id", InnertubeEndpoint.context(config), config);
            JSONObject context = response.optJSONObject("responseContext");
            String value = context == null ? "" : context.optString("visitorData", "");
            if (!value.isEmpty()) {
                visitorData = value;
                visitorDataTime = System.currentTimeMillis();
            }
        } catch (Exception error) {
            android.util.Log.w("PlayerLookup", "Could not get visitor data: " + error.getMessage());
        }
        return visitorData == null ? "" : visitorData;
    }

    private static final int PLAYER_CACHE_LIMIT = 64;
    private static final Map<String, JSONObject> PLAYER_CACHE =
            new LinkedHashMap<String, JSONObject>(16, 0.75f, true);
    private static final Map<String, Object> PLAYER_LOCKS = new HashMap<String, Object>();
    private static long playerCacheGeneration;

    public static void clearPlayerCache(Context context) {
        synchronized (PLAYER_CACHE) {
            PLAYER_CACHE.clear();
            PLAYER_LOCKS.clear();
            playerCacheGeneration++;
        }
        File cacheDir = context.getCacheDir();
        deleteRecursively(new File(cacheDir, "videoinfo_v2"));
        deleteRecursively(new File(cacheDir, "upload_player_v1"));
        deleteRecursively(new File(cacheDir, "playback_v2"));
    }

    /** Forgets the feed metadata of videos; the player answers go with clearPlayerCache(). */
    public static void clearMetadataCache() {
        synchronized (METADATA_CACHE) {
            METADATA_CACHE.clear();
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (file.exists()) file.delete();
    }

    public static JSONObject fetch(String videoId, Config config, Context context) throws Exception {
        return fetch(videoId, config, context, "en");
    }

    public static JSONObject fetch(String videoId, Config config, Context context, String language)
            throws Exception {
        final String requestedLanguage = language == null ? "en" : language;
        return cachedPlayer("player:" + videoId + ":" + requestedLanguage, new PlayerLoader() {
            @Override
            public JSONObject load() throws Exception {
                JSONObject payload = InnertubeEndpoint.context(config).put("videoId", videoId);
                payload.getJSONObject("context").getJSONObject("client").put("hl", requestedLanguage);
                return InnertubeEndpoint.request("player", payload, config);
            }
        });
    }

    private static final long METADATA_MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final Map<String, JSONObject> METADATA_CACHE =
            new LinkedHashMap<String, JSONObject>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
                    return size() > 512;
                }
            };

    /**
     * Feed metadata of a video (title, description, counts, author, publish date). Only these
     * few fields are kept - in memory and for 24 hours on disk - instead of the full player
     * response, which is hundreds of KB and slow to copy and parse on old phones. No stream
     * URLs are stored. The returned object is shared and must not be modified.
     */
    public static JSONObject fetchUploadMetadata(String videoId, Config config, Context context)
            throws Exception {
        synchronized (METADATA_CACHE) {
            JSONObject cached = METADATA_CACHE.get(videoId);
            if (cached != null) return cached;
        }
        File cache = context == null ? null
                : HttpClient.cacheFile(context.getCacheDir(), "video_metadata_v1", videoId);
        if (cache != null && HttpClient.isFresh(cache, METADATA_MAX_AGE_MS)) {
            try {
                JSONObject cached = new JSONObject(HttpClient.readFile(cache));
                rememberMetadata(videoId, cached);
                return cached;
            } catch (Exception ignored) { }
        }
        JSONObject payload = InnertubeEndpoint.context(config)
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true);
        JSONObject client = payload.getJSONObject("context").getJSONObject("client");
        client.put("clientScreen", "WATCH")
                .put("originalUrl", "https://www.youtube.com/watch?v=" + videoId);
        JSONObject metadata = slimMetadata(InnertubeEndpoint.requestFast("player", payload, config));
        if (metadata.optJSONObject("videoDetails") != null) {
            rememberMetadata(videoId, metadata);
            if (cache != null) {
                try { HttpClient.writeFile(cache, metadata.toString()); }
                catch (Exception ignored) { }
            }
        }
        return metadata;
    }

    private static void rememberMetadata(String videoId, JSONObject metadata) {
        synchronized (METADATA_CACHE) {
            METADATA_CACHE.put(videoId, metadata);
        }
    }

    private static JSONObject slimMetadata(JSONObject player) throws org.json.JSONException {
        JSONObject slim = new JSONObject();
        JSONObject details = player.optJSONObject("videoDetails");
        if (details != null) {
            JSONObject copy = new JSONObject();
            for (String key : new String[]{"title", "shortDescription", "viewCount",
                    "lengthSeconds", "author", "channelId"}) {
                if (details.has(key)) copy.put(key, details.opt(key));
            }
            slim.put("videoDetails", copy);
        }
        JSONObject microformat = player.optJSONObject("microformat");
        JSONObject renderer = microformat == null
                ? null : microformat.optJSONObject("playerMicroformatRenderer");
        if (renderer != null) {
            JSONObject copy = new JSONObject();
            for (String key : new String[]{"publishDate", "uploadDate", "description", "category"}) {
                if (renderer.has(key)) copy.put(key, renderer.opt(key));
            }
            slim.put("microformat", new JSONObject().put("playerMicroformatRenderer", copy));
        }
        return slim;
    }

    /**
     * Zwraca bezpośredni URL progresywnego strumienia (wideo+audio w jednym pliku) dla danego itagu,
     * np. 18 = 360p H.264+AAC mp4. Zwraca null, jeśli format ma tylko "signatureCipher"
     * (patrz README - deszyfrowanie sygnatury nie jest tu zaimplementowane).
     */
    public static String findStreamUrl(JSONObject player, int itag) {
        try {
            JSONArray formats = player.getJSONObject("streamingData").getJSONArray("formats");
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.getJSONObject(i);
                if (f.optInt("itag") == itag && f.has("url")) {
                    return f.getString("url");
                }
            }
        } catch (Exception e) {
            // brak streamingData/formats albo tylko signatureCipher
        }
        return null;
    }

    public static String findProgressive360pUrl(JSONObject player) {
        try {
            JSONArray formats = player.getJSONObject("streamingData").getJSONArray("formats");
            String fallback = null;
            for (int i = 0; i < formats.length(); i++) {
                JSONObject format = formats.getJSONObject(i);
                String mimeType = format.optString("mimeType", "").toLowerCase(java.util.Locale.US);
                String quality = format.optString("qualityLabel", "");
                int height = format.optInt("height", 0);
                boolean mp4 = mimeType.startsWith("video/mp4");
                boolean hasAudioAndVideoCodecs = mimeType.contains("avc") && mimeType.contains("mp4a");
                boolean is360p = "360p".equals(quality) || height == 360;
                String url = playableFormatUrl(format);
                if (mp4 && is360p && !url.isEmpty()) {
                    if (format.optInt("itag") == 18 || hasAudioAndVideoCodecs) return url;
                    if (fallback == null) fallback = url;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    public static JSONObject fetchProgressivePlayer(String videoId, Config config, Context context) throws Exception {
        return fetchPlaybackPlayer(videoId, config, "360p");
    }

    public static JSONObject fetchPlaybackPlayer(String videoId, Config config, String mode)
            throws Exception {
        JSONObject latest = null;
        Exception lastError = null;
        // The WEB response almost never has direct stream URLs; use it only when it is already
        // cached (from /feeds/api/videos/ID) instead of spending a request on it first.
        JSONObject web;
        synchronized (PLAYER_CACHE) {
            web = PLAYER_CACHE.get("player:" + videoId + ":en");
        }
        if (web != null && findModeUrl(web, mode) != null) return web;
        for (PlayerProfile profile : PLAYBACK_PROFILES) {
            try {
                JSONObject candidate = fetchProfilePlayer(videoId, config, profile);
                latest = candidate;
                if (findModeUrl(candidate, mode) != null) return candidate;
            } catch (Exception error) {
                lastError = error;
                android.util.Log.w("PlayerLookup", profile.name
                        + " player profile failed: " + error.getMessage());
            }
        }
        if (web == null) {
            try {
                JSONObject candidate = fetch(videoId, config, null);
                if (findModeUrl(candidate, mode) != null || latest == null) latest = candidate;
            } catch (Exception error) {
                lastError = error;
                android.util.Log.w("PlayerLookup", "WEB player profile failed: " + error.getMessage());
            }
        }
        if (latest == null && lastError != null) throw lastError;
        if (latest == null) throw new java.io.IOException("YouTube returned no player response");
        return latest;
    }

    private static JSONObject fetchProfilePlayer(String videoId, Config config, PlayerProfile profile)
            throws Exception {
        return cachedPlayer("player:" + videoId + ":" + profile.name + ":" + profile.version,
                new PlayerLoader() {
            @Override
            public JSONObject load() throws Exception {
                JSONObject client = new JSONObject()
                        .put("clientName", profile.name)
                        .put("clientVersion", profile.version)
                        .put("hl", "en")
                    .put("gl", config.getString("gl", "US"));
                if (!profile.platform.isEmpty()) client.put("platform", profile.platform);
                if (!profile.osName.isEmpty()) client.put("osName", profile.osName);
                if (!profile.osVersion.isEmpty()) client.put("osVersion", profile.osVersion);
                if (!profile.deviceMake.isEmpty()) client.put("deviceMake", profile.deviceMake);
                if (!profile.deviceModel.isEmpty()) client.put("deviceModel", profile.deviceModel);
                String visitor = "ANDROID_VR".equals(profile.name) ? freshVisitorData(config)
                        : config.getString("visitor_data", "");
                if (!visitor.isEmpty()) client.put("visitorData", visitor);
                if (profile.androidSdkVersion > 0) {
                    client.put("androidSdkVersion", profile.androidSdkVersion);
                }
                JSONObject requestContext = new JSONObject().put("client", client);
                if (!profile.clientScreen.isEmpty()) {
                    client.put("clientScreen", profile.clientScreen);
                    requestContext.put("thirdParty", new JSONObject().put("embedUrl",
                            "https://www.youtube.com/embed/" + videoId));
                }
                JSONObject payload = new JSONObject()
                        .put("context", requestContext)
                        .put("videoId", videoId)
                        .put("contentCheckOk", true)
                        .put("racyCheckOk", true);
                java.util.Map<String, String> headers = new HashMap<String, String>();
                headers.put("X-YouTube-Client-Name", profile.protoId);
                headers.put("X-YouTube-Client-Version", profile.version);
                if ("ANDROID_VR".equals(profile.name) && !visitor.isEmpty()) {
                    headers.put("X-Goog-Visitor-Id", visitor);
                }
                return InnertubeEndpoint.request("player", payload, config,
                        profile.userAgent, headers);
            }
        });
    }

    /**
     * Video-only H.264 stream up to {@code maxHeight} and an AAC stream for the experimental
     * muxer, or null. Both need direct URLs and init/index ranges (fragmented MP4).
     */
    public static JSONObject[] findMuxFormats(JSONObject player, int maxHeight) {
        JSONArray formats = adaptiveFormats(player);
        if (formats == null) return null;
        JSONObject bestVideo = null;
        int bestVideoScore = Integer.MIN_VALUE;
        JSONObject bestAudio = null;
        long bestAudioScore = Long.MIN_VALUE;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null || format.optJSONObject("initRange") == null
                    || format.optJSONObject("indexRange") == null
                    || playableFormatUrl(format).isEmpty()) continue;
            String mime = baseMimeType(format);
            String codecs = format.optString("mimeType", "").toLowerCase(java.util.Locale.US);
            if ("video/mp4".equals(mime) && codecs.contains("avc1")) {
                int quality = qualityHeight(format);
                if (quality <= 0 || quality > maxHeight) continue;
                int score = quality * 10 + (format.optInt("fps", 30) <= 30 ? 1 : 0);
                if (score > bestVideoScore) {
                    bestVideoScore = score;
                    bestVideo = format;
                }
            } else if ("audio/mp4".equals(mime)) {
                JSONObject track = format.optJSONObject("audioTrack");
                boolean defaultTrack = track == null || track.optBoolean("audioIsDefault", false);
                boolean drc = format.optBoolean("isDrc", false);
                long score = (defaultTrack ? 2_000_000_000L : 0) + (drc ? 0 : 1_000_000_000L)
                        + format.optInt("averageBitrate", format.optInt("bitrate", 0));
                if (score > bestAudioScore) {
                    bestAudioScore = score;
                    bestAudio = format;
                }
            }
        }
        return bestVideo == null || bestAudio == null ? null : new JSONObject[]{bestVideo, bestAudio};
    }

    /**
     * The audio-only MP4 stream the apps ask for when they keep playing in the background. The
     * track of the video itself wins over a dub, and the loudness-normalised copy loses, so a
     * Polish video is not continued with its English dub. Returns the format as the player
     * answer writes it, ranges and all, or null when the video has none.
     */
    public static JSONObject findAudioFormat(JSONObject player) {
        JSONArray formats = adaptiveFormats(player);
        if (formats == null) return null;
        JSONObject best = null;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null || !"audio/mp4".equals(baseMimeType(format))) continue;
            if (format.optJSONObject("initRange") == null
                    || format.optJSONObject("indexRange") == null) continue;
            if (playableFormatUrl(format).isEmpty()) continue;
            JSONObject track = format.optJSONObject("audioTrack");
            boolean defaultTrack = track == null || track.optBoolean("audioIsDefault", false);
            boolean drc = format.optBoolean("isDrc", false);
            long score = (defaultTrack ? 2_000_000_000L : 0) + (drc ? 0 : 1_000_000_000L)
                    + format.optInt("averageBitrate", format.optInt("bitrate", 0));
            if (score > bestScore) {
                bestScore = score;
                best = format;
            }
        }
        return best;
    }

    /** Direct (deciphered) URL of an adaptive format. */
    public static String formatUrl(JSONObject format) {
        return playableFormatUrl(format);
    }

    /** Returns the stream URL for a playback mode ("360p", "video1080", "audio", "mux720"), or null. */
    public static String findModeUrl(JSONObject player, String mode) {
        if ("mux720".equals(mode)) {
            JSONObject[] mux = findMuxFormats(player, 720);
            return mux == null ? null : playableFormatUrl(mux[0]);
        }
        if ("video1080".equals(mode)) return find1080pVideoUrl(player);
        if ("audio".equals(mode)) return findBestAudioUrl(player);
        return findProgressive360pUrl(player);
    }

    /**
     * Picks the best video-only MP4 stream up to 1080p. H.264 (avc1) is preferred because older
     * devices cannot decode VP9/AV1; vertical videos (Shorts) are ranked by their quality label,
     * so a 1080x1920 stream counts as 1080p. When a video has no 1080p stream the highest lower
     * quality is used instead of failing.
     */
    public static String find1080pVideoUrl(JSONObject player) {
        JSONArray formats = adaptiveFormats(player);
        if (formats == null) return null;
        String bestUrl = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null || !baseMimeType(format).equals("video/mp4")) continue;
            String url = playableFormatUrl(format);
            if (url.isEmpty()) continue;
            int quality = qualityHeight(format);
            if (quality <= 0) continue;
            String codecs = format.optString("mimeType", "").toLowerCase(java.util.Locale.US);
            int codecRank = codecs.contains("avc1") ? 2 : codecs.contains("av01") ? 0 : 1;
            // Streams above 1080p rank below every stream at or under it.
            int qualityRank = quality <= 1080 ? quality : 1080 - quality;
            int score = qualityRank * 10 + codecRank * 3
                    + (format.optInt("fps", 30) <= 30 ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    /**
     * Picks the highest-bitrate audio stream, preferring AAC (audio/mp4) for old Android
     * players, the original/default audio track over dubs, and non-DRC variants.
     */
    public static String findBestAudioUrl(JSONObject player) {
        JSONArray formats = adaptiveFormats(player);
        if (formats == null) return null;
        String bestUrl = null;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null || !baseMimeType(format).startsWith("audio/")) continue;
            String url = playableFormatUrl(format);
            if (url.isEmpty()) continue;
            int bitrate = format.optInt("averageBitrate", format.optInt("bitrate", 0));
            JSONObject track = format.optJSONObject("audioTrack");
            boolean defaultTrack = track == null || track.optBoolean("audioIsDefault", false);
            boolean drc = format.optBoolean("isDrc", false) || url.contains("drc%3D1")
                    || url.contains("xtags=drc");
            long score = ("audio/mp4".equals(baseMimeType(format)) ? 4_000_000_000L : 0)
                    + (defaultTrack ? 2_000_000_000L : 0)
                    + (drc ? 0 : 1_000_000_000L)
                    + bitrate;
            if (score > bestScore) {
                bestScore = score;
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    /** Height as YouTube labels the quality ("1080p60" -> 1080), independent of orientation. */
    private static int qualityHeight(JSONObject format) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([0-9]+)p")
                .matcher(format.optString("qualityLabel", ""));
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        int width = format.optInt("width", 0);
        int height = format.optInt("height", 0);
        if (width > 0 && height > 0) return Math.min(width, height);
        return height;
    }

    private static String baseMimeType(JSONObject format) {
        String mimeType = format.optString("mimeType", "").toLowerCase(java.util.Locale.US);
        int separator = mimeType.indexOf(';');
        return separator < 0 ? mimeType : mimeType.substring(0, separator).trim();
    }

    private static JSONArray adaptiveFormats(JSONObject player) {
        JSONObject streamingData = player.optJSONObject("streamingData");
        return streamingData == null ? null : streamingData.optJSONArray("adaptiveFormats");
    }

    /** Cached responses are shared between requests and must be treated as read-only. */
    private static JSONObject cachedPlayer(String key, PlayerLoader loader) throws Exception {
        Object lock;
        synchronized (PLAYER_CACHE) {
            JSONObject cached = PLAYER_CACHE.get(key);
            if (cached != null) return cached;
            lock = PLAYER_LOCKS.get(key);
            if (lock == null) {
                lock = new Object();
                PLAYER_LOCKS.put(key, lock);
            }
        }
        try {
            synchronized (lock) {
                long generation;
                synchronized (PLAYER_CACHE) {
                    JSONObject cached = PLAYER_CACHE.get(key);
                    if (cached != null) return cached;
                    generation = playerCacheGeneration;
                }
                JSONObject response = loader.load();
                synchronized (PLAYER_CACHE) {
                    if (generation == playerCacheGeneration) {
                        PLAYER_CACHE.put(key, response);
                        while (PLAYER_CACHE.size() > PLAYER_CACHE_LIMIT) {
                            Iterator<String> oldest = PLAYER_CACHE.keySet().iterator();
                            oldest.next();
                            oldest.remove();
                        }
                    }
                }
                return response;
            }
        } finally {
            synchronized (PLAYER_CACHE) {
                if (PLAYER_LOCKS.get(key) == lock) PLAYER_LOCKS.remove(key);
            }
        }
    }

    private interface PlayerLoader {
        JSONObject load() throws Exception;
    }

    private static final class PlayerProfile {
        final String name;
        final String version;
        final String userAgent;
        final String protoId;
        final String platform;
        final String osName;
        final String osVersion;
        final String deviceMake;
        final String deviceModel;
        final String clientScreen;
        final int androidSdkVersion;

        PlayerProfile(String name, String version, String userAgent, String protoId,
                      String platform, String osName, String osVersion, String deviceMake,
                      String deviceModel, String clientScreen, int androidSdkVersion) {
            this.name = name;
            this.version = version;
            this.userAgent = userAgent;
            this.protoId = protoId;
            this.platform = platform;
            this.osName = osName;
            this.osVersion = osVersion;
            this.deviceMake = deviceMake;
            this.deviceModel = deviceModel;
            this.clientScreen = clientScreen;
            this.androidSdkVersion = androidSdkVersion;
        }
    }

    private static String playableFormatUrl(JSONObject format) {
        String url = format.optString("url", "");
        if (!url.isEmpty()) return url;

        String cipher = format.optString("signatureCipher", format.optString("cipher", ""));
        if (cipher.isEmpty()) return "";
        try {
            String cipherUrl = null;
            String signature = null;
            String signatureParameter = "signature";
            for (String pair : cipher.split("&")) {
                int separator = pair.indexOf('=');
                if (separator < 0) continue;
                String key = URLDecoder.decode(pair.substring(0, separator), "UTF-8");
                String value = URLDecoder.decode(pair.substring(separator + 1), "UTF-8");
                if ("url".equals(key)) cipherUrl = value;
                else if ("sig".equals(key) || "signature".equals(key)) signature = value;
                else if ("sp".equals(key)) signatureParameter = value;
            }
            if (cipherUrl != null && signature != null) {
                String joiner = cipherUrl.contains("?") ? "&" : "?";
                return cipherUrl + joiner + URLEncoder.encode(signatureParameter, "UTF-8")
                        + "=" + URLEncoder.encode(signature, "UTF-8");
            }
        } catch (Exception ignored) { }
        return "";
    }
}
