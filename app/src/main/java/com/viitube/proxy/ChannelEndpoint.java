package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ChannelEndpoint implements Endpoint {

    private static final String UPLOADS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA";
    private static final String PLAYLISTS_TAB_PARAMS = "EglwbGF5bGlzdHPyBgQKAkIA";
    private final String mode;

    public ChannelEndpoint(String mode) {
        this.mode = mode;
    }

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                    Config config, Context context) throws Exception {
        String baseUrl = "http://" + host(session);
        if ("events".equals(mode)) {
            // /feeds/api/events?author=NAME (yt2009: apkUserEvents)
            String author = session.getParms().get("author");
            if (author == null || author.trim().isEmpty()) return response(activityFeed(
                    new ArrayList<InnertubeEndpoint.Video>(), "", "", baseUrl));
            return cachedActivity(author.trim(), baseUrl, config, context);
        }

        String channelId = params.length > 0 ? params[0] : "";
        if (channelId.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain", "missing channel id");
        }

        if ("uploads".equals(mode)) {
            int startIndex = parsePositiveInt(session.getParms().get("start-index"), 1, 1000);
            int maxResults = parsePositiveInt(session.getParms().get("max-results"), 25, 50);
            String cacheKey = channelId + "_" + startIndex + "_" + maxResults + "_" + host(session);
            java.io.File cache = HttpClient.cacheFile(context.getCacheDir(),
                    "uploads_metadata_v6", cacheKey);
            if (HttpClient.isFresh(cache, UPLOADS_MAX_AGE_MS)) {
                return response(HttpClient.readFile(cache));
            }
            try {
                boolean[] complete = {false};
                String feed = uploadsFeed(resolveChannelId(channelId, config), baseUrl,
                        startIndex, maxResults, config, context, complete);
                HttpClient.writeFeedCache(cache, feed, complete[0], UPLOADS_MAX_AGE_MS);
                return response(feed);
            } catch (Exception e) {
                if (cache.exists()) {
                    android.util.Log.w("ChannelEndpoint",
                            "Uploads refresh failed; serving cached feed for " + channelId, e);
                    return response(HttpClient.readFile(cache));
                }
                throw e;
            }
        }

        if ("activity".equals(mode)) {
            return cachedActivity(channelId, baseUrl, config, context);
        }

        if ("playlists".equals(mode)) {
            int startIndex = parsePositiveInt(session.getParms().get("start-index"), 1, 1000);
            int maxResults = parsePositiveInt(session.getParms().get("max-results"), 25, 50);
            java.io.File cache = HttpClient.cacheFile(context.getCacheDir(), "channel_playlists_v1",
                    channelId + "_" + startIndex + "_" + maxResults + "_" + host(session));
            if (HttpClient.isFresh(cache, 30L * 60 * 1000)) return response(HttpClient.readFile(cache));
            String resolvedId = resolveChannelId(channelId, config);
            String feed = playlistsFeed(resolvedId, baseUrl, startIndex, maxResults, config);
            HttpClient.writeFile(cache, feed);
            return response(feed);
        }

        if ("channel".equals(mode)) {
            // Profiles are requested often (search results, video pages) and change slowly;
            // the browse response behind them is large, so the finished entry is cached.
            java.io.File cache = HttpClient.cacheFile(context.getCacheDir(), "channel_entry_v2",
                    channelId + "_" + host(session));
            if (HttpClient.isFresh(cache, 60L * 60 * 1000)) return response(HttpClient.readFile(cache));
            JSONObject browse = fetchBrowse(resolveChannelId(channelId, config), mode, config);
            String entry = channelEntry(browse, channelId, baseUrl);
            HttpClient.writeFile(cache, entry);
            return response(entry);
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "not found");
    }

    private static boolean isChannelId(String id) {
        return id != null && id.matches("UC[A-Za-z0-9_-]{22}");
    }

    /** Resolves a legacy username or @handle to a UC channel id; returns the input when unknown. */
    static String resolveChannelId(String user, Config config) {
        if (user == null || isChannelId(user)) return user;
        String handle = user.startsWith("@") ? user : "@" + user;
        try {
            JSONObject payload = InnertubeEndpoint.context(config)
                    .put("url", "https://www.youtube.com/" + java.net.URLEncoder.encode(handle, "UTF-8"));
            JSONObject resolved = InnertubeEndpoint.request("navigation/resolve_url", payload, config);
            String browseId = findFirst(resolved, "browseId");
            if (isChannelId(browseId)) return browseId;
        } catch (Exception e) {
            android.util.Log.w("ChannelEndpoint", "Could not resolve channel " + user, e);
        }
        return user;
    }

    /** Forgets the channel profiles kept in memory (the Clear cache button). */
    static void clearMemory() {
        synchronized (CHANNEL_INFO) {
            CHANNEL_INFO.clear();
        }
    }

    static final class Uploads {
        final List<InnertubeEndpoint.Video> videos = new ArrayList<InnertubeEndpoint.Video>();
        String channelName = "";
        boolean hasMore;
    }

    /**
     * Loads the newest {@code limit} uploads. The channel's "UU" uploads playlist lists every
     * public upload - regular videos, Shorts and past live streams - newest first, so it is
     * preferred over the Videos tab, which leaves Shorts out.
     */
    static Uploads loadUploads(String channelId, int limit, Config config) throws Exception {
        Uploads uploads = new Uploads();
        if (isChannelId(channelId)) {
            try {
                PlaylistEndpoint.Playlist playlist = PlaylistEndpoint.fetchPlaylist(
                        "UU" + channelId.substring(2), limit, config);
                for (InnertubeEndpoint.Video video : playlist.videos) {
                    if (!video.id.isEmpty() && !containsVideo(uploads.videos, video.id)) {
                        uploads.videos.add(video);
                    }
                }
                uploads.channelName = playlist.author;
                uploads.hasMore = playlist.hasMore;
            } catch (Exception e) {
                android.util.Log.w("ChannelEndpoint",
                        "Uploads playlist failed for " + channelId + "; using the Videos tab", e);
            }
        }
        if (uploads.videos.isEmpty()) loadUploadsTab(channelId, limit, config, uploads);
        for (InnertubeEndpoint.Video video : uploads.videos) {
            if (!uploads.channelName.isEmpty()) video.channel = uploads.channelName;
            if (isChannelId(channelId)) video.channelId = channelId;
        }
        return uploads;
    }

    private static void loadUploadsTab(String channelId, int limit, Config config, Uploads uploads)
            throws Exception {
        String continuation = InnertubeEndpoint.browseContinuation(channelId, UPLOADS_TAB_PARAMS);
        JSONObject currentPage = InnertubeEndpoint.requestFast("browse",
                InnertubeEndpoint.context(config).put("continuation", continuation), config);
        continuation = "";
        for (int page = 0; page < 40 && uploads.videos.size() < limit; page++) {
            JSONObject metadata = currentPage.optJSONObject("metadata");
            JSONObject channel = metadata == null ? null : metadata.optJSONObject("channelMetadataRenderer");
            if (channel != null) {
                String title = channel.optString("title", "");
                if (!title.isEmpty()) uploads.channelName = title;
            }

            Object items = uploadItems(currentPage);
            List<JSONObject> renderers = collectVideoRenderers(items, limit);
            for (JSONObject renderer : renderers) {
                InnertubeEndpoint.Video video = InnertubeEndpoint.parseVideo(renderer);
                if (video.id.isEmpty() || containsVideo(uploads.videos, video.id)) continue;
                uploads.videos.add(video);
            }

            continuation = findUploadContinuation(items);
            if (continuation.isEmpty() || uploads.videos.size() >= limit) break;
            JSONObject payload = InnertubeEndpoint.context(config).put("continuation", continuation);
            currentPage = InnertubeEndpoint.requestFast("browse", payload, config);
        }
        uploads.hasMore = !continuation.isEmpty();
    }

    private static JSONObject fetchBrowse(String channelId, String mode, Config config) throws Exception {
        JSONObject payload = InnertubeEndpoint.context(config).put("browseId", channelId);
        // "EglwbGF5bGlzdHM=" no longer selects the tab (YouTube answers with Home).
        if ("playlists".equals(mode)) payload.put("params", PLAYLISTS_TAB_PARAMS);
        return InnertubeEndpoint.request("browse", payload, config);
    }

    private static final long UPLOADS_MAX_AGE_MS = 10L * 60 * 1000;

    private static NanoHTTPD.Response cachedActivity(String author, String baseUrl, Config config,
                                                     Context context) throws Exception {
        java.io.File cache = HttpClient.cacheFile(context.getCacheDir(), "activity_v1",
                author + "_" + baseUrl);
        if (HttpClient.isFresh(cache, UPLOADS_MAX_AGE_MS)) return response(HttpClient.readFile(cache));
        boolean[] complete = {false};
        String feed = activity(author, resolveChannelId(author, config), baseUrl, config, context,
                complete);
        HttpClient.writeFeedCache(cache, feed, complete[0], UPLOADS_MAX_AGE_MS);
        return response(feed);
    }

    private static String activity(String author, String channelId, String baseUrl,
                                   Config config, Context context, boolean[] complete)
            throws Exception {
        Uploads uploads = loadUploads(channelId, 7, config);
        List<InnertubeEndpoint.Video> videos = uploads.videos.size() > 7
                ? new ArrayList<InnertubeEndpoint.Video>(uploads.videos.subList(0, 7)) : uploads.videos;
        complete[0] = InnertubeEndpoint.enrichVideos(videos, config, context);
        videos = InnertubeEndpoint.completeFeedVideos(videos);
        // The apps pass the channel id as "author"; show the channel's name instead.
        String name = uploads.channelName;
        if (name.isEmpty() && !videos.isEmpty()) name = videos.get(0).channel;
        if (name.isEmpty()) name = author;
        return activityFeed(videos, name, channelId, baseUrl);
    }

    /** Legacy user event feed, modelled on yt2009's gdata_activityEntry ("video_uploaded"). */
    private static String activityFeed(List<InnertubeEndpoint.Video> videos, String author,
                                       String channelId, String baseUrl) {
        String safeBase = XmlUtil.escape(baseUrl);
        String safeAuthor = XmlUtil.escape(author);
        String feedId = safeBase + "/feeds/api/events?author="
                + XmlUtil.escape(channelId.isEmpty() ? author : channelId);
        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>")
                .append("<feed xmlns='http://www.w3.org/2005/Atom'")
                .append(" xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'")
                .append(" xmlns:gd='http://schemas.google.com/g/2005'")
                .append(" xmlns:yt='http://gdata.youtube.com/schemas/2007'")
                .append(" xmlns:media='http://search.yahoo.com/mrss/'>")
                .append("<id>").append(feedId).append("</id>")
                .append("<updated>").append(InnertubeEndpoint.utcNow()).append("</updated>")
                .append("<category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#userEvent'/>")
                .append("<title type='text'>Activity of ").append(safeAuthor).append("</title>")
                .append("<logo>http://www.youtube.com/img/pic_youtubelogo_123x63.gif</logo>")
                .append("<author><name>YouTube</name><uri>http://www.youtube.com/</uri></author>")
                .append("<openSearch:totalResults>").append(videos.size()).append("</openSearch:totalResults>")
                .append("<openSearch:startIndex>1</openSearch:startIndex>")
                .append("<openSearch:itemsPerPage>").append(videos.size()).append("</openSearch:itemsPerPage>");
        for (InnertubeEndpoint.Video video : videos) {
            String id = XmlUtil.escape(video.id);
            String timestamp = XmlUtil.escape(InnertubeEndpoint.toAtomDate(video.published));
            String name = video.channel.isEmpty() ? author : video.channel;
            String userId = channelId.isEmpty() ? video.channelId : channelId;
            xml.append("<entry><id>tag:youtube.com,2008:video:").append(id).append("</id>")
                    .append("<updated>").append(timestamp).append("</updated>")
                    .append("<category scheme='http://schemas.google.com/g/2005#kind' term='")
                    .append("http://gdata.youtube.com/schemas/2007#userEvent'/>")
                    .append("<category scheme='http://gdata.youtube.com/schemas/2007/userevents.cat' term='video_uploaded'/>")
                    .append("<title>").append(XmlUtil.escape(video.title)).append("</title>")
                    .append("<yt:videoid>").append(id).append("</yt:videoid>")
                    .append("<yt:username>").append(XmlUtil.escape(name)).append("</yt:username>")
                    .append("<yt:groupId>0</yt:groupId>")
                    .append("<author><name>").append(XmlUtil.escape(name)).append("</name><uri>")
                    .append(safeBase).append("/feeds/api/users/").append(XmlUtil.escape(userId))
                    .append("</uri><yt:userId>").append(XmlUtil.escape(userId)).append("</yt:userId></author>")
                    .append("<link rel='http://gdata.youtube.com/schemas/2007#video' href='")
                    .append(safeBase).append("/feeds/api/videos/").append(id).append("'>")
                    .append(InnertubeEndpoint.videoEntry(video, baseUrl))
                    .append("</link></entry>");
        }
        return xml.append("</feed>").toString();
    }

    /** Channel page details shared by the GData profile entry and the protobuf channel page. */
    static final class ChannelInfo {
        String id = "";
        String name = "";
        String description = "";
        String avatar = "";
        String banner = "";
        String subscribers = "";
        String uploads = "";
    }

    /** Channel details from the channel's browse page (cached for an hour, like the profile). */
    static ChannelInfo fetchChannelInfo(String channelId, Config config) throws Exception {
        synchronized (CHANNEL_INFO) {
            Object[] cached = CHANNEL_INFO.get(channelId);
            if (cached != null && System.currentTimeMillis() - (Long) cached[1] < 60L * 60 * 1000) {
                return (ChannelInfo) cached[0];
            }
        }
        ChannelInfo info = readChannelInfo(fetchBrowse(channelId, "channel", config), channelId);
        synchronized (CHANNEL_INFO) {
            CHANNEL_INFO.put(channelId, new Object[]{info, System.currentTimeMillis()});
        }
        return info;
    }

    private static final java.util.Map<String, Object[]> CHANNEL_INFO =
            new java.util.LinkedHashMap<String, Object[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Object[]> eldest) {
                    return size() > 32;
                }
            };

    static ChannelInfo readChannelInfo(JSONObject root, String requestedId) {
        JSONObject metadata = findObject(root, "channelMetadataRenderer");
        JSONObject header = findObject(root, "pageHeaderRenderer");
        if (header == null) header = findObject(root, "c4TabbedHeaderRenderer");
        JSONObject headerContent = header == null ? null : header.optJSONObject("content");
        JSONObject headerViewModel = headerContent == null
                ? null : headerContent.optJSONObject("pageHeaderViewModel");
        String id = metadata == null ? requestedId : metadata.optString("externalId", requestedId);
        String name = metadata == null ? "" : metadata.optString("title", "");
        if (name.isEmpty()) name = text(header == null ? null : header.optJSONObject("title"));
        if (name.isEmpty() && headerViewModel != null) {
            JSONObject title = headerViewModel.optJSONObject("title");
            JSONObject dynamicTitle = title == null ? null : title.optJSONObject("dynamicTextViewModel");
            name = text(dynamicTitle == null ? null : dynamicTitle.optJSONObject("text"));
        }
        String description = metadata == null ? "" : metadata.optString("description", "");
        if (description.isEmpty() && header != null) {
            description = text(header.optJSONObject("description"));
            if (description.isEmpty()) {
                JSONObject descriptionView = headerViewModel == null
                        ? null : headerViewModel.optJSONObject("description");
                description = text(descriptionView == null ? null : descriptionView.optJSONObject("descriptionPreviewViewModel"));
            }
        }
        String avatar = findAvatar(root);
        String subscriberCount = text(header == null ? null : header.optJSONObject("subscriberCountText"));
        String uploadsCount = text(header == null ? null : header.optJSONObject("videosCountText"));
        if (uploadsCount.isEmpty() && header != null) {
            uploadsCount = text(header.optJSONObject("videoCountText"));
        }
        if (headerViewModel != null) {
            JSONObject headerMetadata = headerViewModel.optJSONObject("metadata");
            JSONObject contentMetadata = headerMetadata == null
                    ? null : headerMetadata.optJSONObject("contentMetadataViewModel");
            JSONArray rows = contentMetadata == null ? null : contentMetadata.optJSONArray("metadataRows");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
                    if (parts == null) continue;
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject part = parts.optJSONObject(j);
                        JSONObject partText = part == null ? null : part.optJSONObject("text");
                        String value = partText == null ? "" : partText.optString("content", "");
                        String lower = value.toLowerCase(java.util.Locale.US);
                        if (subscriberCount.isEmpty() && lower.contains("subscriber")) {
                            subscriberCount = value;
                        }
                        if (uploadsCount.isEmpty() && lower.contains("video")) {
                            uploadsCount = value;
                        }
                    }
                }
            }
        }
        ChannelInfo info = new ChannelInfo();
        info.id = id;
        info.name = name;
        info.description = description;
        info.avatar = InnertubeEndpoint.absoluteUrl(avatar);
        info.banner = InnertubeEndpoint.absoluteUrl(findBanner(root));
        info.subscribers = subscriberCount;
        info.uploads = uploadsCount;
        return info;
    }

    /** Largest banner image (view-model header or the older c4TabbedHeaderRenderer). */
    private static String findBanner(JSONObject root) {
        JSONObject banner = findObject(root, "imageBannerViewModel");
        JSONObject image = banner == null ? null : banner.optJSONObject("image");
        JSONArray sources = image == null ? null : image.optJSONArray("sources");
        if (sources == null) {
            JSONObject header = findObject(root, "c4TabbedHeaderRenderer");
            JSONObject classic = header == null ? null : header.optJSONObject("banner");
            sources = classic == null ? null : classic.optJSONArray("thumbnails");
        }
        if (sources == null || sources.length() == 0) return "";
        JSONObject last = sources.optJSONObject(sources.length() - 1);
        return last == null ? "" : last.optString("url", "");
    }

    private static String channelEntry(JSONObject root, String requestedId, String baseUrl) {
        ChannelInfo info = readChannelInfo(root, requestedId);
        String id = info.id;
        String name = info.name;
        String description = info.description;
        String avatar = info.avatar;
        String subscriberCount = info.subscribers;
        String uploadsCount = info.uploads;
        String now = InnertubeEndpoint.utcNow();
        String safeId = XmlUtil.escape(id);
        String safeName = XmlUtil.escape(name);
        String safeDescription = XmlUtil.escape(description);
        String safeUrl = XmlUtil.escape(baseUrl);

        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>")
                .append("<entry xmlns='http://www.w3.org/2005/Atom'")
                .append(" xmlns:media='http://search.yahoo.com/mrss/'")
                .append(" xmlns:gd='http://schemas.google.com/g/2005'")
                .append(" xmlns:yt='http://gdata.youtube.com/schemas/2007'>")
                .append("<id>").append(safeUrl).append("/feeds/api/users/").append(safeId).append("</id>")
                .append("<published>2005-04-15T00:00:00.000Z</published>")
                .append("<updated>").append(now).append("</updated>")
                .append("<category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#userProfile'/>")
                .append("<title type='text'>").append(safeName).append("</title>")
                .append("<summary>").append(safeDescription).append("</summary>")
                .append("<content type='text'>").append(safeDescription).append("</content>")
                .append("<link rel='alternate' type='text/html' href='https://www.youtube.com/channel/").append(safeId).append("'/>")
                .append("<link rel='self' type='application/atom+xml' href='").append(safeUrl)
                .append("/feeds/api/users/").append(safeId).append("?v=2'/>")
                .append("<author><name>").append(safeName).append("</name><uri>").append(safeUrl)
                .append("/feeds/api/users/").append(safeId).append("</uri></author>");
        appendFeedLink(xml, safeUrl, safeId, "contacts", "user.contacts", "0", true);
        appendFeedLink(xml, safeUrl, safeId, "inbox", "user.inbox", "", false);
        appendFeedLink(xml, safeUrl, safeId, "playlists", "user.playlists", "", false);
        appendFeedLink(xml, safeUrl, safeId, "subscriptions", "user.subscriptions", "0", true);
        appendFeedLink(xml, safeUrl, safeId, "uploads", "user.uploads", numericCount(uploadsCount), true);
        appendFeedLink(xml, safeUrl, safeId, "newsubscriptionvideos", "user.newsubscriptionvideos", "", false);
        xml.append("<yt:maxUploadDuration seconds='0'/>")
                .append("<yt:statistics lastWebAccess='").append(now)
                .append("' subscriberCount='").append(XmlUtil.escape(numericCount(subscriberCount)))
                .append("' videoWatchCount='0' viewCount='0' totalUploadViews='0'/>")
                // 5.x apps read the channel id from the profile (subscribe button, channel page:
                // /feeds/api/channels/ID) and crash on an empty one; the channel parser reads
                // the subscriber count from yt:channelStatistics.
                .append("<yt:channelId>").append(safeId).append("</yt:channelId>")
                .append("<yt:channelStatistics subscriberCount='")
                .append(XmlUtil.escape(numericCount(subscriberCount))).append("' viewCount='0'/>");
        if (!avatar.isEmpty()) xml.append("<media:thumbnail url='").append(XmlUtil.escape(avatar)).append("'/>");
        xml.append("<yt:username display='").append(safeName).append("'>").append(safeId)
                .append("</yt:username></entry>");
        return xml.toString();
    }

    private static String uploadsFeed(String channelId, String baseUrl, int startIndex,
                                      int maxResults, Config config, Context context,
                                      boolean[] complete) throws Exception {
        int endIndex = startIndex + maxResults - 1;
        Uploads uploads = loadUploads(channelId, endIndex, config);
        List<InnertubeEndpoint.Video> allVideos = uploads.videos;
        int offset = Math.min(startIndex - 1, allVideos.size());
        int limit = Math.min(maxResults, allVideos.size() - offset);
        List<InnertubeEndpoint.Video> videos = new ArrayList<InnertubeEndpoint.Video>(
                allVideos.subList(offset, offset + limit));
        // Only the requested page is enriched; earlier entries are needed only for the offset.
        complete[0] = InnertubeEndpoint.enrichVideos(videos, config, context);
        String channelName = uploads.channelName;
        if (channelName.isEmpty()) {
            for (InnertubeEndpoint.Video video : videos) {
                if (!video.channel.isEmpty()) {
                    channelName = video.channel;
                    break;
                }
            }
        }
        videos = InnertubeEndpoint.completeFeedVideos(videos);
        if (channelName.isEmpty()) channelName = channelId;
        String feedUrl = baseUrl + "/feeds/api/users/" + channelId + "/uploads";
        boolean more = uploads.hasMore || allVideos.size() > offset + limit;
        String nextLink = !more || videos.isEmpty() ? ""
                : feedUrl + "?start-index=" + (startIndex + limit) + "&max-results=" + maxResults;
        return InnertubeEndpoint.buildFeed(videos, "Uploads from " + channelName, feedUrl, baseUrl,
                startIndex, nextLink);
    }

    private static Object uploadItems(JSONObject root) {
        JSONObject continuationContents = root.optJSONObject("continuationContents");
        JSONObject richGrid = continuationContents == null
                ? null : continuationContents.optJSONObject("richGridContinuation");
        Object items = richGrid == null ? null : richGrid.opt("contents");
        if (items == null) items = root.opt("onResponseReceivedActions");
        if (items == null) items = root.opt("onResponseReceivedEndpoints");
        return items == null ? root : items;
    }

    private static String findUploadContinuation(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("continuationItemRenderer");
            JSONObject endpoint = renderer == null ? null : renderer.optJSONObject("continuationEndpoint");
            JSONObject command = endpoint == null ? null : endpoint.optJSONObject("continuationCommand");
            if (command != null) {
                String token = command.optString("token", "");
                if (!token.isEmpty()) return token;
            }
            JSONObject viewModel = object.optJSONObject("continuationItemViewModel");
            if (viewModel != null) {
                String token = findFirst(viewModel.opt("continuationCommand"), "token");
                if (!token.isEmpty()) return token;
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String token = findUploadContinuation(object.opt(names.optString(i)));
                    if (!token.isEmpty()) return token;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String token = findUploadContinuation(array.opt(i));
                if (!token.isEmpty()) return token;
            }
        }
        return "";
    }

    private static boolean containsVideo(List<InnertubeEndpoint.Video> videos, String id) {
        for (InnertubeEndpoint.Video video : videos) {
            if (id.equals(video.id)) return true;
        }
        return false;
    }

    private static int parsePositiveInt(String value, int fallback, int maximum) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) return fallback;
            return Math.min(parsed, maximum);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static final class ChannelPlaylists {
        final List<PlaylistSummary> playlists = new ArrayList<PlaylistSummary>();
        String channelName = "";
        String continuation = "";
    }

    /** At least {@code limit} playlists of the channel's Playlists tab (when it has that many). */
    static ChannelPlaylists loadPlaylists(String channelId, int limit, Config config) throws Exception {
        ChannelPlaylists loaded = new ChannelPlaylists();
        JSONObject page = fetchBrowse(channelId, "playlists", config);
        JSONObject metadata = findObject(page, "channelMetadataRenderer");
        if (metadata != null) loaded.channelName = metadata.optString("title", "");
        for (int i = 0; i < 10; i++) {
            Object items = i == 0 ? page : uploadItems(page);
            collectPlaylists(items, loaded.playlists);
            loaded.continuation = findUploadContinuation(items);
            if (loaded.continuation.isEmpty() || loaded.playlists.size() >= limit) break;
            page = InnertubeEndpoint.request("browse",
                    InnertubeEndpoint.context(config).put("continuation", loaded.continuation), config);
        }
        return loaded;
    }

    static final class PlaylistSummary {
        String id = "";
        String title = "";
        String count = "0";
        String firstVideoId = "";
        String thumbnail = "";
    }

    /** Channel playlists, modelled on yt2009's userPlaylists / gdata_playlistEntry. */
    private static String playlistsFeed(String channelId, String baseUrl, int startIndex,
                                        int maxResults, Config config) throws Exception {
        int endIndex = startIndex + maxResults - 1;
        ChannelPlaylists loaded = loadPlaylists(channelId, endIndex, config);
        List<PlaylistSummary> all = loaded.playlists;
        String continuation = loaded.continuation;
        String channelName = loaded.channelName;
        if (channelName.isEmpty()) channelName = channelId;
        int offset = Math.min(startIndex - 1, all.size());
        int limit = Math.min(maxResults, all.size() - offset);
        List<PlaylistSummary> playlists = all.subList(offset, offset + limit);

        String safeBase = XmlUtil.escape(baseUrl);
        String safeChannel = XmlUtil.escape(channelId);
        String safeName = XmlUtil.escape(channelName);
        String userUrl = safeBase + "/feeds/api/users/" + safeChannel;
        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>")
                .append("<feed xmlns='http://www.w3.org/2005/Atom' xmlns:media='http://search.yahoo.com/mrss/'")
                .append(" xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'")
                .append(" xmlns:gd='http://schemas.google.com/g/2005' xmlns:yt='http://gdata.youtube.com/schemas/2007'>")
                .append("<id>").append(userUrl).append("/playlists</id>")
                .append("<updated>").append(InnertubeEndpoint.utcNow()).append("</updated>")
                .append("<category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#playlistLink'/>")
                .append("<title type='text'>Playlists of ").append(safeName).append("</title>")
                .append("<logo>http://www.youtube.com/img/pic_youtubelogo_123x63.gif</logo>")
                .append("<link rel='related' type='application/atom+xml' href='").append(userUrl).append("'/>")
                .append("<author><name>").append(safeName).append("</name><uri>").append(userUrl).append("</uri></author>")
                .append("<openSearch:totalResults>")
                .append(continuation.isEmpty() ? all.size() : Math.max(all.size(), endIndex + 1))
                .append("</openSearch:totalResults>")
                .append("<openSearch:startIndex>").append(startIndex).append("</openSearch:startIndex>")
                .append("<openSearch:itemsPerPage>").append(maxResults).append("</openSearch:itemsPerPage>");
        if (!playlists.isEmpty() && (!continuation.isEmpty() || all.size() > offset + limit)) {
            xml.append("<link rel='next' type='application/atom+xml' href='").append(userUrl)
                    .append("/playlists?start-index=").append(startIndex + limit)
                    .append("&amp;max-results=").append(maxResults).append("'/>");
        }
        for (PlaylistSummary playlist : playlists) {
            String id = XmlUtil.escape(playlist.id);
            String count = XmlUtil.escape(playlist.count);
            String userPlaylistUrl = userUrl + "/playlists/" + id;
            String playlistUrl = safeBase + "/feeds/api/playlists/" + id;
            xml.append("<entry><id>").append(userPlaylistUrl).append("</id>")
                    .append("<playlistId>").append(id).append("</playlistId>")
                    .append("<yt:playlistId>").append(id).append("</yt:playlistId>")
                    .append("<published>2008-08-25T10:05:58.000-07:00</published>")
                    .append("<updated>").append(InnertubeEndpoint.utcNow()).append("</updated>")
                    .append("<category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#playlistLink'/>")
                    .append("<title type='text'>").append(XmlUtil.escape(playlist.title)).append("</title>")
                    .append("<content type='text' src='").append(userPlaylistUrl).append("'>None</content>")
                    .append("<link rel='related' type='application/atom+xml' href='").append(userUrl).append("'/>")
                    .append("<link rel='alternate' type='text/html' href='https://www.youtube.com/playlist?list=")
                    .append(id).append("'/>")
                    .append("<link rel='self' type='application/atom+xml' href='").append(userPlaylistUrl).append("'/>")
                    .append("<author><name>").append(safeName).append("</name><uri>").append(userUrl)
                    .append("</uri><yt:userId>").append(safeChannel).append("</yt:userId></author>")
                    .append("<gd:feedLink rel='http://gdata.youtube.com/schemas/2007#playlist' href='")
                    .append(playlistUrl).append("' countHint='").append(count).append("'/>")
                    .append("<yt:description>None</yt:description><yt:countHint>").append(count)
                    .append("</yt:countHint><summary></summary><media:group>");
            if (!playlist.firstVideoId.isEmpty()) {
                for (String quality : new String[]{"default", "mqdefault", "hqdefault"}) {
                    xml.append("<media:thumbnail url='https://i.ytimg.com/vi/")
                            .append(XmlUtil.escape(playlist.firstVideoId)).append("/").append(quality)
                            .append(".jpg' yt:name='").append(quality).append("'/>");
                }
            } else if (!playlist.thumbnail.isEmpty()) {
                xml.append("<media:thumbnail url='").append(XmlUtil.escape(playlist.thumbnail))
                        .append("' yt:name='hqdefault'/>");
            }
            xml.append("<media:title type='plain'>").append(XmlUtil.escape(playlist.title)).append("</media:title>")
                    .append("</media:group></entry>");
        }
        return xml.append("</feed>").toString();
    }

    private static final java.util.regex.Pattern VIDEO_COUNT = java.util.regex.Pattern.compile(
            "([0-9][0-9,.]*)\\s*(?:videos?|episodes?|lessons?)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Collects classic playlist renderers and the current lockupViewModel playlist cards. */
    private static void collectPlaylists(Object node, List<PlaylistSummary> result) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (String key : new String[]{"gridPlaylistRenderer", "playlistRenderer",
                    "compactPlaylistRenderer"}) {
                JSONObject renderer = object.optJSONObject(key);
                if (renderer == null) continue;
                PlaylistSummary playlist = new PlaylistSummary();
                playlist.id = renderer.optString("playlistId", "");
                playlist.title = text(renderer.optJSONObject("title"));
                String count = text(renderer.optJSONObject("videoCountText"));
                if (count.isEmpty()) count = text(renderer.optJSONObject("videoCountShortText"));
                if (count.isEmpty()) count = renderer.optString("videoCount", "");
                playlist.count = numericCount(count);
                playlist.firstVideoId = findFirst(renderer, "videoId");
                addPlaylist(result, playlist);
            }
            JSONObject lockup = object.optJSONObject("lockupViewModel");
            String type = lockup == null ? "" : lockup.optString("contentType", "");
            if ("LOCKUP_CONTENT_TYPE_PLAYLIST".equals(type) || "LOCKUP_CONTENT_TYPE_PODCAST".equals(type)
                    || "LOCKUP_CONTENT_TYPE_COURSE".equals(type)) {
                PlaylistSummary playlist = new PlaylistSummary();
                playlist.id = lockup.optString("contentId", "");
                JSONObject metadata = lockup.optJSONObject("metadata");
                JSONObject lockupMetadata = metadata == null
                        ? null : metadata.optJSONObject("lockupMetadataViewModel");
                JSONObject title = lockupMetadata == null ? null : lockupMetadata.optJSONObject("title");
                playlist.title = title == null ? "" : title.optString("content", "");
                // The "12 videos" badge sits on the thumbnail; search it before the title text.
                String count = findMatchingString(lockup.opt("contentImage"), VIDEO_COUNT);
                if (count.isEmpty()) count = findMatchingString(lockup.opt("metadata"), VIDEO_COUNT);
                playlist.count = numericCount(count);
                playlist.firstVideoId = findFirst(lockup, "videoId");
                JSONObject sources = findObject(lockup, "image");
                JSONArray images = sources == null ? null : sources.optJSONArray("sources");
                JSONObject image = images == null || images.length() == 0
                        ? null : images.optJSONObject(images.length() - 1);
                if (image != null) playlist.thumbnail = image.optString("url", "");
                addPlaylist(result, playlist);
            }
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                if ("lockupViewModel".equals(key)) continue;
                collectPlaylists(object.opt(key), result);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collectPlaylists(array.opt(i), result);
        }
    }

    private static void addPlaylist(List<PlaylistSummary> result, PlaylistSummary playlist) {
        if (playlist.id.startsWith("VL")) playlist.id = playlist.id.substring(2);
        if (playlist.id.isEmpty() || !playlist.id.matches("[A-Za-z0-9_-]+")) return;
        for (PlaylistSummary existing : result) if (existing.id.equals(playlist.id)) return;
        if (playlist.title.isEmpty()) playlist.title = "Playlist";
        result.add(playlist);
    }

    private static String findMatchingString(Object node, java.util.regex.Pattern pattern) {
        if (node instanceof String) {
            return pattern.matcher((String) node).find() ? (String) node : "";
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String found = findMatchingString(object.opt(names.optString(i)), pattern);
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findMatchingString(array.opt(i), pattern);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static List<JSONObject> collectVideoRenderers(Object root, int limit) {
        List<JSONObject> result = new ArrayList<JSONObject>();
        collectRenderers(root, new String[]{"videoRenderer", "gridVideoRenderer", "compactVideoRenderer",
                "videoWithContextRenderer", "lockupViewModel"}, result, limit);
        return result;
    }

    private static void collectRenderers(Object node, String[] keys, List<JSONObject> result, int limit) {
        if (result.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (String key : keys) {
                JSONObject renderer = object.optJSONObject(key);
                if (renderer != null && !result.contains(renderer)) result.add(renderer);
            }
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length() && result.size() < limit; i++)
                collectRenderers(object.opt(names.optString(i)), keys, result, limit);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && result.size() < limit; i++)
                collectRenderers(array.opt(i), keys, result, limit);
        }
    }

    private static JSONObject findObject(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject found = object.optJSONObject(key);
            if (found != null) return found;
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                found = findObject(object.opt(names.optString(i)), key);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findObject(array.opt(i), key);
                if (found != null) return found;
            }
        }
        return null;
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

    private static String findAvatar(Object node) {
        JSONObject avatarViewModel = findObject(node, "avatarViewModel");
        JSONObject avatarImage = avatarViewModel == null ? null : avatarViewModel.optJSONObject("image");
        JSONArray avatarSources = avatarImage == null ? null : avatarImage.optJSONArray("sources");
        if (avatarSources != null && avatarSources.length() > 0) {
            JSONObject last = avatarSources.optJSONObject(avatarSources.length() - 1);
            if (last != null) return last.optString("url", "");
        }
        JSONObject renderer = findObject(node, "avatar");
        if (renderer == null) return "";
        JSONArray sources = renderer.optJSONArray("sources");
        if (sources == null || sources.length() == 0) {
            JSONObject image = renderer.optJSONObject("image");
            sources = image == null ? null : image.optJSONArray("sources");
        }
        if (sources == null || sources.length() == 0) {
            JSONObject viewModel = renderer.optJSONObject("avatarViewModel");
            JSONObject image = viewModel == null ? null : viewModel.optJSONObject("image");
            sources = image == null ? null : image.optJSONArray("sources");
        }
        if (sources == null || sources.length() == 0) return "";
        JSONObject last = sources.optJSONObject(sources.length() - 1);
        return last == null ? "" : last.optString("url", "");
    }

    private static String text(JSONObject renderer) {
        if (renderer == null) return "";
        String simple = renderer.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = renderer.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) value.append(run.optString("text", ""));
        }
        return value.toString();
    }

    private static String numericCount(String text) {
        if (text == null || text.trim().isEmpty()) return "0";
        String value = text.trim().toLowerCase(java.util.Locale.US).replace(",", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)\\s*([kmb]?)")
                .matcher(value);
        if (!matcher.find()) return "0";
        try {
            double count = Double.parseDouble(matcher.group(1));
            String suffix = matcher.group(2);
            if ("k".equals(suffix)) count *= 1_000d;
            else if ("m".equals(suffix)) count *= 1_000_000d;
            else if ("b".equals(suffix)) count *= 1_000_000_000d;
            return Long.toString(Math.round(count));
        } catch (NumberFormatException ignored) {
            return "0";
        }
    }

    private static void appendFeedLink(StringBuilder xml, String baseUrl, String channelId,
                                       String path, String relation, String count, boolean countHint) {
        xml.append("<gd:feedLink rel='http://gdata.youtube.com/schemas/2007#")
                .append(relation).append("' href='").append(baseUrl).append("/feeds/api/users/")
                .append(channelId).append("/").append(path).append("'");
        if (countHint) xml.append(" countHint='").append(XmlUtil.escape(count)).append("'");
        xml.append("/>");
    }

    private static String host(NanoHTTPD.IHTTPSession session) {
        String host = session.getHeaders().get("host");
        return host == null || host.isEmpty() ? "127.0.0.1" : host;
    }

    private static NanoHTTPD.Response response(String body) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "application/atom+xml; charset=utf-8", body);
    }
}
