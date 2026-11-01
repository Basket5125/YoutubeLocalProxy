package com.viitube.proxy;

import android.content.Context;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Serves the old Google Play Music app (the "skyjam" API at /sj/v1/* and the stream at
 * /music/mplay), backed by YouTube Music. The app was patched to send these to this proxy
 * instead of Google. Everything is HTTP and JSON, so each request is logged and answered here;
 * the account token the app carries is ignored, so any signed-in account gets through.
 *
 * <p>This first cut logs every request and returns the little the app needs to keep going, so
 * the real request shapes can be read off the device before each one is filled in.
 */
public class GpmEndpoint implements Endpoint {

    private static final String TAG = "Gpm";

    /**
     * Album art the search picked for a track (a square YouTube Music thumbnail), by video id.
     * The app fetches art from this proxy as {@code /art/<videoId>}, and this is the better picture
     * to hand back than the 16:9 video frame at i.ytimg.com.
     */
    private static final java.util.Map<String, String> ART_URLS =
            new java.util.LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > MemoryBudget.entries(256);
                }
            };

    /** Remembers the album art URL a search found for a video, so {@link #art} can serve it. */
    static void rememberArt(String videoId, String url) {
        if (videoId == null || videoId.isEmpty() || url == null || url.isEmpty()) return;
        synchronized (ART_URLS) {
            ART_URLS.put(videoId, url);
        }
    }

    private static String rememberedArt(String videoId) {
        synchronized (ART_URLS) {
            return ART_URLS.get(videoId);
        }
    }

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] pathParams,
                                     Config config, Context context) throws Exception {
        if (!config.getBool("gpm_enabled", false)) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                    "text/plain", "play music support is off");
        }
        String uri = session.getUri();
        String query = session.getQueryParameterString();
        String body = readBody(session);
        android.util.Log.i(TAG, session.getMethod() + " " + uri
                + (query == null || query.isEmpty() ? "" : "?" + query)
                + (body.isEmpty() ? "" : " body=" + body));

        if (uri.startsWith("/art/")) return art(uri.substring("/art/".length()));
        String method = lastSegment(uri);
        String json = GpmResponses.forMethod(method, session, query, body, config, context);
        if (json != null) return json(json);

        if (uri.contains("/music/mplay") || uri.contains("/music/fplay")) {
            return GpmStream.serve(session, config, context);
        }
        // Unknown call: an empty object, so a parser does not choke while this is filled in.
        return json("{}");
    }

    /** Serves a track's album art: the YouTube thumbnail, fetched here so the app gets it over HTTP. */
    private static NanoHTTPD.Response art(String videoId) {
        int slash = videoId.indexOf('/');
        if (slash >= 0) videoId = videoId.substring(0, slash);
        int q = videoId.indexOf('?');
        if (q >= 0) videoId = videoId.substring(0, q);
        if (!InnertubeEndpoint.isValidVideoId(videoId)) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                    "text/plain", "no art");
        }
        // The square album art the search found, then the video frame as a fallback.
        java.util.List<String> urls = new java.util.ArrayList<String>();
        String remembered = rememberedArt(videoId);
        if (remembered != null && !remembered.isEmpty()) urls.add(remembered);
        for (String name : new String[]{"hqdefault.jpg", "mqdefault.jpg", "default.jpg"}) {
            urls.add("https://i.ytimg.com/vi/" + videoId + "/" + name);
        }
        for (String imageUrl : urls) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(imageUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    continue;
                }
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                java.io.InputStream in = conn.getInputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                in.close();
                byte[] image = out.toByteArray();
                String type = conn.getContentType();
                if (type == null || !type.startsWith("image/")) type = "image/jpeg";
                NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK, type,
                        new java.io.ByteArrayInputStream(image), image.length);
                response.addHeader("Cache-Control", "public, max-age=86400");
                return response;
            } catch (Exception e) {
                android.util.Log.i(TAG, "art " + imageUrl + " failed: " + e);
            }
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                "text/plain", "no art");
    }

    private static NanoHTTPD.Response json(String body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", body);
        response.addHeader("Cache-Control", "no-cache, no-store");
        return response;
    }

    private static String lastSegment(String uri) {
        int q = uri.indexOf('?');
        String path = q < 0 ? uri : uri.substring(0, q);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String readBody(NanoHTTPD.IHTTPSession session) {
        try {
            Map<String, String> files = new java.util.HashMap<String, String>();
            session.parseBody(files);
            String data = files.get("postData");
            return data == null ? "" : data;
        } catch (Exception e) {
            return "";
        }
    }
}
