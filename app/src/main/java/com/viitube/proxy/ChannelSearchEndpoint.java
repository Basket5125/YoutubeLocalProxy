package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.net.URLEncoder;

public class ChannelSearchEndpoint implements Endpoint {

    private static final String CHANNEL_FILTER_PARAMS = "EgIQAg==";

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                     Config config, Context context) throws Exception {
        String query = session.getParms().get("q");
        if (query == null || query.trim().isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain", "missing q parameter");
        }
        query = query.trim();
        int startIndex = parsePositiveInt(session.getParms().get("start-index"), 1, 500);
        int maxResults = parsePositiveInt(session.getParms().get("max-results"), 25, 50);
        String host = session.getHeaders().get("host");
        String baseUrl = "http://" + (host == null || host.isEmpty() ? "127.0.0.1" : host);
        java.io.File cache = HttpClient.cacheFile(context.getCacheDir(), "channel_search_v2",
                query + "_" + startIndex + "_" + maxResults + "_" + baseUrl);
        if (HttpClient.isFresh(cache, 30L * 60 * 1000)) return atom(HttpClient.readFile(cache));

        int needed = startIndex - 1 + maxResults;
        JSONObject payload = InnertubeEndpoint.context(config).put("query", query)
                .put("params", CHANNEL_FILTER_PARAMS);
        JSONObject result = InnertubeEndpoint.request("search", payload, config);
        List<JSONObject> channels = collectChannels(result, needed);
        // One page holds about 20 channels; follow continuations only for later pages.
        String continuation = findContinuation(result);
        for (int page = 0; page < 4 && channels.size() < needed && !continuation.isEmpty(); page++) {
            JSONObject next = InnertubeEndpoint.request("search",
                    InnertubeEndpoint.context(config).put("continuation", continuation), config);
            int before = channels.size();
            collectChannels(next, channels, needed);
            continuation = findContinuation(next);
            if (channels.size() == before) break;
        }
        int offset = Math.min(startIndex - 1, channels.size());
        int count = Math.min(maxResults, channels.size() - offset);
        String feedId = baseUrl + "/feeds/api/channels?q=" + URLEncoder.encode(query, "UTF-8");
        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>")
                .append("<feed xmlns='http://www.w3.org/2005/Atom'")
                .append(" xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'")
                .append(" xmlns:media='http://search.yahoo.com/mrss/'")
                .append(" xmlns:gd='http://schemas.google.com/g/2005'")
                .append(" xmlns:yt='http://gdata.youtube.com/schemas/2007'>")
                .append("<id>").append(XmlUtil.escape(feedId)).append("</id>")
                .append("<updated>").append(InnertubeEndpoint.utcNow()).append("</updated>")
                .append("<category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#channel'/>")
                .append("<title type='text'>Channel search: ").append(XmlUtil.escape(query)).append("</title>")
                .append("<openSearch:totalResults>")
                .append(continuation.isEmpty() ? channels.size() : Math.max(channels.size(), needed + 1))
                .append("</openSearch:totalResults><openSearch:startIndex>").append(startIndex)
                .append("</openSearch:startIndex><openSearch:itemsPerPage>").append(count)
                .append("</openSearch:itemsPerPage>");
        if (count > 0 && (!continuation.isEmpty() || channels.size() > offset + count)) {
            xml.append("<link rel='next' type='application/atom+xml' href='")
                    .append(XmlUtil.escape(feedId + "&start-index=" + (startIndex + count)
                            + "&max-results=" + maxResults)).append("'/>");
        }
        for (int i = offset; i < offset + count; i++) {
            appendEntry(xml, channels.get(i), baseUrl);
        }
        String feed = xml.append("</feed>").toString();
        HttpClient.writeFile(cache, feed);
        return atom(feed);
    }

    private static NanoHTTPD.Response atom(String body) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "application/atom+xml; charset=utf-8", body);
    }

    /**
     * YouTube now fills the channel card's text fields by position: "subscriberCountText"
     * may hold the @handle and "videoCountText" the subscriber count. Values are therefore
     * sorted by what they contain.
     */
    private static final class Counts {
        String handle = "";
        String subscribers = "";
        String videos = "";

        Counts(JSONObject channel) {
            for (String key : new String[]{"subscriberCountText", "videoCountText", "shortBylineText"}) {
                String value = text(channel.optJSONObject(key)).trim();
                String lower = value.toLowerCase(java.util.Locale.US);
                if (value.startsWith("@")) { if (handle.isEmpty()) handle = value; }
                else if (lower.contains("subscriber")) { if (subscribers.isEmpty()) subscribers = value; }
                else if (lower.contains("video")) { if (videos.isEmpty()) videos = value; }
            }
        }
    }

    private static void appendEntry(StringBuilder xml, JSONObject channel, String baseUrl) {
        String id = channelId(channel);
        if (id.isEmpty()) return;
        String name = text(channel.optJSONObject("title"));
        if (name.isEmpty()) name = id;
        String description = text(channel.optJSONObject("descriptionSnippet"));
        if (description.isEmpty()) description = text(channel.optJSONObject("description"));
        Counts counts = new Counts(channel);
        String subscribers = InnertubeEndpoint.numericCount(counts.subscribers);
        String videos = InnertubeEndpoint.numericCount(counts.videos);
        String thumbnail = thumbnail(channel);
        String safeId = XmlUtil.escape(id);
        String safeName = XmlUtil.escape(name);
        String safeBase = XmlUtil.escape(baseUrl);
        String userUrl = safeBase + "/feeds/api/users/" + safeId;
        xml.append("<entry><id>").append(userUrl).append("</id>")
                .append("<updated>").append(InnertubeEndpoint.utcNow()).append("</updated>")
                .append("<category scheme='http://schemas.google.com/g/2005#kind' term='")
                .append("http://gdata.youtube.com/schemas/2007#userProfile'/>")
                .append("<title type='text'>").append(safeName).append("</title>")
                .append("<summary>").append(XmlUtil.escape(description)).append("</summary>")
                .append("<content type='text'>").append(XmlUtil.escape(description)).append("</content>")
                .append("<link rel='alternate' type='text/html' href='https://www.youtube.com/channel/")
                .append(safeId).append("'/>")
                .append("<link rel='self' type='application/atom+xml' href='").append(userUrl).append("'/>")
                .append("<author><name>").append(safeName).append("</name><uri>")
                .append(userUrl).append("</uri><yt:userId>").append(safeId).append("</yt:userId></author>")
                .append("<gd:feedLink rel='http://gdata.youtube.com/schemas/2007#user.uploads' href='")
                .append(userUrl).append("/uploads' countHint='").append(XmlUtil.escape(videos)).append("'/>")
                .append("<gd:feedLink rel='http://gdata.youtube.com/schemas/2007#user.playlists' href='")
                .append(userUrl).append("/playlists'/>")
                .append("<yt:username display='").append(safeName).append("'>").append(safeId)
                .append("</yt:username><yt:channelId>").append(safeId).append("</yt:channelId>")
                .append("<yt:statistics subscriberCount='").append(XmlUtil.escape(subscribers))
                .append("' videoWatchCount='0' viewCount='0' totalUploadViews='0'/>")
                .append("<yt:channelStatistics subscriberCount='").append(XmlUtil.escape(subscribers))
                .append("' viewCount='0'/>");
        if (!thumbnail.isEmpty()) {
            xml.append("<media:thumbnail url='").append(XmlUtil.escape(thumbnail)).append("'/>");
        }
        xml.append("<yt:videoCount>").append(XmlUtil.escape(videos)).append("</yt:videoCount>")
                .append("</entry>");
    }

    private static String channelId(JSONObject channel) {
        String id = channel.optString("channelId", "");
        return id.isEmpty() ? findFirst(channel, "browseId") : id;
    }

    private static String findContinuation(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject command = object.optJSONObject("continuationCommand");
            if (command != null && !command.optString("token", "").isEmpty()) {
                return command.optString("token", "");
            }
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                if ("channelRenderer".equals(key)) continue;
                String found = findContinuation(object.opt(key));
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findContinuation(array.opt(i));
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static List<JSONObject> collectChannels(Object node, int limit) {
        List<JSONObject> channels = new ArrayList<JSONObject>();
        collectChannels(node, channels, limit);
        return channels;
    }

    private static void collectChannels(Object node, List<JSONObject> channels, int limit) {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (JSONObject channel : channels) seen.add(channelId(channel));
        collectChannels(node, channels, seen, limit);
    }

    private static void collectChannels(Object node, List<JSONObject> channels,
                                        java.util.Set<String> seen, int limit) {
        if (channels.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray names = object.names();
            if (names == null) return;
            for (int i = 0; i < names.length() && channels.size() < limit; i++) {
                String key = names.optString(i);
                Object value = object.opt(key);
                if (("channelRenderer".equals(key) || "gridChannelRenderer".equals(key))
                        && value instanceof JSONObject) {
                    String id = channelId((JSONObject) value);
                    if (!id.isEmpty() && seen.add(id)) channels.add((JSONObject) value);
                    continue; // nothing to find inside a channel card
                }
                collectChannels(value, channels, seen, limit);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && channels.size() < limit; i++) {
                collectChannels(array.opt(i), channels, seen, limit);
            }
        }
    }

    private static String findFirst(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String value = object.optString(key, "");
            if (!value.isEmpty()) return value;
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

    private static String text(JSONObject value) {
        if (value == null) return "";
        String simple = value.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = value.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) text.append(run.optString("text", ""));
        }
        return text.toString();
    }

    private static String thumbnail(JSONObject channel) {
        JSONObject image = channel.optJSONObject("thumbnail");
        JSONArray sources = image == null ? null : image.optJSONArray("thumbnails");
        if (sources == null || sources.length() == 0) return "";
        JSONObject last = sources.optJSONObject(sources.length() - 1);
        String url = last == null ? "" : last.optString("url", "");
        return url.startsWith("//") ? "https:" + url : url;
    }

    private static int parsePositiveInt(String value, int fallback, int maximum) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 1 ? fallback : Math.min(parsed, maximum);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}