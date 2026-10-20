package com.viitube.proxy;

import android.util.Base64;
import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class InnertubeEndpoint implements Endpoint {

    private static final String YOUTUBEI = "https://www.youtube.com/youtubei/v1/";
    private static final String YT2009_CLIENT_VERSION = "2.20230206.06.00";
    private static final String YT2009_VISITOR_DATA = "CgtaUVZYcmNmQXlIUSjtuZ-fBg%3D%3D";
    private static final String DEFAULT_WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; "
            + "rv:102.0) Gecko/20100101 Firefox/102.0";
    private static final int MAX_RESULTS = 20;
    private final String mode;

    public InnertubeEndpoint(String mode) {
        this.mode = mode;
    }

    public static JSONObject context(Config config) throws JSONException {
        String userAgent = configuredUserAgent(config);
        JSONObject client = new JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", YT2009_CLIENT_VERSION)
                .put("hl", "en")
                .put("gl", config.getString("gl", "PL"))
                .put("visitorData", config.getString("visitor_data", YT2009_VISITOR_DATA))
                .put("osName", "Windows")
                .put("osVersion", "10.0")
                .put("platform", "DESKTOP")
                .put("clientFormFactor", "UNKNOWN_FORM_FACTOR")
                .put("browserName", "Firefox")
                .put("browserVersion", "102.0")
                .put("originalUrl", "https://www.youtube.com/")
                .put("userAgent", userAgent);
        return new JSONObject().put("context", new JSONObject().put("client", client));
    }

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                     Config config, Context context) throws Exception {
        if ("search".equals(mode)) {
            String query = session.getParms().get("q");
            if (query == null || query.trim().isEmpty()) {
                return text(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing 'q' parameter");
            }
            String normalizedQuery = query.trim();
            // gdata uses max-results/start-index; "limit" is kept for older callers.
            String maxParam = session.getParms().get("max-results");
            int limit = parseLimit(maxParam != null ? maxParam : session.getParms().get("limit"));
            int startIndex = parseStartIndex(session.getParms().get("start-index"));
            String requestHost = requestHost(session);
            java.io.File cache = HttpClient.cacheFile(context.getCacheDir(), "search",
                    "v7_" + requestHost + "_" + normalizedQuery + "_" + startIndex + "_" + limit);
            if (HttpClient.isFresh(cache, SEARCH_MAX_AGE_MS)) return atom(HttpClient.readFile(cache));
            boolean[] complete = {false};
            String feed = searchFeed(normalizedQuery, startIndex, limit, session, config, context,
                    complete);
            HttpClient.writeFeedCache(cache, feed, complete[0], SEARCH_MAX_AGE_MS);
            return atom(feed);
        }
        if ("suggestions".equals(mode)) {
            String query = session.getParms().get("q");
            if (query == null || query.trim().isEmpty()) {
                return text(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing 'q' parameter");
            }
            return xml(suggestions(query.trim(), config), "application/xml; charset=utf-8");
        }
        String videoId = params.length > 0 ? params[0] : "";
        if ("related".equals(mode)) return atom(relatedFeed(videoId, session, config, context));
        if ("comments".equals(mode)) return atom(commentsFeed(videoId, session, config, context));
        return text(NanoHTTPD.Response.Status.NOT_FOUND, "not found");
    }

    public static String fetchPopularFeed(String region, Config config, String baseUrl,
                                          Context context) throws Exception {
        return fetchPopularFeed(region, "", config, baseUrl, context);
    }

    public static String fetchPopularFeed(String region, String category, Config config,
                                          String baseUrl, Context context) throws Exception {
        return fetchPopularFeed(region, category, MAX_RESULTS, config, baseUrl, context);
    }

    /** Search filter: sort by view count, uploaded this week, videos only. */
    private static final String POPULAR_THIS_WEEK = "CAMSBAgDEAE=";
    /** Search filter: sort by view count, uploaded this month, videos only. */
    private static final String POPULAR_THIS_MONTH = "CAMSBAgEEAE=";
    private static final int MAX_SHORTS_SECONDS = 60;
    /** Regular videos between two chart music videos in the general popular feed. */
    private static final int MUSIC_VIDEO_EVERY = 4;

    /**
     * Most popular videos from Innertube. YouTube removed the Trending page (FEtrending now
     * answers HTTP 400), so the feed is built the way the page was ranked: the most viewed
     * videos uploaded this week in the region (an empty search sorted by view count). Music
     * uses the region's "Top 100 Music Videos" chart; other categories search the category
     * name among the most viewed videos of this month.
     */
    public static String fetchPopularFeed(String region, String category, int resultLimit,
                                          Config config, String baseUrl, Context context) throws Exception {
        String gl = popularRegion(region, config);
        boolean hasCategory = category != null && !category.isEmpty();
        List<Video> videos = popularVideos(gl, category, resultLimit, config);
        if (videos.isEmpty()) throw new IOException("YouTube returned no popular videos");
        enrichVideos(videos, config, context);
        String title = "YouTube Most Popular " + (hasCategory ? category : "Videos") + " in " + gl;
        String feedId = baseUrl + "/feeds/api/standardfeeds/" + gl + "/most_popular"
                + (hasCategory ? "?category=" + URLEncoder.encode(category, "UTF-8") : "");
        return buildFeed(videos, title, feedId, baseUrl);
    }

    static String popularRegion(String region, Config config) {
        return region == null || region.length() != 2 ? config.getString("gl", "US")
                : region.toUpperCase(Locale.US);
    }

    /** The videos of the popular feed, without the extra metadata lookups. */
    static List<Video> popularVideos(String region, String category, int resultLimit,
                                     Config config) throws Exception {
        String gl = popularRegion(region, config);
        boolean hasCategory = category != null && !category.isEmpty();
        boolean music = hasCategory && category.toLowerCase(Locale.US).contains("music");
        List<Video> videos = new ArrayList<Video>();
        if (music) {
            videos.addAll(chartVideos(gl, "Top 100 Music Videos", resultLimit, config));
        }
        // Without a category the feed mixes the most viewed videos with the region's music
        // chart. They are two independent lookups, so the chart is started here and collected
        // below: the home feed then waits for the slower of the two instead of for both.
        final String chartRegion = gl;
        final Config chartConfig = config;
        final int chartLimit = resultLimit / (MUSIC_VIDEO_EVERY + 1) + 1;
        Future<List<Video>> localChart = hasCategory ? null
                : ENRICH_POOL.submit(new java.util.concurrent.Callable<List<Video>>() {
                    @Override
                    public List<Video> call() {
                        return chartVideos(chartRegion, "Trending 20", chartLimit, chartConfig);
                    }
                });
        if (videos.size() < resultLimit) {
            List<Video> found = new ArrayList<Video>();
            collectPopular(hasCategory ? category : "", hasCategory ? POPULAR_THIS_MONTH : POPULAR_THIS_WEEK,
                    gl, resultLimit, config, found);
            if (found.size() < resultLimit && !hasCategory) {
                // Quiet weeks: widen to this month.
                collectPopular("", POPULAR_THIS_MONTH, gl, resultLimit, config, found);
            }
            java.util.Collections.sort(found, (left, right) -> {
                int countOrder = Long.compare(viewCount(right.views), viewCount(left.views));
                return countOrder != 0 ? countOrder : left.id.compareTo(right.id);
            });
            if (hasCategory) {
                for (Video video : found) if (!containsVideo(videos, video.id)) videos.add(video);
            } else {
                // Mostly regular videos; every fifth entry is a music video from the region's
                // trending chart, which also gives the global view-count ranking a local touch.
                List<Video> local = awaitVideos(localChart, "Trending 20 chart");
                int nextLocal = 0;
                for (Video video : found) {
                    if (videos.size() % (MUSIC_VIDEO_EVERY + 1) == MUSIC_VIDEO_EVERY) {
                        while (nextLocal < local.size()
                                && containsVideo(videos, local.get(nextLocal).id)) nextLocal++;
                        if (nextLocal < local.size()) videos.add(local.get(nextLocal++));
                    }
                    if (!containsVideo(videos, video.id)) videos.add(video);
                }
                // Too few regular videos: fill up with the chart.
                for (; nextLocal < local.size() && videos.size() < resultLimit; nextLocal++) {
                    if (!containsVideo(videos, local.get(nextLocal).id)) videos.add(local.get(nextLocal));
                }
            }
        }
        if (videos.size() > resultLimit) {
            videos = new ArrayList<Video>(videos.subList(0, resultLimit));
        }
        if (hasCategory) for (Video video : videos) video.category = category;
        return videos;
    }

    /** The result of a lookup started earlier; an empty list when it failed or took too long. */
    private static List<Video> awaitVideos(Future<List<Video>> pending, String what) {
        if (pending == null) return new ArrayList<Video>();
        try {
            return pending.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            android.util.Log.w("InnertubeEndpoint", what + " unavailable: " + e);
            return new ArrayList<Video>();
        }
    }

    private static List<Video> chartVideos(String gl, String chartTitle, int limit, Config config) {
        List<Video> videos = new ArrayList<Video>();
        try {
            String chart = musicChartPlaylist(gl, chartTitle, config);
            if (!chart.isEmpty()) {
                for (Video video : PlaylistEndpoint.fetchPlaylist(chart, limit, config).videos) {
                    if (isPopularCandidate(video)) videos.add(video);
                }
            }
        } catch (Exception e) {
            android.util.Log.w("InnertubeEndpoint", chartTitle + " chart unavailable: " + e);
        }
        return videos;
    }

    /** Adds search results (with continuations) until {@code limit} candidates are known. */
    private static void collectPopular(String query, String params, String gl, int limit,
                                       Config config, List<Video> videos) throws Exception {
        JSONObject payload = context(config).put("query", query).put("params", params);
        payload.getJSONObject("context").getJSONObject("client").put("gl", gl);
        JSONObject response = post("search", payload, config);
        for (int page = 0; page < 3; page++) {
            List<Video> found = new ArrayList<Video>();
            collectVideoObjects(response, found, Integer.MAX_VALUE);
            for (Video video : found) {
                if (isPopularCandidate(video) && !containsVideo(videos, video.id)) videos.add(video);
            }
            String continuation = PlaylistEndpoint.findContinuation(response);
            if (videos.size() >= limit || continuation.isEmpty()) break;
            JSONObject next = context(config).put("continuation", continuation);
            next.getJSONObject("context").getJSONObject("client").put("gl", gl);
            response = post("search", next, config);
        }
    }

    /** Skips live streams, premieres and Shorts, like the old most_popular chart. */
    private static boolean isPopularCandidate(Video video) {
        if (!isValidVideoId(video.id) || video.duration.isEmpty()) return false;
        String views = video.views.toLowerCase(Locale.US);
        if (views.contains("watching") || views.contains("waiting")) return false;
        int seconds = durationSecondsPublic(video.duration);
        return seconds == 0 || seconds > MAX_SHORTS_SECONDS;
    }

    private static final java.util.Map<String, String[]> MUSIC_CHARTS =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, String[]>());

    /**
     * Id of a YouTube Music chart playlist for a region, by title prefix ("Top 100 Music Videos"
     * or "Trending 20"); chart pages are cached for a day.
     */
    private static String musicChartPlaylist(String gl, String titlePrefix, Config config)
            throws Exception {
        String[] cached = MUSIC_CHARTS.get(gl + "|" + titlePrefix);
        if (cached != null && System.currentTimeMillis() - Long.parseLong(cached[1])
                < TimeUnit.DAYS.toMillis(1)) {
            return cached[0];
        }
        JSONObject payload = new JSONObject()
                .put("context", new JSONObject().put("client", new JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", "1.20260720.01.00")
                        .put("hl", "en")
                        .put("gl", gl)))
                .put("browseId", "FEmusic_charts")
                .put("formData", new JSONObject().put("selectedValues", new JSONArray().put(gl)));
        java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
        headers.put("Origin", "https://music.youtube.com");
        headers.put("X-YouTube-Client-Name", "67");
        headers.put("X-YouTube-Client-Version", "1.20260720.01.00");
        JSONObject response = new JSONObject(HttpClient.postJson(
                "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",
                payload.toString(), DEFAULT_WEB_USER_AGENT, headers, 5000, 8000));
        String now = Long.toString(System.currentTimeMillis());
        for (String prefix : new String[]{"Top 100 Music Videos", "Trending 20"}) {
            MUSIC_CHARTS.put(gl + "|" + prefix, new String[]{findChartPlaylist(response, prefix), now});
        }
        return MUSIC_CHARTS.get(gl + "|" + titlePrefix)[0];
    }

    private static String findChartPlaylist(Object node, String titlePrefix) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject item = object.optJSONObject("musicTwoRowItemRenderer");
            if (item != null && runs(item.optJSONObject("title")).startsWith(titlePrefix)) {
                String browseId = findFirst(item.optJSONObject("navigationEndpoint"), "browseId");
                if (browseId.startsWith("VL")) return browseId.substring(2);
            }
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String found = findChartPlaylist(object.opt(names.optString(i)), titlePrefix);
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findChartPlaylist(array.opt(i), titlePrefix);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    static String fetchLiveEventsFeed(int resultLimit, Config config, String baseUrl)
            throws Exception {
        JSONObject payload = context(config)
                .put("query", "live")
                .put("params", "EgJAAQ==");
        JSONObject response = post("search", payload, config);
        List<Video> videos = collectVideos(response, resultLimit);
        videos = completeFeedVideos(videos);
        String feedId = baseUrl + "/feeds/api/charts/live/events/live_now";
        return buildFeed(videos, "Live now", feedId, baseUrl);
    }

    private static final long SEARCH_MAX_AGE_MS = 30L * 60 * 1000;

    /** Search results loaded so far for a query, so later pages continue from the stored token. */
    /** A playlist among the search results. */
    static final class SearchPlaylist {
        String id = "";
        String title = "";
        String author = "";
        String authorId = "";
        /** Number of videos as YouTube wrote it, empty when the card does not say. */
        String count = "";
        String firstVideoId = "";
        String thumbnail = "";
    }

    /** A channel among the search results. */
    static final class SearchChannel {
        String id = "";
        String name = "";
        String handle = "";
        String subscribers = "";
        String avatar = "";
    }

    private static final class LoadedSearch {
        final List<Video> videos = new ArrayList<Video>();
        final List<SearchPlaylist> playlists = new ArrayList<SearchPlaylist>();
        final List<SearchChannel> channels = new ArrayList<SearchChannel>();
        String continuation = "";
        long estimatedResults;
        long loadedAt;
    }

    private static final java.util.Map<String, LoadedSearch> SEARCHES =
            new java.util.LinkedHashMap<String, LoadedSearch>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, LoadedSearch> eldest) {
                    return size() > 16;
                }
            };

    private String searchFeed(String query, int startIndex, int limit,
                              NanoHTTPD.IHTTPSession session, Config config, Context context,
                              boolean[] complete) throws Exception {
        SearchPage page = searchPage(query, startIndex, limit, config, context);
        List<Video> videos = page.videos;
        int count = videos.size();
        complete[0] = enrichVideos(videos, config, context);
        String baseUrl = "http://" + requestHost(session);
        String feedUrl = baseUrl + "/feeds/api/videos?q=" + URLEncoder.encode(query, "UTF-8");
        String nextLink = page.hasMore ? feedUrl + "&start-index=" + (startIndex + count)
                + "&max-results=" + limit : "";
        return buildFeed(videos, "Search results: " + query, feedUrl, baseUrl, startIndex,
                nextLink, "", (int) page.total, false);
    }

    /** One page of search results (videos only), without the extra metadata lookups. */
    static final class SearchPage {
        List<Video> videos;
        /** Channels and playlists YouTube mixed into the results; only on the first page. */
        List<SearchChannel> channels = new ArrayList<SearchChannel>();
        List<SearchPlaylist> playlists = new ArrayList<SearchPlaylist>();
        boolean hasMore;
        long total;
    }

    /**
     * Results {@code startIndex} (1-based) to {@code startIndex + limit - 1} of a search. Pages
     * already loaded are reused and the following page is prefetched in the background.
     */
    static SearchPage searchPage(String query, int startIndex, int limit, Config config,
                                 Context context) throws Exception {
        int endIndex = startIndex + limit - 1;
        LoadedSearch loaded;
        synchronized (SEARCHES) {
            loaded = SEARCHES.get(query);
            if (loaded == null || (loaded.loadedAt != 0
                    && System.currentTimeMillis() - loaded.loadedAt > SEARCH_MAX_AGE_MS)) {
                loaded = new LoadedSearch();
                SEARCHES.put(query, loaded);
            }
        }
        // Waits for a prefetch of the same query that is still running.
        synchronized (loaded) {
            loadSearchPages(query, loaded, endIndex, config);
        }
        if (!loaded.continuation.isEmpty()) {
            PREFETCH.execute(new SearchPrefetch(query, loaded, endIndex, limit, config, context));
        }

        int offset;
        int count;
        List<Video> videos;
        boolean hasContinuation;
        int loadedCount;
        synchronized (loaded) {
            offset = Math.min(startIndex - 1, loaded.videos.size());
            count = Math.min(limit, loaded.videos.size() - offset);
            videos = new ArrayList<Video>(count);
            for (int i = offset; i < offset + count; i++) videos.add(loaded.videos.get(i).copy());
            hasContinuation = !loaded.continuation.isEmpty();
            loadedCount = loaded.videos.size();
        }
        SearchPage page = new SearchPage();
        page.videos = videos;
        // The cards YouTube puts between the first results; later pages are videos only.
        if (startIndex <= 1) {
            synchronized (loaded) {
                page.channels = new ArrayList<SearchChannel>(loaded.channels);
                page.playlists = new ArrayList<SearchPlaylist>(loaded.playlists);
            }
        }
        page.hasMore = count > 0 && (hasContinuation || loadedCount > offset + count);
        long known = loadedCount + (hasContinuation ? limit : 0);
        page.total = !hasContinuation ? loadedCount
                : Math.max(known, Math.min(loaded.estimatedResults, 1000));
        return page;
    }

    /** Loads result pages until {@code endIndex} results are known. Caller holds the lock. */
    private static void loadSearchPages(String query, LoadedSearch loaded, int endIndex,
                                        Config config) throws Exception {
        if (loaded.loadedAt == 0) {
            JSONObject response = post("search", InnertubeEndpoint.context(config).put("query", query), config);
            loaded.estimatedResults = parseLong(response.optString("estimatedResults", ""));
            addSearchPage(response, loaded);
            loaded.loadedAt = System.currentTimeMillis();
        }
        // About 20 results per page; follow continuations until the requested page is covered.
        for (int page = 0; page < 10 && loaded.videos.size() < endIndex
                && !loaded.continuation.isEmpty(); page++) {
            JSONObject next = post("search", InnertubeEndpoint.context(config)
                    .put("continuation", loaded.continuation), config);
            int before = loaded.videos.size();
            addSearchPage(next, loaded);
            if (loaded.videos.size() == before) loaded.continuation = "";
        }
    }

    private static final ExecutorService PREFETCH = Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "search-prefetch");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }
            });

    /**
     * YouTube takes one to three seconds to answer a continuation, so the next page and its
     * metadata are loaded while the current page is shown; scrolling to it is then fast.
     */
    private static final class SearchPrefetch implements Runnable {
        private final String query;
        private final LoadedSearch loaded;
        private final int shownEnd;
        private final int limit;
        private final Config config;
        private final Context context;

        SearchPrefetch(String query, LoadedSearch loaded, int shownEnd, int limit, Config config,
                       Context context) {
            this.query = query;
            this.loaded = loaded;
            this.shownEnd = shownEnd;
            this.limit = limit;
            this.config = config;
            this.context = context;
        }

        @Override
        public void run() {
            List<Video> next = new ArrayList<Video>();
            synchronized (loaded) {
                try {
                    loadSearchPages(query, loaded, shownEnd + limit, config);
                } catch (Exception e) {
                    android.util.Log.w("InnertubeEndpoint", "Search prefetch failed: " + e);
                }
                for (int i = shownEnd; i < loaded.videos.size() && i < shownEnd + limit; i++) {
                    next.add(loaded.videos.get(i).copy());
                }
            }
            // Warm the metadata caches only; nothing waits for these lookups.
            for (Video video : next) {
                if (!isValidVideoId(video.id)) continue;
                ENRICH_POOL.submit(new MetadataTask(video, config, context));
                if (config.getYouTubeApiKey().isEmpty()) ENRICH_POOL.submit(new LikesTask(video, context));
            }
        }
    }

    private static void addSearchPage(JSONObject page, LoadedSearch loaded) {
        List<Video> found = new ArrayList<Video>(loaded.videos);
        collectVideoObjects(page, found, Integer.MAX_VALUE);
        for (int i = loaded.videos.size(); i < found.size(); i++) loaded.videos.add(found.get(i));
        // Only the first answer is searched for channel and playlist cards: they are shown with
        // the first page of results, and walking every later answer for them would be wasted.
        if (loaded.loadedAt == 0) collectSearchCards(page, loaded);
        loaded.continuation = PlaylistEndpoint.findContinuation(page);
    }

    /**
     * The channel and playlist cards of a search answer. YouTube sends channels as
     * channelRenderer and playlists either as playlistRenderer or, since the move to view
     * models, as a lockupViewModel of type LOCKUP_CONTENT_TYPE_PLAYLIST.
     */
    private static void collectSearchCards(Object node, LoadedSearch loaded) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray names = object.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                Object value = object.opt(key);
                if (value instanceof JSONObject) {
                    JSONObject renderer = (JSONObject) value;
                    if ("channelRenderer".equals(key) || "gridChannelRenderer".equals(key)) {
                        addSearchChannel(renderer, loaded);
                        continue;
                    }
                    if ("playlistRenderer".equals(key) || "gridPlaylistRenderer".equals(key)
                            || "radioRenderer".equals(key)) {
                        addSearchPlaylist(parsePlaylistRenderer(renderer), loaded);
                        continue;
                    }
                    if ("lockupViewModel".equals(key)
                            && "LOCKUP_CONTENT_TYPE_PLAYLIST".equals(renderer.optString("contentType", ""))) {
                        addSearchPlaylist(parseLockupPlaylist(renderer), loaded);
                        continue;
                    }
                }
                collectSearchCards(value, loaded);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collectSearchCards(array.opt(i), loaded);
        }
    }

    private static void addSearchChannel(JSONObject renderer, LoadedSearch loaded) {
        SearchChannel channel = new SearchChannel();
        channel.id = renderer.optString("channelId", "");
        if (channel.id.isEmpty()) channel.id = findFirst(renderer, "browseId");
        if (!channel.id.matches("UC[A-Za-z0-9_-]{22}")) return;
        for (SearchChannel known : loaded.channels) {
            if (known.id.equals(channel.id)) return;
        }
        channel.name = text(renderer.optJSONObject("title"));
        if (channel.name.isEmpty()) channel.name = runs(renderer.optJSONObject("title"));
        // YouTube fills these cards by position: the handle and the subscriber count swap places,
        // so each value is sorted by what it contains.
        for (String key : new String[]{"subscriberCountText", "videoCountText", "shortBylineText"}) {
            String value = text(renderer.optJSONObject(key)).trim();
            if (value.isEmpty()) value = runs(renderer.optJSONObject(key)).trim();
            if (value.isEmpty()) continue;
            if (value.startsWith("@")) {
                if (channel.handle.isEmpty()) channel.handle = value;
            } else if (value.toLowerCase(Locale.US).contains("subscriber")) {
                if (channel.subscribers.isEmpty()) channel.subscribers = value;
            }
        }
        channel.avatar = absoluteUrl(thumbnail(renderer));
        if (channel.name.isEmpty()) return;
        loaded.channels.add(channel);
    }

    private static void addSearchPlaylist(SearchPlaylist playlist, LoadedSearch loaded) {
        if (playlist == null || playlist.id.isEmpty() || playlist.title.isEmpty()) return;
        // A mix (RD...) is not a playlist: asking YouTube to browse it answers with an alert and
        // no videos, so such a card would lead nowhere.
        if (playlist.id.startsWith("RD")) return;
        for (SearchPlaylist known : loaded.playlists) {
            if (known.id.equals(playlist.id)) return;
        }
        loaded.playlists.add(playlist);
    }

    private static SearchPlaylist parsePlaylistRenderer(JSONObject renderer) {
        SearchPlaylist playlist = new SearchPlaylist();
        playlist.id = renderer.optString("playlistId", "");
        playlist.title = text(renderer.optJSONObject("title"));
        if (playlist.title.isEmpty()) playlist.title = runs(renderer.optJSONObject("title"));
        playlist.author = runs(renderer.optJSONObject("shortBylineText"));
        if (playlist.author.isEmpty()) playlist.author = runs(renderer.optJSONObject("longBylineText"));
        playlist.authorId = findFirst(renderer.optJSONObject("shortBylineText"), "browseId");
        playlist.count = numericCount(text(renderer.optJSONObject("videoCountText")));
        if (playlist.count.isEmpty()) playlist.count = renderer.optString("videoCount", "");
        playlist.thumbnail = absoluteUrl(thumbnail(renderer));
        playlist.firstVideoId = findFirst(renderer.opt("navigationEndpoint"), "videoId");
        return playlist;
    }

    private static SearchPlaylist parseLockupPlaylist(JSONObject renderer) {
        SearchPlaylist playlist = new SearchPlaylist();
        playlist.id = renderer.optString("contentId", "");
        JSONObject metadata = renderer.optJSONObject("metadata");
        JSONObject lockupMetadata = metadata == null
                ? null : metadata.optJSONObject("lockupMetadataViewModel");
        JSONObject titleModel = lockupMetadata == null ? null : lockupMetadata.optJSONObject("title");
        playlist.title = titleModel == null ? "" : titleModel.optString("content", "");
        JSONObject contentMetadata = lockupMetadata == null
                ? null : lockupMetadata.optJSONObject("metadata");
        JSONObject metadataView = contentMetadata == null
                ? null : contentMetadata.optJSONObject("contentMetadataViewModel");
        JSONArray rows = metadataView == null ? null : metadataView.optJSONArray("metadataRows");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
                if (parts == null) continue;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    JSONObject textModel = part == null ? null : part.optJSONObject("text");
                    if (textModel == null) continue;
                    String value = textModel.optString("content", "");
                    if (value.isEmpty()) continue;
                    String linkedChannel = findFirst(textModel.opt("commandRuns"), "browseId");
                    if (playlist.author.isEmpty() && linkedChannel.startsWith("UC")) {
                        playlist.author = value;
                        playlist.authorId = linkedChannel;
                    } else if (playlist.author.isEmpty() && !value.equalsIgnoreCase("Playlist")
                            && !value.equalsIgnoreCase("Mix")) {
                        playlist.author = value;
                    }
                }
            }
        }
        playlist.firstVideoId = findFirst(renderer.opt("rendererContext"), "videoId");
        if (playlist.firstVideoId.isEmpty()) playlist.firstVideoId = findFirst(renderer, "videoId");
        JSONObject contentImage = renderer.optJSONObject("contentImage");
        JSONObject collection = contentImage == null
                ? null : contentImage.optJSONObject("collectionThumbnailViewModel");
        JSONObject primary = collection == null ? null : collection.optJSONObject("primaryThumbnail");
        JSONObject thumbnailModel = primary == null
                ? null : primary.optJSONObject("thumbnailViewModel");
        if (thumbnailModel == null && contentImage != null) {
            thumbnailModel = contentImage.optJSONObject("thumbnailViewModel");
        }
        JSONObject image = thumbnailModel == null ? null : thumbnailModel.optJSONObject("image");
        JSONArray sources = image == null ? null : image.optJSONArray("sources");
        if (sources != null && sources.length() > 0) {
            JSONObject last = sources.optJSONObject(sources.length() - 1);
            if (last != null) playlist.thumbnail = absoluteUrl(last.optString("url", ""));
        }
        playlist.count = lockupBadgeCount(thumbnailModel);
        return playlist;
    }

    /** "186 videos" from the badge over a playlist card, as the number alone. */
    private static String lockupBadgeCount(JSONObject thumbnailModel) {
        JSONArray overlays = thumbnailModel == null ? null : thumbnailModel.optJSONArray("overlays");
        if (overlays == null) return "";
        for (int i = 0; i < overlays.length(); i++) {
            JSONObject overlay = overlays.optJSONObject(i);
            JSONObject badgeModel = overlay == null
                    ? null : overlay.optJSONObject("thumbnailOverlayBadgeViewModel");
            JSONArray badges = badgeModel == null ? null : badgeModel.optJSONArray("thumbnailBadges");
            if (badges == null) continue;
            for (int j = 0; j < badges.length(); j++) {
                JSONObject badge = badges.optJSONObject(j);
                JSONObject view = badge == null ? null : badge.optJSONObject("thumbnailBadgeViewModel");
                String text = view == null ? "" : view.optString("text", "");
                if (text.toLowerCase(Locale.US).contains("video")) {
                    String count = numericCount(text);
                    if (!count.isEmpty() && !"0".equals(count)) return count;
                }
            }
        }
        return "";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int parseStartIndex(String value) {
        try {
            int start = Integer.parseInt(value.trim());
            return start < 1 ? 1 : Math.min(start, 1000);
        } catch (Exception ignored) {
            return 1;
        }
    }

    private String relatedFeed(String videoId, NanoHTTPD.IHTTPSession session,
                               Config config, Context context) throws Exception {
        JSONObject payload = InnertubeEndpoint.context(config)
                .put("videoId", videoId)
                .put("autonavState", "STATE_OFF")
                .put("captionsRequested", false)
                .put("contentCheckOk", true)
                .put("playbackContext", new JSONObject()
                        .put("vis", 0)
                        .put("lactMilliseconds", "1"))
                .put("racyCheckOk", true);
        JSONObject client = payload.getJSONObject("context").getJSONObject("client");
        client.put("clientScreen", "WATCH")
                .put("originalUrl", "https://www.youtube.com/watch?v=" + videoId);
        JSONObject response = post("next", payload, config);
        List<Video> candidates = collectVideos(response, 50);
        List<Video> videos = new ArrayList<Video>();
        for (Video candidate : candidates) {
            if (!videoId.equals(candidate.id)) videos.add(candidate);
        }
        if (videos.size() > 12) videos = new ArrayList<Video>(videos.subList(0, 12));
        enrichVideos(videos, config, context);
        String baseUrl = "http://" + requestHost(session);
        return buildFeed(videos, "Related videos",
            baseUrl + "/feeds/api/videos/" + videoId + "/related", baseUrl);
    }

    /** What the watch page shows: the video, its channel and the related videos. */
    static final class WatchPage {
        final Video video = new Video();
        String ownerAvatar = "";
        String subscribers = "";
        /** YouTube's own date label, for example "Oct 3, 2024". */
        String dateText = "";
        final List<Video> related = new ArrayList<Video>();
    }

    /**
     * Loads the watch page of a video: /next gives the channel (with its avatar) and the related
     * videos, while the description, exact counts and likes come from the cached metadata
     * lookups, which run in parallel.
     */
    static WatchPage watchPage(String videoId, int relatedLimit, Config config, Context context)
            throws Exception {
        WatchPage page = new WatchPage();
        page.video.id = videoId;
        Future<Runnable> metadata = ENRICH_POOL.submit(new MetadataTask(page.video, config, context));
        Future<Runnable> likes = config.getYouTubeApiKey().isEmpty()
                ? ENRICH_POOL.submit(new LikesTask(page.video, context)) : null;

        JSONObject response = post("next", watchNextPayload(videoId, config), config);

        JSONObject primary = firstObject(response, "videoPrimaryInfoRenderer");
        if (primary != null) {
            page.video.title = runs(primary.optJSONObject("title"));
            JSONObject viewCount = primary.optJSONObject("viewCount");
            JSONObject viewRenderer = viewCount == null
                    ? null : viewCount.optJSONObject("videoViewCountRenderer");
            if (viewRenderer != null) {
                String views = runs(viewRenderer.optJSONObject("viewCount"));
                if (!views.isEmpty()) page.video.views = numericCount(views);
            }
            page.dateText = runs(primary.optJSONObject("dateText"));
        }
        JSONObject owner = firstObject(response, "videoOwnerRenderer");
        if (owner != null) {
            page.video.channel = runs(owner.optJSONObject("title"));
            page.video.channelId = findFirst(owner.optJSONObject("navigationEndpoint"), "browseId");
            page.ownerAvatar = absoluteUrl(thumbnail(owner));
            page.subscribers = runs(owner.optJSONObject("subscriberCountText"));
        }
        // The list next to the player, found once: walking the whole answer again for the
        // continuation token would cost as much as collecting the videos did.
        JSONObject secondary = firstObject(response, "secondaryResults");
        for (Video candidate : collectVideos(secondary != null ? secondary : response, relatedLimit + 1)) {
            if (!videoId.equals(candidate.id) && page.related.size() < relatedLimit) {
                page.related.add(candidate);
            }
        }
        // The same answer holds the token for the videos after them, so a later page costs one
        // request instead of two.
        rememberRelated(videoId, secondary, page.related);

        // Applied here, on the request thread; a lookup that is too slow keeps filling the cache.
        try {
            metadata.get(4, TimeUnit.SECONDS).run();
        } catch (Exception e) {
            android.util.Log.w("InnertubeEndpoint", "Watch metadata unavailable for " + videoId + ": " + e);
        }
        if (likes != null) {
            try {
                likes.get(2, TimeUnit.SECONDS).run();
            } catch (Exception e) {
                android.util.Log.w("InnertubeEndpoint", "Likes unavailable for " + videoId + ": " + e);
            }
        }
        if (page.video.thumbnail.isEmpty()) {
            page.video.thumbnail = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        }
        return page;
    }

    private static JSONObject watchNextPayload(String videoId, Config config) throws Exception {
        JSONObject payload = InnertubeEndpoint.context(config)
                .put("videoId", videoId)
                .put("autonavState", "STATE_OFF")
                .put("captionsRequested", false)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true);
        payload.getJSONObject("context").getJSONObject("client")
                .put("clientScreen", "WATCH")
                .put("originalUrl", "https://www.youtube.com/watch?v=" + videoId);
        return payload;
    }

    // ------------------------------------------------------------------ related videos, by page

    private static final long RELATED_MAX_AGE_MS = 15L * 60 * 1000;

    private static final class LoadedRelated {
        final List<Video> videos = new ArrayList<Video>();
        String continuation = "";
        long loadedAt;
    }

    private static final java.util.Map<String, LoadedRelated> RELATED =
            new java.util.LinkedHashMap<String, LoadedRelated>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, LoadedRelated> eldest) {
                    return size() > 16;
                }
            };

    static final class RelatedPage {
        List<Video> videos = new ArrayList<Video>();
        boolean hasMore;
    }

    private static LoadedRelated relatedEntry(String videoId) {
        synchronized (RELATED) {
            LoadedRelated loaded = RELATED.get(videoId);
            if (loaded == null || System.currentTimeMillis() - loaded.loadedAt > RELATED_MAX_AGE_MS) {
                loaded = new LoadedRelated();
                RELATED.put(videoId, loaded);
            }
            return loaded;
        }
    }

    /**
     * Keeps the related videos of a watch page, with YouTube's token for the ones after them.
     * The token is looked for in the list next to the player only, so the continuation of the
     * comments is not mistaken for it.
     */
    private static void rememberRelated(String videoId, JSONObject secondaryResults, List<Video> related) {
        if (secondaryResults == null) return;
        LoadedRelated loaded = relatedEntry(videoId);
        synchronized (loaded) {
            if (loaded.loadedAt != 0) return;
            for (Video video : related) loaded.videos.add(video.copy());
            loaded.continuation = PlaylistEndpoint.findContinuation(secondaryResults);
            loaded.loadedAt = System.currentTimeMillis();
        }
    }

    /**
     * Related videos {@code offset} and after, following YouTube's continuations when the page
     * reaches past what is loaded. Used for the endless list under a video.
     */
    static RelatedPage relatedPage(String videoId, int offset, int limit, Config config)
            throws Exception {
        LoadedRelated loaded = relatedEntry(videoId);
        RelatedPage page = new RelatedPage();
        synchronized (loaded) {
            if (loaded.loadedAt == 0) {
                JSONObject response = post("next", watchNextPayload(videoId, config), config);
                JSONObject secondary = firstObject(response, "secondaryResults");
                JSONObject list = secondary != null ? secondary : response;
                for (Video candidate : collectVideos(list, limit + offset + 1)) {
                    if (!videoId.equals(candidate.id)) loaded.videos.add(candidate);
                }
                loaded.continuation = PlaylistEndpoint.findContinuation(list);
                loaded.loadedAt = System.currentTimeMillis();
            }
            for (int request = 0; request < 5 && loaded.videos.size() < offset + limit
                    && !loaded.continuation.isEmpty(); request++) {
                JSONObject next = post("next", InnertubeEndpoint.context(config)
                        .put("continuation", loaded.continuation), config);
                int before = loaded.videos.size();
                for (Video candidate : collectVideos(next, offset + limit + 1)) {
                    if (!videoId.equals(candidate.id) && !containsVideo(loaded.videos, candidate.id)) {
                        loaded.videos.add(candidate);
                    }
                }
                loaded.continuation = PlaylistEndpoint.findContinuation(next);
                if (loaded.videos.size() == before) loaded.continuation = "";
            }
            int start = Math.min(offset, loaded.videos.size());
            int count = Math.min(limit, loaded.videos.size() - start);
            for (int i = start; i < start + count; i++) page.videos.add(loaded.videos.get(i).copy());
            page.hasMore = count > 0
                    && (!loaded.continuation.isEmpty() || loaded.videos.size() > start + count);
        }
        return page;
    }

    private static JSONObject firstObject(Object node, String key) {
        List<JSONObject> found = new ArrayList<JSONObject>(1);
        collectObjects(node, key, found, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Image URLs on YouTube pages are often protocol-relative ("//yt3.ggpht.com/..."). */
    static String absoluteUrl(String url) {
        if (url == null) return "";
        return url.startsWith("//") ? "https:" + url : url;
    }

    /** One comment as the feeds and the protobuf answers need it. */
    static final class Comment {
        String id = "";
        String author = "";
        String authorId = "";
        String avatar = "";
        String text = "";
        /** The time as an Atom date, which the feeds print and the apps turn into "3 days ago". */
        String published = "";
    }

    /** The comment threads YouTube answers with, newest first. */
    private static List<JSONObject> loadCommentThreads(String videoId, int limit, Config config)
            throws Exception {
        final int wanted = limit <= 0 ? 20 : limit;
        List<JSONObject> comments = new ArrayList<JSONObject>();
        JSONObject requestContext = commentRequestContext(config, videoId);
        String continuation = initialCommentsContinuation(videoId);
        JSONObject response = post("next", new JSONObject()
                .put("context", requestContext.getJSONObject("context"))
                .put("continuation", continuation), config);

        collectCommentEntities(response, comments, wanted);
        if (comments.isEmpty()) collectObjects(response, "commentThreadRenderer", comments, wanted);
        if (comments.isEmpty()) collectObjects(response, "commentViewModel", comments, wanted);
        continuation = findCommentsContinuation(response);

        int continuationRequests = 0;
        while (!continuation.isEmpty() && comments.size() < wanted && continuationRequests < 2) {
            response = post("next", new JSONObject()
                    .put("context", requestContext.getJSONObject("context"))
                    .put("continuation", continuation), config);
            int previousCount = comments.size();
            collectCommentEntities(response, comments, wanted);
            if (comments.isEmpty()) collectObjects(response, "commentThreadRenderer", comments, wanted);
            if (comments.isEmpty()) collectObjects(response, "commentViewModel", comments, wanted);
            String nextContinuation = findCommentsContinuation(response);
            if (comments.size() == previousCount || continuation.equals(nextContinuation)) break;
            continuation = nextContinuation;
            continuationRequests++;
        }
        return comments;
    }

    /** The fields of one thread, whichever of the three shapes YouTube used for it. */
    private static Comment parseComment(JSONObject thread) {
        Comment comment = new Comment();
            JSONObject renderer = thread.optJSONObject("comment");
            if (renderer == null && thread.has("commentId")) renderer = thread;
            if (renderer == null) renderer = thread.optJSONObject("commentViewModel");
            if (renderer == null) return comment;
            JSONObject commentRenderer = renderer.optJSONObject("commentRenderer");
            if (commentRenderer != null) renderer = commentRenderer;
            JSONObject commentViewModel = renderer.optJSONObject("commentViewModel");
            if (commentViewModel != null) renderer = commentViewModel;
            JSONObject properties = renderer.optJSONObject("properties");
            String id = renderer.optString("commentId", "");
            if (id.isEmpty() && properties != null) id = properties.optString("commentId", "");
            JSONObject authorText = renderer.optJSONObject("authorText");
            if (authorText == null) authorText = renderer.optJSONObject("authorName");
            JSONObject authorData = renderer.optJSONObject("author");
            String author = runs(authorText);
            if (author.isEmpty() && authorData != null) author = authorData.optString("displayName", "");
            JSONObject contentText = renderer.optJSONObject("contentText");
            if (contentText == null) contentText = renderer.optJSONObject("content");
            String body = runs(contentText);
            if (body.isEmpty() && properties != null) {
                JSONObject propertyContent = properties.optJSONObject("content");
                body = propertyContent == null ? "" : propertyContent.optString("content", "");
            }
            JSONObject publishedText = renderer.optJSONObject("publishedTimeText");
            if (publishedText == null) publishedText = renderer.optJSONObject("publishedTime");
            JSONObject authorEndpoint = renderer.optJSONObject("authorEndpoint");
            JSONObject browseEndpoint = authorEndpoint == null ? null : authorEndpoint.optJSONObject("browseEndpoint");
            String authorId = browseEndpoint == null ? "" : browseEndpoint.optString("browseId", "");
            if (authorId.isEmpty() && authorData != null) authorId = authorData.optString("channelId", "");
            String publishedAt = renderer.optString("publishedAt", "");
            if (publishedAt.isEmpty()) publishedAt = runs(publishedText);
            publishedAt = toAtomDate(publishedAt);
            if (id.isEmpty()) id = authorId;
        comment.id = id;
        comment.author = author;
        comment.authorId = authorId;
        comment.text = body;
        comment.published = publishedAt;
        comment.avatar = absoluteUrl(thumbnail(renderer));
        if (comment.avatar.isEmpty() && authorData != null) {
            // The entities of today's answers keep the picture as a plain link.
            String direct = authorData.optString("avatarThumbnailUrl", "");
            comment.avatar = direct.isEmpty() ? absoluteUrl(thumbnail(authorData)) : absoluteUrl(direct);
        }
        if (comment.avatar.isEmpty()) {
            comment.avatar = absoluteUrl(thumbnailUrl(renderer.optJSONObject("authorThumbnail")));
        }
        return comment;
    }

    /** The comments of a video, for the protobuf API of the 2015-2017 apps. */
    static List<Comment> comments(String videoId, int limit, Config config) throws Exception {
        List<Comment> out = new ArrayList<Comment>();
        for (JSONObject thread : loadCommentThreads(videoId, limit, config)) {
            Comment comment = parseComment(thread);
            if (!comment.text.isEmpty() || !comment.author.isEmpty()) out.add(comment);
        }
        return out;
    }

    private String commentsFeed(String videoId, NanoHTTPD.IHTTPSession session,
                                Config config, Context context) throws Exception {
        List<JSONObject> comments = loadCommentThreads(videoId, 20, config);
        String host = requestHost(session);
        String baseUrl = "http://" + host;
        String videoUrl = baseUrl + "/feeds/api/videos/" + videoId;
        String timestamp = utcNow();
        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>")
                .append("<feed xmlns='http://www.w3.org/2005/Atom'")
                .append(" xmlns:yt='http://gdata.youtube.com/schemas/2007'")
                .append(" xmlns:gd='http://schemas.google.com/g/2005'")
                .append(" xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'>")
                .append("<id>").append(XmlUtil.escape(videoUrl)).append("/comments</id>")
                .append("<updated>").append(timestamp).append("</updated>")
                .append("<title type='text'>Comments on Video</title>")
                .append("<openSearch:totalResults>").append(comments.size()).append("</openSearch:totalResults>")
                .append("<openSearch:startIndex>1</openSearch:startIndex>")
                .append("<openSearch:itemsPerPage>25</openSearch:itemsPerPage>");
        for (JSONObject thread : comments) {
            Comment comment = parseComment(thread);
            if (comment.text.isEmpty() && comment.author.isEmpty()) continue;
            String id = comment.id;
            String body = comment.text;
            String authorId = comment.authorId;
            String publishedAt = comment.published;
            String shownAuthor = comment.author.startsWith("@") ? comment.author : "@" + comment.author;
            xml.append("<entry><id>tag:youtube.com,2008:comment:")
                    .append(XmlUtil.escape(videoId)).append(":").append(XmlUtil.escape(id)).append("</id>")
                    .append("<published>").append(XmlUtil.escape(publishedAt)).append("</published>")
                    .append("<updated>").append(XmlUtil.escape(publishedAt)).append("</updated>")
                    .append("<title type='text'>Comment by ").append(XmlUtil.escape(shownAuthor)).append("</title>")
                    .append("<content type='text'>").append(XmlUtil.escape(body)).append("</content>")
                    .append("<author><name>").append(XmlUtil.escape(shownAuthor)).append("</name>")
                    .append("<uri>").append(XmlUtil.escape(baseUrl)).append("/feeds/api/users/")
                    .append(XmlUtil.escape(authorId)).append("</uri></author>")
                    .append("<yt:videoid>").append(XmlUtil.escape(videoId)).append("</yt:videoid></entry>");
        }
        return xml.append("</feed>").toString();
    }

    static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date());
    }

    private String suggestions(String query, Config config) throws Exception {
        JSONObject response = post("search", InnertubeEndpoint.context(config).put("query", query), config);
        List<Video> videos = collectVideos(response, 8);
        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?><toplevel><CompleteSuggestion>");
        for (Video video : videos) {
            xml.append("<suggestion data='").append(XmlUtil.escape(video.title)).append("'/>");
        }
        return xml.append("</CompleteSuggestion></toplevel>").toString();
    }

    private static JSONObject post(String endpoint, JSONObject payload, Config config)
            throws IOException, JSONException {
        return post(endpoint, payload, config, null, 10000, 15000);
    }

    private static JSONObject post(String endpoint, JSONObject payload, Config config, String userAgent)
            throws IOException, JSONException {
        return post(endpoint, payload, config, userAgent, 10000, 15000);
    }

    private static JSONObject post(String endpoint, JSONObject payload, Config config, String userAgent,
                                   int connectTimeout, int readTimeout)
            throws IOException, JSONException {
        return post(endpoint, payload, config, userAgent, connectTimeout, readTimeout, null);
    }

    private static JSONObject post(String endpoint, JSONObject payload, Config config, String userAgent,
                                   java.util.Map<String, String> extraHeaders)
            throws IOException, JSONException {
        return post(endpoint, payload, config, userAgent, 10000, 15000, extraHeaders);
    }

    private static JSONObject post(String endpoint, JSONObject payload, Config config, String userAgent,
                                   int connectTimeout, int readTimeout,
                                   java.util.Map<String, String> extraHeaders)
            throws IOException, JSONException {
        JSONObject requestContext = payload.optJSONObject("context");
        JSONObject client = requestContext == null ? null : requestContext.optJSONObject("client");
        String clientName = client == null ? "" : client.optString("clientName", "");
        String version = client == null ? "" : client.optString("clientVersion", "");
        if (version.isEmpty() && client != null) {
            version = YT2009_CLIENT_VERSION;
            client.put("clientVersion", version);
        }

        StringBuilder url = new StringBuilder(YOUTUBEI).append(endpoint).append("?alt=json&prettyPrint=false");
        String apiKey = config.getInnertubeApiKey();
        if (!apiKey.isEmpty()) url.append("&key=").append(URLEncoder.encode(apiKey, "UTF-8"));

        java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
        headers.put("X-Goog-Api-Format-Version", "1");
        String clientId = clientId(clientName);
        if (!clientId.isEmpty()) headers.put("X-YouTube-Client-Name", clientId);
        if (!version.isEmpty()) headers.put("X-YouTube-Client-Version", version);
        String language = client == null ? "en" : client.optString("hl", "en");
        headers.put("Accept-Language", language + ",en;q=0.9");
        headers.put("X-YouTube-Bootstrap-Logged-In", "false");
        String visitorData = client == null ? "" : client.optString("visitorData", "");
        if (!visitorData.isEmpty()) headers.put("X-Goog-EOM-Visitor-Id", visitorData);
        String defaultReferer = "MWEB".equals(clientName)
                ? "https://m.youtube.com/" : "https://www.youtube.com/";
        headers.put("Referer", client == null
                ? defaultReferer : client.optString("originalUrl", defaultReferer));
        headers.put("Accept", "*/*");
        if (extraHeaders != null) headers.putAll(extraHeaders);

        if (userAgent == null || userAgent.trim().isEmpty()) {
            userAgent = configuredUserAgent(config);
        }
        if ("Mozilla/5.0".equals(userAgent.trim())) {
            userAgent = "ANDROID".equals(clientName)
                    ? "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    : DEFAULT_WEB_USER_AGENT;
        }

        String response = HttpClient.postJson(url.toString(), payload.toString(), userAgent, headers,
                connectTimeout, readTimeout);
        JSONObject result = new JSONObject(response);
        if (result.has("error")) {
            throw new IOException("YouTube Innertube " + endpoint + " returned an error: "
                    + result.opt("error"));
        }
        return result;
    }

    private static String configuredUserAgent(Config config) {
        String userAgent = config.getString("user_agent", DEFAULT_WEB_USER_AGENT);
        return userAgent.trim().isEmpty() || "Mozilla/5.0".equals(userAgent.trim())
                || userAgent.contains("Chrome/131.0.0.0")
                ? DEFAULT_WEB_USER_AGENT : userAgent;
    }

    private static String clientId(String clientName) {
        if ("WEB".equals(clientName)) return "1";
        if ("MWEB".equals(clientName)) return "2";
        if ("ANDROID".equals(clientName)) return "3";
        return "";
    }

    static JSONObject request(String endpoint, JSONObject payload, Config config) throws Exception {
        return post(endpoint, payload, config);
    }

    static JSONObject request(String endpoint, JSONObject payload, Config config, String userAgent)
            throws Exception {
        return post(endpoint, payload, config, userAgent);
    }

    static JSONObject request(String endpoint, JSONObject payload, Config config, String userAgent,
                              java.util.Map<String, String> headers) throws Exception {
        return post(endpoint, payload, config, userAgent, headers);
    }

    static JSONObject requestFast(String endpoint, JSONObject payload, Config config) throws Exception {
        return post(endpoint, payload, config, null, 1200, 2500);
    }

    static String browseContinuation(String browseId, String params) throws IOException {
        ByteArrayOutputStream browseNavigation = new ByteArrayOutputStream();
        writeProtoString(browseNavigation, 2, browseId);
        writeProtoString(browseNavigation, 3, params);

        ByteArrayOutputStream continuation = new ByteArrayOutputStream();
        writeProtoMessage(continuation, 80226972, browseNavigation.toByteArray());
        String base64 = Base64.encodeToString(continuation.toByteArray(), Base64.NO_WRAP);
        return URLEncoder.encode(base64, "UTF-8");
    }

    private static JSONObject commentRequestContext(Config config, String videoId) throws JSONException {
        JSONObject requestContext = InnertubeEndpoint.context(config);
        JSONObject client = requestContext.getJSONObject("context").getJSONObject("client")
                .put("clientScreen", "WATCH")
                .put("originalUrl", "https://www.youtube.com/watch?v=" + videoId);
        return requestContext;
    }

    private static String initialCommentsContinuation(String videoId) throws IOException {
        ByteArrayOutputStream commentsRequest = new ByteArrayOutputStream();

        ByteArrayOutputStream videoMessage = new ByteArrayOutputStream();
        writeProtoString(videoMessage, 2, videoId);
        writeProtoMessage(commentsRequest, 2, videoMessage.toByteArray());
        writeProtoVarintField(commentsRequest, 3, 6);

        ByteArrayOutputStream commentsData = new ByteArrayOutputStream();
        writeProtoString(commentsData, 4, videoId);
        writeProtoVarintField(commentsData, 15, 2);

        ByteArrayOutputStream commentsSection = new ByteArrayOutputStream();
        writeProtoMessage(commentsSection, 4, commentsData.toByteArray());
        writeProtoString(commentsSection, 8, "comments-section");
        writeProtoMessage(commentsRequest, 6, commentsSection.toByteArray());

        String base64 = Base64.encodeToString(commentsRequest.toByteArray(), Base64.NO_WRAP);
        return URLEncoder.encode(base64, "UTF-8");
    }

    private static void writeProtoString(ByteArrayOutputStream output, int field, String value)
            throws IOException {
        writeProtoMessage(output, field, value.getBytes("UTF-8"));
    }

    private static void writeProtoMessage(ByteArrayOutputStream output, int field, byte[] value)
            throws IOException {
        writeProtoVarint(output, ((long) field << 3) | 2);
        writeProtoVarint(output, value.length);
        output.write(value);
    }

    private static void writeProtoVarintField(ByteArrayOutputStream output, int field, long value)
            throws IOException {
        writeProtoVarint(output, (long) field << 3);
        writeProtoVarint(output, value);
    }

    private static void writeProtoVarint(ByteArrayOutputStream output, long value) throws IOException {
        while ((value & ~0x7fL) != 0) {
            output.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        output.write((int) value);
    }

    private static String findCommentsContinuation(JSONObject response) {
        JSONArray endpoints = response.optJSONArray("onResponseReceivedEndpoints");
        if (endpoints != null) {
            for (int i = 0; i < endpoints.length(); i++) {
                JSONObject endpoint = endpoints.optJSONObject(i);
                if (endpoint == null) continue;
                JSONObject command = endpoint.optJSONObject("reloadContinuationItemsCommand");
                if (command == null) command = endpoint.optJSONObject("appendContinuationItemsAction");
                JSONArray items = command == null ? null : command.optJSONArray("continuationItems");
                String token = continuationFromItems(items);
                if (!token.isEmpty()) return token;
            }
        }
        return findCommentContinuation(response, "");
    }

    private static String continuationFromItems(JSONArray items) {
        if (items == null) return "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject renderer = item == null ? null : item.optJSONObject("continuationItemRenderer");
            JSONObject endpoint = renderer == null ? null : renderer.optJSONObject("continuationEndpoint");
            JSONObject command = endpoint == null ? null : endpoint.optJSONObject("continuationCommand");
            if (command != null) {
                String token = command.optString("token", "");
                if (!token.isEmpty()) return token;
            }
        }
        return "";
    }

    /** Time a feed request waits for extra metadata before it is answered. */
    private static final long ENRICH_BUDGET_MS = 2000;
    /**
     * Shared pool for metadata lookups. Lookups that miss the budget keep running here and fill
     * the caches, so the next request for the same videos is answered with full data at once
     * (the same idea as yt2009's video cache).
     */
    private static final ExecutorService ENRICH_POOL = Executors.newFixedThreadPool(10,
            new java.util.concurrent.ThreadFactory() {
                private int count;
                @Override
                public synchronized Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "metadata-" + (++count));
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }
            });

    /**
     * Fills in metadata within {@link #ENRICH_BUDGET_MS}.
     *
     * @return true when every lookup finished in time, so the finished feed may be cached
     */
    static boolean enrichVideos(List<Video> videos, Config config, Context context) {
        if (videos.isEmpty()) return true;

        String apiKey = config.getYouTubeApiKey();
        if (!apiKey.isEmpty()) enrichVideosFromDataApi(videos, config, apiKey);
        boolean fetchFullMetadata = apiKey.isEmpty();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ENRICH_BUDGET_MS);

        // Each task returns the change to apply; changes are applied on this thread only.
        CompletionService<Runnable> completion = new ExecutorCompletionService<Runnable>(ENRICH_POOL);
        List<Future<Runnable>> futures = new ArrayList<Future<Runnable>>();
        int submitted = 0;
        for (Video video : videos) {
            if (!isValidVideoId(video.id)) continue;
            if (fetchFullMetadata || video.description.isEmpty()
                    || "0".equals(numericCount(video.views)) || video.channel.isEmpty()) {
                futures.add(completion.submit(new MetadataTask(video, config, context)));
                submitted++;
            }
            if (fetchFullMetadata) {
                futures.add(completion.submit(new LikesTask(video, context)));
                submitted++;
            }
        }

        int completed = 0;
        try {
            for (; completed < submitted; completed++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<Runnable> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) break;
                try {
                    future.get().run();
                } catch (ExecutionException e) {
                    android.util.Log.w("InnertubeEndpoint", "Could not load video metadata: "
                            + e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Lookups already running finish in the background and fill the caches; queued ones are
        // dropped so they do not hold up the next page or feed.
        for (Future<Runnable> future : futures) future.cancel(false);
        return completed >= submitted;
    }

    private static void enrichVideosFromDataApi(List<Video> videos, Config config, String apiKey) {
        try {
            StringBuilder ids = new StringBuilder();
            for (Video video : videos) {
                if (video.id.isEmpty()) continue;
                if (ids.length() > 0) ids.append(',');
                ids.append(video.id);
            }
            if (ids.length() == 0) return;
            String url = "https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&id="
                    + URLEncoder.encode(ids.toString(), "UTF-8")
                    + "&key=" + URLEncoder.encode(apiKey, "UTF-8");
            JSONObject response = new JSONObject(HttpClient.getUrl(url, DEFAULT_WEB_USER_AGENT,
                    1000, 1500));
            JSONArray items = response.optJSONArray("items");
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                JSONObject snippet = item.optJSONObject("snippet");
                JSONObject statistics = item.optJSONObject("statistics");
                for (Video video : videos) {
                    if (!id.equals(video.id)) continue;
                    if (snippet != null) {
                        video.title = snippet.optString("title", video.title);
                        video.description = snippet.optString("description", video.description);
                        video.channel = snippet.optString("channelTitle", video.channel);
                        video.channelId = snippet.optString("channelId", video.channelId);
                        video.published = snippet.optString("publishedAt", video.published);
                    }
                    if (statistics != null) {
                        video.views = statistics.optString("viewCount", video.views);
                        video.likes = statistics.optString("likeCount", video.likes);
                    }
                    break;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("InnertubeEndpoint", "Could not enrich videos with YouTube Data API", e);
        }
    }

    private static void applyPlayerMetadata(Video video, JSONObject player) {
        JSONObject details = player.optJSONObject("videoDetails");
        if (details == null) return;

        String title = details.optString("title", "");
        if (!title.isEmpty()) video.title = title;
        String description = details.optString("shortDescription", "");
        JSONObject microformat = player.optJSONObject("microformat");
        JSONObject playerMetadata = microformat == null
                ? null : microformat.optJSONObject("playerMicroformatRenderer");
        if (description.isEmpty() && playerMetadata != null) {
            description = text(playerMetadata.optJSONObject("description"));
        }
        if (!description.isEmpty()) video.description = description;

        String views = details.optString("viewCount", "");
        if (!views.isEmpty()) video.views = views;
        String duration = details.optString("lengthSeconds", "");
        if (!duration.isEmpty()) video.duration = duration;
        video.channel = details.optString("author", video.channel);
        video.channelId = details.optString("channelId", video.channelId);
        if (playerMetadata != null && video.category.isEmpty()) {
            video.category = playerMetadata.optString("category", "");
        }
        if (playerMetadata != null && !hasAbsoluteDate(video.published)) {
            String published = playerMetadata.optString("publishDate",
                    playerMetadata.optString("uploadDate", ""));
            if (!published.isEmpty()) video.published = published;
        }
    }

    private static boolean hasAbsoluteDate(String value) {
        return value != null && value.matches("\\d{4}-\\d{2}-\\d{2}.*");
    }

    /** Loads feed metadata in the background; the returned change is applied by the caller. */
    private static final class MetadataTask implements Callable<Runnable> {
        private final Video video;
        private final Config config;
        private final Context context;

        MetadataTask(Video video, Config config, Context context) {
            this.video = video;
            this.config = config;
            this.context = context;
        }

        @Override
        public Runnable call() throws Exception {
            return new ApplyMetadata(video, PlayerLookup.fetchUploadMetadata(video.id, config, context));
        }
    }

    private static final class ApplyMetadata implements Runnable {
        private final Video video;
        private final JSONObject metadata;

        ApplyMetadata(Video video, JSONObject metadata) {
            this.video = video;
            this.metadata = metadata;
        }

        @Override
        public void run() {
            applyPlayerMetadata(video, metadata);
        }
    }

    private static final class LikesTask implements Callable<Runnable> {
        private final Video video;
        private final Context context;

        LikesTask(Video video, Context context) {
            this.video = video;
            this.context = context;
        }

        @Override
        public Runnable call() throws Exception {
            return new ApplyLikes(video, fetchRydLikes(video.id, context));
        }
    }

    private static final class ApplyLikes implements Runnable {
        private final Video video;
        private final String likes;

        ApplyLikes(Video video, String likes) {
            this.video = video;
            this.likes = likes;
        }

        @Override
        public void run() {
            if (!likes.isEmpty()) video.likes = likes;
        }
    }

    private static final java.util.Map<String, String> RYD_MEMORY =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > 512;
                }
            });

    private static String fetchRydLikes(String videoId, Context context) throws Exception {
        String remembered = RYD_MEMORY.get(videoId);
        if (remembered != null) return remembered;
        String likes = fetchRydLikesUncached(videoId, context);
        RYD_MEMORY.put(videoId, likes);
        return likes;
    }

    private static String fetchRydLikesUncached(String videoId, Context context) throws Exception {
        java.io.File cache = HttpClient.cacheFile(context.getCacheDir(), "ryd_likes_v1", videoId);
        if (HttpClient.isFresh(cache, TimeUnit.HOURS.toMillis(24))) {
            JSONObject cached = new JSONObject(HttpClient.readFile(cache));
            return cached.optString("likes", "");
        }
        String url = "https://returnyoutubedislikeapi.com/votes?videoId="
                + URLEncoder.encode(videoId, "UTF-8");
        JSONObject response = new JSONObject(HttpClient.getUrl(url, "ViitubeProxy/1.0",
                1000, 1500));
        if (!response.has("likes") || response.isNull("likes")) return "";
        String likes = response.optString("likes", "");
        HttpClient.writeFile(cache, new JSONObject().put("likes", likes).toString());
        return likes;
    }

    private static boolean isTruncated(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        return trimmed.endsWith("...") || trimmed.endsWith("…");
    }

    private static List<Video> collectVideos(JSONObject root, int limit) {
        List<Video> result = new ArrayList<Video>();
        collectVideoObjects(root, result, limit);
        return result;
    }

    private static void collectVideoObjects(Object node, List<Video> result, int limit) {
        if (result.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String[] keys = {"videoRenderer", "compactVideoRenderer", "gridVideoRenderer",
                    "playlistVideoRenderer", "lockupViewModel"};
            for (String key : keys) {
                JSONObject renderer = object.optJSONObject(key);
                if (renderer != null) {
                    String id = renderer.optString("videoId", renderer.optString("contentId", ""));
                    if (isValidVideoId(id) && !containsVideo(result, id)) result.add(parseVideo(renderer));
                }
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length() && result.size() < limit; i++) {
                    collectVideoObjects(object.opt(names.optString(i)), result, limit);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && result.size() < limit; i++) {
                collectVideoObjects(array.opt(i), result, limit);
            }
        }
    }

    /** Forgets the lists and lookups kept in memory (the Clear cache button). */
    static void clearMemory() {
        synchronized (SEARCHES) {
            SEARCHES.clear();
        }
        synchronized (RELATED) {
            RELATED.clear();
        }
        MUSIC_CHARTS.clear();
        RYD_MEMORY.clear();
    }

    static Video parseVideo(JSONObject renderer) {
        Video video = new Video();
        JSONObject lockup = renderer.optJSONObject("lockupViewModel");
        if (lockup == null && "LOCKUP_CONTENT_TYPE_VIDEO".equals(renderer.optString("contentType", ""))) {
            lockup = renderer;
        }
        if (lockup != null) return parseLockupVideo(lockup);

        video.id = renderer.optString("videoId", renderer.optString("contentId", ""));
        video.title = text(renderer.optJSONObject("title"));
        if (video.title.isEmpty()) video.title = runs(renderer.optJSONObject("title"));
        video.description = runs(renderer.optJSONObject("descriptionSnippet"));
        if (video.description.isEmpty()) video.description = renderer.optString("description", "");
        if (isTruncated(video.description)) video.description = "";
        video.channel = runs(renderer.optJSONObject("ownerText"));
        if (video.channel.isEmpty()) video.channel = runs(renderer.optJSONObject("shortBylineText"));
        video.channelId = findFirst(renderer, "browseId");
        video.views = text(renderer.optJSONObject("viewCountText"));
        if (video.views.isEmpty()) video.views = text(renderer.optJSONObject("shortViewCountText"));
        video.duration = text(renderer.optJSONObject("lengthText"));
        if (video.duration.isEmpty()) video.duration = renderer.optString("lengthSeconds", "");
        video.published = text(renderer.optJSONObject("publishedTimeText"));
        JSONArray videoInfo = renderer.optJSONObject("videoInfo") == null
                ? null : renderer.optJSONObject("videoInfo").optJSONArray("runs");
        if (videoInfo != null) {
            for (int i = 0; i < videoInfo.length(); i++) {
                JSONObject run = videoInfo.optJSONObject(i);
                String part = run == null ? "" : run.optString("text", "").trim();
                String normalized = part.toLowerCase(Locale.US);
                if (video.views.isEmpty() && normalized.contains("view")) video.views = part;
                else if (video.published.isEmpty() && normalized.endsWith(" ago")) video.published = part;
            }
        }
        if (video.views.isEmpty()) video.views = "0";
        video.channelAvatar = channelAvatar(renderer);
        video.thumbnail = thumbnail(renderer);
        video.description = video.description.replace("\n", " ");
        return video;
    }

    /**
     * The channel picture a feed item carries: next to the video renderers as
     * channelThumbnailWithLinkRenderer, and in the newer view models as an avatarViewModel. It
     * is already in the answer, so the feeds need no lookup of their own for it.
     */
    private static String channelAvatar(JSONObject renderer) {
        JSONObject link = firstObject(renderer, "channelThumbnailWithLinkRenderer");
        String url = thumbnailUrl(link != null ? link.optJSONObject("thumbnail")
                : renderer.optJSONObject("channelThumbnail"));
        if (!url.isEmpty()) return url;
        JSONObject avatar = firstObject(renderer, "avatarViewModel");
        JSONObject image = avatar == null ? null : avatar.optJSONObject("image");
        JSONArray sources = image == null ? null : image.optJSONArray("sources");
        if (sources != null && sources.length() > 0) {
            JSONObject last = sources.optJSONObject(sources.length() - 1);
            if (last != null) return absoluteUrl(last.optString("url", ""));
        }
        return "";
    }

    private static String thumbnailUrl(JSONObject container) {
        JSONArray list = container == null ? null : container.optJSONArray("thumbnails");
        if (list == null || list.length() == 0) return "";
        JSONObject last = list.optJSONObject(list.length() - 1);
        return last == null ? "" : absoluteUrl(last.optString("url", ""));
    }

    static boolean isValidVideoId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}");
    }

    static List<Video> completeFeedVideos(List<Video> videos) {
        List<Video> complete = new ArrayList<Video>(videos.size());
        int dropped = 0;
        for (Video video : videos) {
            if (video == null || !isValidVideoId(video.id)
                    || video.title.trim().isEmpty()
                    || video.channel.trim().isEmpty()
                    || video.channelId.trim().isEmpty()
                    || video.channel.trim().equals(video.channelId.trim())) {
                dropped++;
                continue;
            }
            if (video.thumbnail.trim().isEmpty()) {
                video.thumbnail = "https://i.ytimg.com/vi/" + video.id + "/hqdefault.jpg";
            }
            complete.add(video);
        }
        if (dropped > 0) {
            android.util.Log.w("InnertubeEndpoint", "Dropped " + dropped
                    + " incomplete or malformed video feed entr" + (dropped == 1 ? "y" : "ies"));
        }
        return complete;
    }

    private static final java.util.regex.Pattern COMPACT_COUNT =
            java.util.regex.Pattern.compile("[0-9][0-9.,]*\\s*[kmb]?");

    private static Video parseLockupVideo(JSONObject renderer) {
        Video video = new Video();
        video.id = renderer.optString("contentId", "");
        JSONObject metadata = renderer.optJSONObject("metadata");
        JSONObject lockupMetadata = metadata == null
                ? null : metadata.optJSONObject("lockupMetadataViewModel");
        JSONObject titleModel = lockupMetadata == null ? null : lockupMetadata.optJSONObject("title");
        video.title = titleModel == null ? "" : titleModel.optString("content", "");
        JSONObject contentMetadata = lockupMetadata == null
                ? null : lockupMetadata.optJSONObject("metadata");
        JSONObject metadataView = contentMetadata == null
                ? null : contentMetadata.optJSONObject("contentMetadataViewModel");
        JSONArray rows = metadataView == null ? null : metadataView.optJSONArray("metadataRows");
        List<String> metadataParts = new ArrayList<String>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
                if (parts == null) continue;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    JSONObject text = part == null ? null : part.optJSONObject("text");
                    if (text != null) {
                        String value = text.optString("content", "");
                        String linkedChannel = findFirst(text.opt("commandRuns"), "browseId");
                        if (video.channel.isEmpty() && !value.isEmpty()
                                && linkedChannel.startsWith("UC")) {
                            video.channel = value;
                            video.channelId = linkedChannel;
                        } else if (!value.isEmpty()) {
                            metadataParts.add(value);
                        }
                    }
                }
            }
        }
        if (video.channel.isEmpty() && !metadataParts.isEmpty()) {
            // Collaborations list "Channel A and Channel B" without a channel link.
            String first = metadataParts.get(0).toLowerCase(Locale.US);
            if (!first.contains(" view") && !first.contains(" ago")) video.channel = metadataParts.get(0);
        }
        for (String part : metadataParts) {
            String normalized = part.toLowerCase(Locale.US);
            if (normalized.contains(" view") || COMPACT_COUNT.matcher(normalized).matches()) {
                // "1.2M views", or the compact "207M" used for related videos
                video.views = numericCount(part);
            } else if (normalized.contains(" ago")) {
                video.published = part;
            }
        }
        if (video.channelId.isEmpty()) video.channelId = findFirst(renderer, "browseId");
        video.channelAvatar = channelAvatar(renderer);
        video.description = "";
        JSONObject contentImage = renderer.optJSONObject("contentImage");
        JSONObject thumbnailModel = contentImage == null
                ? null : contentImage.optJSONObject("thumbnailViewModel");
        JSONObject image = thumbnailModel == null ? null : thumbnailModel.optJSONObject("image");
        JSONArray sources = image == null ? null : image.optJSONArray("sources");
        if (sources != null && sources.length() > 0) {
            JSONObject last = sources.optJSONObject(sources.length() - 1);
            if (last != null) video.thumbnail = last.optString("url", "");
        }
        JSONArray overlayItems = thumbnailModel == null ? null : thumbnailModel.optJSONArray("overlays");
        if (overlayItems != null && overlayItems.length() > 0) {
            for (int i = 0; i < overlayItems.length(); i++) {
                JSONObject overlay = overlayItems.optJSONObject(i);
                if (overlay == null) continue;
                JSONObject badgeModel = overlay.optJSONObject("thumbnailOverlayBadgeViewModel");
                if (badgeModel == null) {
                    JSONObject bottomOverlay = overlay.optJSONObject("thumbnailBottomOverlayViewModel");
                    JSONArray badges = bottomOverlay == null ? null : bottomOverlay.optJSONArray("badges");
                    badgeModel = badges == null ? null : badges.optJSONObject(0);
                }
                JSONArray badges = badgeModel == null ? null : badgeModel.optJSONArray("thumbnailBadges");
                JSONObject badge = badges == null ? null : badges.optJSONObject(0);
                JSONObject badgeView = badge == null ? null : badge.optJSONObject("thumbnailBadgeViewModel");
                if (badgeView == null && badgeModel != null) {
                    badgeView = badgeModel.optJSONObject("thumbnailBadgeViewModel");
                }
                if (badgeView == null && overlay.optJSONObject("thumbnailBottomOverlayViewModel") != null) {
                    JSONObject bottomOverlay = overlay.optJSONObject("thumbnailBottomOverlayViewModel");
                    JSONArray bottomBadges = bottomOverlay.optJSONArray("badges");
                    JSONObject bottomBadge = bottomBadges == null ? null : bottomBadges.optJSONObject(0);
                    badgeView = bottomBadge == null ? null : bottomBadge.optJSONObject("thumbnailBadgeViewModel");
                }
                if (badgeView != null) {
                    video.duration = badgeView.optString("text", "");
                    break;
                }
            }
        }
        return video;
    }

    private static void collectCommentEntities(Object node, List<JSONObject> result, int limit)
            throws JSONException {
        if (result.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject entity = object.optJSONObject("commentEntityPayload");
            if (entity != null) {
                JSONObject properties = entity.optJSONObject("properties");
                JSONObject author = entity.optJSONObject("author");
                JSONObject toolbar = entity.optJSONObject("toolbar");
                JSONObject content = properties == null ? null : properties.optJSONObject("content");
                String commentId = properties == null ? "" : properties.optString("commentId", "");
                if (!commentId.isEmpty() && !containsComment(result, commentId)) {
                    JSONObject normalized = new JSONObject()
                            .put("commentId", commentId)
                            .put("authorText", new JSONObject().put("simpleText",
                                    author == null ? "" : author.optString("displayName", "")))
                            .put("contentText", new JSONObject().put("simpleText",
                                    content == null ? "" : content.optString("content", "")))
                            .put("publishedAt", properties.optString("publishedTime", ""))
                            .put("author", author == null ? new JSONObject() : author)
                            .put("likeCountA11y", toolbar == null ? "" : toolbar.optString("likeCountA11y", ""));
                    result.add(normalized);
                }
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length() && result.size() < limit; i++) {
                    collectCommentEntities(object.opt(names.optString(i)), result, limit);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && result.size() < limit; i++) {
                collectCommentEntities(array.opt(i), result, limit);
            }
        }
    }

    private static boolean containsComment(List<JSONObject> comments, String id) {
        for (JSONObject comment : comments) {
            if (id.equals(comment.optString("commentId", ""))) return true;
        }
        return false;
    }

    static String buildFeed(List<Video> videos, String title, String feedId, String baseUrl) {
        return buildFeed(videos, title, feedId, baseUrl, 1, "");
    }

    static String buildFeed(List<Video> videos, String title, String feedId, String baseUrl,
                            int startIndex, String nextLink) {
        return buildFeed(videos, title, feedId, baseUrl, startIndex, nextLink, "", -1, false);
    }

    /**
     * @param extraHeader  raw XML inserted after the feed metadata (for example playlist details)
     * @param totalResults total number of results, or -1 to use the number of entries
     * @param positions    whether to add yt:position elements (playlist feeds)
     */
    static String buildFeed(List<Video> videos, String title, String feedId, String baseUrl,
                            int startIndex, String nextLink, String extraHeader,
                            int totalResults, boolean positions) {
        videos = completeFeedVideos(videos);
        StringBuilder xml = feedStart(title, feedId);
        String feedUrl = feedId;
        String basePath = feedUrl.contains("/feeds/api/videos")
            ? feedUrl.substring(0, feedUrl.indexOf("/feeds/api/videos")) + "/feeds/api/videos"
            : feedUrl;
        xml.append("<category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#video'/>")
            .append("<logo>http://www.youtube.com/img/pic_youtubelogo_123x63.gif</logo>")
            .append("<link rel='alternate' type='text/html' href='http://www.youtube.com'/>")
            .append("<link rel='http://schemas.google.com/g/2005#feed' type='application/atom+xml' href='")
                .append(XmlUtil.escape(basePath)).append("'/>")
            .append("<link rel='http://schemas.google.com/g/2005#batch' type='application/atom+xml' href='")
                .append(XmlUtil.escape(basePath + "/batch")).append("'/>")
            .append("<author><name>YouTube/yt2009</name><uri>http://www.youtube.com/</uri></author>")
            .append("<generator version='2.0' uri='").append(XmlUtil.escape(baseUrl))
            .append("'>YouTube data API</generator>");
        xml.append("<openSearch:totalResults>")
                .append(totalResults >= 0 ? totalResults : videos.size()).append("</openSearch:totalResults>")
                .append("<openSearch:startIndex>").append(startIndex).append("</openSearch:startIndex>")
                .append("<openSearch:itemsPerPage>").append(videos.size()).append("</openSearch:itemsPerPage>");
        if (!nextLink.isEmpty()) {
            xml.append("<link rel='next' type='application/atom+xml' href='")
                    .append(XmlUtil.escape(nextLink)).append("'/>");
        }
        xml.append(extraHeader);
        int position = startIndex;
        for (Video video : videos) {
            appendVideoEntry(xml, video, baseUrl, positions ? position++ : 0);
        }
        return xml.append("</feed>").toString();
    }

    static String videoEntry(Video video, String baseUrl) {
        StringBuilder xml = new StringBuilder();
        appendVideoEntry(xml, video, baseUrl, 0);
        return xml.toString();
    }

    private static void appendVideoEntry(StringBuilder xml, Video video, String baseUrl, int position) {
        String videoUrl = baseUrl + "/feeds/api/videos/" + video.id;
        String safeId = XmlUtil.escape(video.id);
        String safeChannelId = XmlUtil.escape(video.channelId);
        String safeTitle = XmlUtil.escape(video.title);
        String safeDescription = XmlUtil.escape(video.description);
        String safeChannel = XmlUtil.escape(video.channel);
        String category = XmlUtil.escape(video.category);
        xml.append("<entry><id>").append(XmlUtil.escape(videoUrl)).append("</id>")
            .append("<youTubeId id='").append(safeId).append("'>").append(safeId).append("</youTubeId>")
            .append("<published>").append(XmlUtil.escape(toAtomDate(video.published))).append("</published>")
            .append("<updated>").append(XmlUtil.escape(toAtomDate(video.published))).append("</updated>")
            .append("<title type='text'>").append(safeTitle).append("</title>")
                .append("<content type='text'>").append(safeDescription).append("</content>")
            .append("<link rel='http://gdata.youtube.com/schemas/2007#video.related' href='")
            .append(XmlUtil.escape(videoUrl)).append("/related'/>")
            .append("<author><name>").append(safeChannel).append("</name><uri>")
            .append(XmlUtil.escape(baseUrl)).append("/feeds/api/users/").append(safeChannelId)
            .append("</uri><yt:userId>").append(safeChannelId).append("</yt:userId></author>")
            .append("<gd:comments><gd:feedLink href='").append(XmlUtil.escape(videoUrl))
            .append("/comments' countHint='0'/></gd:comments><media:group>")
            .append("<media:title>").append(safeTitle).append("</media:title>")
                .append(category.isEmpty() ? "" : "<category scheme='http://gdata.youtube.com/schemas/2007/categories.cat' label='" + category + "' term='" + category + "'/>")
                .append(category.isEmpty() ? "" : "<media:category label='" + category + "' scheme='http://gdata.youtube.com/schemas/2007/categories.cat'>" + category + "</media:category>")
            .append("<media:content url='").append(XmlUtil.escape(baseUrl)).append("/video/")
            .append(safeId).append("' type='video/3gpp' medium='video' expression='full' duration='")
            .append(durationSecondsPublic(video.duration)).append("' yt:format='3'/>")
            .append("<media:description type='plain'>").append(safeDescription).append("</media:description>")
            .append("<media:keywords>").append(safeTitle).append("</media:keywords>")
            .append("<media:player url='http://www.youtube.com/watch?v=").append(safeId).append("'/>")
                .append("<media:thumbnail yt:name='hqdefault' url='").append(XmlUtil.escape(video.thumbnail))
                .append("' height='240' width='320' time='00:00:00'/>")
            .append("<media:thumbnail yt:name='poster' url='http://i.ytimg.com/vi/").append(safeId)
            .append("/0.jpg' height='240' width='320' time='00:00:00'/>")
            .append("<media:thumbnail yt:name='default' url='http://i.ytimg.com/vi/").append(safeId)
            .append("/0.jpg' height='240' width='320' time='00:00:00'/>")
            .append("<yt:duration seconds='").append(durationSecondsPublic(video.duration)).append("'/>")
            .append("<yt:uploaded>").append(XmlUtil.escape(toAtomDate(video.published))).append("</yt:uploaded>")
            .append("<yt:uploaderId>").append(safeChannelId).append("</yt:uploaderId>")
            .append("<yt:videoid id='").append(safeId).append("'>").append(safeId).append("</yt:videoid>")
            .append("<youTubeId id='").append(safeId).append("'>").append(safeId).append("</youTubeId>")
            .append("<media:credit role='uploader' yt:display='").append(safeChannel)
            .append("' name='").append(safeChannelId).append("'>").append(safeChannelId)
            .append("</media:credit></media:group><gd:rating average='5' max='5' min='1' numRaters='0' rel='http://schemas.google.com/g/2005#overall'/>")
            .append("<yt:statistics favoriteCount='0' viewCount='").append(numericCount(video.views)).append("'/>")
            .append("<yt:rating numLikes='").append(numericCount(video.likes))
            .append("' numDislikes='0'/>");
        if (position > 0) xml.append("<yt:position>").append(position).append("</yt:position>");
        xml.append("</entry>");
    }

    private static StringBuilder feedStart(String title, String id) {
        return new StringBuilder("<?xml version='1.0' encoding='UTF-8'?><feed xmlns='http://www.w3.org/2005/Atom'")
            .append(" xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'")
            .append(" xmlns:media='http://search.yahoo.com/mrss/' xmlns:gd='http://schemas.google.com/g/2005'")
            .append(" xmlns:yt='http://gdata.youtube.com/schemas/2007'>")
            .append("<id>").append(XmlUtil.escape(id)).append("</id><title type='text'>")
            .append(XmlUtil.escape(title)).append("</title>");
    }

        static String numericCount(String value) {
            if (value == null) return "0";
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("([0-9]+(?:[.,][0-9]+)?)\\s*([kmb]?)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(value.replace(",", ""));
            if (!matcher.find()) return "0";
            try {
                double count = Double.parseDouble(matcher.group(1));
                String suffix = matcher.group(2).toLowerCase(Locale.US);
                if ("k".equals(suffix)) count *= 1_000d;
                else if ("m".equals(suffix)) count *= 1_000_000d;
                else if ("b".equals(suffix)) count *= 1_000_000_000d;
                return Long.toString(Math.round(count));
            } catch (NumberFormatException ignored) {
                return "0";
            }
        }

        private static long viewCount(String value) {
            try {
                return Long.parseLong(numericCount(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        private static String requestHost(NanoHTTPD.IHTTPSession session) {
            String host = session.getHeaders().get("host");
            return host == null || host.isEmpty() ? "127.0.0.1" : host;
        }

        private static void collectObjects(Object node, String key, List<JSONObject> result, int limit) {
        if (result.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject found = object.optJSONObject(key);
            if (found != null) result.add(found);
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length() && result.size() < limit; i++)
                collectObjects(object.opt(names.optString(i)), key, result, limit);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && result.size() < limit; i++) collectObjects(array.opt(i), key, result, limit);
        }
    }

    private static boolean containsVideo(List<Video> videos, String id) {
        for (Video video : videos) if (id.equals(video.id)) return true;
        return false;
    }

    private static String text(JSONObject value) {
        return runs(value);
    }

    private static String runs(JSONObject value) {
        if (value == null) return "";
        JSONArray runs = value.optJSONArray("runs");
        if (runs == null) return value.optString("simpleText", "");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) text.append(runs.optJSONObject(i) == null ? "" : runs.optJSONObject(i).optString("text", ""));
        return text.toString();
    }

    private static String thumbnail(JSONObject renderer) {
        JSONObject thumbnail = renderer.optJSONObject("thumbnail");
        JSONArray thumbs = thumbnail == null ? null : thumbnail.optJSONArray("thumbnails");
        if (thumbs == null || thumbs.length() == 0) return "";
        JSONObject last = thumbs.optJSONObject(thumbs.length() - 1);
        return last == null ? "" : last.optString("url", "");
    }

    private static String findFirst(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has(key)) return object.optString(key, "");
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String found = findFirst(object.opt(names.optString(i)), key);
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findFirst(array.opt(i), key);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    static int durationSecondsPublic(String duration) {
        if (duration == null || duration.isEmpty()) return 0;
        try { return Integer.parseInt(duration); } catch (NumberFormatException ignored) { }
        int total = 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(duration);
        if (duration.startsWith("PT") && matcher.matches()) {
            try { total += Integer.parseInt(matcher.group(1)) * 3600; } catch (Exception ignored) { }
            try { total += Integer.parseInt(matcher.group(2)) * 60; } catch (Exception ignored) { }
            try { total += Integer.parseInt(matcher.group(3)); } catch (Exception ignored) { }
            if (total > 0) return total;
        }
        String[] parts = duration.split(":");
        try {
            if (parts.length == 3) return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
            if (parts.length == 2) return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) { }
        return 0;
    }

    private static NanoHTTPD.Response atom(String body) {
        return xml(body, "application/atom+xml; charset=utf-8");
    }

    private static NanoHTTPD.Response xml(String body, String mimeType) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mimeType, body);
    }

    private static NanoHTTPD.Response text(NanoHTTPD.Response.IStatus status, String body) {
        return NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", body);
    }

    private static int parseLimit(String value) {
        try {
            int limit = Integer.parseInt(value);
            return limit >= 1 && limit <= 50 ? limit : MAX_RESULTS;
        } catch (Exception ignored) {
            return MAX_RESULTS;
        }
    }

    private static String findCommentContinuation(Object node, String parentKey) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String panel = object.optString("panelIdentifier", "").toLowerCase(Locale.US);
            boolean commentSection = (parentKey != null && parentKey.toLowerCase(Locale.US).contains("comment"))
                    || object.has("commentSectionRenderer")
                    || object.has("commentThreadRenderer")
                    || panel.contains("comment");
            if (commentSection) {
                String token = object.optString("continuation", "");
                JSONObject endpoint = object.optJSONObject("continuationEndpoint");
                JSONObject command = endpoint == null ? null : endpoint.optJSONObject("continuationCommand");
                if (token.isEmpty() && command != null) token = command.optString("token", "");
                JSONObject button = object.optJSONObject("button");
                JSONObject buttonRenderer = button == null ? null : button.optJSONObject("buttonRenderer");
                JSONObject commandWrapper = buttonRenderer == null ? null : buttonRenderer.optJSONObject("command");
                JSONObject buttonContinuation = commandWrapper == null ? null : commandWrapper.optJSONObject("continuationCommand");
                if (token.isEmpty() && buttonContinuation != null) token = buttonContinuation.optString("token", "");
                if (token.isEmpty()) token = findFirst(object, "token");
                if (!token.isEmpty()) return token;
            }
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                String found = findCommentContinuation(object.opt(key), key);
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findCommentContinuation(array.opt(i), parentKey);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static final java.util.regex.Pattern ISO_DATE = java.util.regex.Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?)?"
                    + "\\s*(Z|[+-]\\d{2}:?\\d{2})?");

    /**
     * Returns a date in the exact form the legacy apps parse, like yt2009's toISOString():
     * "2024-05-01T15:00:00.000Z" (UTC, milliseconds, 'Z'). YouTube's own dates such as
     * "2024-05-01T08:00:00-07:00" or "2024-05-01T15:00:00Z" are converted; relative texts
     * ("3 years ago") are approximated.
     */
    static String toAtomDate(String source) {
        java.util.regex.Matcher iso = ISO_DATE.matcher(source == null ? "" : source.trim());
        if (iso.matches()) {
            java.util.Calendar calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            calendar.clear();
            calendar.set(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)) - 1,
                    Integer.parseInt(iso.group(3)),
                    iso.group(4) == null ? 0 : Integer.parseInt(iso.group(4)),
                    iso.group(5) == null ? 0 : Integer.parseInt(iso.group(5)),
                    iso.group(6) == null ? 0 : Integer.parseInt(iso.group(6)));
            String fraction = iso.group(7);
            if (fraction != null) {
                calendar.set(java.util.Calendar.MILLISECOND,
                        Integer.parseInt((fraction + "00").substring(0, 3)));
            }
            long time = calendar.getTimeInMillis();
            String zone = iso.group(8);
            if (zone != null && !"Z".equals(zone)) {
                String digits = zone.substring(1).replace(":", "");
                long offset = (Integer.parseInt(digits.substring(0, 2)) * 60L
                        + Integer.parseInt(digits.substring(2, 4))) * 60_000L;
                time -= zone.charAt(0) == '-' ? -offset : offset;
            }
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format(new java.util.Date(time));
        }
        long age = 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(\\d+)\\s*(seconds?|minutes?|hours?|days?|weeks?|months?|years?|s|m|h|d|w|mo|y)\\s+ago",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(source == null ? "" : source);
        if (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.US);
            if (unit.startsWith("second") || "s".equals(unit)) age = amount * 1000L;
            else if (unit.startsWith("minute") || "m".equals(unit)) age = amount * 60_000L;
            else if (unit.startsWith("hour") || "h".equals(unit)) age = amount * 3_600_000L;
            else if (unit.startsWith("day") || "d".equals(unit)) age = amount * 86_400_000L;
            else if (unit.startsWith("week") || "w".equals(unit)) age = amount * 604_800_000L;
            else if (unit.startsWith("month") || "mo".equals(unit)) age = amount * 2_592_000_000L;
            else if (unit.startsWith("year") || "y".equals(unit)) age = amount * 31_536_000_000L;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date(System.currentTimeMillis() - age));
    }

    static class Video {
        String id = "";
        String title = "";
        String description = "";
        String channel = "";
        String channelId = "";
        String views = "0";
        String likes = "0";
        String duration = "";
        String published = "";
        String thumbnail = "";
        String category = "";
        /** Picture of the channel, as the feed answer carried it. */
        String channelAvatar = "";

        Video copy() {
            Video copy = new Video();
            copy.id = id;
            copy.title = title;
            copy.description = description;
            copy.channel = channel;
            copy.channelId = channelId;
            copy.views = views;
            copy.channelAvatar = channelAvatar;
            copy.likes = likes;
            copy.duration = duration;
            copy.published = published;
            copy.thumbnail = thumbnail;
            copy.category = category;
            return copy;
        }
    }
}
