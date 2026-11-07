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

    /**
     * Covers already served, kept in memory so scrolling back through a list does not fetch them
     * again over the slow link: bounded in bytes (a cover is 40-70 KB), not in entries.
     */
    private static final long ART_BYTES_MAX = MemoryBudget.SMALL_HEAP ? 3L * 1024 * 1024 : 8L * 1024 * 1024;
    private static long artBytes;
    private static final java.util.LinkedHashMap<String, Object[]> ART_CACHE =
            new java.util.LinkedHashMap<String, Object[]>(32, 0.75f, true);

    private static Object[] cachedArt(String videoId) {
        synchronized (ART_CACHE) {
            return ART_CACHE.get(videoId);
        }
    }

    private static void keepArt(String videoId, byte[] image, String type) {
        synchronized (ART_CACHE) {
            if (ART_CACHE.put(videoId, new Object[]{image, type}) == null) artBytes += image.length;
            java.util.Iterator<java.util.Map.Entry<String, Object[]>> it = ART_CACHE.entrySet().iterator();
            while (artBytes > ART_BYTES_MAX && it.hasNext()) {
                artBytes -= ((byte[]) it.next().getValue()[0]).length;
                it.remove();
            }
        }
    }

    static void clearMemory() {
        synchronized (ART_CACHE) {
            ART_CACHE.clear();
            artBytes = 0;
        }
    }

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
                + (body.isEmpty() ? "" : " body=" + (body.length() > 300 ? body.substring(0, 300) + "..." : body)));

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
        Object[] art = InnertubeEndpoint.isValidVideoId(videoId) ? loadArt(videoId) : null;
        if (art == null) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                    "text/plain", "no art");
        }
        return artResponse((byte[]) art[0], (String) art[1]);
    }

    /** One lock per cover, so a prefetch and the app's own request do not fetch it twice. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> ART_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<String, Object>();

    /** Fetches covers in the background, one at a time and at low priority. */
    private static final java.util.concurrent.ThreadPoolExecutor ART_PREFETCH =
            new java.util.concurrent.ThreadPoolExecutor(MemoryBudget.SMALL_HEAP ? 1 : 2,
                    MemoryBudget.SMALL_HEAP ? 1 : 2, 20, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(64),
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    static {
        ART_PREFETCH.allowCoreThreadTimeOut(true);
    }

    /**
     * Fetches the covers of a list the app is about to show. The app gives up on a cover that is
     * slow to come (many at once over the phone's link) and then marks it missing for good, so
     * the covers are made ready here before it asks.
     */
    static void prefetchArt(final java.util.List<String> videoIds) {
        for (final String id : videoIds) {
            if (!InnertubeEndpoint.isValidVideoId(id) || cachedArt(id) != null) continue;
            ART_PREFETCH.execute(new ArtTask(id));
        }
    }

    private static final class ArtTask implements Runnable {
        private final String videoId;

        ArtTask(String videoId) {
            this.videoId = videoId;
        }

        @Override
        public void run() {
            loadArt(videoId);
        }
    }

    /** {image bytes, content type} of a cover, from memory or fetched once; null when none. */
    private static Object[] loadArt(String videoId) {
        Object[] kept = cachedArt(videoId);
        if (kept != null) return kept;
        Object created = new Object();
        Object existing = ART_LOCKS.putIfAbsent(videoId, created);
        Object lock = existing != null ? existing : created;
        synchronized (lock) {
            try {
                kept = cachedArt(videoId);
                if (kept != null) return kept;
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
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(12000);
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                        if (conn.getResponseCode() != 200) {
                            conn.disconnect();
                            continue;
                        }
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
                        java.io.InputStream in = conn.getInputStream();
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                        in.close();
                        byte[] image = out.toByteArray();
                        String type = conn.getContentType();
                        if (type == null || !type.startsWith("image/")) type = "image/jpeg";
                        keepArt(videoId, image, type);
                        return new Object[]{image, type};
                    } catch (Exception e) {
                        android.util.Log.i(TAG, "art " + imageUrl + " failed: " + e);
                    }
                }
                return null;
            } finally {
                ART_LOCKS.remove(videoId);
            }
        }
    }

    private static NanoHTTPD.Response artResponse(byte[] image, String type) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, type, new java.io.ByteArrayInputStream(image), image.length);
        response.addHeader("Cache-Control", "public, max-age=86400");
        return response;
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

    /**
     * The request body as text. Read as raw bytes - not through NanoHTTPD's parseBody, which
     * turns it into a String - because the app gzips its JSON posts (the radio ones among them),
     * and a gzipped body only survives as bytes.
     */
    private static String readBody(NanoHTTPD.IHTTPSession session) {
        try {
            String lengthHeader = session.getHeaders().get("content-length");
            int length = lengthHeader == null ? 0 : Integer.parseInt(lengthHeader.trim());
            if (length <= 0) return "";
            byte[] data = new byte[length];
            java.io.InputStream in = session.getInputStream();
            int read = 0;
            while (read < length) {
                int n = in.read(data, read, length - read);
                if (n < 0) break;
                read += n;
            }
            java.io.InputStream text = new java.io.ByteArrayInputStream(data, 0, read);
            String encoding = session.getHeaders().get("content-encoding");
            boolean gzip = (encoding != null && encoding.toLowerCase(java.util.Locale.US).contains("gzip"))
                    || (read > 1 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b);
            if (gzip) text = new java.util.zip.GZIPInputStream(text);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, read * 4));
            byte[] buffer = new byte[4096];
            int n;
            while ((n = text.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            android.util.Log.i(TAG, "body unreadable: " + e);
            return "";
        }
    }
}
