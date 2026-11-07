package com.viitube.proxy;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Serves a Play Music stream request (/music/mplay?mjck=...) with the audio of the matching
 * YouTube track. The store id is the YouTube video id (with the app's leading "T"). The audio
 * (at the bitrate the settings ask for) is fetched from the Invidious instance and remuxed from
 * its DASH fragments into one
 * classic MP4 - Android's MediaPlayer cannot prepare the raw fragments, but it plays this - and
 * handed over as audio/mp4, which is the only thing the download step accepts.
 */
final class GpmStream {

    /**
     * How many built songs are kept. Each holds the whole audio file in memory (4-6 MB for a
     * song), so an old phone with a small heap keeps two: the one playing and the next one.
     */
    private static final int MAX_PLANS = MemoryBudget.SMALL_HEAP ? 2 : 4;

    /**
     * The songs the player read last; their files are never let go. Two, because Play Music asks
     * for the next song ahead of time while the current one still plays (and may be seeked).
     */
    private static volatile String playing = "";
    private static volatile String previous = "";

    /** Built files kept so the app's repeated range requests for one song reuse one remux. */
    private static final Map<String, Mp4Mux.Plan> PLANS =
            new LinkedHashMap<String, Mp4Mux.Plan>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Mp4Mux.Plan> eldest) {
                    // The stream reads the buffer piece by piece, so dropping the one that plays
                    // would cut the song off ("buffer gone"); keep it a little longer instead.
                    boolean evict = size() > MAX_PLANS && !eldest.getKey().equals(playing)
                            && !eldest.getKey().equals(previous);
                    if (evict) Mp4Mux.dropBuffer(eldest.getKey());
                    return evict;
                }
            };

    /**
     * One lock per song, so a build warmed in the background and a real request share it. Typed as
     * the concrete class on purpose: {@code Map.putIfAbsent} is a Java 8 default method and is
     * missing on Android 4.4, but {@code ConcurrentHashMap.putIfAbsent} has been there since Java 5.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS =
            new java.util.concurrent.ConcurrentHashMap<String, Object>();

    /**
     * Warms the likely-next track, so the first play does not wait on the build. One thread only:
     * the limit is the phone's bandwidth to the Invidious instance, so a second concurrent download
     * just halves both and makes the track the user actually taps slower, not faster.
     */
    private static final java.util.concurrent.ExecutorService WARMERS =
            java.util.concurrent.Executors.newFixedThreadPool(1, new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "gpm-warm");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }
            });

    /** The song being warmed and its task, so a tap on a different track can free the bandwidth. */
    private static volatile String warming = "";
    private static volatile java.util.concurrent.Future<?> warmTask;

    private GpmStream() { }

    /**
     * Builds the remux for a track ahead of time, off the request thread, so the tap that follows
     * finds it ready. Called with the top search/Explore result; a build already done or running
     * is left alone.
     */
    static void warm(String videoId, Config config) {
        if (videoId == null || videoId.isEmpty()) return;
        final String songId = videoId.length() == 12 && videoId.charAt(0) == 'T'
                ? videoId.substring(1) : videoId;
        if (!InnertubeEndpoint.isValidVideoId(songId)) return;
        synchronized (PLANS) {
            if (PLANS.containsKey(songId)) return;
        }
        if (songId.equals(warming)) return;
        warming = songId;
        warmTask = WARMERS.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    build(songId, config);
                } catch (Exception e) {
                    android.util.Log.i("Gpm", "warm " + songId + " failed: " + e);
                }
            }
        });
    }

    /** Stops a running warm of a different track, so the one the user tapped gets the bandwidth. */
    private static void cancelWarmExcept(String songId) {
        java.util.concurrent.Future<?> task = warmTask;
        if (task != null && !songId.equals(warming) && !task.isDone()) {
            task.cancel(true);
        }
    }

    /**
     * The remux for a song, built once and cached. The per-song lock means a background warm and
     * the real request never build the same track twice; the second caller waits for the first.
     */
    private static Mp4Mux.Plan build(String songId, Config config) throws Exception {
        synchronized (PLANS) {
            Mp4Mux.Plan ready = PLANS.get(songId);
            if (ready != null) return ready;
        }
        Object created = new Object();
        Object existing = LOCKS.putIfAbsent(songId, created);
        Object lock = existing != null ? existing : created;
        synchronized (lock) {
            synchronized (PLANS) {
                Mp4Mux.Plan ready = PLANS.get(songId);
                if (ready != null) return ready;
            }
            // The Invidious instance is the audio source: it proxies the stream so this
            // server can fetch it (YouTube's own stream urls answer 403 server-side).
            InvidiousLookup.Streams streams = InvidiousLookup.streams(songId, config);
            if (streams == null || streams.audio == null || streams.audioUrl.isEmpty()) {
                throw new java.io.IOException("no audio for " + songId);
            }
            long started = System.currentTimeMillis();
            long clen = parseLong(streams.audio.optString("contentLength", "0"));
            // Download the whole audio once, then remux it from memory: a range request per
            // fragment through Invidious is what made a track take forever to start.
            byte[] audio = download(streams.audioUrl, clen);
            Mp4Mux.putBuffer(songId, audio);
            Mp4Mux.Plan plan;
            try {
                plan = Mp4Mux.prepareAudioClassic(streams.audio, "mem:" + songId);
            } catch (Exception e) {
                Mp4Mux.dropBuffer(songId);
                throw e;
            }
            android.util.Log.i("Gpm", "remuxed " + songId + " to classic m4a, "
                    + plan.length / 1024 + " KB from " + (audio.length / 1024) + " KB in "
                    + (System.currentTimeMillis() - started) + "ms");
            synchronized (PLANS) {
                PLANS.put(songId, plan);
            }
            LOCKS.remove(songId);
            return plan;
        }
    }

    static NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession session, Config config, Context context) {
        String songId = session.getParms().get("songid");
        if (songId == null || songId.isEmpty()) songId = session.getParms().get("mjck");
        if (songId == null || songId.isEmpty()) {
            return text(NanoHTTPD.Response.Status.BAD_REQUEST, "missing songid");
        }
        if (songId.length() == 12 && songId.charAt(0) == 'T') songId = songId.substring(1);
        if (!InnertubeEndpoint.isValidVideoId(songId)) {
            android.util.Log.i("Gpm", "stream id not a YouTube id: " + songId);
            return text(NanoHTTPD.Response.Status.NOT_FOUND, "track not available");
        }
        if (!songId.equals(playing)) {
            previous = playing;
            playing = songId;
        }
        cancelWarmExcept(songId);
        try {
            Mp4Mux.Plan plan = build(songId, config);
            return serve(session, plan);
        } catch (Exception e) {
            android.util.Log.w("Gpm", "stream " + songId + " failed", e);
            return text(NanoHTTPD.Response.Status.INTERNAL_ERROR, "stream error");
        }
    }

    /** Serves the remuxed file, whole or by the range the player asked for, as audio/mp4. */
    private static NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession session, Mp4Mux.Plan plan) {
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
            } catch (NumberFormatException ignored) {
            }
        }
        if (start >= plan.length || start > end) {
            NanoHTTPD.Response invalid = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
            invalid.addHeader("Content-Range", "bytes */" + plan.length);
            return invalid;
        }
        long length = end - start + 1;
        java.io.InputStream body = session.getMethod() == NanoHTTPD.Method.HEAD
                ? new java.io.ByteArrayInputStream(new byte[0]) : Mp4Mux.open(plan, start);
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                partial ? NanoHTTPD.Response.Status.PARTIAL_CONTENT : NanoHTTPD.Response.Status.OK,
                "audio/mp4", body, length);
        response.addHeader("Accept-Ranges", "bytes");
        if (partial) {
            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + plan.length);
        }
        response.addHeader("Cache-Control", "no-cache, no-store");
        return response;
    }

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    /**
     * Reads a whole stream into memory. The length is known (the format's contentLength), so one
     * ranged request fetches it through the same path the muxer uses, which googlevideo serves
     * where a plain full GET is refused.
     */
    private static byte[] download(String url, long length) throws java.io.IOException {
        if (length > 0) return HttpClient.getRange(url, 0, length - 1, UA);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Range", "bytes=0-");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        java.io.InputStream in = conn.getInputStream();
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** When Android is short of memory: let go of every built song except the one playing. */
    static void trimMemory() {
        synchronized (PLANS) {
            java.util.Iterator<String> ids = PLANS.keySet().iterator();
            while (ids.hasNext()) {
                String id = ids.next();
                if (id.equals(playing) || id.equals(previous)) continue;
                Mp4Mux.dropBuffer(id);
                ids.remove();
            }
        }
    }

    static void clearMemory() {
        synchronized (PLANS) {
            for (String id : PLANS.keySet()) Mp4Mux.dropBuffer(id);
            PLANS.clear();
        }
    }

    private static NanoHTTPD.Response text(NanoHTTPD.Response.Status status, String message) {
        return NanoHTTPD.newFixedLengthResponse(status, "text/plain", message);
    }
}
