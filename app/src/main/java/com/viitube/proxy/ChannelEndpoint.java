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
    private final String mode;

    public ChannelEndpoint(String mode) {
        this.mode = mode;
    }

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                    Config config, Context context) throws Exception {
        String channelId = params.length > 0 ? params[0] : "";
        if (channelId.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain", "missing channel id");
        }

        String baseUrl = "http://" + host(session);
        if ("uploads".equals(mode)) {
            int startIndex = parsePositiveInt(session.getParms().get("start-index"), 1, 500);
            int maxResults = parsePositiveInt(session.getParms().get("max-results"), 25, 50);
            String cacheKey = channelId + "_" + startIndex + "_" + maxResults + "_" + host(session);
            java.io.File cache = HttpClient.cacheFile(context.getCacheDir(),
                    "uploads_metadata_v4", cacheKey);
            if (HttpClient.isFresh(cache, 30L * 1000)) {
                return response(HttpClient.readFile(cache));
            }

            String continuation = InnertubeEndpoint.browseContinuation(channelId, UPLOADS_TAB_PARAMS);
            JSONObject payload = InnertubeEndpoint.context(config).put("continuation", continuation);
            try {
                JSONObject browse = InnertubeEndpoint.requestFast("browse", payload, config);
                String feed = uploadsFeed(browse, channelId, baseUrl, session, config, context);
                HttpClient.writeFile(cache, feed);
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

        JSONObject browse = fetchBrowse(channelId, mode, config);
        if ("channel".equals(mode)) return response(channelEntry(browse, channelId, baseUrl));
        if ("playlists".equals(mode)) return response(playlistsFeed(browse, channelId, baseUrl));
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "not found");
    }

    private static JSONObject fetchBrowse(String channelId, String mode, Config config) throws Exception {
        JSONObject payload = InnertubeEndpoint.context(config).put("browseId", channelId);
        if ("uploads".equals(mode)) payload.put("params", "EgZ2aWRlb3M=");
        else if ("playlists".equals(mode)) payload.put("params", "EglwbGF5bGlzdHM=");
        return InnertubeEndpoint.request("browse", payload, config);
    }

    private static String channelEntry(JSONObject root, String requestedId, String baseUrl) {
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
                .append("' videoWatchCount='0' viewCount='0' totalUploadViews='0'/>");
        if (!avatar.isEmpty()) xml.append("<media:thumbnail url='").append(XmlUtil.escape(avatar)).append("'/>");
        xml.append("<yt:username display='").append(safeName).append("'>").append(safeId)
                .append("</yt:username></entry>");
        return xml.toString();
    }

    private static String uploadsFeed(JSONObject root, String channelId, String baseUrl,
                                      NanoHTTPD.IHTTPSession session, Config config, Context context)
            throws Exception {
        int startIndex = parsePositiveInt(session.getParms().get("start-index"), 1, 500);
        int maxResults = parsePositiveInt(session.getParms().get("max-results"), 25, 50);
        int endIndex = startIndex + maxResults - 1;
        List<InnertubeEndpoint.Video> allVideos = new ArrayList<InnertubeEndpoint.Video>();
        String continuation = "";
        String channelName = "";
        JSONObject currentPage = root;

        for (int page = 0; page < 20 && allVideos.size() < endIndex; page++) {
            JSONObject metadata = currentPage.optJSONObject("metadata");
            JSONObject channel = metadata == null ? null : metadata.optJSONObject("channelMetadataRenderer");
            if (channel != null) {
                String title = channel.optString("title", "");
                if (!title.isEmpty()) channelName = title;
            }

            Object items = uploadItems(currentPage);
            List<JSONObject> renderers = collectVideoRenderers(items, endIndex);
            for (JSONObject renderer : renderers) {
                InnertubeEndpoint.Video video = InnertubeEndpoint.parseVideo(renderer);
                if (video.id.isEmpty() || containsVideo(allVideos, video.id)) continue;
                if (!channelName.isEmpty()) video.channel = channelName;
                video.channelId = channelId;
                allVideos.add(video);
            }

            continuation = findUploadContinuation(items);
            if (continuation.isEmpty() || allVideos.size() >= endIndex) break;
            JSONObject payload = InnertubeEndpoint.context(config).put("continuation", continuation);
            currentPage = InnertubeEndpoint.requestFast("browse", payload, config);
        }

        InnertubeEndpoint.enrichVideos(allVideos, config, context);
        if (channelName.isEmpty()) {
            for (InnertubeEndpoint.Video video : allVideos) {
                if (!video.channel.isEmpty()) {
                    channelName = video.channel;
                    break;
                }
            }
        }
        allVideos = InnertubeEndpoint.completeFeedVideos(allVideos);
        if (channelName.isEmpty()) channelName = channelId;
        int offset = Math.min(startIndex - 1, allVideos.size());
        int limit = Math.min(maxResults, allVideos.size() - offset);
        List<InnertubeEndpoint.Video> videos = new ArrayList<InnertubeEndpoint.Video>(
                allVideos.subList(offset, offset + limit));
        String feedUrl = baseUrl + "/feeds/api/users/" + channelId + "/uploads";
        String nextLink = continuation.isEmpty() || videos.isEmpty() ? ""
                : feedUrl + "?start-index=" + (startIndex + videos.size()) + "&max-results=" + maxResults;
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
            int parsed = Integer.parseInt(value);
            return parsed >= 1 && parsed <= maximum ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String playlistsFeed(JSONObject root, String channelId, String baseUrl) {
        List<JSONObject> playlists = collectPlaylistRenderers(root, 25);
        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>")
                .append("<feed xmlns='http://www.w3.org/2005/Atom' xmlns:media='http://search.yahoo.com/mrss/'")
                .append(" xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'")
                .append(" xmlns:gd='http://schemas.google.com/g/2005' xmlns:yt='http://gdata.youtube.com/schemas/2007'>")
                .append("<id>").append(XmlUtil.escape(baseUrl)).append("/feeds/api/users/")
                .append(XmlUtil.escape(channelId)).append("/playlists</id>")
                .append("<updated>").append(InnertubeEndpoint.utcNow()).append("</updated>")
                .append("<title type='text'>User Playlists</title>")
                .append("<openSearch:totalResults>").append(playlists.size()).append("</openSearch:totalResults>")
                .append("<openSearch:startIndex>1</openSearch:startIndex><openSearch:itemsPerPage>")
                .append(playlists.size()).append("</openSearch:itemsPerPage>");
        for (JSONObject playlist : playlists) {
            String id = playlist.optString("playlistId", "");
            if (id.isEmpty()) id = playlist.optString("browseId", "");
            if (id.isEmpty()) continue;
            String title = text(playlist.optJSONObject("title"));
            if (title.isEmpty()) title = text(playlist.optJSONObject("headline"));
            String count = text(playlist.optJSONObject("videoCountText"));
            String firstVideoId = findFirst(playlist, "videoId");
            String playlistUrl = baseUrl + "/feeds/api/playlists/" + id;
            xml.append("<entry><id>").append(XmlUtil.escape(baseUrl)).append("/feeds/api/users/")
                    .append(XmlUtil.escape(channelId)).append("/playlists/").append(XmlUtil.escape(id)).append("</id>")
                    .append("<playlistId>").append(XmlUtil.escape(id)).append("</playlistId>")
                    .append("<yt:playlistId>").append(XmlUtil.escape(id)).append("</yt:playlistId>")
                    .append("<published>2008-08-25T10:05:58.000-07:00</published>")
                    .append("<updated>").append(InnertubeEndpoint.utcNow()).append("</updated>")
                    .append("<category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#playlistLink'/>")
                    .append("<title type='text'>").append(XmlUtil.escape(title)).append("</title>")
                    .append("<content type='text' src='").append(XmlUtil.escape(playlistUrl)).append("'/>")
                    .append("<link rel='related' type='application/atom+xml' href='").append(XmlUtil.escape(baseUrl))
                    .append("/feeds/api/users/").append(XmlUtil.escape(channelId)).append("'/>")
                    .append("<link rel='alternate' type='text/html' href='https://www.youtube.com/playlist?list=")
                    .append(XmlUtil.escape(id)).append("'/>")
                    .append("<link rel='self' type='application/atom+xml' href='").append(XmlUtil.escape(playlistUrl)).append("'/>")
                    .append("<author><name>").append(XmlUtil.escape(channelId)).append("</name><uri>")
                    .append(XmlUtil.escape(baseUrl)).append("/feeds/api/users/").append(XmlUtil.escape(channelId))
                    .append("</uri></author><gd:feedLink rel='http://gdata.youtube.com/schemas/2007#playlist' href='")
                    .append(XmlUtil.escape(playlistUrl)).append("' countHint='").append(XmlUtil.escape(numericCount(count))).append("'/>")
                    .append("<yt:description></yt:description><yt:countHint>").append(XmlUtil.escape(numericCount(count)))
                    .append("</yt:countHint><media:group>");
            if (!firstVideoId.isEmpty()) {
                for (String quality : new String[]{"default", "mqdefault", "hqdefault"}) {
                    xml.append("<media:thumbnail url='https://i.ytimg.com/vi/").append(XmlUtil.escape(firstVideoId))
                            .append("/").append(quality).append(".jpg' yt:name='").append(quality).append("'/>");
                }
            }
            xml.append("<media:title type='plain'>").append(XmlUtil.escape(title)).append("</media:title>")
                    .append("</media:group></entry>");
        }
        return xml.append("</feed>").toString();
    }

    private static List<JSONObject> collectVideoRenderers(Object root, int limit) {
        List<JSONObject> result = new ArrayList<JSONObject>();
        collectRenderers(root, new String[]{"videoRenderer", "gridVideoRenderer", "compactVideoRenderer",
                "videoWithContextRenderer", "lockupViewModel"}, result, limit);
        return result;
    }

    private static List<JSONObject> collectPlaylistRenderers(Object root, int limit) {
        List<JSONObject> result = new ArrayList<JSONObject>();
        collectRenderers(root, new String[]{"gridPlaylistRenderer", "playlistRenderer", "compactPlaylistRenderer"}, result, limit);
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
