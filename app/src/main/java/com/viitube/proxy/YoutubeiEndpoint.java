package com.viitube.proxy;

import android.content.Context;
import android.util.Base64;
import com.viitube.proxy.proto.AndroidSearchRequestProto;
import com.viitube.proxy.proto.BareAndroidRequestProto;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Second API next to the GData feeds: the protobuf Innertube API (/youtubei/v1/...) of the
 * 2015-2016 YouTube Android apps. Requests and answers follow the gdatav2innertube server
 * (messages in app/src/main/proto, compiled by tools/protoc_java.py); the data comes from the
 * same YouTube lookups as the GData feeds.
 */
public class YoutubeiEndpoint implements Endpoint {

    private static final String TAG = "Youtubei";
    private static final String PROTOBUF = "application/x-protobuf";
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final int PAGE_SIZE = 20;

    private final String mode;

    public YoutubeiEndpoint(String mode) {
        this.mode = mode;
    }

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                     Config config, Context context) throws Exception {
        // Always read the body: NanoHTTPD would take unread bytes for the next request on a
        // kept-alive connection.
        byte[] body = readBody(session);
        if ("config".equals(mode)) return config(session);
        if ("device_204".equals(mode)) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT, "text/plain", "");
        }
        if ("deviceregistration".equals(mode)) {
            JSONObject device = new JSONObject()
                    .put("id", randomId(9))
                    .put("key", "AP+lc79/lqV58X9FLDdn7SiOzH8hDb1ItXMmm25Cb4YDLWZkI+gXBiwwOvcssAY");
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                    "application/json", device.toString());
        }
        if ("c2dm".equals(mode)) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain",
                    "id=" + randomId(40));
        }
        if ("guide".equals(mode)) return protobuf(YoutubeiResponses.guide());

        if ("search".equals(mode)) return search(body, config, context);
        BareAndroidRequestProto.Root request;
        try {
            request = BareAndroidRequestProto.Root.parseFrom(body);
        } catch (IOException e) {
            return badRequest("Invalid protobuf request: " + e.getMessage());
        }
        if ("browse".equals(mode)) return browse(request, session, config, context);
        if ("next".equals(mode)) return next(request, config, context);
        if ("player".equals(mode)) return player(request, session, config);
        if ("resolve_url".equals(mode)) return resolveUrl(request.getBrowseId(), config);
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "not found");
    }

    // ------------------------------------------------------------------ browse

    private NanoHTTPD.Response browse(BareAndroidRequestProto.Root request, NanoHTTPD.IHTTPSession session,
                                      Config config, Context context) throws Exception {
        String id = request.getBrowseId();
        int version = clientMajorVersion(request, session);
        String gl = InnertubeEndpoint.popularRegion(requestRegion(request), config);
        if (!request.getContinuation().isEmpty()) {
            // The messages known from the reference server have no continuation answer; an
            // empty response ends the list in the app.
            return protobuf(new byte[0]);
        }
        if (id.isEmpty()) id = "FEwhat_to_watch";
        long now = System.currentTimeMillis();
        android.util.Log.i(TAG, "browse " + id + " (app " + version + ", " + gl + ")");

        if (id.startsWith("FEwhat_to_watch")) {
            final boolean v11 = version >= 11;
            String key = "home_" + gl + "_" + (v11 ? "v11" : "v10");
            byte[] cached = cached(context, key, POPULAR_MAX_AGE_MS);
            if (cached != null) return protobuf(cached);
            List<InnertubeEndpoint.Video> home = popular(gl, "", 30, config);
            // Version 11 apps show the same chart as Trending; older apps have a Music tab.
            List<InnertubeEndpoint.Video> second = v11 ? home : popular(gl, "Music", 20, config);
            byte[] answer = YoutubeiResponses.home(version, home, second, now);
            if (!home.isEmpty()) store(context, key, answer);
            return protobuf(answer);
        }
        if (id.startsWith("FEtrending") || id.startsWith("FEmusic")) {
            boolean music = id.startsWith("FEmusic");
            String key = (music ? "music_" : "trending_") + gl;
            byte[] cached = cached(context, key, POPULAR_MAX_AGE_MS);
            if (cached != null) return protobuf(cached);
            List<InnertubeEndpoint.Video> videos = popular(gl, music ? "Music" : "", 30, config);
            byte[] answer = YoutubeiResponses.videoList(music ? "FEmusic" : "Trending",
                    music ? "Music" : "Trending", videos, now);
            if (!videos.isEmpty()) store(context, key, answer);
            return protobuf(answer);
        }
        if (id.startsWith("VL")) {
            String playlistId = id.substring(2);
            if (!playlistId.matches("[A-Za-z0-9_-]+")) return protobuf(new byte[0]);
            String key = "playlist_" + playlistId;
            byte[] cached = cached(context, key, 15L * 60 * 1000);
            if (cached != null) return protobuf(cached);
            PlaylistEndpoint.Playlist playlist = PlaylistEndpoint.fetchPlaylist(playlistId, 100, config);
            byte[] answer = YoutubeiResponses.playlist(playlistId, playlist);
            store(context, key, answer);
            return protobuf(answer);
        }
        if (id.startsWith("UC") || id.startsWith("@")) {
            String channelId = ChannelEndpoint.resolveChannelId(id, config);
            if (!channelId.matches("UC[A-Za-z0-9_-]{22}")) {
                android.util.Log.i(TAG, "Unknown channel " + id);
                return protobuf(new byte[0]);
            }
            return protobuf(channel(channelId, config, context, now));
        }
        android.util.Log.i(TAG, "No answer for browse id " + id);
        return protobuf(new byte[0]);
    }

    private static final long POPULAR_MAX_AGE_MS = 60L * 60 * 1000;

    private static final java.util.Map<String, Object[]> POPULAR = new java.util.HashMap<String, Object[]>();

    /**
     * Popular videos, shared for an hour by every tab and app version, so Home and Trending
     * show the same chart (YouTube's ranking shifts a little between two searches).
     */
    private static List<InnertubeEndpoint.Video> popular(String gl, String category, int limit,
                                                         Config config) {
        String key = gl + "|" + category + "|" + limit;
        synchronized (POPULAR) {
            Object[] cached = POPULAR.get(key);
            if (cached != null && System.currentTimeMillis() - (Long) cached[1] < POPULAR_MAX_AGE_MS) {
                @SuppressWarnings("unchecked")
                List<InnertubeEndpoint.Video> videos = (List<InnertubeEndpoint.Video>) cached[0];
                return videos;
            }
        }
        try {
            List<InnertubeEndpoint.Video> videos = InnertubeEndpoint.popularVideos(gl, category, limit, config);
            if (!videos.isEmpty()) {
                synchronized (POPULAR) {
                    POPULAR.put(key, new Object[]{videos, System.currentTimeMillis()});
                }
            }
            return videos;
        } catch (Exception e) {
            android.util.Log.w(TAG, "Popular videos unavailable (" + gl + " " + category + "): " + e);
            return new ArrayList<InnertubeEndpoint.Video>();
        }
    }

    private static final ExecutorService LOADERS = Executors.newCachedThreadPool(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "youtubei-loader");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static final class ChannelInfoTask implements Callable<ChannelEndpoint.ChannelInfo> {
        private final String channelId;
        private final Config config;

        ChannelInfoTask(String channelId, Config config) {
            this.channelId = channelId;
            this.config = config;
        }

        @Override
        public ChannelEndpoint.ChannelInfo call() throws Exception {
            return ChannelEndpoint.fetchChannelInfo(channelId, config);
        }
    }

    private static final class PlaylistsTask implements Callable<ChannelEndpoint.ChannelPlaylists> {
        private final String channelId;
        private final Config config;

        PlaylistsTask(String channelId, Config config) {
            this.channelId = channelId;
            this.config = config;
        }

        @Override
        public ChannelEndpoint.ChannelPlaylists call() throws Exception {
            return ChannelEndpoint.loadPlaylists(channelId, 30, config);
        }
    }

    /** Channel page; the header, uploads and playlists are three requests made in parallel. */
    private static byte[] channel(String channelId, Config config, Context context, long now)
            throws Exception {
        String key = "channel_" + channelId;
        byte[] cached = cached(context, key, 30L * 60 * 1000);
        if (cached != null) return cached;
        Future<ChannelEndpoint.ChannelInfo> infoTask = LOADERS.submit(new ChannelInfoTask(channelId, config));
        Future<ChannelEndpoint.ChannelPlaylists> playlistsTask =
                LOADERS.submit(new PlaylistsTask(channelId, config));
        ChannelEndpoint.Uploads uploads = ChannelEndpoint.loadUploads(channelId, 30, config);

        ChannelEndpoint.ChannelInfo info;
        try {
            info = infoTask.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            android.util.Log.w(TAG, "Channel header unavailable for " + channelId + ": " + e);
            info = new ChannelEndpoint.ChannelInfo();
            info.id = channelId;
        }
        if (info.id.isEmpty()) info.id = channelId;
        if (info.name.isEmpty()) info.name = uploads.channelName;
        List<ChannelEndpoint.PlaylistSummary> playlists = new ArrayList<ChannelEndpoint.PlaylistSummary>();
        try {
            playlists = playlistsTask.get(15, TimeUnit.SECONDS).playlists;
        } catch (Exception e) {
            android.util.Log.w(TAG, "Channel playlists unavailable for " + channelId + ": " + e);
        }
        for (InnertubeEndpoint.Video video : uploads.videos) {
            if (video.channel.isEmpty()) video.channel = info.name;
            if (video.channelId.isEmpty()) video.channelId = info.id;
        }
        byte[] answer = YoutubeiResponses.channel(info, uploads.videos, playlists, now);
        if (!uploads.videos.isEmpty()) store(context, key, answer);
        return answer;
    }

    // ------------------------------------------------------------------ next, player, search

    private NanoHTTPD.Response next(BareAndroidRequestProto.Root request, Config config, Context context)
            throws Exception {
        String videoId = request.getBrowseId();
        if (!InnertubeEndpoint.isValidVideoId(videoId)) return badRequest("missing/invalid video id");
        String key = "next_" + videoId;
        byte[] cached = cached(context, key, 10L * 60 * 1000);
        if (cached != null) return protobuf(cached);
        InnertubeEndpoint.WatchPage page = InnertubeEndpoint.watchPage(videoId, 20, config, context);
        byte[] answer = YoutubeiResponses.next(page, System.currentTimeMillis());
        if (!page.video.title.isEmpty()) store(context, key, answer);
        return protobuf(answer);
    }

    /**
     * Video details and stream URLs. The 360p file is linked directly on googlevideo.com, like
     * the real API did; the experimental 720p mode adds the stream muxed by this proxy.
     */
    private NanoHTTPD.Response player(BareAndroidRequestProto.Root request, NanoHTTPD.IHTTPSession session,
                                      Config config) throws Exception {
        String videoId = request.getBrowseId();
        if (!InnertubeEndpoint.isValidVideoId(videoId)) return badRequest("missing/invalid video id");
        YoutubeiResponses.PlayerInfo info = new YoutubeiResponses.PlayerInfo();
        info.videoId = videoId;
        JSONObject player;
        try {
            player = PlayerLookup.fetchPlaybackPlayer(videoId, config, "360p");
        } catch (Exception e) {
            android.util.Log.w(TAG, "No player response for " + videoId + ": " + e);
            info.error = "YouTube did not answer; try again";
            return protobuf(YoutubeiResponses.player(info));
        }
        JSONObject details = player.optJSONObject("videoDetails");
        if (details == null) {
            try {
                details = PlayerLookup.fetchUploadMetadata(videoId, config, null).optJSONObject("videoDetails");
            } catch (Exception e) {
                android.util.Log.w(TAG, "No metadata for " + videoId + ": " + e);
            }
        }
        if (details != null) {
            info.title = details.optString("title", "");
            info.lengthSeconds = details.optInt("lengthSeconds", 0);
            info.channelId = details.optString("channelId", "");
            info.author = details.optString("author", "");
            info.description = details.optString("shortDescription", "");
            info.viewCount = details.optLong("viewCount", 0);
        }
        String url = PlayerLookup.findProgressive360pUrl(player);
        if (url != null) {
            info.formats.add(YoutubeiResponses.progressive360p(url));
            if ("mux720".equals(config.getString("playback_mode", "360p"))) {
                info.formats.add(YoutubeiResponses.muxed720p("http://" + host(session) + "/video/" + videoId));
            }
        } else {
            JSONObject playability = player.optJSONObject("playabilityStatus");
            String reason = playability == null ? "" : playability.optString("reason", "");
            info.error = reason.isEmpty() ? "No playable stream for this video" : reason;
            android.util.Log.w(TAG, "No 360p stream for " + videoId + ": " + info.error);
        }
        return protobuf(YoutubeiResponses.player(info));
    }

    private NanoHTTPD.Response search(byte[] body, Config config, Context context) throws Exception {
        AndroidSearchRequestProto.Root request;
        try {
            request = AndroidSearchRequestProto.Root.parseFrom(body);
        } catch (IOException e) {
            return badRequest("Invalid protobuf request: " + e.getMessage());
        }
        String query = request.getQuery().trim();
        if (query.isEmpty()) return badRequest("Missing query");
        // SearchRequestParams.index is the offset of the page (page number * 20).
        int offset = request.hasParams() ? Math.max(0, request.getParams().getIndex()) : 0;
        String key = "search_" + query + "_" + offset;
        byte[] cached = cached(context, key, 30L * 60 * 1000);
        if (cached != null) return protobuf(cached);
        InnertubeEndpoint.SearchPage page = InnertubeEndpoint.searchPage(query, offset + 1, PAGE_SIZE,
                config, context);
        List<InnertubeEndpoint.Video> videos = InnertubeEndpoint.completeFeedVideos(page.videos);
        byte[] answer = YoutubeiResponses.search(videos, System.currentTimeMillis());
        if (!videos.isEmpty()) store(context, key, answer);
        return protobuf(answer);
    }

    // ------------------------------------------------------------------ resolve_url, config

    private NanoHTTPD.Response resolveUrl(String url, Config config) throws Exception {
        if (url.isEmpty()) return badRequest("Missing URL");
        String path = url.replaceFirst("^[a-z]+://[^/]+", "");
        String videoId = queryParameter(path, "v");
        if (videoId.isEmpty()) {
            java.util.regex.Matcher shortLink = java.util.regex.Pattern
                    .compile("(?:youtu\\.be/|/shorts/|/embed/|/v/)([A-Za-z0-9_-]{11})").matcher(url);
            if (shortLink.find()) videoId = shortLink.group(1);
        }
        if (InnertubeEndpoint.isValidVideoId(videoId)) return protobuf(YoutubeiResponses.resolveVideo(videoId));
        String list = queryParameter(path, "list");
        if (!list.isEmpty()) return protobuf(YoutubeiResponses.resolveBrowse("VL" + list));
        String query = queryParameter(path, "search_query");
        if (!query.isEmpty()) return protobuf(YoutubeiResponses.resolveSearch(query));
        java.util.regex.Matcher channel = java.util.regex.Pattern
                .compile("^/(?:channel/(UC[A-Za-z0-9_-]{22})|(?:user|c)/([^/?#]+)|(@[^/?#]+))").matcher(path);
        if (channel.find()) {
            if (channel.group(1) != null) return protobuf(YoutubeiResponses.resolveBrowse(channel.group(1)));
            String name = URLDecoder.decode(channel.group(2) != null ? channel.group(2) : channel.group(3), "UTF-8");
            String id = ChannelEndpoint.resolveChannelId(name, config);
            // A name YouTube does not know is looked up with a search instead.
            return protobuf(id.matches("UC[A-Za-z0-9_-]{22}") ? YoutubeiResponses.resolveBrowse(id)
                    : YoutubeiResponses.resolveSearch(name.startsWith("@") ? name.substring(1) : name));
        }
        return protobuf(YoutubeiResponses.resolveBrowse("FEwhat_to_watch"));
    }

    private static String queryParameter(String path, String name) {
        int query = path.indexOf('?');
        if (query < 0) return "";
        for (String pair : path.substring(query + 1).split("[&#]")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                try {
                    return URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** JSON, as the reference server answered; the apps only need a visitor id from it. */
    private static NanoHTTPD.Response config(NanoHTTPD.IHTTPSession session) throws Exception {
        String visitor = session.getHeaders().get("x-goog-visitor-id");
        if (visitor == null || visitor.isEmpty()) visitor = session.getRemoteIpAddress();
        JSONObject answer = new JSONObject().put("responseContext", new JSONObject()
                .put("globalConfigGroup", new JSONObject()
                        .put("coldConfigData", "")
                        .put("hotConfigData", "")
                        .put("coldHashData", "b25iYm1pbmRlY2hhbm5lbA==")
                        .put("hotHashData", "b25iYm1pbmRlY2hhbm5lbA=="))
                .put("visitorData", visitor));
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json",
                answer.toString());
    }

    // ------------------------------------------------------------------ helpers

    private static int clientMajorVersion(BareAndroidRequestProto.Root request, NanoHTTPD.IHTTPSession session) {
        String version = "";
        if (request.getContextCount() > 0 && request.getContext(0).getClientCount() > 0) {
            version = request.getContext(0).getClient(0).getClientVersion();
        }
        if (version.isEmpty()) {
            String header = session.getHeaders().get("x-youtube-client-version");
            if (header != null) version = header;
        }
        java.util.regex.Matcher major = java.util.regex.Pattern.compile("^(\\d+)").matcher(version.trim());
        return major.find() ? Integer.parseInt(major.group(1)) : 6;
    }

    private static String requestRegion(BareAndroidRequestProto.Root request) {
        if (request.getContextCount() > 0 && request.getContext(0).getClientCount() > 0) {
            String gl = request.getContext(0).getClient(0).getGl();
            if (gl.matches("[A-Za-z]{2}")) return gl.toUpperCase(Locale.US);
        }
        return null;
    }

    private static byte[] readBody(NanoHTTPD.IHTTPSession session) throws IOException {
        String header = session.getHeaders().get("content-length");
        int length;
        try {
            length = header == null ? 0 : Integer.parseInt(header.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Bad Content-Length: " + header);
        }
        if (length < 0 || length > MAX_BODY) throw new IOException("Request body too large: " + length);
        byte[] body = new byte[length];
        InputStream in = session.getInputStream();
        int read = 0;
        while (read < length) {
            int count = in.read(body, read, length - read);
            if (count < 0) throw new IOException("Request body ended after " + read + " of " + length + " bytes");
            read += count;
        }
        if ("gzip".equalsIgnoreCase(session.getHeaders().get("content-encoding"))) {
            InputStream gzip = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(body));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = gzip.read(buffer)) > 0) {
                out.write(buffer, 0, count);
                if (out.size() > MAX_BODY) throw new IOException("Request body too large");
            }
            body = out.toByteArray();
        }
        return body;
    }

    private static final java.util.Map<String, Object[]> MEMORY =
            new java.util.LinkedHashMap<String, Object[]>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Object[]> eldest) {
                    return size() > 24;
                }
            };

    /** Finished answers, kept in memory and on disk (base64, like the other caches' text files). */
    private static byte[] cached(Context context, String key, long maxAgeMs) {
        synchronized (MEMORY) {
            Object[] entry = MEMORY.get(key);
            if (entry != null && System.currentTimeMillis() - (Long) entry[1] < maxAgeMs) {
                return (byte[]) entry[0];
            }
        }
        java.io.File file = HttpClient.cacheFile(context.getCacheDir(), "youtubei_v1", key);
        if (!HttpClient.isFresh(file, maxAgeMs)) return null;
        try {
            byte[] answer = Base64.decode(HttpClient.readFile(file).trim(), Base64.NO_WRAP);
            synchronized (MEMORY) {
                MEMORY.put(key, new Object[]{answer, file.lastModified()});
            }
            return answer;
        } catch (Exception e) {
            return null;
        }
    }

    private static void store(Context context, String key, byte[] answer) {
        synchronized (MEMORY) {
            MEMORY.put(key, new Object[]{answer, System.currentTimeMillis()});
        }
        try {
            HttpClient.writeFile(HttpClient.cacheFile(context.getCacheDir(), "youtubei_v1", key),
                    Base64.encodeToString(answer, Base64.NO_WRAP));
        } catch (IOException e) {
            android.util.Log.w(TAG, "Could not cache " + key + ": " + e);
        }
    }

    private static NanoHTTPD.Response protobuf(byte[] body) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, PROTOBUF,
                new ByteArrayInputStream(body), body.length);
    }

    private static NanoHTTPD.Response badRequest(String message) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                "text/plain; charset=utf-8", message);
    }

    private static String host(NanoHTTPD.IHTTPSession session) {
        String host = session.getHeaders().get("host");
        return host == null || host.isEmpty() ? "127.0.0.1" : host;
    }

    private static String randomId(int length) {
        String chars = "qwertyuiopasdfghjklzxcvbnm1234567890";
        java.util.Random random = new java.util.Random();
        StringBuilder id = new StringBuilder(length);
        for (int i = 0; i < length; i++) id.append(chars.charAt(random.nextInt(chars.length())));
        return id.toString();
    }
}
