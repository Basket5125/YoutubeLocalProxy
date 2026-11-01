package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Subtitles. The apps ask for the list of tracks of a video and then for one track, the way
 * YouTube's own /api/timedtext did it, and get back the plain transcript XML - the shape every
 * one of these apps can read (`/transcript/text` of the parser in YouTube 12.45.56, class abar).
 * Today's YouTube answers with `<timedtext format="3">` instead, so the cues are turned around
 * here.
 */
public class CaptionsEndpoint implements Endpoint {

    private final String mode;

    public CaptionsEndpoint() {
        this("timedtext");
    }

    public CaptionsEndpoint(String mode) {
        this.mode = mode;
    }

    private static final String TAG = "Captions";
    /** A track rarely changes, and converting it costs a request to YouTube. */
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    /** One subtitle track of a video. */
    static final class Track {
        String url = "";
        String name = "";
        String languageCode = "";
        String languageName = "";
        String vssId = "";
        String kind = "";
        boolean translatable;
    }

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                     Config config, Context context) throws Exception {
        Map<String, String> query = session.getParms();
        String videoId = query.get("v");
        if (videoId == null) videoId = query.get("video_id");
        // The feed of the GData apps names the video in the path instead of a parameter.
        if (videoId == null && params != null && params.length > 0) videoId = params[0];
        if (videoId == null || !InnertubeEndpoint.isValidVideoId(videoId)) {
            return xml("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<transcript_list/>");
        }
        List<Track> tracks = tracks(videoId, config, context);
        if ("gdata".equals(mode)) {
            String host = session.getHeaders().get("host");
            return feed(videoId, tracks, "http://" + (host == null ? "127.0.0.1:8080" : host));
        }
        if ("list".equals(query.get("type"))) return xml(trackList(videoId, tracks));

        String language = query.get("lang");
        if (language == null) language = query.get("hl");
        Track track = pick(tracks, language, query.get("name"), query.get("kind"));
        if (track == null) {
            android.util.Log.i(TAG, "no track " + language + " for " + videoId);
            return xml("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<transcript/>");
        }
        return xml(transcript(videoId, track, config, context));
    }

    /** The tracks of a video, read out of the same player answer the streams come from. */
    static List<Track> tracks(String videoId, Config config, Context context) {
        JSONObject player;
        try {
            player = PlayerLookup.fetchPlaybackPlayer(videoId, config, "360p");
        } catch (Exception e) {
            android.util.Log.w(TAG, "No player answer for the subtitles of " + videoId + ": " + e);
            return new ArrayList<Track>();
        }
        return tracksOf(player);
    }

    /** The tracks of a player answer that has already been read. */
    static List<Track> tracksOf(JSONObject player) {
        List<Track> tracks = new ArrayList<Track>();
        if (player == null) return tracks;
        JSONObject captions = player.optJSONObject("captions");
        JSONObject list = captions == null
                ? null : captions.optJSONObject("playerCaptionsTracklistRenderer");
        JSONArray entries = list == null ? null : list.optJSONArray("captionTracks");
        if (entries == null) return tracks;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            Track track = new Track();
            track.url = entry.optString("baseUrl", "");
            track.languageCode = entry.optString("languageCode", "");
            track.vssId = entry.optString("vssId", "");
            track.kind = entry.optString("kind", "");
            track.translatable = entry.optBoolean("isTranslatable", false);
            track.name = entry.optString("trackName", "");
            track.languageName = text(entry.optJSONObject("name"));
            if (track.languageName.isEmpty()) track.languageName = track.languageCode;
            if (!track.url.isEmpty() && !track.languageCode.isEmpty()) tracks.add(track);
        }
        return tracks;
    }

    /** The track the app asked for, or the first one of that language. */
    private static Track pick(List<Track> tracks, String language, String name, String kind) {
        if (tracks.isEmpty()) return null;
        if (language == null || language.isEmpty()) return tracks.get(0);
        Track sameLanguage = null;
        for (Track track : tracks) {
            if (!track.languageCode.equalsIgnoreCase(language)) continue;
            boolean nameMatches = name == null || name.isEmpty() || name.equals(track.name);
            boolean kindMatches = kind == null || kind.isEmpty() || kind.equals(track.kind);
            if (nameMatches && kindMatches) return track;
            if (sameLanguage == null) sameLanguage = track;
        }
        if (sameLanguage != null) return sameLanguage;
        // A country variant of what was asked for ("de" against "de-DE") still beats nothing.
        for (Track track : tracks) {
            String code = track.languageCode;
            if (code.toLowerCase(java.util.Locale.US).startsWith(
                    language.toLowerCase(java.util.Locale.US))) {
                return track;
            }
        }
        return null;
    }

    /** The list of tracks, as the apps read it. */
    private static String trackList(String videoId, List<Track> tracks) {
        StringBuilder out = new StringBuilder(256);
        out.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<transcript_list docid=\"")
                .append(XmlUtil.escape(videoId)).append("\">");
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            out.append("<track id=\"").append(i)
                    .append("\" name=\"").append(XmlUtil.escape(track.name))
                    .append("\" lang_code=\"").append(XmlUtil.escape(track.languageCode))
                    .append("\" lang_original=\"").append(XmlUtil.escape(track.languageName))
                    .append("\" lang_translated=\"").append(XmlUtil.escape(track.languageName))
                    .append("\" cantran=\"").append(track.translatable ? "true" : "false");
            if (!track.vssId.isEmpty()) out.append("\" vss_id=\"").append(XmlUtil.escape(track.vssId));
            if (!track.kind.isEmpty()) out.append("\" kind=\"").append(XmlUtil.escape(track.kind));
            out.append("\"/>");
        }
        return out.append("</transcript_list>").toString();
    }

    /** The same tracks as the caption feed of the GData API, for the apps that ask for that. */
    private static NanoHTTPD.Response feed(String videoId, List<Track> tracks, String baseUrl) {
        StringBuilder out = new StringBuilder(512);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<feed xmlns=\"http://www.w3.org/2005/Atom\" ")
                .append("xmlns:yt=\"http://gdata.youtube.com/schemas/2007\">")
                .append("<id>tag:youtube.com,2008:video:").append(XmlUtil.escape(videoId))
                .append(":captions</id>")
                .append("<title>Caption tracks</title>")
                .append("<openSearch:totalResults xmlns:openSearch=\"http://a9.com/-/spec/opensearch/1.1/\">")
                .append(tracks.size()).append("</openSearch:totalResults>");
        for (Track track : tracks) {
            String url = proxyUrl(baseUrl, videoId, track);
            out.append("<entry xml:lang=\"").append(XmlUtil.escape(track.languageCode))
                    .append("\"><id>tag:youtube.com,2008:video:").append(XmlUtil.escape(videoId))
                    .append(":caption:").append(XmlUtil.escape(track.vssId)).append("</id>")
                    .append("<title>").append(XmlUtil.escape(track.languageName)).append("</title>")
                    .append("<content type=\"application/vnd.youtube.timedtext; charset=UTF-8\" src=\"")
                    .append(XmlUtil.escape(url)).append("\"/>")
                    .append("<yt:derived>").append(track.kind.isEmpty() ? "uploaded" : "speechRecognition")
                    .append("</yt:derived>")
                    .append("<link rel=\"alternate\" type=\"application/xml\" href=\"")
                    .append(XmlUtil.escape(url)).append("\"/></entry>");
        }
        out.append("</feed>");
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "application/atom+xml; charset=utf-8", out.toString());
    }

    /** One track as the plain transcript the apps parse, kept on this phone for a day. */
    static String transcript(String videoId, Track track, Config config, Context context) {
        String key = videoId + "_" + track.languageCode + "_" + track.kind + "_" + track.name;
        File cache = context == null
                ? null : HttpClient.cacheFile(context.getCacheDir(), "captions_v1", key);
        if (cache != null && HttpClient.isFresh(cache, MAX_AGE_MS)) {
            try {
                return HttpClient.readFile(cache);
            } catch (Exception ignored) {
            }
        }
        String converted;
        try {
            String userAgent = config.getString("user_agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:102.0) Gecko/20100101 Firefox/102.0");
            converted = toTranscript(HttpClient.getUrl(track.url, userAgent));
        } catch (Exception e) {
            android.util.Log.w(TAG, "Subtitles of " + videoId + " (" + track.languageCode
                    + ") failed: " + e);
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<transcript/>";
        }
        if (cache != null) {
            try {
                HttpClient.writeFile(cache, converted);
            } catch (Exception ignored) {
            }
        }
        return converted;
    }

    /**
     * YouTube's answer is `<timedtext format="3"><body><p t="1360" d="1680">text</p>`, with the
     * words of a track it wrote itself split into `<s>` pieces inside the line. The apps read
     * `<transcript><text start="1.36" dur="1.68">`, so the times become seconds and the pieces
     * are put back into one line.
     */
    static String toTranscript(String timedtext) {
        StringBuilder out = new StringBuilder(Math.max(256, timedtext.length() / 2));
        out.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<transcript>");
        java.util.regex.Matcher cue = java.util.regex.Pattern
                .compile("<p\\b([^>]*)>(.*?)</p>", java.util.regex.Pattern.DOTALL)
                .matcher(timedtext);
        boolean found = false;
        while (cue.find()) {
            String attributes = cue.group(1);
            String body = cue.group(2);
            long start = attribute(attributes, "t");
            long duration = attribute(attributes, "d");
            if (start < 0) continue;
            String text = body.replaceAll("<[^>]*>", "").trim();
            if (text.isEmpty()) continue;
            found = true;
            out.append("<text start=\"").append(seconds(start)).append('"');
            if (duration > 0) out.append(" dur=\"").append(seconds(duration)).append('"');
            out.append('>').append(text).append("</text>");
        }
        // An answer that already is a transcript is passed on as it is.
        if (!found && timedtext.contains("<transcript")) return timedtext;
        return out.append("</transcript>").toString();
    }

    /** The value of one attribute of a cue, in milliseconds, or -1. */
    private static long attribute(String attributes, String name) {
        java.util.regex.Matcher value = java.util.regex.Pattern
                .compile("\\b" + name + "=\"(-?\\d+)\"").matcher(attributes);
        if (!value.find()) return -1;
        try {
            return Long.parseLong(value.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String seconds(long milliseconds) {
        return String.format(java.util.Locale.US, "%d.%03d", milliseconds / 1000,
                Math.abs(milliseconds % 1000));
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

    /**
     * Where this proxy serves a track. The apps fetch the link of a track as it is, so it has to
     * be one of ours; the track itself is named by its language, and by its kind when YouTube
     * wrote it rather than the channel.
     */
    static String proxyUrl(String baseUrl, String videoId, Track track) {
        StringBuilder url = new StringBuilder(baseUrl).append("/api/timedtext?v=").append(videoId)
                .append("&lang=").append(encode(track.languageCode));
        if (!track.kind.isEmpty()) url.append("&kind=").append(encode(track.kind));
        if (!track.name.isEmpty()) url.append("&name=").append(encode(track.name));
        return url.toString();
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    private static NanoHTTPD.Response xml(String body) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "text/xml; charset=utf-8", body);
    }
}
