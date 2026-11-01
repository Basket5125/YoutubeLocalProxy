package com.viitube.proxy;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Gets the sound of a video ready before it is asked for.
 *
 * <p>The apps from version 10 on switch to the file that holds only sound the moment the screen
 * goes off. Looking that file up then - a player answer, and with an Invidious instance a second
 * lookup on top of it - is what made the sound come back seconds after the screen went dark. So
 * the lookup is done here instead, while the video is still starting, and the first bytes of the
 * stream are fetched as well: the name is resolved, the connection is open and the edge has the
 * file when the app finally asks for it.
 */
final class AudioWarmup {

    private static final String TAG = "AudioWarmup";
    /** A stream URL is good for a few hours; warming the same video again inside this is waste. */
    private static final long AGAIN_AFTER_MS = 5L * 60 * 1000;
    /** Enough to open the connection and make the edge hold the file, and no more. */
    private static final int FIRST_BYTES = 1024;
    /**
     * How long this waits before looking anything up. The screen goes off minutes into a video,
     * while the seconds right after the player answer are the ones in which the video starts -
     * and on an old phone anything else running then is what makes the picture stop.
     */
    private static final long SETTLE_MS = 10000;

    private static final ExecutorService POOL = MemoryBudget.prefetchPool("audio-warmup");
    private static final Map<String, Long> WARMED = new HashMap<String, Long>();

    private AudioWarmup() { }

    /**
     * Starts getting the sound of {@code videoId} ready, and returns at once. {@code player} is
     * the player answer already at hand, or null when there is none and it has to be looked up.
     */
    static void prepare(String videoId, Config config, JSONObject player) {
        if (videoId == null || videoId.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (WARMED) {
            Long when = WARMED.get(videoId);
            if (when != null && now - when.longValue() < AGAIN_AFTER_MS) return;
            // Written before the work runs, so two requests for the same video warm it once.
            WARMED.put(videoId, Long.valueOf(now));
            if (WARMED.size() > 64) WARMED.clear();
        }
        POOL.execute(new Warm(videoId, config, player));
    }

    /** Forgets what was warmed, so the next request looks the streams up again. */
    static void clearMemory() {
        synchronized (WARMED) {
            WARMED.clear();
        }
    }

    private static final class Warm implements Runnable {
        private final String videoId;
        private final Config config;
        private final JSONObject player;

        Warm(String videoId, Config config, JSONObject player) {
            this.videoId = videoId;
            this.config = config;
            this.player = player;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(SETTLE_MS);
                String url = audioUrl();
                if (url.isEmpty()) return;
                HttpClient.getRange(url, 0, FIRST_BYTES - 1,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                android.util.Log.i(TAG, "sound of " + videoId + " ready");
            } catch (Exception e) {
                android.util.Log.i(TAG, "Could not get the sound of " + videoId + " ready: " + e);
            }
        }

        /** The same stream the player answer names for the background, found the same way. */
        private String audioUrl() throws Exception {
            // Only what is already at hand: a lookup of its own would be one more request made
            // while the video is starting, which is what this is supposed to save.
            if (player == null) return "";
            InvidiousLookup.Streams streams = InvidiousLookup.streams(videoId, config);
            if (streams != null && !streams.audioUrl.isEmpty()) return streams.audioUrl;
            JSONObject answer = player;
            JSONObject audio = PlayerLookup.findAudioFormat(answer);
            if (audio != null) {
                String url = PlayerLookup.formatUrl(audio);
                if (url != null && !url.isEmpty()) return url;
            }
            String progressive = PlayerLookup.findProgressive360pUrl(answer);
            return progressive == null ? "" : progressive;
        }
    }
}
