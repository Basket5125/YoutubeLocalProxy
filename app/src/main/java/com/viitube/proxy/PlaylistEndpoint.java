package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Playlist feeds, modelled on yt2009's userPlaylistStart: /feeds/api/playlists/ID and
 * /feeds/api/users/USER/playlists/ID return the same feed of video entries, preceded by
 * the playlist metadata the legacy apps read (title, author, count, thumbnails).
 */
public class PlaylistEndpoint implements Endpoint {

    /** Innertube serves up to 100 playlist videos per page. */
    private static final int MAX_PAGES = 60;
    private final boolean userPlaylist;

    public PlaylistEndpoint() {
        this(false);
    }

    /** @param userPlaylist true for /feeds/api/users/USER/playlists/ID routes */
    public PlaylistEndpoint(boolean userPlaylist) {
        this.userPlaylist = userPlaylist;
    }

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                     Config config, Context context) throws Exception {
        String playlistId = params.length > 0 ? params[params.length - 1] : "";
        String user = userPlaylist && params.length > 1 ? params[0] : "";
        if (playlistId.startsWith("VL")) playlistId = playlistId.substring(2);
        if (playlistId.isEmpty() || !playlistId.matches("[A-Za-z0-9_-]+")) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain", "missing/invalid playlist id");
        }
        int startIndex = parsePositiveInt(session.getParms().get("start-index"), 1, 5000);
        int maxResults = parsePositiveInt(session.getParms().get("max-results"), 25, 50);
        int endIndex = startIndex + maxResults - 1;
        String host = session.getHeaders().get("host");
        String baseUrl = "http://" + (host == null || host.isEmpty() ? "127.0.0.1" : host);

        java.io.File cache = HttpClient.cacheFile(context.getCacheDir(), "playlist_feed_v1",
                playlistId + "_" + user + "_" + startIndex + "_" + maxResults + "_" + baseUrl);
        if (HttpClient.isFresh(cache, 15L * 60 * 1000)) return atom(HttpClient.readFile(cache));

        Playlist playlist = fetchPlaylist(playlistId, endIndex, config);
        // Positions are counted on the raw list so pages stay stable; only this page is enriched.
        int offset = Math.min(startIndex - 1, playlist.videos.size());
        int count = Math.min(maxResults, playlist.videos.size() - offset);
        List<InnertubeEndpoint.Video> videos = new ArrayList<InnertubeEndpoint.Video>(
                playlist.videos.subList(offset, offset + count));
        boolean complete = InnertubeEndpoint.enrichVideos(videos, config, context);
        videos = InnertubeEndpoint.completeFeedVideos(videos);

        if (playlist.author.isEmpty() && !videos.isEmpty()) {
            playlist.author = videos.get(0).channel;
            playlist.authorId = videos.get(0).channelId;
        }
        if (user.isEmpty()) user = playlist.authorId;
        String feedUrl = baseUrl + (user.isEmpty() ? "/feeds/api/playlists/" + playlistId
                : "/feeds/api/users/" + user + "/playlists/" + playlistId);
        String nextLink = "";
        if (count > 0 && playlist.hasMore) {
            nextLink = feedUrl + "?start-index=" + (startIndex + count) + "&max-results=" + maxResults;
        }
        int total = Math.max(playlist.total, playlist.videos.size());
        String firstVideoId = playlist.videos.isEmpty() ? "" : playlist.videos.get(0).id;
        String header = playlistHeader(playlistId, playlist, firstVideoId, user, baseUrl, total);
        String xml = InnertubeEndpoint.buildFeed(videos, playlist.title, feedUrl, baseUrl,
                startIndex, nextLink, header, total, true);
        if (complete) HttpClient.writeFile(cache, xml);
        return atom(xml);
    }

    private static NanoHTTPD.Response atom(String xml) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "application/atom+xml; charset=utf-8", xml);
    }

    /** Equivalent of yt2009's gdata_userPlaylistStart. */
    private static String playlistHeader(String playlistId, Playlist playlist, String firstVideoId,
                                         String user, String baseUrl, int total) {
        String safeId = XmlUtil.escape(playlistId);
        String safeBase = XmlUtil.escape(baseUrl);
        String authorName = XmlUtil.escape(playlist.author.isEmpty() ? user : playlist.author);
        String authorUri = safeBase + "/feeds/api/users/" + XmlUtil.escape(user);
        StringBuilder xml = new StringBuilder()
                .append("<category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#playlistLink'/>")
                .append("<summary>").append(XmlUtil.escape(playlist.description)).append("</summary>")
                .append("<content type='application/atom+xml;type=feed' src='").append(safeBase)
                .append("/feeds/api/playlists/").append(safeId).append("'/>");
        if (!user.isEmpty()) {
            xml.append("<link rel='related' type='application/atom+xml' href='").append(authorUri).append("'/>")
                    .append("<link rel='self' type='application/atom+xml' href='").append(authorUri)
                    .append("/playlists/").append(safeId).append("'/>");
        }
        xml.append("<link rel='alternate' type='text/html' href='https://www.youtube.com/playlist?list=")
                .append(safeId).append("'/>")
                .append("<author><name>").append(authorName).append("</name><uri>").append(authorUri)
                .append("</uri></author>")
                .append("<yt:description>").append(XmlUtil.escape(playlist.description)).append("</yt:description>")
                .append("<yt:countHint>").append(total).append("</yt:countHint><media:group>")
                .append("<media:title type='plain'>").append(XmlUtil.escape(playlist.title)).append("</media:title>");
        if (!firstVideoId.isEmpty()) {
            String safeVideo = XmlUtil.escape(firstVideoId);
            xml.append("<media:thumbnail url='http://i.ytimg.com/vi/").append(safeVideo)
                    .append("/default.jpg' height='90' width='120' yt:name='default'/>")
                    .append("<media:thumbnail url='http://i.ytimg.com/vi/").append(safeVideo)
                    .append("/hqdefault.jpg' height='360' width='480' yt:name='hqdefault'/>");
        }
        return xml.append("</media:group><yt:playlistId>").append(safeId).append("</yt:playlistId>")
                .append("<playlistId>").append(safeId).append("</playlistId>").toString();
    }

    static final class Playlist {
        String title = "Playlist";
        String description = "";
        String author = "";
        String authorId = "";
        int total = -1;
        boolean hasMore;
        List<InnertubeEndpoint.Video> videos = new ArrayList<InnertubeEndpoint.Video>();
    }

    /** Playlist pages already loaded, so a later page continues from the stored token. */
    private static final class LoadedPlaylist {
        final Playlist metadata = new Playlist();
        final List<InnertubeEndpoint.Video> videos = new ArrayList<InnertubeEndpoint.Video>();
        String continuation = "";
        long loadedAt;
    }

    private static final long LOADED_MAX_AGE_MS = 10L * 60 * 1000;
    private static final java.util.Map<String, LoadedPlaylist> LOADED =
            new java.util.LinkedHashMap<String, LoadedPlaylist>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, LoadedPlaylist> eldest) {
                    return size() > 24;
                }
            };
    private static final java.util.Map<String, Object> LOCKS = new java.util.HashMap<String, Object>();

    /**
     * Loads at least {@code limit} videos of a playlist (when available). Loaded pages are kept
     * for ten minutes, so the next page of a feed, or the activity tab after the uploads tab,
     * needs at most the requests for the pages not loaded yet. The returned videos are copies
     * that callers may modify.
     */
    static Playlist fetchPlaylist(String playlistId, int limit, Config config) throws Exception {
        Object lock;
        synchronized (LOADED) {
            lock = LOCKS.get(playlistId);
            if (lock == null) {
                lock = new Object();
                LOCKS.put(playlistId, lock);
            }
        }
        synchronized (lock) {
            LoadedPlaylist loaded;
            synchronized (LOADED) {
                loaded = LOADED.get(playlistId);
            }
            if (loaded == null || System.currentTimeMillis() - loaded.loadedAt > LOADED_MAX_AGE_MS) {
                loaded = new LoadedPlaylist();
                JSONObject response = InnertubeEndpoint.request("browse",
                        InnertubeEndpoint.context(config).put("browseId", "VL" + playlistId), config);
                readMetadata(response, loaded.metadata);
                addPage(response, loaded);
                loaded.loadedAt = System.currentTimeMillis();
            }
            for (int page = 0; page < MAX_PAGES && !loaded.continuation.isEmpty()
                    && loaded.videos.size() < limit; page++) {
                JSONObject nextPage = InnertubeEndpoint.request("browse", InnertubeEndpoint.context(config)
                        .put("continuation", loaded.continuation), config);
                int previousSize = loaded.videos.size();
                addPage(nextPage, loaded);
                if (loaded.videos.size() == previousSize) loaded.continuation = "";
            }
            synchronized (LOADED) {
                LOADED.put(playlistId, loaded);
            }

            Playlist playlist = new Playlist();
            playlist.title = loaded.metadata.title;
            playlist.description = loaded.metadata.description;
            playlist.author = loaded.metadata.author;
            playlist.authorId = loaded.metadata.authorId;
            playlist.total = loaded.metadata.total;
            playlist.hasMore = !loaded.continuation.isEmpty() || loaded.videos.size() > limit;
            for (int i = 0; i < loaded.videos.size() && i < limit; i++) {
                playlist.videos.add(loaded.videos.get(i).copy());
            }
            return playlist;
        }
    }

    /** Adds every video of a page (a page is never cut, or its rest would be skipped). */
    private static void addPage(JSONObject page, LoadedPlaylist loaded) {
        List<JSONObject> entries = new ArrayList<JSONObject>();
        collectPlaylistVideos(page, entries, Integer.MAX_VALUE);
        java.util.Set<String> known = new java.util.HashSet<String>();
        for (InnertubeEndpoint.Video video : loaded.videos) known.add(video.id);
        for (JSONObject renderer : entries) {
            InnertubeEndpoint.Video video = InnertubeEndpoint.parseVideo(renderer);
            if (known.add(video.id)) loaded.videos.add(video);
        }
        loaded.continuation = findContinuation(page);
    }

    private static void readMetadata(JSONObject response, Playlist playlist) {
        JSONObject metadata = findObject(response, "playlistMetadataRenderer");
        JSONObject header = findObject(response, "playlistHeaderRenderer");
        JSONObject sidebar = findObject(response, "playlistSidebarPrimaryInfoRenderer");
        JSONObject pageHeader = findObject(response, "pageHeaderViewModel");

        String title = metadata == null ? "" : stringOrText(metadata, "title");
        if (title.isEmpty() && header != null) title = stringOrText(header, "title");
        if (title.isEmpty() && sidebar != null) title = stringOrText(sidebar, "title");
        if (title.isEmpty() && pageHeader != null) {
            JSONObject titleModel = pageHeader.optJSONObject("title");
            JSONObject dynamic = titleModel == null ? null : titleModel.optJSONObject("dynamicTextViewModel");
            JSONObject text = dynamic == null ? null : dynamic.optJSONObject("text");
            if (text != null) title = text.optString("content", "");
        }
        if (!title.isEmpty()) playlist.title = title;
        if (metadata != null) playlist.description = stringOrText(metadata, "description");

        if (header != null) {
            JSONObject owner = header.optJSONObject("ownerText");
            playlist.author = text(owner);
            playlist.authorId = findString(owner, "browseId");
        }
        if (playlist.authorId.isEmpty()) {
            JSONObject owner = findObject(response, "videoOwnerRenderer");
            if (owner != null) {
                playlist.author = text(owner.optJSONObject("title"));
                playlist.authorId = findString(owner, "browseId");
            }
        }
        if (playlist.authorId.isEmpty() && pageHeader != null) {
            String id = findString(pageHeader, "browseId");
            if (id.startsWith("UC")) playlist.authorId = id;
        }
        playlist.total = findVideoCount(response);
    }

    private static final java.util.regex.Pattern VIDEO_COUNT =
            java.util.regex.Pattern.compile("^([0-9][0-9,.\\u00a0 ]*)\\s+videos?$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Finds the "123 videos" label of the playlist header (classic or view-model layout). */
    private static int findVideoCount(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray names = object.names();
            if (names == null) return -1;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                // Skip the video list itself; the count lives in the header/sidebar.
                if ("playlistVideoListRenderer".equals(key) || "playlistVideoRenderer".equals(key)) continue;
                Object value = object.opt(key);
                if (value instanceof String) {
                    java.util.regex.Matcher matcher = VIDEO_COUNT.matcher(((String) value).trim());
                    if (matcher.find()) {
                        String digits = matcher.group(1).replaceAll("[^0-9]", "");
                        if (!digits.isEmpty()) return Integer.parseInt(digits);
                    }
                } else if (key.equals("runs") && value instanceof JSONArray) {
                    String joined = runsText((JSONArray) value);
                    java.util.regex.Matcher matcher = VIDEO_COUNT.matcher(joined.trim());
                    if (matcher.find()) {
                        String digits = matcher.group(1).replaceAll("[^0-9]", "");
                        if (!digits.isEmpty()) return Integer.parseInt(digits);
                    }
                } else {
                    int found = findVideoCount(value);
                    if (found >= 0) return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                int found = findVideoCount(array.opt(i));
                if (found >= 0) return found;
            }
        }
        return -1;
    }

    static String findContinuation(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject item = object.optJSONObject("continuationItemRenderer");
            if (item != null) {
                JSONObject endpoint = item.optJSONObject("continuationEndpoint");
                String token = continuationToken(endpoint);
                if (!token.isEmpty()) return token;
            }
            JSONObject viewModel = object.optJSONObject("continuationItemViewModel");
            if (viewModel != null) {
                JSONObject endpoint = viewModel.optJSONObject("continuationEndpoint");
                String token = continuationToken(endpoint);
                if (!token.isEmpty()) return token;
                // continuationCommand.innertubeCommand.continuationCommand.token (2026 layout)
                token = findString(viewModel.opt("continuationCommand"), "token");
                if (!token.isEmpty()) return token;
            }
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String token = findContinuation(object.opt(names.optString(i)));
                if (!token.isEmpty()) return token;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String token = findContinuation(array.opt(i));
                if (!token.isEmpty()) return token;
            }
        }
        return "";
    }

    private static String continuationToken(JSONObject endpoint) {
        if (endpoint == null) return "";
        JSONObject command = endpoint.optJSONObject("continuationCommand");
        if (command != null) return command.optString("token", "");
        JSONObject executor = endpoint.optJSONObject("commandExecutorCommand");
        JSONArray commands = executor == null ? null : executor.optJSONArray("commands");
        if (commands != null) for (int i = 0; i < commands.length(); i++) {
            JSONObject child = commands.optJSONObject(i);
            command = child == null ? null : child.optJSONObject("continuationCommand");
            if (command != null) return command.optString("token", "");
        }
        return "";
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

    private static void collectPlaylistVideos(Object node, List<JSONObject> videos, int limit) {
        if (videos.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("playlistVideoRenderer");
            if (renderer != null && renderer.optBoolean("isPlayable", true)) {
                addVideo(videos, renderer, renderer.optString("videoId", ""));
            }
            JSONObject lockup = object.optJSONObject("lockupViewModel");
            if (lockup != null
                    && "LOCKUP_CONTENT_TYPE_VIDEO".equals(lockup.optString("contentType", ""))) {
                addVideo(videos, object, lockup.optString("contentId", ""));
            }
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length() && videos.size() < limit; i++) {
                String key = names.optString(i);
                if ("playlistVideoRenderer".equals(key) || "lockupViewModel".equals(key)) continue;
                collectPlaylistVideos(object.opt(key), videos, limit);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && videos.size() < limit; i++)
                collectPlaylistVideos(array.opt(i), videos, limit);
        }
    }

    private static void addVideo(List<JSONObject> videos, JSONObject renderer, String videoId) {
        if (InnertubeEndpoint.isValidVideoId(videoId) && !containsVideo(videos, videoId)) {
            videos.add(renderer);
        }
    }

    private static boolean containsVideo(List<JSONObject> videos, String videoId) {
        for (JSONObject video : videos) {
            JSONObject lockup = video.optJSONObject("lockupViewModel");
            String id = lockup != null ? lockup.optString("contentId", "") : video.optString("videoId", "");
            if (videoId.equals(id)) return true;
        }
        return false;
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

    private static String findString(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Object value = object.opt(key);
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String found = findString(object.opt(names.optString(i)), key);
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findString(array.opt(i), key);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    /** Reads a field that is either a plain string or a {simpleText|runs} text object. */
    private static String stringOrText(JSONObject parent, String key) {
        Object value = parent.opt(key);
        if (value instanceof String) return (String) value;
        if (value instanceof JSONObject) return text((JSONObject) value);
        return "";
    }

    private static String text(JSONObject value) {
        if (value == null) return "";
        String simple = value.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = value.optJSONArray("runs");
        return runs == null ? value.optString("content", "") : runsText(runs);
    }

    private static String runsText(JSONArray runs) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) out.append(run.optString("text", ""));
        }
        return out.toString();
    }
}
