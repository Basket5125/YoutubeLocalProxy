package com.viitube.proxy;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * Odpowiednik fetch_video_details()/GetVideoInfo z Pythona: odpytuje youtubei/v1/player,
 * cache'uje na dysku (jak is_cache_valid/mtime), i wyciąga bezpośredni URL strumienia.
 */
public class PlayerLookup {

    private static final long CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    public static JSONObject fetch(String videoId, Config config, Context context) throws Exception {
        return fetch(videoId, config, context, "en");
    }

    public static JSONObject fetch(String videoId, Config config, Context context, String language)
            throws Exception {
        File cache = HttpClient.cacheFile(context.getCacheDir(), "videoinfo_v2",
                videoId + "_" + language);
        if (HttpClient.isFresh(cache, CACHE_MAX_AGE_MS)) {
            return new JSONObject(HttpClient.readFile(cache));
        }

        JSONObject payload = InnertubeEndpoint.context(config).put("videoId", videoId);
        payload.getJSONObject("context").getJSONObject("client").put("hl", language);
        JSONObject response = InnertubeEndpoint.request("player", payload, config);
        HttpClient.writeFile(cache, response.toString());
        return response;
    }

    public static JSONObject fetchUploadMetadata(String videoId, Config config, Context context)
            throws Exception {
        File cache = HttpClient.cacheFile(context.getCacheDir(), "upload_player_v1", videoId);
        if (HttpClient.isFresh(cache, CACHE_MAX_AGE_MS)) {
            JSONObject cached = new JSONObject(HttpClient.readFile(cache));
            if (cached.optJSONObject("videoDetails") != null) return cached;
        }

        JSONObject payload = InnertubeEndpoint.context(config)
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true);
        JSONObject client = payload.getJSONObject("context").getJSONObject("client");
        client.put("clientScreen", "WATCH")
                .put("originalUrl", "https://www.youtube.com/watch?v=" + videoId);
        JSONObject response = InnertubeEndpoint.requestFast("player", payload, config);
        if (response.optJSONObject("videoDetails") != null) {
            HttpClient.writeFile(cache, response.toString());
        }
        return response;
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
        JSONObject player = fetch(videoId, config, context);
        if (findProgressive360pUrl(player) != null) return player;

        String[][] profiles = {
                {"ANDROID", "21.16", "com.google.android.youtube/21.16.256 (Linux; U; Android 14) gzip"},
                {"MWEB", "2.20250101.00.00", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"}
        };
        JSONObject latest = player;
        for (String[] profile : profiles) {
            File cache = HttpClient.cacheFile(context.getCacheDir(), "playback_v2", videoId + "_" + profile[0]);
            JSONObject candidate = null;
            if (HttpClient.isFresh(cache, 15L * 60 * 1000)) {
                try { candidate = new JSONObject(HttpClient.readFile(cache)); }
                catch (Exception ignored) { }
            }
            if (candidate == null) {
                JSONObject client = new JSONObject()
                        .put("clientName", profile[0])
                        .put("clientVersion", profile[1])
                        .put("hl", "en")
                        .put("gl", "US");
                if ("ANDROID".equals(profile[0])) {
                    client.put("deviceMake", "Google")
                            .put("deviceModel", "Android")
                            .put("osName", "Android")
                            .put("osVersion", "14");
                }
                JSONObject payload = new JSONObject()
                        .put("context", new JSONObject().put("client", client))
                        .put("videoId", videoId)
                        .put("contentCheckOk", true)
                        .put("racyCheckOk", true);
                candidate = InnertubeEndpoint.request("player", payload, config, profile[2]);
                HttpClient.writeFile(cache, candidate.toString());
            }
            latest = candidate;
            if (findProgressive360pUrl(candidate) != null) return candidate;
        }
        return latest;
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
