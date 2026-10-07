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
            int limit = parseLimit(session.getParms().get("limit"));
                String requestHost = requestHost(session);
                java.io.File cache = HttpClient.cacheFile(context.getCacheDir(), "search",
                    "v6_" + requestHost + "_" + normalizedQuery + "_" + limit);
            if (HttpClient.isFresh(cache, 30L * 60 * 1000)) return atom(HttpClient.readFile(cache));
            String feed = searchFeed(normalizedQuery, limit, session, config, context);
            HttpClient.writeFile(cache, feed);
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
        JSONObject payload = context(config).put("browseId", "FEtrending");
        payload.getJSONObject("context").getJSONObject("client").put("gl", region);
        JSONObject response = post("browse", payload, config);
        List<Video> videos = collectVideos(response, 50);
        java.util.Collections.sort(videos, (left, right) -> {
            int countOrder = Long.compare(viewCount(right.views), viewCount(left.views));
            return countOrder != 0 ? countOrder : left.id.compareTo(right.id);
        });
        if (videos.size() > MAX_RESULTS) {
            videos = new ArrayList<Video>(videos.subList(0, MAX_RESULTS));
        }
        enrichVideos(videos, config, context);
        return buildFeed(videos, "YouTube Most Popular Videos in " + region.toUpperCase(Locale.US),
            baseUrl + "/feeds/api/standardfeeds/" + region.toUpperCase(Locale.US) + "/most_popular", baseUrl);
    }

    private String searchFeed(String query, int limit, NanoHTTPD.IHTTPSession session,
                              Config config, Context context) throws Exception {
        JSONObject payload = InnertubeEndpoint.context(config)
                .put("query", query);
        JSONObject response = post("search", payload, config);
        List<Video> videos = collectVideos(response, limit);
        enrichVideos(videos, config, context);
        String baseUrl = "http://" + requestHost(session);
        return buildFeed(videos, "Search results: " + query,
            baseUrl + "/feeds/api/videos?q=" + URLEncoder.encode(query, "UTF-8"), baseUrl);
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

    private String commentsFeed(String videoId, NanoHTTPD.IHTTPSession session,
                                Config config, Context context) throws Exception {
        List<JSONObject> comments = new ArrayList<JSONObject>();
        JSONObject requestContext = commentRequestContext(config, videoId);
        String continuation = initialCommentsContinuation(videoId);
        JSONObject response = post("next", new JSONObject()
                .put("context", requestContext.getJSONObject("context"))
                .put("continuation", continuation), config);

        collectCommentEntities(response, comments, 20);
        if (comments.isEmpty()) collectObjects(response, "commentThreadRenderer", comments, 20);
        if (comments.isEmpty()) collectObjects(response, "commentViewModel", comments, 20);
        continuation = findCommentsContinuation(response);

        int continuationRequests = 0;
        while (!continuation.isEmpty() && comments.size() < 20 && continuationRequests < 2) {
            response = post("next", new JSONObject()
                    .put("context", requestContext.getJSONObject("context"))
                    .put("continuation", continuation), config);
            int previousCount = comments.size();
            collectCommentEntities(response, comments, 20);
            if (comments.isEmpty()) collectObjects(response, "commentThreadRenderer", comments, 20);
            if (comments.isEmpty()) collectObjects(response, "commentViewModel", comments, 20);
            String nextContinuation = findCommentsContinuation(response);
            if (comments.size() == previousCount || continuation.equals(nextContinuation)) break;
            continuation = nextContinuation;
            continuationRequests++;
        }
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
            JSONObject renderer = thread.optJSONObject("comment");
            if (renderer == null && thread.has("commentId")) renderer = thread;
            if (renderer == null) renderer = thread.optJSONObject("commentViewModel");
            if (renderer == null) continue;
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
            if (publishedAt.isEmpty()) publishedAt = toAtomDate(runs(publishedText));
            if (id.isEmpty()) id = authorId;
            String shownAuthor = author.startsWith("@") ? author : "@" + author;
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

        if (userAgent == null || userAgent.trim().isEmpty()) {
            userAgent = configuredUserAgent(config);
        }
        if ("Mozilla/5.0".equals(userAgent.trim())
                || userAgent.contains("Chrome/131.0.0.0")) {
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

    static void enrichVideos(List<Video> videos, Config config, Context context) {
        if (videos.isEmpty()) return;

        String apiKey = config.getYouTubeApiKey();
        if (!apiKey.isEmpty()) enrichVideosFromDataApi(videos, config, apiKey);
        enrichVideosFromInnertube(videos, config, context, apiKey.isEmpty());
        if (apiKey.isEmpty()) enrichVideoLikesFromRyd(videos, context);
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

    private static void enrichVideosFromInnertube(List<Video> videos, Config config,
                                                  Context context, boolean fetchFullMetadata) {
        List<Video> missing = new ArrayList<Video>();
        for (Video video : videos) {
            if (fetchFullMetadata || video.description.isEmpty()
                    || "0".equals(numericCount(video.views)) || video.channel.isEmpty()) {
                missing.add(video);
            }
        }
        if (missing.isEmpty()) return;

        int workerCount = Math.min(8, missing.size());
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CompletionService<PlayerMetadataResult> completion =
                new ExecutorCompletionService<PlayerMetadataResult>(executor);
        List<Future<PlayerMetadataResult>> futures = new ArrayList<Future<PlayerMetadataResult>>();
        for (final Video video : missing) {
            futures.add(completion.submit(new Callable<PlayerMetadataResult>() {
                @Override
                public PlayerMetadataResult call() throws Exception {
                    return new PlayerMetadataResult(video,
                            PlayerLookup.fetchUploadMetadata(video.id, config, context));
                }
            }));
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int completed = 0;
        try {
            while (completed < futures.size()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<PlayerMetadataResult> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) break;
                completed++;
                try {
                    applyPlayerMetadata(future.get());
                } catch (ExecutionException e) {
                    android.util.Log.w("InnertubeEndpoint",
                            "Could not load Innertube video metadata", e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            android.util.Log.w("InnertubeEndpoint", "Interrupted while loading upload metadata", e);
        } finally {
            for (Future<PlayerMetadataResult> future : futures) {
                if (!future.isDone()) future.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    private static void applyPlayerMetadata(PlayerMetadataResult result) {
        Video video = result.video;
        JSONObject player = result.response;
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
    }

    private static void enrichVideoLikesFromRyd(List<Video> videos, Context context) {
        int workerCount = Math.min(8, videos.size());
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CompletionService<RydResult> completion = new ExecutorCompletionService<RydResult>(executor);
        List<Future<RydResult>> futures = new ArrayList<Future<RydResult>>();
        for (final Video video : videos) {
            if (video.id.isEmpty()) continue;
            futures.add(completion.submit(new Callable<RydResult>() {
                @Override
                public RydResult call() throws Exception {
                    return new RydResult(video, fetchRydLikes(video.id, context));
                }
            }));
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        int completed = 0;
        try {
            while (completed < futures.size()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<RydResult> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) break;
                completed++;
                try {
                    RydResult result = future.get();
                    if (!result.likes.isEmpty()) result.video.likes = result.likes;
                } catch (ExecutionException e) {
                    android.util.Log.w("InnertubeEndpoint",
                            "Could not load like count from Return YouTube Dislike", e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            android.util.Log.w("InnertubeEndpoint",
                    "Interrupted while loading like counts", e);
        } finally {
            for (Future<RydResult> future : futures) {
                if (!future.isDone()) future.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    private static String fetchRydLikes(String videoId, Context context) throws Exception {
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

    private static final class RydResult {
        final Video video;
        final String likes;

        RydResult(Video video, String likes) {
            this.video = video;
            this.likes = likes;
        }
    }

    private static final class PlayerMetadataResult {
        final Video video;
        final JSONObject response;

        PlayerMetadataResult(Video video, JSONObject response) {
            this.video = video;
            this.response = response;
        }
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
        video.published = text(renderer.optJSONObject("publishedTimeText"));
        video.thumbnail = thumbnail(renderer);
        video.description = video.description.replace("\n", " ");
        return video;
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
                        if (!value.isEmpty()) metadataParts.add(value);
                    }
                }
            }
        }
        for (String part : metadataParts) {
            String normalized = part.toLowerCase(Locale.US);
            if (normalized.contains(" view")) {
                video.views = numericCount(part);
            } else if (normalized.contains(" ago")) {
                video.published = part;
            }
        }
        video.channelId = findFirst(renderer, "browseId");
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
        xml.append("<openSearch:totalResults>").append(videos.size()).append("</openSearch:totalResults>")
                .append("<openSearch:startIndex>").append(startIndex).append("</openSearch:startIndex>")
                .append("<openSearch:itemsPerPage>").append(videos.size()).append("</openSearch:itemsPerPage>");
        if (!nextLink.isEmpty()) {
            xml.append("<link rel='next' type='application/atom+xml' href='")
                    .append(XmlUtil.escape(nextLink)).append("'/>");
        }
        for (Video video : videos) {
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
                .append("' numDislikes='0'/></entry>");
        }
        return xml.append("</feed>").toString();
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

    static String toAtomDate(String source) {
        if (source != null && source.matches("\\d{4}-\\d{2}-\\d{2}")) return source + "T00:00:00.000Z";
        if (source != null && source.matches("\\d{4}-\\d{2}-\\d{2}T.*")) return source;
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
    }
}
