package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;

import java.util.Map;

/**
 * Zamiast download_video()+ffmpeg z Pythona: tylko HTTP redirect na oryginalny plik z googlevideo.com.
 * Zero pobierania i transkodowania na telefonie - cały ciężki ruch (sam film) idzie już
 * bezpośrednio telefon <-> Google.
 */
public class VideoRedirectEndpoint implements Endpoint {

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                      Config config, Context context) throws Exception {
        Map<String, String> qs = session.getParms();
        String videoId = params.length > 0 ? params[0] : qs.get("v");
        if (videoId == null) {
            videoId = qs.get("video_id");
        }
        if (videoId == null || videoId.length() != 11) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain", "missing/invalid video id");
        }

        JSONObject player = PlayerLookup.fetchProgressivePlayer(videoId, config, context);
        int itag = config.getInt("preferred_itag", 18);
        boolean strict360p = params.length > 0;
        String url = strict360p
            ? PlayerLookup.findProgressive360pUrl(player)
            : PlayerLookup.findStreamUrl(player, itag);
        if (url == null && !strict360p) url = PlayerLookup.findProgressive360pUrl(player);

        if (url == null) {
            String reason = "YouTube nie udostępnił gotowego URL progresywnego MP4 360p";
            try {
                JSONObject formats = player.optJSONObject("streamingData");
                if (formats == null || formats.optJSONArray("formats") == null
                        || formats.optJSONArray("formats").length() == 0) {
                    reason = "YouTube nie zwrócił progresywnych formatów MP4";
                } else {
                    reason += "; dostępny format może wymagać odszyfrowania signatureCipher";
                }
            } catch (Exception ignored) { }
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain",
                    reason);
        }

        NanoHTTPD.Response resp = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.REDIRECT, "text/plain", "");
        resp.addHeader("Location", url);
        return resp;
    }
}
