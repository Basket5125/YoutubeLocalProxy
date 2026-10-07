package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * "Most Popular" z YouTube Data API v3 (chart=mostPopular),
 * filtr Shorts (<= 60s), konwersja do formatu Atom/GData,
 * cache na dysku 24h przez HttpClient.cacheFile / isFresh, z fallbackiem Innertube.
 */
public class MostPopularEndpoint implements Endpoint {

        private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000L;
    private static final int MAX_RESULTS = 20;
    private static final int MAX_SHORTS_SECONDS = 60;
    private static final String USER_AGENT = "ViitubeProxy/1.0";

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                     Config config, Context context) throws IOException {

        String region = (params.length > 0 && params[0] != null && !params[0].isEmpty())
                ? params[0].toUpperCase(Locale.US) : "US";

        // cache: <cacheDir>/feeds/most_popular_US.json  (rozszerzenie .json narzuca cacheFile)
        File cacheFile = HttpClient.cacheFile(context.getCacheDir(), "feeds", "most_popular_v3_" + region);

        // 1) Cache hit (5h)?
        if (HttpClient.isFresh(cacheFile, CACHE_TTL_MS)) {
                        return atomResponse(HttpClient.readFile(cacheFile), session);
        }

                // Keep one Data API request per region per day; use Innertube when unavailable.
        try {
            String apiKey = config.getYouTubeApiKey();
                        if (apiKey != null && !apiKey.isEmpty()) {
                                String xml = fetchAndBuildAtom(region, apiKey);
                                if (xml != null && !xml.isEmpty()) {
                                        HttpClient.writeFile(cacheFile, xml);
                                        return atomResponse(xml, session);
                                }
            }
                } catch (Exception e) {
                        e.printStackTrace();
                }

                try {
                        String xml = InnertubeEndpoint.fetchPopularFeed(region, config,
                                "__BASE_URL__", context);
            if (xml != null && !xml.isEmpty()) {
                HttpClient.writeFile(cacheFile, xml);
                return atomResponse(xml, session);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

                // Fallback: stale cache before the bundled sample feed.
                if (cacheFile.exists()) return atomResponse(HttpClient.readFile(cacheFile), session);

        return atomResponse(readAsset(context, "feeds/most_popular_default.xml"), session);
    }

        private NanoHTTPD.Response atomResponse(String xml, NanoHTTPD.IHTTPSession session) {
                String host = session.getHeaders().get("host");
                String baseUrl = "http://" + (host == null || host.isEmpty() ? "127.0.0.1" : host);
        return NanoHTTPD.newFixedLengthResponse(
                                NanoHTTPD.Response.Status.OK, "application/atom+xml", xml.replace("__BASE_URL__", baseUrl));
    }

    // ---------------------------------------------------------------------
    private String fetchAndBuildAtom(String region, String apiKey) throws Exception {
        String url = "https://www.googleapis.com/youtube/v3/videos"
                + "?part=snippet,contentDetails,statistics"
                + "&chart=mostPopular"
                + "&regionCode=" + URLEncoder.encode(region, "UTF-8")
                + "&maxResults=" + (MAX_RESULTS * 2)
                + "&key=" + URLEncoder.encode(apiKey, "UTF-8");

        String json = HttpClient.getUrl(url, USER_AGENT);
        JSONObject root = new JSONObject(json);
        JSONArray items = root.optJSONArray("items");
        if (items == null) items = new JSONArray();

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String nowIso = iso.format(new Date());

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<?xml version='1.0' encoding='UTF-8'?>\n");
        String feedUrl = "__BASE_URL__/feeds/api/standardfeeds/" + region + "/most_popular";
        sb.append("<feed xmlns='http://www.w3.org/2005/Atom'")
                .append(" xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'")
                .append(" xmlns:media='http://search.yahoo.com/mrss/'")
                .append(" xmlns:gd='http://schemas.google.com/g/2005'")
                .append(" xmlns:yt='http://gdata.youtube.com/schemas/2007'>\n");

        sb.append("  <id>").append(feedUrl).append("</id>\n");
        sb.append("  <updated>").append(nowIso).append("</updated>\n");
        sb.append("  <category scheme='http://schemas.google.com/g/2005#kind'")
                .append(" term='http://gdata.youtube.com/schemas/2007#video'/>\n");
        sb.append("  <title type='text'>YouTube Most Popular Videos in ").append(region).append("</title>\n");
        sb.append("  <logo>http://www.youtube.com/img/pic_youtubelogo_123x63.gif</logo>\n");
        sb.append("  <link rel='alternate' type='text/html' href='http://www.youtube.com'/>\n");
        sb.append("  <link rel='http://schemas.google.com/g/2005#feed' type='application/atom+xml' href='__BASE_URL__/feeds/api/videos'/>\n");
        sb.append("  <link rel='http://schemas.google.com/g/2005#batch' type='application/atom+xml' href='__BASE_URL__/feeds/api/videos/batch'/>\n");
        sb.append("  <link rel='hub' href='http://pubsubhubbub.appspot.com'/>\n");
        sb.append("  <author><name>YouTube/yt2009</name><uri>http://www.youtube.com/</uri></author>\n");
        sb.append("  <generator version='2.0' uri='__BASE_URL__/'>YouTube data API</generator>\n");
        sb.append("  <openSearch:totalResults>").append(items.length()).append("</openSearch:totalResults>\n");
        sb.append("  <openSearch:startIndex>1</openSearch:startIndex>\n");
        sb.append("  <openSearch:itemsPerPage>").append(MAX_RESULTS).append("</openSearch:itemsPerPage>\n");

        int emitted = 0;
        for (int i = 0; i < items.length() && emitted < MAX_RESULTS; i++) {
            JSONObject v = items.getJSONObject(i);
            JSONObject snippet = v.optJSONObject("snippet");
            JSONObject contentDetails = v.optJSONObject("contentDetails");
            JSONObject stats = v.optJSONObject("statistics");
            if (snippet == null || contentDetails == null) continue;

            // filtr Shorts (<= 60s)
            String iso8601 = contentDetails.optString("duration", "");
            int seconds = parseIso8601Duration(iso8601);
            if (seconds > 0 && seconds <= MAX_SHORTS_SECONDS) continue;

            String videoId   = v.optString("id");
            String title     = snippet.optString("title", "");
            String desc      = snippet.optString("description", "");
            String channel   = snippet.optString("channelTitle", "");
            String channelId = snippet.optString("channelId", "");
            String published = snippet.optString("publishedAt", "");
            String categoryName = "Science &amp; Technology";
            if (!InnertubeEndpoint.isValidVideoId(videoId) || title.trim().isEmpty()
                    || channel.trim().isEmpty() || channelId.trim().isEmpty()
                    || channel.trim().equals(channelId.trim())) {
                android.util.Log.w("MostPopularEndpoint",
                        "Skipping incomplete or malformed popular video entry");
                continue;
            }

            long viewCount = 0, likeCount = 0, favCount = 0;
            if (stats != null) {
                viewCount = parseLong(stats.optString("viewCount"));
                likeCount = parseLong(stats.optString("likeCount"));
                favCount  = parseLong(stats.optString("favoriteCount"));
            }

            String etag = v.optString("etag", "");
            String uploaderUserId = channelId;

            sb.append("  <entry gd:etag='").append(escapeXml(etag)).append("'>\n");
            sb.append("    <id>__BASE_URL__/feeds/api/videos/").append(escapeXml(videoId)).append("</id>\n");
            sb.append("    <youTubeId id='").append(escapeXml(videoId)).append("'>")
                    .append(escapeXml(videoId)).append("</youTubeId>\n");
            sb.append("    <published>").append(escapeXml(published)).append("</published>\n");
            sb.append("    <updated>").append(escapeXml(published)).append("</updated>\n");
            sb.append("    <category scheme='http://schemas.google.com/g/2005#kind' term='http://gdata.youtube.com/schemas/2007#video'/>");
            sb.append("<category scheme='http://gdata.youtube.com/schemas/2007/categories.cat' label='")
                    .append(categoryName).append("' term='").append(categoryName).append("'>Science &amp; Technology</category>\n");
            sb.append("    <title type='text'>").append(escapeXml(title)).append("</title>\n");
            sb.append("    <content type='text'>").append(escapeXml(desc)).append("</content>\n");
            sb.append("    <link rel='alternate' type='text/html' href='http://www.youtube.com/watch?v=")
                    .append(videoId).append("&amp;feature=youtube_gdata'/>\n");
            sb.append("    <link rel='http://gdata.youtube.com/schemas/2007#video.related'")
                    .append(" href='__BASE_URL__/feeds/api/videos/")
                    .append(escapeXml(videoId)).append("/related'/>\n");
            sb.append("    <link rel='http://gdata.youtube.com/schemas/2007#mobile' type='text/html'")
                    .append(" href='http://m.youtube.com/details?v=").append(videoId).append("'/>\n");
            sb.append("    <link rel='http://gdata.youtube.com/schemas/2007#uploader'")
                    .append(" type='application/atom+xml' href='__BASE_URL__/feeds/api/users/")
                    .append(channelId).append("?v=2'/>\n");
            sb.append("    <link rel='self' type='application/atom+xml'")
                    .append(" href='__BASE_URL__/feeds/api/users/")
                    .append(channelId).append("/uploads/").append(videoId).append("?v=2'/>\n");
            sb.append("    <author><name>").append(escapeXml(channel)).append("</name>")
                    .append("<uri>__BASE_URL__/feeds/api/users/").append(escapeXml(channelId)).append("</uri>")
                    .append("<yt:userId>").append(uploaderUserId).append("</yt:userId></author>\n");

            for (String[] ac : new String[][]{
                    {"comment", "allowed"}, {"commentVote", "allowed"},
                    {"videoRespond", "moderated"}, {"rate", "allowed"},
                    {"embed", "allowed"}, {"list", "allowed"},
                    {"autoPlay", "allowed"}, {"syndicate", "allowed"}}) {
                sb.append("    <yt:accessControl action='").append(ac[0])
                        .append("' permission='").append(ac[1]).append("'/>\n");
            }

            sb.append("    <gd:comments><gd:feedLink href='__BASE_URL__/feeds/api/videos/")
                    .append(escapeXml(videoId)).append("/comments' countHint='0'/></gd:comments>\n");

            sb.append("    <media:group>\n");
            sb.append("      <media:title>").append(escapeXml(title)).append("</media:title>\n");
            sb.append("      <media:category label='").append(categoryName)
                    .append("' scheme='http://gdata.youtube.com/schemas/2007/categories.cat'>Science &amp; Technology</media:category>\n");
                    sb.append("      <media:content url='__BASE_URL__/video/").append(escapeXml(videoId))
                            .append("' type='video/3gpp' medium='video' expression='full' duration='")
                            .append(seconds).append("' yt:format='3'/>\n");
            sb.append("      <media:credit role='uploader' yt:display='")
                    .append(escapeXml(channel)).append("' name='").append(escapeXml(channelId)).append("'>").append(escapeXml(channelId))
                    .append("</media:credit>\n");
            sb.append("      <media:description type='plain'>").append(escapeXml(desc))
                    .append("</media:description>\n");
            sb.append("      <media:keywords>").append(escapeXml(title)).append("</media:keywords>\n");
            sb.append("      <media:license type='text/html' href='http://www.youtube.com/t/terms'>youtube</media:license>\n");
            sb.append("      <media:player url='http://www.youtube.com/watch?v=").append(videoId)
                    .append("&amp;feature=youtube_gdata_player'/>\n");

            appendThumb(sb, videoId, "default",   120,  90, null);
            appendThumb(sb, videoId, "mqdefault", 320, 180, null);
            appendThumb(sb, videoId, "hqdefault", 480, 360, null);
            appendThumb(sb, videoId, "sddefault", 640, 480, null);
            appendThumb(sb, videoId, "1", 120, 90, formatHms(seconds / 5));
            appendThumb(sb, videoId, "2", 120, 90, formatHms(seconds / 2));
            appendThumb(sb, videoId, "3", 120, 90, formatHms(seconds * 4 / 5));

            sb.append("      <yt:duration seconds='").append(seconds).append("'/>");
            sb.append("      <yt:uploaded>").append(escapeXml(published)).append("</yt:uploaded>\n");
            sb.append("      <yt:uploaderId>").append(uploaderUserId).append("</yt:uploaderId>\n");
            sb.append("      <yt:videoid id='").append(escapeXml(videoId)).append("'>")
                    .append(escapeXml(videoId)).append("</yt:videoid>\n");
            sb.append("      <youTubeId id='").append(escapeXml(videoId)).append("'>")
                    .append(escapeXml(videoId)).append("</youTubeId>\n");
            sb.append("    </media:group>\n");

            sb.append("    <gd:rating average='0' max='5' min='1' numRaters='0'")
                    .append(" rel='http://schemas.google.com/g/2005#overall'/>\n");
            sb.append("    <yt:statistics favoriteCount='").append(favCount)
                    .append("' viewCount='").append(viewCount).append("'/>\n");
            sb.append("    <yt:rating numDislikes='0' numLikes='").append(likeCount).append("'/>\n");
            sb.append("  </entry>\n");

            emitted++;
        }

        sb.append("</feed>\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    private void appendThumb(StringBuilder sb, String videoId, String name,
                             int w, int h, String time) {
        sb.append("      <media:thumbnail url='http://i.ytimg.com/vi/").append(videoId)
                .append("/").append(name).append(".jpg' height='").append(h)
                .append("' width='").append(w).append("'");
        if (time != null) sb.append(" time='").append(time).append("'");
        sb.append(" yt:name='").append(name).append("'/>\n");
    }

    private String formatHms(int totalSeconds) {
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    private int parseIso8601Duration(String d) {
        if (d == null || d.isEmpty()) return 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")
                    .matcher(d);
            if (!m.matches()) return 0;
            int h = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
            int mi = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            int s = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
            return h * 3600 + mi * 60 + s;
        } catch (Exception e) { return 0; }
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String readAsset(Context context, String path) throws IOException {
        InputStream in = context.getAssets().open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }
}