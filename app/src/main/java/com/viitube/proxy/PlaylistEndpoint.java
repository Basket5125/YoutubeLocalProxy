package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PlaylistEndpoint implements Endpoint {

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                     Config config, Context context) throws Exception {
        String playlistId = params.length > 0 ? params[0] : "";
        if (playlistId.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain", "missing playlist id");
        }
        JSONObject payload = InnertubeEndpoint.context(config).put("browseId", "VL" + playlistId);
        JSONObject response = InnertubeEndpoint.request("browse", payload, config);
        String host = session.getHeaders().get("host");
        String baseUrl = "http://" + (host == null || host.isEmpty() ? "127.0.0.1" : host);
        String title = playlistTitle(response);
        List<JSONObject> entries = new ArrayList<JSONObject>();
        collectPlaylistVideos(response, entries, 50);

        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>")
                .append("<feed xmlns='http://www.w3.org/2005/Atom' xmlns:media='http://search.yahoo.com/mrss/'")
                .append(" xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'")
                .append(" xmlns:gd='http://schemas.google.com/g/2005' xmlns:yt='http://gdata.youtube.com/schemas/2007'>")
                .append("<id>").append(XmlUtil.escape(baseUrl)).append("/feeds/api/playlists/")
                .append(XmlUtil.escape(playlistId)).append("</id><updated>")
                .append(InnertubeEndpoint.utcNow()).append("</updated>")
                .append("<category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#playlist'/>")
                .append("<title type='text'>").append(XmlUtil.escape(title)).append("</title>")
                .append("<logo>http://www.youtube.com/img/pic_youtubelogo_123x63.gif</logo>")
                .append("<openSearch:totalResults>").append(entries.size()).append("</openSearch:totalResults>")
                .append("<openSearch:startIndex>1</openSearch:startIndex><openSearch:itemsPerPage>")
                .append(entries.size()).append("</openSearch:itemsPerPage><yt:playlistId>")
                .append(XmlUtil.escape(playlistId)).append("</yt:playlistId>");

        int position = 1;
        for (JSONObject renderer : entries) {
            InnertubeEndpoint.Video video = InnertubeEndpoint.parseVideo(renderer);
            String safeId = XmlUtil.escape(video.id);
            String videoUrl = baseUrl + "/feeds/api/videos/" + video.id;
            xml.append("<entry><id>").append(XmlUtil.escape(videoUrl)).append("</id>")
                    .append("<youTubeId id='").append(safeId).append("'>").append(safeId).append("</youTubeId>")
                    .append("<published>").append(InnertubeEndpoint.toAtomDate(video.published)).append("</published>")
                    .append("<updated>").append(InnertubeEndpoint.toAtomDate(video.published)).append("</updated>")
                    .append("<category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#video'/>")
                    .append("<title type='text'>").append(XmlUtil.escape(video.title)).append("</title>")
                    .append("<content type='text'>").append(XmlUtil.escape(video.description)).append("</content>")
                    .append("<link rel='alternate' type='text/html' href='https://www.youtube.com/watch?v=").append(safeId).append("'/>")
                    .append("<link rel='http://gdata.youtube.com/schemas/2007#video.related' href='")
                    .append(XmlUtil.escape(videoUrl)).append("/related'/>")
                    .append("<author><name>").append(XmlUtil.escape(video.channel)).append("</name><uri>")
                    .append(XmlUtil.escape(baseUrl)).append("/feeds/api/users/").append(XmlUtil.escape(video.channelId))
                    .append("</uri><yt:userId>").append(XmlUtil.escape(video.channelId)).append("</yt:userId></author>")
                    .append("<gd:comments><gd:feedLink href='").append(XmlUtil.escape(videoUrl)).append("/comments'/></gd:comments>")
                    .append("<media:group><media:title>").append(XmlUtil.escape(video.title)).append("</media:title>")
                    .append("<media:content url='").append(XmlUtil.escape(baseUrl)).append("/video/").append(safeId)
                    .append("' type='video/3gpp' medium='video' expression='full' duration='")
                    .append(InnertubeEndpoint.durationSecondsPublic(video.duration)).append("' yt:format='3'/>")
                    .append("<media:description type='plain'>").append(XmlUtil.escape(video.description)).append("</media:description>")
                    .append("<media:player url='https://www.youtube.com/watch?v=").append(safeId).append("'/>")
                    .append("<media:thumbnail yt:name='hqdefault' url='https://i.ytimg.com/vi/").append(safeId).append("/hqdefault.jpg'/>")
                    .append("<yt:duration seconds='").append(InnertubeEndpoint.durationSecondsPublic(video.duration)).append("'/>")
                    .append("<yt:videoid id='").append(safeId).append("'>").append(safeId).append("</yt:videoid>")
                    .append("<youTubeId id='").append(safeId).append("'>").append(safeId).append("</youTubeId></media:group>")
                    .append("<yt:statistics favoriteCount='0' viewCount='").append(video.views.replaceAll("[^0-9]", ""))
                    .append("'/><yt:position>").append(position++).append("</yt:position></entry>");
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "application/atom+xml; charset=utf-8", xml.append("</feed>").toString());
    }

    private static String playlistTitle(Object node) {
        JSONObject renderer = findObject(node, "playlistMetadataRenderer");
        if (renderer != null) return text(renderer.optJSONObject("title"));
        renderer = findObject(node, "playlistHeaderRenderer");
        if (renderer != null) return text(renderer.optJSONObject("title"));
        return "Playlist";
    }

    private static void collectPlaylistVideos(Object node, List<JSONObject> videos, int limit) {
        if (videos.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("playlistVideoRenderer");
            if (renderer != null && !renderer.optString("videoId", "").isEmpty()) videos.add(renderer);
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length() && videos.size() < limit; i++)
                collectPlaylistVideos(object.opt(names.optString(i)), videos, limit);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && videos.size() < limit; i++)
                collectPlaylistVideos(array.opt(i), videos, limit);
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

    private static String text(JSONObject value) {
        if (value == null) return "";
        String simple = value.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = value.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) out.append(run.optString("text", ""));
        }
        return out.toString();
    }
}
