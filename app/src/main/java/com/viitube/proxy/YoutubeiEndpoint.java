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
    /** Uploads on the first page of a channel; the rest is loaded while scrolling. */
    private static final int CHANNEL_UPLOADS = 30;

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
        if ("guide".equals(mode)) return protobuf(YoutubeiResponses.guide(guideVersion(body, session)));
        if ("unknown".equals(mode)) {
            // A /youtubei/v1 endpoint this proxy does not serve (liking, subscribing, uploading).
            // An empty message leaves that part of the screen empty; a 404 or a 400 shows an error.
            android.util.Log.i(TAG, "No answer for " + session.getUri() + " (" + body.length + " bytes)");
            return protobuf(new byte[0]);
        }

        if ("search".equals(mode)) return search(body, config, context);
        BareAndroidRequestProto.Root request;
        try {
            request = BareAndroidRequestProto.Root.parseFrom(body);
        } catch (IOException e) {
            // Not something this proxy can read; an empty answer keeps the app going.
            android.util.Log.w(TAG, "Unreadable " + mode + " request (" + body.length + " bytes): " + e);
            return protobuf(new byte[0]);
        }
        if ("browse".equals(mode)) return browse(request, session, config, context);
        if ("next".equals(mode)) return next(request, session, config, context);
        if ("player".equals(mode)) return player(request, session, config);
        if ("resolve_url".equals(mode)) return resolveUrl(request.getBrowseId(), config);
        // An endpoint this proxy does not serve. An empty message is what the apps handle best:
        // they leave the part of the screen it belongs to empty instead of showing an error.
        android.util.Log.i(TAG, "No answer for " + session.getUri() + " (" + body.length + " bytes)");
        return protobuf(new byte[0]);
    }

    // ------------------------------------------------------------------ continuation tokens

    /**
     * Tokens for the endless lists. The app only sends them back, so the proxy keeps no state
     * for them: a token says which list it belongs to, how far that list got and what it is
     * about ("vtp1|s|40|cats" is the search for cats from result 40 on).
     */
    private static final String TOKEN_PREFIX = "vtp1|";
    private static final String SEARCH_TOKEN = "s";
    private static final String COMMENTS_TOKEN = "c";
    private static final String UPLOADS_TOKEN = "u";
    private static final String RELATED_TOKEN = "r";

    private static String continuationToken(String kind, int offset, String argument) {
        return TOKEN_PREFIX + kind + "|" + offset + "|" + argument;
    }

    /** {kind, offset, argument} of one of this proxy's tokens, or null for anything else. */
    private static String[] parseToken(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) return null;
        String[] parts = token.substring(TOKEN_PREFIX.length()).split("\\|", 3);
        if (parts.length < 3 || parts[0].isEmpty() || parts[2].isEmpty()) return null;
        try {
            Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        return parts;
    }

    /**
     * How far a list may be scrolled. The offset is clamped, so a token is only written while it
     * stays below the limit: a clamped offset would answer the same page again and the list would
     * never end.
     */
    private static final int MAX_OFFSET = 1000;

    private static int tokenOffset(String[] parts) {
        int offset = Integer.parseInt(parts[1]);
        return offset < 0 ? 0 : Math.min(offset, MAX_OFFSET);
    }

    /** The token for the entries after this page, or "" when the list ends here. */
    private static String nextToken(String kind, int offset, int count, boolean more, String argument) {
        if (!more || count <= 0 || offset + count >= MAX_OFFSET) return "";
        return continuationToken(kind, offset + count, argument);
    }

    private static List<InnertubeEndpoint.Video> slice(List<InnertubeEndpoint.Video> videos,
                                                       int offset, int limit) {
        int start = Math.min(offset, videos.size());
        int count = Math.min(limit, videos.size() - start);
        List<InnertubeEndpoint.Video> page = new ArrayList<InnertubeEndpoint.Video>(count);
        for (int i = start; i < start + count; i++) page.add(videos.get(i));
        return page;
    }

    private static final java.util.Map<String, String> NO_AVATARS =
            java.util.Collections.unmodifiableMap(new java.util.HashMap<String, String>());

    /**
     * The channel pictures the feed cards of the 2017 apps show left of the title. Ones already
     * known are free; the rest are looked up at once, with a budget, because the feed they
     * belong to is then kept for an hour. A channel that does not answer in time keeps the
     * default picture.
     */
    private static java.util.Map<String, String> feedAvatars(List<InnertubeEndpoint.Video> videos,
                                                             Config config) {
        java.util.Map<String, String> found = new java.util.HashMap<String, String>();
        List<String> missing = new ArrayList<String>();
        synchronized (AVATARS) {
            for (InnertubeEndpoint.Video video : videos) {
                String id = video.channelId;
                // Most feed answers carry the picture already; only the rest is looked up.
                if (!video.channelAvatar.isEmpty() || !id.matches("UC[A-Za-z0-9_-]{22}")
                        || found.containsKey(id) || missing.contains(id)) {
                    continue;
                }
                String avatar = AVATARS.get(id);
                if (avatar != null) found.put(id, avatar);
                else missing.add(id);
            }
        }
        if (missing.isEmpty()) return found;
        List<Future<ChannelEndpoint.ChannelInfo>> pending =
                new ArrayList<Future<ChannelEndpoint.ChannelInfo>>(missing.size());
        for (String id : missing) pending.add(LOADERS.submit(new ChannelInfoTask(id, config)));
        long deadline = System.currentTimeMillis() + AVATAR_BUDGET_MS;
        for (int i = 0; i < pending.size(); i++) {
            try {
                long left = deadline - System.currentTimeMillis();
                ChannelEndpoint.ChannelInfo info = pending.get(i).get(Math.max(1, left),
                        TimeUnit.MILLISECONDS);
                if (info.avatar.isEmpty()) continue;
                found.put(missing.get(i), info.avatar);
                synchronized (AVATARS) {
                    AVATARS.put(missing.get(i), info.avatar);
                }
            } catch (Exception e) {
                // The lookup keeps running and fills the cache for the next time the feed is built.
                android.util.Log.i(TAG, "No channel picture for " + missing.get(i) + " yet");
            }
        }
        return found;
    }

    /** How long a feed waits for the channel pictures it does not know yet. */
    private static final long AVATAR_BUDGET_MS = 2500;

    /** Channel avatars of pages already answered, so later pages show the same picture. */
    private static final java.util.Map<String, String> AVATARS =
            new java.util.LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > 32;
                }
            };

    // ------------------------------------------------------------------ browse

    private NanoHTTPD.Response browse(BareAndroidRequestProto.Root request, NanoHTTPD.IHTTPSession session,
                                      Config config, Context context) throws Exception {
        String id = request.getBrowseId();
        int version = clientMajorVersion(request, session);
        String gl = InnertubeEndpoint.popularRegion(requestRegion(request), config);
        if (!request.getContinuation().isEmpty()) {
            return browseContinuation(request.getContinuation(), config, context);
        }
        if (id.isEmpty()) id = "FEwhat_to_watch";
        long now = System.currentTimeMillis();
        android.util.Log.i(TAG, "browse " + id + " (app " + version + ", " + gl + ")");

        if (id.startsWith("FEwhat_to_watch")) {
            final boolean v11 = version >= 11;
            String key = "home_" + gl + "_"
                    + (version >= YoutubeiResponses.PIVOT_BAR_VERSION ? "v12" : v11 ? "v11" : "v10");
            byte[] cached = cached(context, key, POPULAR_MAX_AGE_MS);
            if (cached != null) return protobuf(cached);
            List<InnertubeEndpoint.Video> home = popular(gl, "", 30, config);
            // Version 11 apps show the same chart as Trending; older apps have a Music tab.
            List<InnertubeEndpoint.Video> second = v11 ? home : popular(gl, "Music", 20, config);
            byte[] answer = YoutubeiResponses.home(version, home, second, now,
                    v11 && version >= YoutubeiResponses.PIVOT_BAR_VERSION
                            ? feedAvatars(home, config) : NO_AVATARS);
            if (!home.isEmpty()) store(context, key, answer);
            return protobuf(answer);
        }
        if (id.startsWith("FEtrending") || id.startsWith("FEmusic")) {
            boolean music = id.startsWith("FEmusic");
            String key = (music ? "music_" : "trending_") + gl
                    + (version >= YoutubeiResponses.PIVOT_BAR_VERSION ? "_v12" : "");
            byte[] cached = cached(context, key, POPULAR_MAX_AGE_MS);
            if (cached != null) return protobuf(cached);
            List<InnertubeEndpoint.Video> videos = popular(gl, music ? "Music" : "", 30, config);
            boolean feedCards = version >= YoutubeiResponses.PIVOT_BAR_VERSION;
            byte[] answer = YoutubeiResponses.videoList(music ? "FEmusic" : "Trending",
                    music ? "Music" : "Trending", videos, now, feedCards,
                    feedCards ? feedAvatars(videos, config) : NO_AVATARS);
            if (!videos.isEmpty()) store(context, key, answer);
            return protobuf(answer);
        }
        if (id.startsWith("FEsubscriptions")) return protobuf(YoutubeiResponses.subscriptions());
        if (id.startsWith("FElibrary")) return protobuf(YoutubeiResponses.library());
        if (id.startsWith("FEaccount")) return protobuf(YoutubeiResponses.account());
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

    /**
     * More videos for a list the app is already showing. Only the channel uploads are paged this
     * way; a token this proxy did not write gets an empty answer, which ends the list.
     */
    private NanoHTTPD.Response browseContinuation(String token, Config config, Context context)
            throws Exception {
        String[] parts = parseToken(token);
        if (parts == null || !UPLOADS_TOKEN.equals(parts[0])) {
            android.util.Log.i(TAG, "No answer for this continuation");
            return protobuf(new byte[0]);
        }
        String channelId = parts[2];
        if (!channelId.matches("UC[A-Za-z0-9_-]{22}")) return protobuf(new byte[0]);
        int offset = tokenOffset(parts);
        String key = "uploads_" + channelId + "_" + offset;
        byte[] cached = cached(context, key, 30L * 60 * 1000);
        if (cached != null) return protobuf(cached);
        android.util.Log.i(TAG, "uploads of " + channelId + " from " + offset);
        ChannelEndpoint.Uploads uploads = ChannelEndpoint.loadUploads(channelId, offset + PAGE_SIZE, config);
        List<InnertubeEndpoint.Video> page = slice(uploads.videos, offset, PAGE_SIZE);
        for (InnertubeEndpoint.Video video : page) {
            if (video.channel.isEmpty()) video.channel = uploads.channelName;
            if (video.channelId.isEmpty()) video.channelId = channelId;
        }
        boolean more = uploads.hasMore || uploads.videos.size() > offset + page.size();
        String next = nextToken(UPLOADS_TOKEN, offset, page.size(), more, channelId);
        String avatar;
        synchronized (AVATARS) {
            avatar = AVATARS.get(channelId);
        }
        byte[] answer = YoutubeiResponses.browseVideosContinuation(page, avatar,
                System.currentTimeMillis(), next);
        if (!page.isEmpty()) store(context, key, answer);
        if (!next.isEmpty()) {
            LOADERS.execute(new UploadsPrefetch(channelId, offset + 2 * PAGE_SIZE, config));
        }
        return protobuf(answer);
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

    /**
     * Loads the page after the one just answered, so the next scroll is served from the cache.
     * Started only once a list has actually been scrolled, so a list nobody scrolls costs
     * nothing.
     */
    private static final class UploadsPrefetch implements Runnable {
        private final String channelId;
        private final int needed;
        private final Config config;

        UploadsPrefetch(String channelId, int needed, Config config) {
            this.channelId = channelId;
            this.needed = needed;
            this.config = config;
        }

        @Override
        public void run() {
            try {
                ChannelEndpoint.loadUploads(channelId, needed, config);
            } catch (Exception e) {
                android.util.Log.i(TAG, "Prefetch of uploads of " + channelId + " failed: " + e);
            }
        }
    }

    private static final class RelatedPrefetch implements Runnable {
        private final String videoId;
        private final int offset;
        private final Config config;

        RelatedPrefetch(String videoId, int offset, Config config) {
            this.videoId = videoId;
            this.offset = offset;
            this.config = config;
        }

        @Override
        public void run() {
            try {
                InnertubeEndpoint.relatedPage(videoId, offset, PAGE_SIZE, config);
            } catch (Exception e) {
                android.util.Log.i(TAG, "Prefetch of related videos of " + videoId + " failed: " + e);
            }
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
        ChannelEndpoint.Uploads uploads = ChannelEndpoint.loadUploads(channelId, CHANNEL_UPLOADS, config);

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
        synchronized (AVATARS) {
            if (!info.avatar.isEmpty()) AVATARS.put(channelId, info.avatar);
        }
        // What the Videos tab loads when it is scrolled to the end.
        boolean more = uploads.hasMore || uploads.videos.size() >= CHANNEL_UPLOADS;
        String uploadsToken = nextToken(UPLOADS_TOKEN, 0, uploads.videos.size(), more, channelId);
        byte[] answer = YoutubeiResponses.channel(info, uploads.videos, playlists, now, uploadsToken);
        if (!uploads.videos.isEmpty()) store(context, key, answer);
        return answer;
    }

    // ------------------------------------------------------------------ next, player, search

    private NanoHTTPD.Response next(BareAndroidRequestProto.Root request, NanoHTTPD.IHTTPSession session,
                                    Config config, Context context) throws Exception {
        // The apps send the continuation of the related videos in the /next request's field 8.
        String continuation = request.getNextContinuation().isEmpty()
                ? request.getContinuation() : request.getNextContinuation();
        if (!continuation.isEmpty()) {
            String[] token = parseToken(continuation);
            if (token != null && COMMENTS_TOKEN.equals(token[0])) {
                return commentsContinuation(token, config, context);
            }
            return relatedContinuation(continuation, config, context);
        }

        String videoId = request.getBrowseId();
        if (!InnertubeEndpoint.isValidVideoId(videoId)) {
            android.util.Log.i(TAG, "next request without a video id");
            return protobuf(new byte[0]);
        }
        int version = clientMajorVersion(request, session);
        boolean slim = version >= YoutubeiResponses.PIVOT_BAR_VERSION;
        boolean withComments = slim && config.getBool("show_comments", false);
        String key = "next_" + videoId + (slim ? "_v12" : "") + (withComments ? "_c" : "");
        byte[] cached = cached(context, key, 10L * 60 * 1000);
        if (cached != null) return protobuf(cached);
        // Loaded next to the watch page, not after it, and only waited for briefly: a video
        // whose comments are slow still opens at once, without them. Off unless the settings
        // ask for them, because they sit under the video whether they fit there or not.
        Future<List<InnertubeEndpoint.Comment>> commentTask = withComments
                ? LOADERS.submit(new CommentsTask(videoId, COMMENT_PAGE, config)) : null;
        InnertubeEndpoint.WatchPage page = InnertubeEndpoint.watchPage(videoId, PAGE_SIZE, config, context);
        List<InnertubeEndpoint.Comment> comments = new ArrayList<InnertubeEndpoint.Comment>();
        if (commentTask != null) {
            try {
                comments = commentTask.get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                android.util.Log.i(TAG, "Comments of " + videoId + " were not ready: " + e);
            }
        }
        String relatedToken = nextToken(RELATED_TOKEN, 0, page.related.size(), true, videoId);
        String moreComments = comments.size() < COMMENT_PAGE ? ""
                : continuationToken(COMMENTS_TOKEN, comments.size(), videoId);
        byte[] answer = YoutubeiResponses.next(page, System.currentTimeMillis(), version,
                relatedToken, moreComments, comments);
        if (!page.video.title.isEmpty()) store(context, key, answer);
        return protobuf(answer);
    }

    /** The comments of a video, which the app asks for with the token of its watch page. */
    private NanoHTTPD.Response commentsContinuation(String[] token, Config config, Context context)
            throws Exception {
        String videoId = token[2];
        if (!InnertubeEndpoint.isValidVideoId(videoId)) return protobuf(new byte[0]);
        int offset = tokenOffset(token);
        String key = "comments_" + videoId + "_" + offset;
        byte[] cached = cached(context, key, 15L * 60 * 1000);
        if (cached != null) return protobuf(cached);
        android.util.Log.i(TAG, "comments of " + videoId + " from " + offset);
        List<InnertubeEndpoint.Comment> loaded = InnertubeEndpoint.comments(videoId,
                offset + COMMENT_PAGE, config);
        List<InnertubeEndpoint.Comment> page = new ArrayList<InnertubeEndpoint.Comment>();
        for (int i = offset; i < loaded.size() && page.size() < COMMENT_PAGE; i++) {
            page.add(loaded.get(i));
        }
        String next = nextToken(COMMENTS_TOKEN, offset, page.size(),
                loaded.size() > offset + page.size(), videoId);
        byte[] answer = YoutubeiResponses.commentsContinuation(page, System.currentTimeMillis(), next);
        if (!page.isEmpty()) store(context, key, answer);
        return protobuf(answer);
    }

    private static final class CommentsTask implements Callable<List<InnertubeEndpoint.Comment>> {
        private final String videoId;
        private final int limit;
        private final Config config;

        CommentsTask(String videoId, int limit, Config config) {
            this.videoId = videoId;
            this.limit = limit;
            this.config = config;
        }

        @Override
        public List<InnertubeEndpoint.Comment> call() throws Exception {
            return InnertubeEndpoint.comments(videoId, limit, config);
        }
    }

    /** Comments per page; the apps ask for the next ones as the list is scrolled. */
    private static final int COMMENT_PAGE = 20;

    /** More related videos under a video, following YouTube's own continuations. */
    private NanoHTTPD.Response relatedContinuation(String token, Config config, Context context)
            throws Exception {
        String[] parts = parseToken(token);
        if (parts == null || !RELATED_TOKEN.equals(parts[0])
                || !InnertubeEndpoint.isValidVideoId(parts[2])) {
            android.util.Log.i(TAG, "No answer for this next continuation");
            return protobuf(new byte[0]);
        }
        String videoId = parts[2];
        int offset = tokenOffset(parts);
        String key = "related_" + videoId + "_" + offset;
        byte[] cached = cached(context, key, 15L * 60 * 1000);
        if (cached != null) return protobuf(cached);
        android.util.Log.i(TAG, "related videos of " + videoId + " from " + offset);
        InnertubeEndpoint.RelatedPage page = InnertubeEndpoint.relatedPage(videoId, offset, PAGE_SIZE, config);
        String next = nextToken(RELATED_TOKEN, offset, page.videos.size(), page.hasMore, videoId);
        byte[] answer = YoutubeiResponses.relatedContinuation(page.videos, System.currentTimeMillis(), next);
        if (!page.videos.isEmpty()) store(context, key, answer);
        if (!next.isEmpty()) {
            LOADERS.execute(new RelatedPrefetch(videoId, offset + page.videos.size(), config));
        }
        return protobuf(answer);
    }

    /**
     * Video details and stream URLs. The 360p file is linked directly on googlevideo.com, like
     * the real API did; the experimental 720p mode adds the stream muxed by this proxy.
     */
    private NanoHTTPD.Response player(BareAndroidRequestProto.Root request, NanoHTTPD.IHTTPSession session,
                                      Config config) throws Exception {
        String videoId = request.getBrowseId();
        YoutubeiResponses.PlayerInfo info = new YoutubeiResponses.PlayerInfo();
        if (!InnertubeEndpoint.isValidVideoId(videoId)) {
            // The app shows this instead of starting the player, which beats an HTTP error.
            android.util.Log.i(TAG, "player request without a video id");
            info.error = "This video cannot be played through the proxy";
            return protobuf(YoutubeiResponses.player(info));
        }
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
        // Which qualities the app offers and which one it starts with. 360p comes straight from
        // YouTube; everything above it is muxed by this proxy out of the streams of the Invidious
        // instance, so those exist only when an instance is set. The app plays the first format
        // in the list, so the playback format of the settings decides the order.
        String mode = config.getString("playback_mode", "360p");
        InvidiousLookup.Streams streams = InvidiousLookup.streams(videoId, config);
        boolean withInstance = streams != null;
        String proxy = "http://" + host(session) + "/video/" + videoId;
        YoutubeiResponses.StreamFormat small = url != null ? YoutubeiResponses.progressive360p(url)
                : withInstance && !streams.progressive.isEmpty()
                        ? YoutubeiResponses.progressive360p(streams.progressive) : null;
        if (withInstance && "select".equals(mode)) {
            // Every quality the instance has, climbing from 144p. The apps sort the formats and
            // then drop each one whose quality is lower than the entry before it, so a falling
            // list leaves a single item and no menu at all. 360p stays the direct YouTube link.
            List<Integer> heights = new ArrayList<Integer>(streams.heights);
            java.util.Collections.sort(heights);
            boolean has360 = false;
            for (Integer height : heights) {
                int value = height.intValue();
                if (value == 360 && small != null) {
                    info.formats.add(small);
                    has360 = true;
                } else {
                    info.formats.add(YoutubeiResponses.muxedQuality(value, proxy + "?q=" + value));
                }
            }
            if (!has360 && small != null) info.formats.add(0, small);
        } else if (!"360p".equals(mode) && (withInstance || small != null)) {
            // A format chosen in the settings: only that one, so nothing else can be picked.
            info.formats.add(YoutubeiResponses.muxed720p(proxy));
        } else if (small != null) {
            info.formats.add(small);
        }
        // Playing in the background needs a file with only sound in it, otherwise the app says
        // "No streaming data available for background playback". The instance has one; without
        // an instance the 360p file is offered instead, which carries its video along but plays.
        String audioUrl = withInstance && !streams.audioUrl.isEmpty() ? streams.audioUrl
                : small != null ? small.url : null;
        if (audioUrl != null) info.formats.add(YoutubeiResponses.audioOnly(audioUrl));
        if (info.formats.isEmpty()) {
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
            android.util.Log.w(TAG, "Unreadable search request (" + body.length + " bytes): " + e);
            return protobuf(new byte[0]);
        }
        // A continuation holds the query and how far the list got; a first request the query and,
        // with filters, SearchRequestParams.index as the offset of the page (page number * 20).
        String[] parts = parseToken(request.getContinuation());
        boolean continuation = parts != null && SEARCH_TOKEN.equals(parts[0]);
        String query;
        int offset;
        if (continuation) {
            query = parts[2];
            offset = tokenOffset(parts);
        } else if (!request.getContinuation().isEmpty()) {
            // A token from somewhere else; an empty answer ends the list instead of showing an error.
            android.util.Log.i(TAG, "No answer for this search continuation");
            return protobuf(new byte[0]);
        } else {
            query = request.getQuery().trim();
            if (query.isEmpty()) {
                // The apps send one of these while the search box is still empty.
                android.util.Log.i(TAG, "search request without a query");
                return protobuf(YoutubeiResponses.search(
                        new ArrayList<InnertubeEndpoint.Video>(),
                        new ArrayList<InnertubeEndpoint.SearchChannel>(),
                        new ArrayList<InnertubeEndpoint.SearchPlaylist>(),
                        System.currentTimeMillis(), ""));
            }
            offset = request.hasParams() ? Math.max(0, request.getParams().getIndex()) : 0;
        }
        String key = "search_" + query + "_" + offset + (continuation ? "_more" : "");
        byte[] cached = cached(context, key, 30L * 60 * 1000);
        if (cached != null) return protobuf(cached);
        InnertubeEndpoint.SearchPage page = InnertubeEndpoint.searchPage(query, offset + 1, PAGE_SIZE,
                config, context);
        List<InnertubeEndpoint.Video> videos = InnertubeEndpoint.completeFeedVideos(page.videos);
        // Entries that were skipped still count, so the next page starts after them.
        String next = videos.isEmpty() ? ""
                : nextToken(SEARCH_TOKEN, offset, page.videos.size(), page.hasMore, query);
        long now = System.currentTimeMillis();
        byte[] answer = continuation ? YoutubeiResponses.searchContinuation(videos, now, next)
                : YoutubeiResponses.search(videos, page.channels, page.playlists, now, next);
        if (!videos.isEmpty()) store(context, key, answer);
        return protobuf(answer);
    }

    // ------------------------------------------------------------------ resolve_url, config

    private NanoHTTPD.Response resolveUrl(String url, Config config) throws Exception {
        if (url.isEmpty()) {
            android.util.Log.i(TAG, "resolve_url request without a URL");
            return protobuf(YoutubeiResponses.resolveBrowse("FEwhat_to_watch"));
        }
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

    /** The app version of a request whose body is not parsed as a BareAndroidRequest (guide). */
    private static int guideVersion(byte[] body, NanoHTTPD.IHTTPSession session) {
        BareAndroidRequestProto.Root request = new BareAndroidRequestProto.Root();
        try {
            request = BareAndroidRequestProto.Root.parseFrom(body);
        } catch (IOException e) {
            android.util.Log.i(TAG, "Guide request without a readable context: " + e);
        }
        return clientMajorVersion(request, session);
    }

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

    /**
     * Finished answers. Every page of an endless list has its own key, so a few lists being
     * scrolled fill this quickly; 96 entries keep a scrolled-back list in memory instead of
     * reading and decoding it from disk again.
     */
    private static final java.util.Map<String, Object[]> MEMORY =
            new java.util.LinkedHashMap<String, Object[]>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Object[]> eldest) {
                    return size() > 96;
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

    /**
     * Keeps an answer. The memory copy is there at once; the disk copy is written on a background
     * thread, because encoding and writing it would otherwise delay the answer itself.
     */
    private static void store(final Context context, final String key, final byte[] answer) {
        synchronized (MEMORY) {
            MEMORY.put(key, new Object[]{answer, System.currentTimeMillis()});
        }
        WRITERS.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpClient.writeFile(HttpClient.cacheFile(context.getCacheDir(), "youtubei_v1", key),
                            Base64.encodeToString(answer, Base64.NO_WRAP));
                } catch (Exception e) {
                    android.util.Log.w(TAG, "Could not cache " + key + ": " + e);
                }
            }
        });
    }

    /** One thread for the disk copies, so they never hold up an answer or each other. */
    private static final ExecutorService WRITERS = Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "youtubei-cache");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }
            });

    /** Forgets every answer kept in memory; the disk copies are removed by CacheReset. */
    static void clearMemory() {
        synchronized (MEMORY) {
            MEMORY.clear();
        }
        synchronized (AVATARS) {
            AVATARS.clear();
        }
        synchronized (POPULAR) {
            POPULAR.clear();
        }
    }

    private static NanoHTTPD.Response protobuf(byte[] body) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, PROTOBUF,
                new ByteArrayInputStream(body), body.length);
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
