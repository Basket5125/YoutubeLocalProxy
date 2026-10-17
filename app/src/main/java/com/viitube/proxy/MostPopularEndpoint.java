package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Most popular videos as a legacy Atom/GData feed: YouTube Data API v3 (chart=mostPopular,
 * once a day per region) when a key is set, otherwise Innertube (see
 * InnertubeEndpoint.fetchPopularFeed). Shorts (<= 60 s) are left out.
 */
public class MostPopularEndpoint implements Endpoint {

    static final String[][] CATEGORIES = {
            {"1", "Film & Animation"}, {"2", "Autos & Vehicles"}, {"10", "Music"},
            {"15", "Pets & Animals"}, {"17", "Sports"}, {"18", "Short Movies"},
            {"19", "Travel & Events"}, {"20", "Gaming"}, {"21", "Videoblogging"},
            {"22", "People & Blogs"}, {"23", "Comedy"}, {"24", "Entertainment"},
            {"25", "News & Politics"}, {"26", "Howto & Style"}, {"27", "Education"},
            {"28", "Science & Technology"}, {"29", "Nonprofits & Activism"},
            {"30", "Movies"}, {"31", "Anime & Animation"}, {"32", "Action & Adventure"},
            {"33", "Classics"}, {"34", "Comedy"}, {"35", "Documentary"},
            {"36", "Drama"}, {"37", "Family"}, {"38", "Foreign"}, {"39", "Horror"},
            {"40", "Sci-Fi & Fantasy"}, {"41", "Thriller"}, {"42", "Shorts"},
            {"43", "Shows"}, {"44", "Trailers"}
    };

