package com.viitube.proxy;

import android.content.Context;
import com.viitube.proxy.proto.AndroidNextRequestProto;
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
        if ("next".equals(mode)) return next(request, body, session, config, context);
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
    /** The Community tab and the Playlists tab of a channel load further pages too. */
    private static final String COMMUNITY_TOKEN = "p";
    private static final String PLAYLISTS_TOKEN = "l";
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
            TasteProfile taste = TasteProfile.get(context);
            // Once this phone has searched for a few things, the home feed is built out of them
            // instead of the plain chart, the way the apps of today fill theirs from an account.
            boolean profiled = taste.ready();
            if (profiled) key += "_" + taste.signature();
            byte[] cached = cached(context, key, profiled ? PROFILE_MAX_AGE_MS : POPULAR_MAX_AGE_MS);
            if (cached != null) return protobuf(cached);
            List<InnertubeEndpoint.Video> home = profiled
                    ? profiledFeed(taste, gl, config, context) : popular(gl, "", 30, config);
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
            return protobuf(channel(channelId, config, context, now, version));
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
        if (parts == null) {
            android.util.Log.i(TAG, "No answer for this continuation");
            return protobuf(new byte[0]);
        }
        String channelId = parts[2];
        if (!channelId.matches("UC[A-Za-z0-9_-]{22}")) return protobuf(new byte[0]);
        int offset = tokenOffset(parts);
        if (COMMUNITY_TOKEN.equals(parts[0])) return communityPage(channelId, offset, config, context);
        if (PLAYLISTS_TOKEN.equals(parts[0])) return playlistsPage(channelId, offset, config, context);
        if (!UPLOADS_TOKEN.equals(parts[0])) {
            android.util.Log.i(TAG, "No answer for this continuation");
            return protobuf(new byte[0]);
        }
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
            PREFETCHERS.execute(new UploadsPrefetch(channelId, offset + 2 * PAGE_SIZE, config));
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

    /**
     * The lookups a request fans out to. The pool is bounded: ten answers of a megabyte being
     * parsed at the same time is what runs an old phone out of memory, and what does not fit
     * runs on the request's own thread instead of waiting in a queue.
     */
    private static final ExecutorService LOADERS =
            MemoryBudget.pool("youtubei-loader", 6, Thread.NORM_PRIORITY, 32);

    /** Reading the next page of a list ahead; dropped when the phone has better things to do. */
    private static final ExecutorService PREFETCHERS = MemoryBudget.prefetchPool("youtubei-ahead");


    /** The posts of a channel's Community tab. */
    private static final class CommunityTask implements Callable<List<ChannelEndpoint.Post>> {
        private final String channelId;
        private final Config config;

        CommunityTask(String channelId, Config config) {
            this.channelId = channelId;
            this.config = config;
        }

        @Override
        public List<ChannelEndpoint.Post> call() throws Exception {
            return ChannelEndpoint.loadCommunity(channelId, COMMUNITY_POSTS, config);
        }
    }

    /**
     * Posts of a channel's Community tab. Each one carries a picture of its own, and a phone of
     * 2013 decoding twenty of them at once is what made that tab crawl.
     */
    private static final int COMMUNITY_POSTS = 10;


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

    /**
     * Reads ahead what the app is likely to ask for next, when the setting asks for it: the
     * stream and the page of the video that follows this one, and the newest upload of the
     * channel of the video being watched. Nobody waits for any of it.
     */
    private static void preload(Context context, Config config, String videoId,
                                InnertubeEndpoint.WatchPage page, YoutubeiResponses.Queue queue) {
        if (!config.getBool("preload", false)) return;
        String next = "";
        if (queue != null && queue.next() >= 0) next = queue.videos.get(queue.next()).id;
        else if (!page.related.isEmpty()) next = page.related.get(0).id;
        if (!next.isEmpty()) PREFETCHERS.execute(new WatchPrefetch(next, config, context));
        String channelId = page.video.channelId;
        if (channelId.startsWith("UC")) {
            PREFETCHERS.execute(new UploadsPrefetch(channelId, PAGE_SIZE, config));
        }
    }

    /** The page and the stream of a video, read before it is asked for. */
    private static final class WatchPrefetch implements Runnable {
        private final String videoId;
        private final Config config;
        private final Context context;

        WatchPrefetch(String videoId, Config config, Context context) {
            this.videoId = videoId;
            this.config = config;
            this.context = context;
        }

        /**
         * Nothing is read ahead while the video the person is watching starts. On an old phone
         * those seconds are all the processor there is, and a page read ahead then is a video
         * that stutters now.
         */
        private static final long SETTLE_MS = 15000;

        @Override
        public void run() {
            try {
                Thread.sleep(SETTLE_MS);
                InnertubeEndpoint.watchPage(videoId, PAGE_SIZE, config, context);
                PlayerLookup.fetchPlaybackPlayer(videoId, config, "360p");
            } catch (Exception e) {
                android.util.Log.i(TAG, "Reading " + videoId + " ahead failed: " + e);
            }
        }
    }

    /** More posts of a channel's Community tab. */
    private NanoHTTPD.Response communityPage(String channelId, int offset, Config config,
                                             Context context) throws Exception {
        String key = "community_" + channelId + "_" + offset;
        byte[] cached = cached(context, key, 30L * 60 * 1000);
        if (cached != null) return protobuf(cached);
        ChannelEndpoint.Posts loaded = ChannelEndpoint.communityPage(channelId,
                offset + ChannelEndpoint.POSTS_PER_PAGE, config);
        List<ChannelEndpoint.Post> page = new ArrayList<ChannelEndpoint.Post>();
        for (int i = offset; i < loaded.posts.size()
                && page.size() < ChannelEndpoint.POSTS_PER_PAGE; i++) {
            page.add(loaded.posts.get(i));
        }
        boolean more = !loaded.continuation.isEmpty()
                || loaded.posts.size() > offset + page.size();
        String next = nextToken(COMMUNITY_TOKEN, offset, page.size(), more, channelId);
        byte[] answer = YoutubeiResponses.communityContinuation(page, next);
        if (!page.isEmpty()) store(context, key, answer);
        return protobuf(answer);
    }

    /** More playlists of a channel. */
    private NanoHTTPD.Response playlistsPage(String channelId, int offset, Config config,
                                             Context context) throws Exception {
        String key = "channelplaylists_" + channelId + "_" + offset;
        byte[] cached = cached(context, key, 30L * 60 * 1000);
        if (cached != null) return protobuf(cached);
        ChannelEndpoint.ChannelPlaylists loaded = ChannelEndpoint.loadPlaylists(channelId,
                offset + ChannelEndpoint.PLAYLISTS_PER_PAGE, config);
        List<ChannelEndpoint.PlaylistSummary> page =
                new ArrayList<ChannelEndpoint.PlaylistSummary>();
        for (int i = offset; i < loaded.playlists.size()
                && page.size() < ChannelEndpoint.PLAYLISTS_PER_PAGE; i++) {
            page.add(loaded.playlists.get(i));
        }
        boolean more = !loaded.continuation.isEmpty()
                || loaded.playlists.size() > offset + page.size();
        String next = nextToken(PLAYLISTS_TOKEN, offset, page.size(), more, channelId);
        byte[] answer = YoutubeiResponses.playlistsContinuation(page, loaded.channelName,
                channelId, next);
        if (!page.isEmpty()) store(context, key, answer);
        return protobuf(answer);
    }

    /** A feed built from what was searched for is read again sooner than the chart. */
    private static final long PROFILE_MAX_AGE_MS = 15L * 60 * 1000;
    /** Videos taken from each of the searches this phone made. */
    private static final int PER_SEARCH = 5;
    /** How many of the newest searches the feed is built out of. */
    private static final int SEARCHES_USED = 4;
    /** How many of the last watched videos bring their related videos along. */
    private static final int WATCHED_USED = 2;
    /** How many of the last watched channels have their newest uploads shown. */
    private static final int CHANNELS_USED = 2;
    /** How many videos the home feed holds. */
    private static final int FEED_SIZE = 30;

    /**
     * The home feed of someone who has used this phone: the newest searches are asked for again,
     * the video last opened brings its related videos along, and the chart fills the rest. The
     * sources take turns, so the feed is not one search followed by another.
     */
    private static List<InnertubeEndpoint.Video> profiledFeed(TasteProfile taste, String region,
                                                              Config config, Context context) {
        List<List<InnertubeEndpoint.Video>> sources = new ArrayList<List<InnertubeEndpoint.Video>>();
        for (String query : taste.searches(SEARCHES_USED)) {
            try {
                InnertubeEndpoint.SearchPage page = InnertubeEndpoint.searchPage(query, 1,
                        PER_SEARCH, config, context);
                sources.add(InnertubeEndpoint.completeFeedVideos(page.videos));
            } catch (Exception e) {
                android.util.Log.i(TAG, "Nothing for " + query + " in the home feed: " + e);
            }
        }
        // What the last videos watched lead to, which is how YouTube's own feed is mostly built.
        for (String watched : taste.lastVideos(WATCHED_USED)) {
            try {
                sources.add(InnertubeEndpoint.relatedPage(watched, 0, PER_SEARCH, config).videos);
            } catch (Exception e) {
                android.util.Log.i(TAG, "No related videos for the home feed: " + e);
            }
        }
        // The newest videos of the channels watched: the part of the feed that changes by itself.
        for (String channelId : taste.watchedChannels(CHANNELS_USED)) {
            try {
                ChannelEndpoint.Uploads uploads = ChannelEndpoint.loadUploads(channelId,
                        PER_SEARCH, config);
                List<InnertubeEndpoint.Video> newest = slice(uploads.videos, 0, PER_SEARCH);
                for (InnertubeEndpoint.Video video : newest) {
                    if (video.channel.isEmpty()) video.channel = uploads.channelName;
                    if (video.channelId.isEmpty()) video.channelId = channelId;
                }
                sources.add(newest);
            } catch (Exception e) {
                android.util.Log.i(TAG, "No uploads of " + channelId + " for the home feed: " + e);
            }
        }

        // The sources take turns, so the feed is not one search followed by another.
        List<InnertubeEndpoint.Video> feed = new ArrayList<InnertubeEndpoint.Video>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int round = 0; feed.size() < FEED_SIZE; round++) {
            boolean added = false;
            for (List<InnertubeEndpoint.Video> source : sources) {
                if (round >= source.size() || feed.size() >= FEED_SIZE) continue;
                InnertubeEndpoint.Video video = source.get(round);
                if (video == null || video.id.isEmpty() || !seen.add(video.id)) continue;
                feed.add(video);
                added = true;
            }
            if (!added) break;
        }
        // The chart only fills what is left: it is the same for everyone, so a feed of someone's
        // own ends up as theirs and not as a list of whatever the world watched this week.
        int profiled = feed.size();
        if (profiled < FEED_SIZE) {
            try {
                for (InnertubeEndpoint.Video video : popular(region, "", FEED_SIZE, config)) {
                    if (feed.size() >= FEED_SIZE) break;
                    if (video.id.isEmpty() || !seen.add(video.id)) continue;
                    feed.add(video);
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "No chart for the home feed: " + e);
            }
        }
        android.util.Log.i(TAG, "home feed from " + taste.searchCount() + " searches: " + profiled
                + " videos out of " + sources.size() + " sources, " + (feed.size() - profiled)
                + " from the chart");
        return feed;
    }

    /** Channel page; the header, uploads and playlists are three requests made in parallel. */
    private static byte[] channel(String channelId, Config config, Context context, long now,
                                  int clientMajorVersion) throws Exception {
        boolean withCommunity = clientMajorVersion >= YoutubeiResponses.COMMUNITY_TAB_VERSION;
        String key = "channel_" + channelId + (withCommunity ? "_c" : "");
        byte[] cached = cached(context, key, 30L * 60 * 1000);
        if (cached != null) return cached;
        Future<ChannelEndpoint.ChannelInfo> infoTask = LOADERS.submit(new ChannelInfoTask(channelId, config));
        Future<ChannelEndpoint.ChannelPlaylists> playlistsTask =
                LOADERS.submit(new PlaylistsTask(channelId, config));
        // The Community tab is one more request, made next to the others.
        Future<List<ChannelEndpoint.Post>> postsTask = withCommunity
                ? LOADERS.submit(new CommunityTask(channelId, config)) : null;
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
        List<ChannelEndpoint.Post> posts = new ArrayList<ChannelEndpoint.Post>();
        if (postsTask != null) {
            try {
                posts = postsTask.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                android.util.Log.w(TAG, "Channel posts unavailable for " + channelId + ": " + e);
            }
        }
        String communityToken = posts.isEmpty() ? ""
                : nextToken(COMMUNITY_TOKEN, 0, posts.size(), true, channelId);
        String playlistsToken = playlists.isEmpty() ? ""
                : nextToken(PLAYLISTS_TOKEN, 0, playlists.size(), true, channelId);
        byte[] answer = YoutubeiResponses.channel(info, uploads.videos, playlists, now, uploadsToken,
                posts, clientMajorVersion, communityToken, playlistsToken);
        if (!uploads.videos.isEmpty()) store(context, key, answer);
        return answer;
    }

    // ------------------------------------------------------------------ next, player, search

    private NanoHTTPD.Response next(BareAndroidRequestProto.Root request, byte[] body,
                                    NanoHTTPD.IHTTPSession session, Config config, Context context)
            throws Exception {
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
        boolean withComments = slim && config.getBool("show_comments", true);
        // The playlist a video is opened from, which the apps send along with it: field 4 of the
        // request is the playlist, field 7 the place in it.
        String playlistId = "";
        int playlistIndex = 0;
        try {
            AndroidNextRequestProto.Root watch = AndroidNextRequestProto.Root.parseFrom(body);
            playlistId = watch.getPlaylistId();
            playlistIndex = watch.getPlaylistIndex();
        } catch (IOException e) {
            android.util.Log.i(TAG, "next request without playlist fields: " + e);
        }
        String key = "next_" + videoId + (slim ? "_v12" : "") + (withComments ? "_c" : "")
                + (version >= 14 ? "_v14" : "")
                + (playlistId.isEmpty() ? "" : "_" + playlistId + "_" + playlistIndex);
        byte[] cached = cached(context, key, 10L * 60 * 1000);
        if (cached != null) return protobuf(cached);
        // Loaded next to the watch page, not after it, and only waited for briefly: a video
        // whose comments are slow still opens at once, without them. Off unless the settings
        // ask for them, because they sit under the video whether they fit there or not.
        Future<List<InnertubeEndpoint.Comment>> commentTask = withComments
                ? LOADERS.submit(new CommentsTask(videoId, COMMENT_PAGE, config)) : null;
        InnertubeEndpoint.WatchPage page = InnertubeEndpoint.watchPage(videoId, PAGE_SIZE, config, context);
        TasteProfile.get(context).rememberWatch(videoId, page.video.channelId);
        List<InnertubeEndpoint.Comment> comments = new ArrayList<InnertubeEndpoint.Comment>();
        if (commentTask != null) {
            try {
                comments = commentTask.get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                android.util.Log.i(TAG, "Comments of " + videoId + " were not ready: " + e);
            }
        }
        // With comments switched on the token of the list belongs to them from the start, even
        // when the first ones were too slow to arrive: a list that still carried the token of
        // the related videos went on loading those under the comments.
        String relatedToken = withComments ? ""
                : nextToken(RELATED_TOKEN, 0, page.related.size(), true, videoId);
        String moreComments = !withComments ? ""
                : continuationToken(COMMENTS_TOKEN, comments.size(), videoId);
        YoutubeiResponses.Queue queue = queue(videoId, playlistId, playlistIndex, config);
        byte[] answer = YoutubeiResponses.next(page, System.currentTimeMillis(), version,
                relatedToken, moreComments, comments, queue);
        preload(context, config, videoId, page, queue);
        if (!page.video.title.isEmpty()) store(context, key, answer);
        return protobuf(answer);
    }

    /**
     * The queue of the playlist a video is played as part of, or null. The apps play their way
     * down this list on their own, so it is also what makes the next video of a playlist start.
     */
    private static YoutubeiResponses.Queue queue(String videoId, String playlistId, int index,
                                                 Config config) {
        if (playlistId.isEmpty() || !playlistId.matches("[A-Za-z0-9_-]{2,60}")) return null;
        // A mix (RD...) is made up by YouTube for one video and cannot be read as a playlist.
        if (playlistId.startsWith("RD")) return null;
        PlaylistEndpoint.Playlist playlist;
        try {
            playlist = PlaylistEndpoint.fetchPlaylist(playlistId, QUEUE_SIZE, config);
        } catch (Exception e) {
            android.util.Log.w(TAG, "No queue for " + playlistId + ": " + e);
            return null;
        }
        YoutubeiResponses.Queue queue = new YoutubeiResponses.Queue();
        queue.playlistId = playlistId;
        queue.title = playlist.title;
        queue.author = playlist.author;
        queue.total = Math.max(playlist.total, playlist.videos.size());
        queue.videos.addAll(playlist.videos);
        // The place the app sent is used when it is the video being watched; it can be off when
        // a playlist changed since, and then the video itself says where in the list it is.
        queue.index = index >= 0 && index < queue.videos.size()
                && videoId.equals(queue.videos.get(index).id) ? index : -1;
        if (queue.index < 0) {
            for (int i = 0; i < queue.videos.size(); i++) {
                if (videoId.equals(queue.videos.get(i).id)) {
                    queue.index = i;
                    break;
                }
            }
        }
        if (queue.index < 0) return null;
        return queue;
    }

    /** How much of a playlist the queue of the watch page carries. */
    private static final int QUEUE_SIZE = 100;

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
        // YouTube is asked, not the page that was just read: a page filled to the last comment
        // still has more behind it, and the list would otherwise end there.
        boolean more = InnertubeEndpoint.hasMoreComments(videoId, offset + page.size());
        String next = nextToken(COMMENTS_TOKEN, offset, page.size(), more, videoId);
        byte[] answer = YoutubeiResponses.commentsContinuation(page, System.currentTimeMillis(), next);
        if (!page.isEmpty()) store(context, key, answer);
        // The page after this one is read while this one is on screen, so the list does not stop
        // to wait for it when it is scrolled on.
        if (!next.isEmpty()) {
            PREFETCHERS.execute(new CommentsPrefetch(videoId, offset + page.size() + COMMENT_PAGE, config));
        }
        return protobuf(answer);
    }

    /** Reads the comments after the ones just sent into the cache; nobody waits for it. */
    private static final class CommentsPrefetch implements Runnable {
        private final String videoId;
        private final int limit;
        private final Config config;

        CommentsPrefetch(String videoId, int limit, Config config) {
            this.videoId = videoId;
            this.limit = limit;
            this.config = config;
        }

        @Override
        public void run() {
            try {
                InnertubeEndpoint.comments(videoId, limit, config);
            } catch (Exception e) {
                android.util.Log.i(TAG, "Prefetch of comments of " + videoId + " failed: " + e);
            }
        }
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
            PREFETCHERS.execute(new RelatedPrefetch(videoId, offset + page.videos.size(), config));
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
        // The subtitle tracks of the video, pointed back at this proxy: the apps fetch the link
        // of a track as it is, and this proxy turns YouTube's answer into what they can read.
        String base = "http://" + host(session);
        for (CaptionsEndpoint.Track track : config.getBool("show_captions", true)
                ? CaptionsEndpoint.tracksOf(player)
                : java.util.Collections.<CaptionsEndpoint.Track>emptyList()) {
            info.captions.add(new YoutubeiResponses.CaptionTrack(
                    CaptionsEndpoint.proxyUrl(base, videoId, track), track.languageName,
                    track.languageCode, track.vssId, track.translatable));
        }
        // These apps switch to the file with only sound the moment the screen goes off, so it
        // is made ready here, while the video is still being asked for.
        AudioWarmup.prepare(videoId, config, player);
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
        // Whether the video can be watched at all; the sound added below does not make it so.
        boolean playable = !info.formats.isEmpty();
        // Playing in the background needs a file with only sound in it, otherwise the app says
        // "No streaming data available for background playback" and stops. It reads that file
        // out of the adaptive list of the answer and wants itag 139 or 140 there, so the file
        // is listed with the ranges of its header and of its index. The instance has one; with
        // no instance the audio stream of YouTube itself is used.
        YoutubeiResponses.StreamFormat audio = withInstance
                ? adaptiveAudio(streams.audio, streams.audioUrl) : null;
        if (audio == null) {
            JSONObject youtubeAudio = PlayerLookup.findAudioFormat(player);
            audio = youtubeAudio == null ? null
                    : adaptiveAudio(youtubeAudio, PlayerLookup.formatUrl(youtubeAudio));
        }
        // The plain list of the answer gets the sound as well: that is where the apps look when
        // they play only the audio of a video without going to the background. Without a file of
        // its own the 360p one is named there, which carries its video along but does play.
        String audioUrl = audio != null ? audio.url
                : withInstance && !streams.audioUrl.isEmpty() ? streams.audioUrl
                : small != null ? small.url : null;
        if (audioUrl != null) info.formats.add(YoutubeiResponses.audioOnly(audioUrl));
        if (audio != null) info.formats.add(audio);
        if (!playable) {
            JSONObject playability = player.optJSONObject("playabilityStatus");
            String reason = playability == null ? "" : playability.optString("reason", "");
            info.error = reason.isEmpty() ? "No playable stream for this video" : reason;
            info.formats.clear();
            android.util.Log.w(TAG, "No 360p stream for " + videoId + ": " + info.error);
        }
        return protobuf(YoutubeiResponses.player(info));
    }

    /**
     * One audio stream of a player answer - YouTube's own or an Invidious one - as the adaptive
     * format the background player needs. Null when the stream has no header and index ranges,
     * because nothing can be played out of it then.
     */
    private static YoutubeiResponses.StreamFormat adaptiveAudio(JSONObject format, String url) {
        if (format == null || url == null || url.isEmpty()) return null;
        int[] init = rangeOf(format.optJSONObject("initRange"));
        int[] index = rangeOf(format.optJSONObject("indexRange"));
        if (init == null || index == null) return null;
        long bitrate = numberOf(format, "averageBitrate", numberOf(format, "bitrate", 0));
        return YoutubeiResponses.adaptiveAudio((int) numberOf(format, "itag", 140), url,
                format.optString("mimeType", ""), (int) bitrate,
                numberOf(format, "contentLength", 0), init, index);
    }

    /** "initRange": {"start": "0", "end": "739"} as a pair of numbers, or null. */
    private static int[] rangeOf(JSONObject range) {
        if (range == null) return null;
        long end = numberOf(range, "end", -1);
        if (end < 0) return null;
        return new int[]{(int) numberOf(range, "start", 0), (int) end};
    }

    /** A number of a player answer, which writes some of them as strings. */
    private static long numberOf(JSONObject holder, String name, long fallback) {
        Object value = holder.opt(name);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
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
            // What is searched for is what the home feed is built out of later on.
            TasteProfile.get(context).rememberSearch(query);
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
     * scrolled fill this quickly; the entries keep a scrolled-back list in memory instead of
     * reading and decoding it from disk again, and how many of them a phone with little heap
     * can afford is {@link MemoryBudget}'s to say.
     */
    private static final int MEMORY_ENTRIES = MemoryBudget.entries(64);
    private static final java.util.Map<String, Object[]> MEMORY =
            new java.util.LinkedHashMap<String, Object[]>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Object[]> eldest) {
                    return size() > MEMORY_ENTRIES;
                }
            };

    /** Finished answers, kept in memory and on disk (base64, like the other caches' text files). */
    /**
     * Where the finished answers are kept. They are written as the bytes they are: the earlier
     * copies were base64 text, which cost a third more room and turned every read into a string
     * of twice the size of the answer before it could be decoded. The name is a new one so those
     * older files are not read as if they were bytes; they go on the next cache clear.
     */
    private static final String ANSWER_CACHE = "youtubei_v2";

    private static byte[] cached(Context context, String key, long maxAgeMs) {
        synchronized (MEMORY) {
            Object[] entry = MEMORY.get(key);
            if (entry != null && System.currentTimeMillis() - (Long) entry[1] < maxAgeMs) {
                return (byte[]) entry[0];
            }
        }
        java.io.File file = HttpClient.cacheFile(context.getCacheDir(), ANSWER_CACHE, key);
        if (!HttpClient.isFresh(file, maxAgeMs)) return null;
        try {
            byte[] answer = HttpClient.readBytes(file);
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
                    HttpClient.writeBytes(
                            HttpClient.cacheFile(context.getCacheDir(), ANSWER_CACHE, key), answer);
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
