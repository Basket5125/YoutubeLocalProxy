package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Zamiast download_video()+ffmpeg z Pythona: tylko HTTP redirect na oryginalny plik z googlevideo.com.
 * Zero pobierania i transkodowania na telefonie - cały ciężki ruch (sam film) idzie już
 * bezpośrednio telefon <-> Google.
 */
public class VideoRedirectEndpoint implements Endpoint {

    private static final long PLAN_MAX_AGE_MS = 60L * 60 * 1000;
    private static final java.util.Map<String, Mp4Mux.Plan> PLANS =
            new java.util.LinkedHashMap<String, Mp4Mux.Plan>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Mp4Mux.Plan> eldest) {
                    return size() > 8;
                }
            };

    /**
     * Experimental 720p: the 720p H.264 video-only stream and the AAC stream are muxed on the
     * phone while playing (see {@link Mp4Mux}). Players request the file several times with
     * Range headers, so the layout is kept per video.
     */
    /** YouTube answers 403 past about a minute when a URL needs a PO token: probe the end. */
    private static boolean endReachable(JSONObject format) {
        long length = format.optLong("contentLength", 0);
        if (length <= 0) return false;
        try {
            HttpClient.getRange(PlayerLookup.formatUrl(format), length - 1024, length - 1,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            return true;
        } catch (java.io.IOException e) {
            android.util.Log.i("Mp4Mux", "itag " + format.optInt("itag") + " stops early: " + e.getMessage());
            return false;
        }
    }

    private static NanoHTTPD.Response muxed(NanoHTTPD.IHTTPSession session, String videoId,
                                            Config config) throws Exception {
        return muxed(session, videoId, config, 720);
    }

    private static NanoHTTPD.Response muxed(NanoHTTPD.IHTTPSession session, String videoId,
                                            Config config, int maxHeight) throws Exception {
        String planKey = videoId + "|" + maxHeight;
        Mp4Mux.Plan plan;
        synchronized (PLANS) {
            plan = PLANS.get(planKey);
        }
        if (plan == null || System.currentTimeMillis() - plan.createdAt > PLAN_MAX_AGE_MS) {
            long started = System.currentTimeMillis();
            JSONObject[] formats = null;
            String[] urls = null;
            // An Invidious instance serves the two 720p streams itself, so they stay reachable
            // past the first minute; YouTube's own links do not without a PO token.
            InvidiousLookup.Streams instance = InvidiousLookup.streams(videoId, config);
            InvidiousLookup.Pair pair = instance == null ? null : instance.pair(maxHeight);
            if (pair != null) {
                formats = new JSONObject[]{pair.video, pair.audio};
                urls = new String[]{pair.videoUrl, pair.audioUrl};
            }
            for (JSONObject player : formats != null
                    ? java.util.Collections.<JSONObject>emptyList()
                    : PlayerLookup.fetchPlaybackCandidatesForMux(videoId, config)) {
                JSONObject[] candidate = PlayerLookup.findMuxFormats(player, maxHeight);
                if (candidate != null && endReachable(candidate[0]) && endReachable(candidate[1])) {
                    formats = candidate;
                    break;
                }
            }
            if (formats == null) {
                throw new java.io.IOException("YouTube serves only the first minute of the 720p "
                        + "streams without a PO token");
            }
            if (urls == null) {
                urls = new String[]{PlayerLookup.formatUrl(formats[0]),
                        PlayerLookup.formatUrl(formats[1])};
            }
            plan = Mp4Mux.prepareBest(formats[0], formats[1], urls[0], urls[1]);
            android.util.Log.i("Mp4Mux", videoId + ": " + (plan.classic ? "classic" : "fragmented")
                    + " MP4, video itag " + formats[0].optInt("itag") + " + audio itag "
                    + formats[1].optInt("itag") + ", " + plan.pieces.size() + " chunks, "
                    + plan.length / (1024 * 1024) + " MB, prepared in "
                    + (System.currentTimeMillis() - started) + "ms");
            synchronized (PLANS) {
                PLANS.put(planKey, plan);
            }
        }

        long start = 0;
        long end = plan.length - 1;
        String range = session.getHeaders().get("range");
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String[] bounds = range.substring(6).split(",")[0].split("-", 2);
            try {
                if (!bounds[0].trim().isEmpty()) start = Long.parseLong(bounds[0].trim());
                if (bounds.length > 1 && !bounds[1].trim().isEmpty()) {
                    end = Math.min(end, Long.parseLong(bounds[1].trim()));
                }
                partial = true;
            } catch (NumberFormatException ignored) { }
        }
        if (start >= plan.length || start > end) {
            NanoHTTPD.Response invalid = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
            invalid.addHeader("Content-Range", "bytes */" + plan.length);
            return invalid;
        }
        long length = end - start + 1;
        android.util.Log.i("Mp4Mux", session.getMethod() + " " + videoId + " bytes " + start + "-" + end
                + " (" + (range == null ? "no Range header" : range) + "), user agent: "
                + session.getHeaders().get("user-agent"));
        java.io.InputStream body = session.getMethod() == NanoHTTPD.Method.HEAD
                ? new java.io.ByteArrayInputStream(new byte[0]) : Mp4Mux.open(plan, start);
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                partial ? NanoHTTPD.Response.Status.PARTIAL_CONTENT : NanoHTTPD.Response.Status.OK,
                "video/mp4", body, length);
        response.addHeader("Accept-Ranges", "bytes");
        if (partial) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + plan.length);
        response.addHeader("Cache-Control", "no-cache, no-store");
        return response;
    }

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                      Config config, Context context) throws Exception {
        Map<String, String> qs = session.getParms();
        String videoId = params.length > 0 ? params[0] : qs.get("v");
        if (videoId == null) {
            videoId = qs.get("video_id");
        }
        // yt2009-style links append suffixes such as "VIDEO_ID/lower" or "VIDEO_ID/mp4".
        if (videoId != null && videoId.length() > 11
                && InnertubeEndpoint.isValidVideoId(videoId.substring(0, 11))) {
            videoId = videoId.substring(0, 11);
        }
        if (!InnertubeEndpoint.isValidVideoId(videoId)) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain", "missing/invalid video id");
        }

        // Read on every request so a format change in the app applies to the next video.
        String mode = config.getString("playback_mode", "360p");
        // A request can name the quality it wants: "q" for the links this proxy writes, "fmt"
        // for the legacy clients, which ask for a format number.
        int wanted = requestedHeight(qs);
        if (wanted == 360) {
            // 360p plays straight from YouTube, with no instance in between.
            JSONObject direct = PlayerLookup.fetchPlaybackPlayer(videoId, config, "360p");
            String url360 = PlayerLookup.findProgressive360pUrl(direct);
            if (url360 != null) return redirectTo(url360);
        }
        // The other formats are muxed from the streams of the Invidious instance, whose links
        // keep working past the first minute.
        if ((wanted > 360 || (wanted == 0 && !"360p".equals(mode)))
                && !InvidiousLookup.instance(config).isEmpty()) {
            try {
                return muxed(session, videoId, config, wanted > 0 ? wanted : 720);
            } catch (Exception e) {
                android.util.Log.w("VideoRedirect", "No 720p from the Invidious instance for "
                        + videoId + ": " + e);
            }
            String single = InvidiousLookup.progressiveUrl(videoId, config);
            if (single != null) return redirectTo(single);
        }
        if ("mux720".equals(mode)) {
            try {
                return muxed(session, videoId, config);
            } catch (Exception e) {
                android.util.Log.w("VideoRedirect", "720p mux failed for " + videoId
                        + "; falling back to 360p: " + e);
                mode = "360p";
            }
        }
        JSONObject player = PlayerLookup.fetchPlaybackPlayer(videoId, config, mode);
        String url = PlayerLookup.findModeUrl(player, mode);
        if (url == null && !"360p".equals(mode)) {
            android.util.Log.w("VideoRedirect", "No " + mode + " stream for " + videoId
                    + "; falling back to 360p");
            JSONObject fallback = PlayerLookup.fetchPlaybackPlayer(videoId, config, "360p");
            url = PlayerLookup.findProgressive360pUrl(fallback);
            if (url != null) player = fallback;
        }

        if (url == null) {
            JSONObject playability = player.optJSONObject("playabilityStatus");
            String playabilityStatus = playability == null ? "" : playability.optString("status", "");
            String playabilityReason = playability == null ? "" : playability.optString("reason", "");
            if (!playabilityStatus.isEmpty() && !"OK".equals(playabilityStatus)) {
                String message = "YouTube playback unavailable (" + playabilityStatus + ")"
                        + (playabilityReason.isEmpty() ? "" : ": " + playabilityReason);
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                        "text/plain; charset=utf-8", message);
            }
            String reason = "YouTube did not provide a direct stream for playback mode " + mode;
            try {
                JSONObject formats = player.optJSONObject("streamingData");
                JSONArray adaptive = formats == null ? null : formats.optJSONArray("adaptiveFormats");
                JSONArray progressive = formats == null ? null : formats.optJSONArray("formats");
                if ((adaptive == null || adaptive.length() == 0)
                        && (progressive == null || progressive.length() == 0)) {
                    reason += ": YouTube returned no playable formats";
                } else {
                    reason += "; the available format may require signature deciphering";
                }
            } catch (Exception ignored) { }
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain",
                    reason);
        }

        return redirectTo(url);
    }

    /**
     * The height a request asks for: "q=720" from the links this proxy writes, or the format
     * number of the legacy clients ("fmt=22"). 0 when the request does not say.
     */
    private static int requestedHeight(Map<String, String> query) {
        int height = parseHeight(query.get("q"));
        if (height > 0) return height;
        String fmt = query.get("fmt");
        if (fmt == null) return 0;
        String value = fmt.trim();
        if ("37".equals(value)) return 1080;
        if ("22".equals(value)) return 720;
        if ("35".equals(value)) return 480;
        if ("34".equals(value) || "18".equals(value) || "3".equals(value)) return 360;
        if ("5".equals(value) || "6".equals(value) || "1".equals(value)) return 360;
        return parseHeight(value);
    }

    private static int parseHeight(String value) {
        if (value == null) return 0;
        try {
            int height = Integer.parseInt(value.trim().replace("p", ""));
            return height >= 144 && height <= 2160 ? height : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 302, not 301: a permanent redirect is cached by players and HTTP stacks, which kept
     * serving the stream of the previously selected format (and expired stream URLs).
     */
    private static NanoHTTPD.Response redirectTo(String url) {
        NanoHTTPD.Response resp = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FOUND, "text/plain", "");
        resp.addHeader("Location", url);
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.addHeader("Pragma", "no-cache");
        resp.addHeader("Expires", "0");
        return resp;
    }
}