    private static final long DATA_API_CACHE_TTL_MS = 24L * 60 * 60 * 1000L;
    private static final long INNERTUBE_CACHE_TTL_MS = 60L * 60 * 1000L;
    private static final int MAX_RESULTS = 20;
        private static final int MAX_API_RESULTS = 50;
    private static final int MAX_SHORTS_SECONDS = 60;
    private static final String USER_AGENT = "ViitubeProxy/1.0";

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                     Config config, Context context) throws IOException {

        // /standardfeeds/REGION/FEED[_Category] or region-less /standardfeeds/FEED[_Category]
        String[] path = params;
        String region;
        if (path.length > 0 && path[0] != null && path[0].matches("[A-Za-z]{2}")) {
            region = path[0].toUpperCase(Locale.US);
            path = java.util.Arrays.copyOfRange(path, 1, path.length);
        } else {
            region = config.getString("gl", "US").toUpperCase(Locale.US);
        }
        String requestedCategory = null;
        for (String segment : path) {
            String category = stripStandardFeedName(segment);
            if (!category.isEmpty()) requestedCategory = category;
        }
        if (requestedCategory == null || requestedCategory.isEmpty()) {
            requestedCategory = session.getParms().get("videoCategoryId");
        }
        if (requestedCategory == null || requestedCategory.isEmpty()) {
            requestedCategory = session.getParms().get("category");
        }
        String categoryId = resolveCategoryId(requestedCategory);
        if (requestedCategory != null && !requestedCategory.isEmpty() && categoryId.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain", "unknown video category");
        }
        String categoryName = categoryName(categoryId);
        int resultLimit = parsePositiveInt(session.getParms().get("max-results"), MAX_RESULTS,
                MAX_API_RESULTS);

        String apiKey = config.getYouTubeApiKey();
        boolean useDataApi = apiKey != null && !apiKey.isEmpty();
        // The Data API is asked once a day per region (quota); Innertube results change faster.
        long maxAge = useDataApi ? DATA_API_CACHE_TTL_MS : INNERTUBE_CACHE_TTL_MS;
        File cacheFile = HttpClient.cacheFile(context.getCacheDir(), "feeds",
                "most_popular_v4_" + (useDataApi ? "api_" : "it_") + region + "_" + categoryId
                        + "_" + resultLimit);
        if (HttpClient.isFresh(cacheFile, maxAge)) {
            return atomResponse(HttpClient.readFile(cacheFile), session);
        }

        if (useDataApi) {
            try {
                String xml = fetchAndBuildAtom(region, categoryId, categoryName, resultLimit, apiKey);
                if (xml != null && xml.contains("<entry>")) {
                    HttpClient.writeFile(cacheFile, xml);
                    return atomResponse(xml, session);
                }
            } catch (Exception e) {
                android.util.Log.w("MostPopularEndpoint", "Data API failed; using Innertube: " + e);
            }
        }

        try {
            String xml = InnertubeEndpoint.fetchPopularFeed(region, categoryName,
                    resultLimit, config, "__BASE_URL__", context);
            HttpClient.writeFile(cacheFile, xml);
            return atomResponse(xml, session);
        } catch (Exception e) {
            android.util.Log.w("MostPopularEndpoint", "Innertube popular feed failed: " + e);
        }

        // YouTube unreachable: serve the last feed, or an empty one so the client still starts.
        if (cacheFile.exists()) return atomResponse(HttpClient.readFile(cacheFile), session);
        return atomResponse(InnertubeEndpoint.buildFeed(new java.util.ArrayList<InnertubeEndpoint.Video>(),
                "YouTube Most Popular", "__BASE_URL__/feeds/api/standardfeeds/" + region
                        + "/most_popular", "__BASE_URL__"), session);
    }

        private NanoHTTPD.Response atomResponse(String xml, NanoHTTPD.IHTTPSession session) {
                String host = session.getHeaders().get("host");
                String baseUrl = "http://" + (host == null || host.isEmpty() ? "127.0.0.1" : host);
        return NanoHTTPD.newFixedLengthResponse(
                                NanoHTTPD.Response.Status.OK, "application/atom+xml", xml.replace("__BASE_URL__", baseUrl));
    }

    // ---------------------------------------------------------------------
        private String fetchAndBuildAtom(String region, String categoryId, String categoryName,
                                                                         int resultLimit, String apiKey) throws Exception {
        String url = "https://www.googleapis.com/youtube/v3/videos"
                + "?part=snippet,contentDetails,statistics"
                + "&chart=mostPopular"
                + "&regionCode=" + URLEncoder.encode(region, "UTF-8")
                + "&maxResults=" + Math.min(MAX_API_RESULTS, resultLimit * 2)
                                + (categoryId.isEmpty() ? "" : "&videoCategoryId=" + URLEncoder.encode(categoryId, "UTF-8"))
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
        String feedUrl = "__BASE_URL__/feeds/api/standardfeeds/" + region + "/most_popular"
                + (categoryId.isEmpty() ? "" : "?category=" + categoryId);
        sb.append("<feed xmlns='http://www.w3.org/2005/Atom'")
                .append(" xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'")
                .append(" xmlns:media='http://search.yahoo.com/mrss/'")
                .append(" xmlns:gd='http://schemas.google.com/g/2005'")
                .append(" xmlns:yt='http://gdata.youtube.com/schemas/2007'>\n");

        sb.append("  <id>").append(feedUrl).append("</id>\n");
        sb.append("  <updated>").append(nowIso).append("</updated>\n");
        sb.append("  <category scheme='http://schemas.google.com/g/2005#kind'")
                .append(" term='http://gdata.youtube.com/schemas/2007#video'/>\n");
        sb.append("  <title type='text'>YouTube Most Popular ")
                .append(categoryId.isEmpty() ? "Videos" : escapeXml(categoryName))
                .append(" in ").append(region).append("</title>\n");
        sb.append("  <logo>http://www.youtube.com/img/pic_youtubelogo_123x63.gif</logo>\n");
        sb.append("  <link rel='alternate' type='text/html' href='http://www.youtube.com'/>\n");
        sb.append("  <link rel='http://schemas.google.com/g/2005#feed' type='application/atom+xml' href='__BASE_URL__/feeds/api/videos'/>\n");
        sb.append("  <link rel='http://schemas.google.com/g/2005#batch' type='application/atom+xml' href='__BASE_URL__/feeds/api/videos/batch'/>\n");
        sb.append("  <link rel='hub' href='http://pubsubhubbub.appspot.com'/>\n");
        sb.append("  <author><name>YouTube/yt2009</name><uri>http://www.youtube.com/</uri></author>\n");
        sb.append("  <generator version='2.0' uri='__BASE_URL__/'>YouTube data API</generator>\n");
        sb.append("  <openSearch:totalResults>").append(items.length()).append("</openSearch:totalResults>\n");
        sb.append("  <openSearch:startIndex>1</openSearch:startIndex>\n");
        sb.append("  <openSearch:itemsPerPage>").append(resultLimit).append("</openSearch:itemsPerPage>\n");

        int emitted = 0;
        for (int i = 0; i < items.length() && emitted < resultLimit; i++) {
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
            String published = InnertubeEndpoint.toAtomDate(snippet.optString("publishedAt", ""));
            String escapedCategoryName = escapeXml(categoryName);
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
            if (!categoryId.isEmpty()) {
                sb.append("<category scheme='http://gdata.youtube.com/schemas/2007/categories.cat' label='")
                        .append(escapedCategoryName).append("' term='").append(categoryId).append("'>")
                        .append(escapedCategoryName).append("</category>\n");
            }
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
            if (!categoryId.isEmpty()) {
                sb.append("      <media:category label='").append(escapedCategoryName)
                        .append("' scheme='http://gdata.youtube.com/schemas/2007/categories.cat'>")
                        .append(escapedCategoryName).append("</media:category>\n");
            }
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

    /** Legacy chart names; all of them are served from the same popular videos. */
    private static final String[] STANDARD_FEEDS = {"most_popular", "most_viewed", "top_rated",
            "recently_featured", "most_discussed", "most_recent", "top_favorites",
            "most_responded", "most_shared", "most_linked", "trending_videos", "on_the_web",
            "watch_on_mobile"};

    /** "most_popular_Music" -> "Music", "top_rated" -> "", "Music" -> "Music". */
    private static String stripStandardFeedName(String segment) {
        if (segment == null) return "";
        for (String feed : STANDARD_FEEDS) {
            if (segment.equals(feed)) return "";
            if (segment.startsWith(feed + "_")) return segment.substring(feed.length() + 1);
        }
        return segment;
    }

        static String resolveCategoryId(String value) {
                if (value == null || value.trim().isEmpty()) return "";
        String normalized = normalizeCategory(value);
        String legacyId = legacyCategoryId(normalized);
        if (!legacyId.isEmpty()) return legacyId;
                for (String[] category : CATEGORIES) {
                        if (category[0].equals(value.trim())
                    || normalizeCategory(category[1]).equals(normalized)) return category[0];
                }
                return "";
        }

        static String categoryName(String id) {
                for (String[] category : CATEGORIES) {
                        if (category[0].equals(id)) return category[1];
                }
                return id.isEmpty() ? "" : "Category " + id;
        }

        private static String normalizeCategory(String value) {
                return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
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

        private static String legacyCategoryId(String category) {
                switch (category) {
                        case "autos": return "2";
                        case "comedy": return "23";
                        case "education": return "27";
                        case "entertainment": return "24";
                        case "film": return "1";
                        case "games": return "20";
                        case "music": return "10";
                        case "news": return "25";
                        case "nonprofit": return "29";
                        case "people": return "22";
                        case "animals": return "15";
                        case "tech": return "28";
                        case "sports": return "17";
                        case "travel": return "19";
                        case "howto": return "26";
                        default: return "";
                }
        }

}