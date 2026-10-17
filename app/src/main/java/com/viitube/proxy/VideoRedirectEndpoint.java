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
        Mp4Mux.Plan plan;
        synchronized (PLANS) {
            plan = PLANS.get(videoId);
        }
        if (plan == null || System.currentTimeMillis() - plan.createdAt > PLAN_MAX_AGE_MS) {
            long started = System.currentTimeMillis();
            JSONObject[] formats = null;
            for (JSONObject player : PlayerLookup.fetchPlaybackCandidatesForMux(videoId, config)) {
                JSONObject[] candidate = PlayerLookup.findMuxFormats(player, 720);
                if (candidate != null && endReachable(candidate[0]) && endReachable(candidate[1])) {
                    formats = candidate;
                    break;
                }
            }
            if (formats == null) {
                throw new java.io.IOException("YouTube serves only the first minute of the 720p "
                        + "streams without a PO token");
            }
            plan = Mp4Mux.prepareBest(formats[0], formats[1], PlayerLookup.formatUrl(formats[0]),
                    PlayerLookup.formatUrl(formats[1]));
            android.util.Log.i("Mp4Mux", videoId + ": " + (plan.classic ? "classic" : "fragmented")
                    + " MP4, video itag " + formats[0].optInt("itag") + " + audio itag "
                    + formats[1].optInt("itag") + ", " + plan.pieces.size() + " chunks, "
                    + plan.length / (1024 * 1024) + " MB, prepared in "
                    + (System.currentTimeMillis() - started) + "ms");
            synchronized (PLANS) {
                PLANS.put(videoId, plan);
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

        // 302, not 301: a permanent redirect is cached by players and HTTP stacks, which kept
        // serving the stream of the previously selected format (and expired stream URLs).
        NanoHTTPD.Response resp = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FOUND, "text/plain", "");
        resp.addHeader("Location", url);
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.addHeader("Pragma", "no-cache");
        resp.addHeader("Expires", "0");
        return resp;
    }
}
