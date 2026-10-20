package com.viitube.proxy;

import com.viitube.proxy.proto.AndroidFullNextProto;
import com.viitube.proxy.proto.AndroidGuideProto;
import com.viitube.proxy.proto.AndroidPlayerProto;
import com.viitube.proxy.proto.AndroidResolveProto;
import com.viitube.proxy.proto.AndroidSearchProto;
import com.viitube.proxy.proto.VideoListProto;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds the protobuf answers of the Innertube API used by the 2015-2016 YouTube Android apps
 * (/youtubei/v1/browse, next, player, search, guide, navigation/resolve_url). The message
 * layouts and the values the apps expect come from the gdatav2innertube server; this class only
 * turns data into messages and does no network access, so it can be tested on a desktop JVM.
 */
final class YoutubeiResponses {

    static final String DEFAULT_AVATAR = "https://yt3.ggpht.com/a/default-user=s88-c-k-c0x00ffffff-no-rj";

    /**
     * Apps from this version on get the classic bottom bar (Home, Trending, Library, Account)
     * instead of tabs above the feed, and the watch page of the 2017 apps.
     */
    static final int PIVOT_BAR_VERSION = 12;

    // IconType values of the 2017 apps (the enum of YouTube 12.45.56). Only the ones the app has
    // a drawable for are used; it draws an unknown icon as an empty image.
    private static final int ICON_MIX = 17;
    private static final int ICON_DISLIKE = 51;
    private static final int ICON_TAB_TRENDING = 67;
    private static final int ICON_TAB_ACCOUNT = 69;
    private static final int ICON_LIKE = 160;
    /** SHARE_ARROW: the curved arrow (ic_share); SHARE (48) is the three dots instead. */
    private static final int ICON_SHARE = 100;
    private static final int ICON_ADDTO = 46;
    /** The bottom bar's own icons */
    private static final int ICON_PIVOT_SUBSCRIPTIONS = 408;
    private static final int ICON_PIVOT_HOME = 406;
    private static final int ICON_PIVOT_TRENDING = 407;
    private static final int ICON_PIVOT_LIBRARY = 410;

    private YoutubeiResponses() { }

    // ------------------------------------------------------------------ text formatting

    /** "1.2M views", like formatViewCount() in the reference server. */
    static String shortCount(long count, String unit) {
        if (count >= 1_000_000_000L) return oneDecimal(count / 1_000_000_000d) + "B " + unit;
        if (count >= 1_000_000L) return oneDecimal(count / 1_000_000d) + "M " + unit;
        if (count >= 1_000L) return oneDecimal(count / 1_000d) + "K " + unit;
        return count + " " + unit;
    }

    /** "1.2M", the label YouTube put on the like button. */
    static String shortNumber(long count) {
        return shortCount(count, "").trim();
    }

    private static String oneDecimal(double value) {
        String text = String.format(Locale.US, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** "1,234,567 views" / "1 view", the long form YouTube showed under videos. */
    static String fullCount(long count, String singular, String plural) {
        return String.format(Locale.US, "%,d", count) + " " + (count == 1 ? singular : plural);
    }

    static long parseCount(String value) {
        try {
            return Long.parseLong(InnertubeEndpoint.numericCount(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** m:ss or h:mm:ss; empty when the length is unknown (live streams). */
    static String lengthText(String duration) {
        int seconds = InnertubeEndpoint.durationSecondsPublic(duration);
        if (seconds <= 0) return "";
        int hours = seconds / 3600;
        int minutes = seconds % 3600 / 60;
        int rest = seconds % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest);
        return String.format(Locale.US, "%d:%02d", minutes, rest);
    }

    /** "3 days ago" for feed items; YouTube's relative labels are passed through. */
    static String relativeTime(String published, long now) {
        if (published == null || published.trim().isEmpty()) return "";
        String text = published.trim();
        // Related videos use compact labels ("1d ago", "2mo ago"); the apps showed "1 day ago".
        boolean compact = COMPACT_AGE.matcher(text).matches();
        if (!compact && !text.matches("\\d{4}-\\d{2}-\\d{2}.*")) return text;
        long time = parseAtomDate(InnertubeEndpoint.toAtomDate(text));
        if (time <= 0) return "";
        long minutes = Math.max(0, (now - time) / 60_000L);
        if (minutes < 60) return plural(Math.max(1, minutes), "minute") + " ago";
        long hours = minutes / 60;
        if (hours < 24) return plural(hours, "hour") + " ago";
        long days = hours / 24;
        if (days < 7) return plural(days, "day") + " ago";
        if (days < 30) return plural(days / 7, "week") + " ago";
        if (days < 365) return plural(days / 30, "month") + " ago";
        return plural(days / 365, "year") + " ago";
    }

    private static final java.util.regex.Pattern COMPACT_AGE =
            java.util.regex.Pattern.compile("\\d+\\s*(?:s|m|h|d|w|mo|y) ago");

    private static String plural(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }

    /** "Published on Oct 3, 2024" from an ISO date, or from YouTube's own date label. */
    static String publishedOn(String published, String dateText) {
        if (published != null && published.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            long time = parseAtomDate(InnertubeEndpoint.toAtomDate(published));
            if (time > 0) {
                SimpleDateFormat format = new SimpleDateFormat("MMM d, yyyy", Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                return "Published on " + format.format(new java.util.Date(time));
            }
        }
        if (dateText != null && !dateText.isEmpty()) {
            return dateText.matches("[A-Z][a-z]{2} \\d{1,2}, \\d{4}") ? "Published on " + dateText : dateText;
        }
        return "";
    }

    private static long parseAtomDate(String atom) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(atom).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String hqThumbnail(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    /** The wide picture the feed cards show; YouTube falls back to the smaller file itself. */
    private static String maxresThumbnail(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/maxresdefault.jpg";
    }

    private static String avatarOrDefault(String avatar) {
        return avatar == null || avatar.isEmpty() ? DEFAULT_AVATAR : avatar;
    }

    // ------------------------------------------------------------------ browse (video_list_proto)

    /** A line the app draws in bold, like the subscribe button's own label. */
    private static VideoListProto.TextRuns browseRunsBold(String text) {
        return new VideoListProto.TextRuns().addRun(
                new VideoListProto.TextRuns.TextRun().setText(text).setBold(1));
    }

    private static VideoListProto.TextRuns browseRuns(String text) {
        return new VideoListProto.TextRuns().addRun(new VideoListProto.TextRuns.TextRun().setText(text));
    }

    private static VideoListProto.Thumbnails browseThumbnails(String url, int width, int height) {
        return new VideoListProto.Thumbnails().addThumb(new VideoListProto.Thumbnails.Thumbnail()
                .setUrl(url).setWidth(width).setHeight(height));
    }

    private static VideoListProto.BrowseNavigation browseNavigation(String browseId, String params,
                                                                   String canonicalBaseUrl) {
        VideoListProto.BrowseNavigation.BrowseData data = new VideoListProto.BrowseNavigation.BrowseData()
                .setBrowseId(browseId);
        if (params != null && !params.isEmpty()) data.setParams(params);
        if (canonicalBaseUrl != null && !canonicalBaseUrl.isEmpty()) data.setCanonicalBaseUrl(canonicalBaseUrl);
        return new VideoListProto.BrowseNavigation().setData(data);
    }

    private static VideoListProto.AuthorData browseAuthor(String name, String channelId) {
        VideoListProto.AuthorData.AuthorDataContent content = new VideoListProto.AuthorData.AuthorDataContent()
                .setAuthorDisplayName(name);
        if (channelId != null && !channelId.isEmpty()) {
            content.setNavigation(browseNavigation(channelId, null, "/channel/" + channelId));
        }
        return new VideoListProto.AuthorData().setAc(content);
    }

    /** generateVideo() of the reference server. */
    static VideoListProto.CompactVideoRenderer browseVideo(InnertubeEndpoint.Video video,
                                                          String authorAvatar, long now) {
        long views = parseCount(video.views);
        VideoListProto.AuthorData author = browseAuthor(video.channel, video.channelId);
        return new VideoListProto.CompactVideoRenderer()
                .setVideoId(video.id)
                .setThumblist(browseThumbnails(hqThumbnail(video.id), 480, 360))
                .setTitleData(browseRuns(video.title))
                .setPublishedTimeText(browseRuns(relativeTime(video.published, now)))
                .setViewCountText(browseRuns(fullCount(views, "view", "views")))
                .setLengthText(browseRuns(lengthText(video.duration)))
                .setShortViewCounts(browseRuns(shortCount(views, "views")))
                .setShortAuthorData(author)
                .setLongAuthorData(author)
                .setNavData(new VideoListProto.NavigationData().setNavProperties(
                        new VideoListProto.NavigationData.NavData().setNavId(video.id)))
                .setAuthorAvatar(browseThumbnails(avatarOrDefault(authorAvatar), 88, 88));
    }

    /** generatePlaylist() of the reference server. */
    static VideoListProto.PlaylistRenderer browsePlaylist(String id, String title, String author,
                                                         String authorId, String count,
                                                         String firstVideoId, String thumbnail) {
        String thumbUrl = firstVideoId != null && !firstVideoId.isEmpty() ? hqThumbnail(firstVideoId)
                : thumbnail != null && !thumbnail.isEmpty() ? InnertubeEndpoint.absoluteUrl(thumbnail)
                : DEFAULT_AVATAR;
        VideoListProto.AuthorData owner = browseAuthor(author, authorId);
        String videoCount = count + " videos";
        return new VideoListProto.PlaylistRenderer()
                .setId(id)
                .setPlaylistName(browseRuns(title))
                .setThumbnail(browseThumbnails(thumbUrl, 480, 360))
                .setSeparatedThumbnail(browseThumbnails(thumbUrl, 480, 360))
                .setShortOwner(owner)
                .setLongOwner(owner)
                .setVideoCount(browseRuns(videoCount))
                .setVideoCount2(browseRuns(videoCount))
                .setPlaylistBrowse(browseNavigation("VL" + id, null, null))
                .setBareVideoCount(browseRuns(count))
                .setWebUrl("https://www.youtube.com/playlist?list=" + id);
    }

    private static VideoListProto.Root browseRoot() {
        VideoListProto.Root.ContextType.ClientType client = new VideoListProto.Root.ContextType.ClientType()
                .setService(6)
                .addParam(new VideoListProto.Root.ContextType.ClientType.ClientParam()
                        .setName("client.name").setValue("ANDROID"))
                .addParam(new VideoListProto.Root.ContextType.ClientType.ClientParam()
                        .setName("client.version").setValue("1.0"));
        return new VideoListProto.Root().addContext(new VideoListProto.Root.ContextType()
                .addClient(client).setMaxAgeSeconds(300));
    }

    private static VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs tab(
            String identifier, String title, boolean selected, int icon,
            VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                    .ContentEntry.ContentData.ContentActualData content) {
        return tab(identifier, title, selected, icon, content, null);
    }

    /**
     * One tab. With a continuation token the app loads the next entries when the list is
     * scrolled to its end and sends the token back with the browse request.
     */
    private static VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs tab(
            String identifier, String title, boolean selected, int icon,
            VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                    .ContentEntry.ContentData.ContentActualData content, String continuationToken) {
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer renderer =
                new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer()
                        .addContentsEntry(new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer
                                .Tabs.TabRenderer.ContentEntry().addContentArray(
                                        sectionList(content, continuationToken)))
                        .setSelected(selected ? 1 : 0)
                        .setTabIdentifier(identifier)
                        .setTabTitle(title);
        if (icon > 0) {
            renderer.addIconData(new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer
                    .Tabs.TabRenderer.Icon().setIconType(icon));
        }
        return new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs().addTabRender(renderer);
    }

    /** The section list of a tab, with the token for its next page when there is one. */
    private static VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer.ContentEntry.ContentData sectionList(
            VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer.ContentEntry.ContentData.ContentActualData content, String continuationToken) {
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer.ContentEntry.ContentData data =
                new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer.ContentEntry.ContentData().addC(content);
        if (continuationToken != null && !continuationToken.isEmpty()) {
            data.addLoadCommand(new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer.ContentEntry.ContentData.LoadData().addNext(
                    new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer.ContentEntry.ContentData.LoadData.NextContinuationData()
                            .setToken(continuationToken)));
        }
        return data;
    }

    /** One item section holding every video, as the channel tabs and their next pages use. */
    private static VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer.ContentEntry.ContentData.ContentActualData videoSection(
            List<InnertubeEndpoint.Video> videos, String authorAvatar, long now) {
        VideoListProto.ItemSectionRenderer section = new VideoListProto.ItemSectionRenderer();
        for (InnertubeEndpoint.Video video : videos) {
            section.addIcsContent(new VideoListProto.ItemSectionRenderer.IcsContents()
                    .addVideo(browseVideo(video, authorAvatar, now)));
        }
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer.ContentEntry.ContentData.ContentActualData content = tabContent();
        content.addSection(section);
        return content;
    }

    /**
     * The answer to a browse request with a continuation token: more videos for the list the app
     * is showing, and the token for the page after them.
     */
    static byte[] browseVideosContinuation(List<InnertubeEndpoint.Video> videos, String authorAvatar,
                                           long now, String continuationToken) {
        return new VideoListProto.Root().setContinuation(new VideoListProto.Root.ContinuationContents()
                .addSectionListContinuation(sectionList(videoSection(videos, authorAvatar, now),
                        continuationToken))).toByteArray();
    }

    private static VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
            .ContentEntry.ContentData.ContentActualData tabContent() {
        return new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                .ContentEntry.ContentData.ContentActualData();
    }

    /**
     * The card the 2017 apps show in the home and Trending feeds: a wide thumbnail with the
     * duration over it, the title under it and one line of "Channel - views - age". The compact
     * renderer below is what the 2015-2016 apps use, and it is what those feeds keep.
     */
    static VideoListProto.VideoWithContextRenderer browseFeedVideo(InnertubeEndpoint.Video video,
                                                                   String authorAvatar, long now) {
        StringBuilder byline = new StringBuilder();
        if (!video.channel.isEmpty()) byline.append(video.channel);
        long views = parseCount(video.views);
        if (views > 0) {
            if (byline.length() > 0) byline.append(" \u2022 ");
            byline.append(shortCount(views, "views"));
        }
        String age = relativeTime(video.published, now);
        if (!age.isEmpty()) {
            if (byline.length() > 0) byline.append(" \u2022 ");
            byline.append(age);
        }
        VideoListProto.VideoWithContextRenderer card = new VideoListProto.VideoWithContextRenderer()
                .setVideoId(video.id)
                .setTitle(browseRuns(video.title))
                .setByline(browseRuns(byline.toString()))
                // maxresdefault first; not every upload has one, and the smaller file listed
                // after it is what the app loads when the big one is missing.
                .setThumbnail(new VideoListProto.Thumbnails()
                        .addThumb(new VideoListProto.Thumbnails.Thumbnail()
                                .setUrl(maxresThumbnail(video.id)).setWidth(1280).setHeight(720))
                        .addThumb(new VideoListProto.Thumbnails.Thumbnail()
                                .setUrl(hqThumbnail(video.id)).setWidth(480).setHeight(360)))
                .setNavData(new VideoListProto.NavigationData().setNavProperties(
                        new VideoListProto.NavigationData.NavData().setNavId(video.id)));
        VideoListProto.VideoWithContextRenderer.ChannelAvatar.Avatar avatar =
                new VideoListProto.VideoWithContextRenderer.ChannelAvatar.Avatar()
                        .setThumbnail(browseThumbnails(avatarOrDefault(
                                video.channelAvatar.isEmpty() ? authorAvatar : video.channelAvatar),
                                88, 88));
        if (!video.channelId.isEmpty()) {
            avatar.setNavigation(browseNavigation(video.channelId, null, "/channel/" + video.channelId));
        }
        card.setAvatar(new VideoListProto.VideoWithContextRenderer.ChannelAvatar().addImage(avatar));
        String length = lengthText(video.duration);
        if (!length.isEmpty()) {
            card.setDuration(browseRuns(length));
            card.addOverlay(new VideoListProto.VideoWithContextRenderer.Overlays().addTime(
                    new VideoListProto.VideoWithContextRenderer.Overlays.TimeStatus()
                            .addText(browseRuns(length))));
        }
        return card;
    }

    /** One item section per video, as on the reference server's home and trending tabs. */
    private static VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
            .ContentEntry.ContentData.ContentActualData videoSections(List<InnertubeEndpoint.Video> videos,
                                                                       long now) {
        return videoSections(videos, now, false, NO_AVATARS);
    }

    private static final java.util.Map<String, String> NO_AVATARS =
            java.util.Collections.unmodifiableMap(new java.util.HashMap<String, String>());

    private static VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
            .ContentEntry.ContentData.ContentActualData videoSections(List<InnertubeEndpoint.Video> videos,
                                                                      long now, boolean feedCards,
                                                                      java.util.Map<String, String> avatars) {
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                .ContentEntry.ContentData.ContentActualData content = tabContent();
        for (InnertubeEndpoint.Video video : videos) {
            VideoListProto.ItemSectionRenderer.IcsContents item =
                    new VideoListProto.ItemSectionRenderer.IcsContents();
            if (feedCards) item.addFeedVideo(browseFeedVideo(video, avatars.get(video.channelId), now));
            else item.addVideo(browseVideo(video, null, now));
            content.addSection(new VideoListProto.ItemSectionRenderer().addIcsContent(item));
        }
        return content;
    }

    private static byte[] finishBrowse(VideoListProto.Root root,
                                       VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer tabs,
                                       VideoListProto.Root.HeaderRender header) {
        root.addContents(new VideoListProto.Root.ResponseBody().addBrowseData(tabs));
        if (header != null) root.addHeader(header);
        return root.toByteArray();
    }

    private static VideoListProto.Root.HeaderRender textHeader(String text) {
        return new VideoListProto.Root.HeaderRender().addContent(
                new VideoListProto.Root.HeaderRender.HeaderContent().addText(browseRuns(text)));
    }

    /**
     * FEwhat_to_watch. Apps from version 12 get a single Home tab, because they navigate with
     * the bottom bar of the guide response and leave out the tab strip when a browse answer has
     * one tab; version 11 shows tabs (Home, Trending, Account) above the feed, and older ones
     * get Home and Music tabs under a "What to watch" header.
     */
    static byte[] home(int clientMajorVersion, List<InnertubeEndpoint.Video> home,
                       List<InnertubeEndpoint.Video> second, long now,
                       java.util.Map<String, String> avatars) {
        boolean v11Tabs = clientMajorVersion >= 11;
        VideoListProto.Root root = browseRoot();
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer tabs =
                new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer();
        if (clientMajorVersion >= PIVOT_BAR_VERSION) {
            tabs.addTab(tab("FEwhat_to_watch", "Home", true, 0, videoSections(home, now, true, avatars)));
            return finishBrowse(root, tabs, null);
        }
        tabs.addTab(tab("FEwhat_to_watch", "Home", true, v11Tabs ? 65 : 0, videoSections(home, now)));
        if (!v11Tabs) {
            tabs.addTab(tab("FEmusic", "Music", false, 0, videoSections(second, now)));
            return finishBrowse(root, tabs, textHeader("What to watch"));
        }
        tabs.addTab(tab("FEtrending", "Trending", false, 67, videoSections(second, now)));
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                .ContentEntry.ContentData.ContentActualData account = tabContent();
        account.addSection(new VideoListProto.ItemSectionRenderer().addIcsContent(
                new VideoListProto.ItemSectionRenderer.IcsContents().addAccountLink(
                        new VideoListProto.AccountTabLink()
                                .setIconData(new VideoListProto.AccountTabLink.Icon().setIconType(69))
                                .addText(browseRuns("Account")))));
        tabs.addTab(tab("FEaccount", "Account", false, 69, account));
        return finishBrowse(root, tabs, textHeader("YouTube"));
    }

    private static VideoListProto.AccountTabLink browseLink(String text, int icon, String browseId) {
        VideoListProto.AccountTabLink link = new VideoListProto.AccountTabLink()
                .setIconData(new VideoListProto.AccountTabLink.Icon().setIconType(icon))
                .addText(browseRuns(text));
        if (browseId != null) link.setNavigation(browseNavigation(browseId, null, null));
        return link;
    }

    /** One tab of link rows, as the Library and Account pages of the bottom bar are built. */
    private static byte[] linkPage(String identifier, String title,
                                   List<VideoListProto.AccountTabLink> links) {
        VideoListProto.ItemSectionRenderer section = new VideoListProto.ItemSectionRenderer();
        for (VideoListProto.AccountTabLink link : links) {
            section.addIcsContent(new VideoListProto.ItemSectionRenderer.IcsContents().addAccountLink(link));
        }
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                .ContentEntry.ContentData.ContentActualData content = tabContent();
        content.addSection(section);
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer tabs =
                new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer()
                        .addTab(tab(identifier, title, true, 0, content));
        return finishBrowse(browseRoot(), tabs, textHeader(title));
    }

    /**
     * FElibrary, the third item of the bottom bar. Watch history, Watch later and playlists need
     * an account, so the page links to the feeds this proxy does serve.
     */
    static byte[] library() {
        List<VideoListProto.AccountTabLink> links = new java.util.ArrayList<VideoListProto.AccountTabLink>();
        links.add(browseLink("Popular on YouTube", ICON_TAB_TRENDING, "FEtrending"));
        links.add(browseLink("Music", ICON_MIX, "FEmusic"));
        return linkPage("FElibrary", "Library", links);
    }

    /** FEsubscriptions, the third item of the bottom bar. Subscriptions need an account. */
    static byte[] subscriptions() {
        List<VideoListProto.AccountTabLink> links = new java.util.ArrayList<VideoListProto.AccountTabLink>();
        links.add(browseLink("Subscriptions need an account, which the proxy cannot sign in to",
                ICON_PIVOT_SUBSCRIPTIONS, null));
        links.add(browseLink("Popular on YouTube", ICON_TAB_TRENDING, "FEtrending"));
        links.add(browseLink("Music", ICON_MIX, "FEmusic"));
        return linkPage("FEsubscriptions", "Subscriptions", links);
    }

    /** FEaccount. Signing in is not supported. */
    static byte[] account() {
        List<VideoListProto.AccountTabLink> links = new java.util.ArrayList<VideoListProto.AccountTabLink>();
        links.add(browseLink("Signing in is not available", ICON_TAB_ACCOUNT, null));
        links.add(browseLink("Popular on YouTube", ICON_TAB_TRENDING, "FEtrending"));
        links.add(browseLink("Music", ICON_MIX, "FEmusic"));
        return linkPage("FEaccount", "Account", links);
    }

    /** A single list of videos (FEtrending, FEmusic). */
    static byte[] videoList(String identifier, String title, List<InnertubeEndpoint.Video> videos,
                            long now) {
        return videoList(identifier, title, videos, now, false, NO_AVATARS);
    }

    /** With {@code feedCards} the videos are the wide cards of the 2017 apps' feeds. */
    static byte[] videoList(String identifier, String title, List<InnertubeEndpoint.Video> videos,
                            long now, boolean feedCards, java.util.Map<String, String> avatars) {
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer tabs =
                new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer()
                        .addTab(tab(identifier, title, true, 0,
                                videoSections(videos, now, feedCards, avatars)));
        return finishBrowse(browseRoot(), tabs, textHeader(title));
    }

    /** UC... channel page: header, Home (latest uploads shelf), Videos and Playlists tabs. */
    static byte[] channel(ChannelEndpoint.ChannelInfo info, List<InnertubeEndpoint.Video> uploads,
                          List<ChannelEndpoint.PlaylistSummary> playlists, long now,
                          String uploadsToken) {
        String channelId = info.id;
        String name = info.name.isEmpty() ? channelId : info.name;
        VideoListProto.Root root = browseRoot();

        VideoListProto.SubscribeButtonData.SubContents subscribe = new VideoListProto.SubscribeButtonData.SubContents()
                .setChannelId(channelId)
                .setInt3(0).setInt4(1).setInt6(0).setInt8(0)
                .addSubscribeButton(browseRunsBold("SUBSCRIBE"))
                .addSubscribedLabel(browseRunsBold("SUBSCRIBED"))
                .addSubscribeLabel(browseRunsBold("SUBSCRIBE"))
                .addUnsubscribeLabel(browseRunsBold("UNSUBSCRIBE"))
                .addAction(new VideoListProto.SubscribeButtonData.SubContents.SubAction().addSubscr1(
                        new VideoListProto.SubscribeButtonData.SubContents.SubAction.Sub1()
                                .setId(channelId).setParams("b")))
                // The reference server put the unsubscribe action into the subscribe field too.
                .addAction(new VideoListProto.SubscribeButtonData.SubContents.SubAction().addSubscr2(
                        new VideoListProto.SubscribeButtonData.SubContents.SubAction.Sub2()
                                .setId(channelId).setParams("c")))
                .setIdentifier("c4-subscribe");
        VideoListProto.Root.HeaderRender.C4Header c4 = new VideoListProto.Root.HeaderRender.C4Header()
                .setChannelId(channelId)
                .setChannelName(name)
                .addAvatars(browseThumbnails(avatarOrDefault(info.avatar), 160, 160))
                .addSubscribeCount(browseRuns(subscriberText(info.subscribers)))
                .addSubscribeButton(new VideoListProto.SubscribeButtonData().addContent(subscribe));
        if (!info.banner.isEmpty()) c4.addMobileBanners(browseThumbnails(info.banner, 1060, 175));
        VideoListProto.Root.HeaderRender header = new VideoListProto.Root.HeaderRender().addChannelHeader(c4);

        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer tabs =
                new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer();
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                .ContentEntry.ContentData.ContentActualData home = tabContent();
        if (!uploads.isEmpty()) {
            VideoListProto.ShelfRenderer.Contents.ShelfContents shelfContents =
                    new VideoListProto.ShelfRenderer.Contents.ShelfContents();
            for (int i = 0; i < uploads.size() && i < 10; i++) {
                shelfContents.addContent(new VideoListProto.ShelfRenderer.Contents.ShelfContents.ShelfContentsContents()
                        .addVideo(browseVideo(uploads.get(i), info.avatar, now)));
            }
            home.addShelf(new VideoListProto.ShelfRenderer()
                    .setHeader(new VideoListProto.ShelfRenderer.ShelfHeader().addContent(
                            new VideoListProto.ShelfRenderer.ShelfHeader.Contents().setShelfName("Latest uploads")))
                    .setContent(new VideoListProto.ShelfRenderer.Contents().setShelfContent(shelfContents)));
        }
        tabs.addTab(tab("Home", "Home", true, 0, home));

        if (!uploads.isEmpty()) {
            tabs.addTab(tab("Videos", "Videos", false, 0,
                    videoSection(uploads, info.avatar, now), uploadsToken));
        }

        if (!playlists.isEmpty()) {
            VideoListProto.ItemSectionRenderer section = new VideoListProto.ItemSectionRenderer();
            for (ChannelEndpoint.PlaylistSummary playlist : playlists) {
                section.addIcsContent(new VideoListProto.ItemSectionRenderer.IcsContents().addPlaylist(
                        browsePlaylist(playlist.id, playlist.title, name, channelId, playlist.count,
                                playlist.firstVideoId, playlist.thumbnail)));
            }
            VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                    .ContentEntry.ContentData.ContentActualData content = tabContent();
            content.addSection(section);
            tabs.addTab(tab("Playlists", "Playlists", false, 0, content));
        }
        return finishBrowse(root, tabs, header);
    }

    /** "1.2M subscribers" from YouTube's label ("1.23M subscribers") or a plain number. */
    static String subscriberText(String subscribers) {
        if (subscribers == null || subscribers.trim().isEmpty()) return "";
        return shortCount(parseCount(subscribers), "subscribers");
    }

    /** VL... playlist page. */
    static byte[] playlist(String playlistId, PlaylistEndpoint.Playlist playlist) {
        VideoListProto.Root root = browseRoot();
        int total = Math.max(playlist.total, playlist.videos.size());
        String count = total + " videos";
        VideoListProto.Root.HeaderRender.VlHeader vl = new VideoListProto.Root.HeaderRender.VlHeader()
                .setId(playlistId)
                .addName(browseRuns(playlist.title))
                .addVideoCount(browseRuns(count));
        if (!playlist.description.isEmpty()) vl.addDescription(browseRuns(playlist.description));
        vl.addOwner(browseAuthor(playlist.author, playlist.authorId))
                .addUnk11(new VideoListProto.Root.HeaderRender.VlHeader.Pb11().setInt1(1))
                .setPublic(1)
                .addNavigationData(browseNavigation(playlistId, null, null))
                .addInlineProperty(browseRuns(count));
        VideoListProto.Root.HeaderRender header = new VideoListProto.Root.HeaderRender().addPlaylistHeader(vl);

        VideoListProto.PlaylistData data = new VideoListProto.PlaylistData().setId(playlistId);
        int index = 1;
        for (InnertubeEndpoint.Video video : playlist.videos) {
            VideoListProto.PlaylistData.PlaylistVideo.Contents contents =
                    new VideoListProto.PlaylistData.PlaylistVideo.Contents()
                            .setId(video.id)
                            .addTitle(browseRuns(video.title))
                            .addIndex(browseRuns(Integer.toString(index++)))
                            .setThumbnail(browseThumbnails(hqThumbnail(video.id), 480, 360))
                            .setOwner(browseAuthor(video.channel, video.channelId))
                            .addLength(browseRuns(lengthText(video.duration)))
                            .addEndpoint(new VideoListProto.PlaylistData.PlaylistVideo.Contents.EndpointData()
                                    .addContent(new VideoListProto.PlaylistData.PlaylistVideo.Contents
                                            .EndpointData.EndpointDataContents()
                                            .setId(video.id).setPlaylistId(playlistId)));
            data.addVideo(new VideoListProto.PlaylistData.PlaylistVideo().addContent(contents));
        }
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                .ContentEntry.ContentData.ContentActualData content = tabContent();
        content.addPlaylist(data);
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer tabs =
                new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer()
                        .addTab(tab("Playlist", playlist.title, true, 0, content));
        return finishBrowse(root, tabs, header);
    }

    // ------------------------------------------------------------------ search (android_search)

    private static AndroidSearchProto.TextRuns searchRuns(String text) {
        return new AndroidSearchProto.TextRuns().addRun(new AndroidSearchProto.TextRuns.TextRun().setText(text));
    }

    private static AndroidSearchProto.Thumbnails searchThumbnails(String url, int width, int height) {
        return new AndroidSearchProto.Thumbnails().addThumb(new AndroidSearchProto.Thumbnails.Thumbnail()
                .setUrl(url).setWidth(width).setHeight(height));
    }

    private static AndroidSearchProto.AuthorData searchAuthor(String name, String channelId) {
        AndroidSearchProto.AuthorData.AuthorDataContent content = new AndroidSearchProto.AuthorData.AuthorDataContent()
                .setAuthorDisplayName(name);
        if (channelId != null && !channelId.isEmpty()) {
            content.setNavigation(new AndroidSearchProto.BrowseNavigation().addData(
                    new AndroidSearchProto.BrowseNavigation.BrowseData()
                            .setBrowseId(channelId).setCanonicalBaseUrl("/channel/" + channelId)));
        }
        return new AndroidSearchProto.AuthorData().setAc(content);
    }

    static AndroidSearchProto.CompactVideoRenderer searchVideo(InnertubeEndpoint.Video video, long now) {
        long views = parseCount(video.views);
        AndroidSearchProto.AuthorData author = searchAuthor(video.channel, video.channelId);
        return new AndroidSearchProto.CompactVideoRenderer()
                .setVideoId(video.id)
                .setThumblist(searchThumbnails(hqThumbnail(video.id), 480, 360))
                .setTitleData(searchRuns(video.title))
                .setPublishedTimeText(searchRuns(relativeTime(video.published, now)))
                .setViewCountText(searchRuns(fullCount(views, "view", "views")))
                .setLengthText(searchRuns(lengthText(video.duration)))
                .setShortViewCounts(searchRuns(shortCount(views, "views")))
                .setShortAuthorData(author)
                .setLongAuthorData(author)
                .setNavData(new AndroidSearchProto.NavigationData().setNavProperties(
                        new AndroidSearchProto.NavigationData.NavData().setNavId(video.id)))
                .setAuthorAvatar(searchThumbnails(DEFAULT_AVATAR, 88, 88));
    }

    /** The channel cards YouTube mixes into the results. */
    private static AndroidSearchProto.ChannelRenderer searchChannel(InnertubeEndpoint.SearchChannel channel) {
        AndroidSearchProto.ChannelRenderer renderer = new AndroidSearchProto.ChannelRenderer()
                .setId(channel.id)
                .setName(searchRuns(channel.name))
                .setAvatar(searchThumbnails(avatarOrDefault(channel.avatar), 88, 88))
                .setAvatar2(searchThumbnails(avatarOrDefault(channel.avatar), 176, 176))
                .setNavigation(new AndroidSearchProto.BrowseNavigation().addData(
                        new AndroidSearchProto.BrowseNavigation.BrowseData()
                                .setBrowseId(channel.id)
                                .setCanonicalBaseUrl("/channel/" + channel.id)));
        String subscribers = subscriberText(channel.subscribers);
        if (!subscribers.isEmpty()) renderer.setSubCount(searchRuns(subscribers));
        if (!channel.handle.isEmpty()) renderer.setHandle(searchRuns(channel.handle));
        return renderer;
    }

    /** The playlist cards; tapping one opens the playlist with browse VL<id>. */
    private static AndroidSearchProto.PlaylistRenderer searchPlaylist(InnertubeEndpoint.SearchPlaylist playlist) {
        String thumbnail = !playlist.firstVideoId.isEmpty() ? hqThumbnail(playlist.firstVideoId)
                : !playlist.thumbnail.isEmpty() ? playlist.thumbnail : DEFAULT_AVATAR;
        AndroidSearchProto.AuthorData owner = searchAuthor(playlist.author, playlist.authorId);
        AndroidSearchProto.PlaylistRenderer renderer = new AndroidSearchProto.PlaylistRenderer()
                .setId(playlist.id)
                .setPlaylistName(searchRuns(playlist.title))
                .setThumbnail(searchThumbnails(thumbnail, 480, 360))
                .setSeparatedThumbnail(searchThumbnails(thumbnail, 480, 360))
                .setShortOwner(owner)
                .setLongOwner(owner)
                .setPlaylistBrowse(new AndroidSearchProto.BrowseNavigation().addData(
                        new AndroidSearchProto.BrowseNavigation.BrowseData().setBrowseId("VL" + playlist.id)))
                .setWebUrl("https://www.youtube.com/playlist?list=" + playlist.id);
        if (!playlist.count.isEmpty()) {
            String count = playlist.count + " videos";
            renderer.setVideoCount(searchRuns(count))
                    .setVideoCount2(searchRuns(count))
                    .setBareVideoCount(searchRuns(playlist.count));
        }
        return renderer;
    }

    private static AndroidSearchProto.Continuations searchContinuations(String token) {
        return new AndroidSearchProto.Continuations().addNext(
                new AndroidSearchProto.Continuations.NextContinuationData().setToken(token));
    }

    private static AndroidSearchProto.Root.Contents.SearchResults.SearchResultsContents
            .ItemSectionRender.ItemSectionRenderContents searchItem() {
        return new AndroidSearchProto.Root.Contents.SearchResults.SearchResultsContents
                .ItemSectionRender.ItemSectionRenderContents();
    }

    /**
     * One page of results: the channels and playlists YouTube found first, then the videos. The
     * token is what the app sends back when the last result is on screen; it is empty on the
     * last page, which ends the list.
     */
    static byte[] search(List<InnertubeEndpoint.Video> videos,
                         List<InnertubeEndpoint.SearchChannel> channels,
                         List<InnertubeEndpoint.SearchPlaylist> playlists,
                         long now, String continuationToken) {
        AndroidSearchProto.Root.Contents.SearchResults results = searchResults(videos, channels,
                playlists, now, continuationToken);
        return new AndroidSearchProto.Root().addContent(new AndroidSearchProto.Root.Contents()
                .addResultsContainer(results)).toByteArray();
    }

    /** The same page as an answer to a continuation request, which the app appends to the list. */
    static byte[] searchContinuation(List<InnertubeEndpoint.Video> videos, long now,
                                     String continuationToken) {
        AndroidSearchProto.Root.Contents.SearchResults results = searchResults(videos,
                new java.util.ArrayList<InnertubeEndpoint.SearchChannel>(),
                new java.util.ArrayList<InnertubeEndpoint.SearchPlaylist>(), now, continuationToken);
        return new AndroidSearchProto.Root().setContinuation(
                new AndroidSearchProto.Root.ContinuationContents().addSectionListContinuation(results))
                .toByteArray();
    }

    private static AndroidSearchProto.Root.Contents.SearchResults searchResults(
            List<InnertubeEndpoint.Video> videos,
            List<InnertubeEndpoint.SearchChannel> channels,
            List<InnertubeEndpoint.SearchPlaylist> playlists,
            long now, String continuationToken) {
        AndroidSearchProto.Root.Contents.SearchResults.SearchResultsContents.ItemSectionRender section =
                new AndroidSearchProto.Root.Contents.SearchResults.SearchResultsContents.ItemSectionRender();
        for (InnertubeEndpoint.SearchChannel channel : channels) {
            section.addContent(searchItem().addChannel(searchChannel(channel)));
        }
        for (InnertubeEndpoint.SearchPlaylist playlist : playlists) {
            section.addContent(searchItem().addPlaylist(searchPlaylist(playlist)));
        }
        for (InnertubeEndpoint.Video video : videos) {
            section.addContent(searchItem().addVideo(searchVideo(video, now)));
        }
        AndroidSearchProto.Root.Contents.SearchResults results =
                new AndroidSearchProto.Root.Contents.SearchResults().addContent(
                        new AndroidSearchProto.Root.Contents.SearchResults.SearchResultsContents()
                                .addSection(section));
        if (continuationToken != null && !continuationToken.isEmpty()) {
            results.addContinuation(searchContinuations(continuationToken));
        }
        return results;
    }

    // ------------------------------------------------------------------ next (android_full_next)

    private static AndroidFullNextProto.TextRuns nextRunsBold(String text) {
        return new AndroidFullNextProto.TextRuns().addRun(
                new AndroidFullNextProto.TextRuns.TextRun().setText(text).setBold(1));
    }

    private static AndroidFullNextProto.TextRuns nextRuns(String text) {
        return new AndroidFullNextProto.TextRuns().addRun(new AndroidFullNextProto.TextRuns.TextRun().setText(text));
    }

    private static AndroidFullNextProto.Thumbnails nextThumbnails(String url, int width, int height) {
        return new AndroidFullNextProto.Thumbnails().addThumb(new AndroidFullNextProto.Thumbnails.Thumbnail()
                .setUrl(url).setWidth(width).setHeight(height));
    }

    private static AndroidFullNextProto.BrowseNavigation nextChannelNavigation(String channelId) {
        return new AndroidFullNextProto.BrowseNavigation().addData(
                new AndroidFullNextProto.BrowseNavigation.BrowseData()
                        .setBrowseId(channelId).setCanonicalBaseUrl("/channel/" + channelId));
    }

    static AndroidFullNextProto.CompactVideoRenderer nextVideo(InnertubeEndpoint.Video video, long now) {
        long views = parseCount(video.views);
        AndroidFullNextProto.AuthorData.AuthorDataContent content =
                new AndroidFullNextProto.AuthorData.AuthorDataContent().setAuthorDisplayName(video.channel);
        if (!video.channelId.isEmpty()) content.setNavigation(nextChannelNavigation(video.channelId));
        AndroidFullNextProto.AuthorData author = new AndroidFullNextProto.AuthorData().setAc(content);
        return new AndroidFullNextProto.CompactVideoRenderer()
                .setVideoId(video.id)
                .setThumblist(nextThumbnails(hqThumbnail(video.id), 480, 360))
                .setTitleData(nextRuns(video.title))
                .setPublishedTimeText(nextRuns(relativeTime(video.published, now)))
                .setViewCountText(nextRuns(fullCount(views, "view", "views")))
                .setLengthText(nextRuns(lengthText(video.duration)))
                .setShortViewCounts(nextRuns(shortCount(views, "views")))
                .setShortAuthorData(author)
                .setLongAuthorData(author)
                .setNavData(new AndroidFullNextProto.NavigationData().setNavProperties(
                        new AndroidFullNextProto.NavigationData.NavData().setNavId(video.id)))
                .setAuthorAvatar(nextThumbnails(DEFAULT_AVATAR, 88, 88));
    }

    /**
     * The watch page: video metadata, channel with subscribe button, related videos. Apps from
     * version 12 get the metadata as slimVideoMetadataRenderer: they still parse
     * videoMetadataRenderer but have no view for it, so with the older message their watch page
     * stayed empty above the related videos.
     */
    static byte[] next(InnertubeEndpoint.WatchPage page, long now, int clientMajorVersion,
                       String relatedToken, String commentsToken,
                       List<InnertubeEndpoint.Comment> comments) {
        InnertubeEndpoint.Video video = page.video;
        String channelId = video.channelId;
        long views = parseCount(video.views);
        long likes = parseCount(video.likes);

        AndroidFullNextProto.VideoMetadataRenderer metadata = new AndroidFullNextProto.VideoMetadataRenderer()
                .setTitle(nextRuns(video.title))
                .setViewCount(nextRuns(fullCount(views, "view", "views")))
                .setDescription(nextRuns(video.description))
                .setPublishDate(nextRuns(publishedOn(video.published, page.dateText)))
                .setVideoId(video.id)
                .setShortViewCount(nextRuns(shortCount(views, "views")))
                .setAllowRatings(1)
                .setIsLimitedState(0)
                .addPb13(new AndroidFullNextProto.VideoMetadataRenderer.PbVarious13().addPb(
                        new AndroidFullNextProto.VideoMetadataRenderer.PbVarious13.PbVarious()
                                .setVideoId(nextRuns(video.id))
                                .addCount1(new AndroidFullNextProto.SimpleText().setText(likes + " likes"))
                                .setInt2(2)
                                .setInt3((int) Math.min(likes, Integer.MAX_VALUE))
                                .setInt13(1)
                                .setInt23(0)));

        AndroidFullNextProto.AuthorRendererData.SubscribeButtonData.SubContents subscribe =
                nextSubscribeButton(channelId, page.subscribers);
        AndroidFullNextProto.AuthorRendererData author = new AndroidFullNextProto.AuthorRendererData()
                .setAvatars(nextThumbnails(avatarOrDefault(page.ownerAvatar), 48, 48))
                .setName(nextRuns(video.channel))
                .setNaviData(nextChannelNavigation(channelId))
                .addSubscribeButton(new AndroidFullNextProto.AuthorRendererData.SubscribeButtonData()
                        .addContent(subscribe));
        String subscribers = subscriberText(page.subscribers);
        if (!subscribers.isEmpty()) author.setSubscribeCount(nextRuns(subscribers));

        AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults.SingleColumnWatchNextResultsContents
                .Results.ResultsContents sections = new AndroidFullNextProto.Root.Contents
                .SingleColumnWatchNextResults.SingleColumnWatchNextResultsContents.Results.ResultsContents();
        if (clientMajorVersion >= PIVOT_BAR_VERSION) {
            sections.addItemSectionRender(nextSection().addContent(nextSectionContents()
                    .addSlimVideoMetadata(slimMetadata(page, subscribe, now))));
        } else {
            sections.addItemSectionRender(nextSection().addContent(nextSectionContents().addVideoMetadata(metadata)));
            sections.addItemSectionRender(nextSection().addContent(nextSectionContents().addAuthorData(author)));
        }
        // The comments ride along with the watch page. A section that only carries the token is
        // never asked for: nothing of it is on screen, so nothing triggers the continuation.
        if (!comments.isEmpty() || (commentsToken != null && !commentsToken.isEmpty())) {
            AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                    .SingleColumnWatchNextResultsContents.Results.ResultsContents.ItemSectionRenderer
                    commentSection = nextSection();
            for (InnertubeEndpoint.Comment comment : comments) {
                commentSection.addContent(nextSectionContents().addCommentThread(commentThread(comment, now)));
            }
            if (commentsToken != null && !commentsToken.isEmpty()) {
                commentSection.addCommentRequest(commentRequest(commentsToken));
            }
            sections.addItemSectionRender(commentSection);
        }
        sections.addItemSectionRender(relatedSection(page.related, now));

        AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                .SingleColumnWatchNextResultsContents.Results results =
                new AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                        .SingleColumnWatchNextResultsContents.Results().addContent(sections);
        if (relatedToken != null && !relatedToken.isEmpty()) {
            results.addContinuation(nextContinuations(relatedToken));
        }
        return new AndroidFullNextProto.Root()
                .addContent(new AndroidFullNextProto.Root.Contents().addNextResults(
                        new AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults().addContent(
                                new AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                                        .SingleColumnWatchNextResultsContents().addResult(results))))
                .setNavigationEndpoint(new AndroidFullNextProto.NavigationData().setNavProperties(
                        new AndroidFullNextProto.NavigationData.NavData().setNavId(video.id)))
                .setPoverlays(new AndroidFullNextProto.Root.PlayerOverlays().setOverlay(
                        new AndroidFullNextProto.Root.PlayerOverlays.PlayerOverlaysRenderer()))
                .toByteArray();
    }

    private static AndroidFullNextProto.Continuations nextContinuations(String token) {
        return new AndroidFullNextProto.Continuations().addNext(
                new AndroidFullNextProto.Continuations.NextContinuationData().setToken(token));
    }

    private static AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
            .SingleColumnWatchNextResultsContents.Results.ResultsContents.ItemSectionRenderer
            relatedSection(List<InnertubeEndpoint.Video> related, long now) {
        AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                .SingleColumnWatchNextResultsContents.Results.ResultsContents.ItemSectionRenderer section =
                nextSection();
        for (InnertubeEndpoint.Video item : related) {
            section.addContent(nextSectionContents().addNextVideo(nextVideo(item, now)));
        }
        return section;
    }

    /** One comment, as the apps of 2015-2017 draw it. */
    private static AndroidFullNextProto.CommentThreadRenderer commentThread(
            InnertubeEndpoint.Comment comment, long now) {
        AndroidFullNextProto.CommentRenderer renderer = new AndroidFullNextProto.CommentRenderer()
                .setCommentId(comment.id)
                .setAuthor(nextRuns(comment.author))
                .setContent(nextRuns(comment.text))
                .setAuthorThumbnail(nextThumbnails(avatarOrDefault(comment.avatar), 48, 48));
        String age = relativeTime(comment.published, now);
        if (!age.isEmpty()) renderer.setPublishedTime(nextRuns(age));
        if (!comment.authorId.isEmpty()) {
            renderer.setAuthorEndpoint(nextChannelNavigation(comment.authorId));
        }
        return new AndroidFullNextProto.CommentThreadRenderer().setComment(
                new AndroidFullNextProto.CommentThreadRenderer.CommentWrapper().setComment(renderer));
    }

    /**
     * The comments under a video, as the answer to the continuation the watch page asked for.
     * They go back as more entries for the section that is already on screen.
     */
    static byte[] commentsContinuation(List<InnertubeEndpoint.Comment> comments, long now,
                                       String continuationToken) {
        AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                .SingleColumnWatchNextResultsContents.Results.ResultsContents.ItemSectionRenderer section =
                nextSection();
        for (InnertubeEndpoint.Comment comment : comments) {
            section.addContent(nextSectionContents().addCommentThread(commentThread(comment, now)));
        }
        if (continuationToken != null && !continuationToken.isEmpty()) {
            section.addCommentRequest(commentRequest(continuationToken));
        }
        return new AndroidFullNextProto.Root().setContinuation(
                new AndroidFullNextProto.Root.ContinuationContents()
                        .addItemSectionContinuation(section)).toByteArray();
    }

    /** The token the app sends back to ask for the comments of a video. */
    private static AndroidFullNextProto.NextRequest commentRequest(String token) {
        return new AndroidFullNextProto.NextRequest().addContinuation(
                new AndroidFullNextProto.NextRequest.ContinuationData().setToken(token));
    }

    /** More related videos for the list under a video, as the answer to a continuation request. */
    static byte[] relatedContinuation(List<InnertubeEndpoint.Video> related, long now,
                                      String continuationToken) {
        AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                .SingleColumnWatchNextResultsContents.Results.ResultsContents sections =
                new AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                        .SingleColumnWatchNextResultsContents.Results.ResultsContents();
        sections.addItemSectionRender(relatedSection(related, now));
        AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                .SingleColumnWatchNextResultsContents.Results results =
                new AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                        .SingleColumnWatchNextResultsContents.Results().addContent(sections);
        if (continuationToken != null && !continuationToken.isEmpty()) {
            results.addContinuation(nextContinuations(continuationToken));
        }
        return new AndroidFullNextProto.Root().setContinuation(
                new AndroidFullNextProto.Root.ContinuationContents()
                        .addSectionListContinuation(results)).toByteArray();
    }

    /** The subscribe button; the 2017 apps read the subscriber count out of it. */
    private static AndroidFullNextProto.AuthorRendererData.SubscribeButtonData.SubContents
            nextSubscribeButton(String channelId, String subscribers) {
        AndroidFullNextProto.AuthorRendererData.SubscribeButtonData.SubContents subscribe =
                new AndroidFullNextProto.AuthorRendererData.SubscribeButtonData.SubContents()
                        .setChannelId(channelId)
                        .setInt3(0).setInt4(1).setInt6(0).setInt8(0)
                        .setSubscribeButton(nextRunsBold("SUBSCRIBE"))
                        .setSubscribedLabel(nextRunsBold("SUBSCRIBED"))
                        .setSubscribeLabel(nextRunsBold("SUBSCRIBE"))
                        .setUnsubscribeLabel(nextRunsBold("UNSUBSCRIBE"))
                        .addAction(new AndroidFullNextProto.AuthorRendererData.SubscribeButtonData.SubContents
                                .SubAction().addSubscr1(new AndroidFullNextProto.AuthorRendererData
                                        .SubscribeButtonData.SubContents.SubAction.Sub1()
                                        .setId(channelId).setParams("b")))
                        .addAction(new AndroidFullNextProto.AuthorRendererData.SubscribeButtonData.SubContents
                                .SubAction().addSubscr2(new AndroidFullNextProto.AuthorRendererData
                                        .SubscribeButtonData.SubContents.SubAction.Sub2()
                                        .setId(channelId).setParams("c")))
                        .setIdentifier("watch-subscribe");
        long count = parseCount(subscribers);
        if (count > 0) {
            // Field 20 is the line of the expanded panel and field 23 the one of the collapsed
            // panel, the other way round from what their names on the reference server suggest.
            subscribe.setShortSubscriberCount(nextRuns(fullCount(count, "subscriber", "subscribers")));
            subscribe.setLongSubscriberCount(nextRuns(shortCount(count, "subscribers")));
        }
        return subscribe;
    }

    /**
     * The share sheet of the system, as the 2017 apps build it: every extra of an ACTION_SEND
     * intent, its type and the title of the chooser.
     */
    private static AndroidFullNextProto.ButtonEndpoint shareEndpoint(String videoId, String title) {
        AndroidFullNextProto.ButtonEndpoint.ShareIntent share =
                new AndroidFullNextProto.ButtonEndpoint.ShareIntent()
                        .setType("text/plain")
                        .setChooserTitle("Share")
                        .addExtras(new AndroidFullNextProto.ButtonEndpoint.ShareIntent.Extra()
                                .setKey("android.intent.extra.TEXT")
                                .setValue("https://youtu.be/" + videoId))
                        .addExtras(new AndroidFullNextProto.ButtonEndpoint.ShareIntent.Extra()
                                .setKey("android.intent.extra.SUBJECT")
                                .setValue(title));
        return new AndroidFullNextProto.ButtonEndpoint().setShare(share);
    }

    /** One of the buttons under the video of the 2017 apps' watch page. */
    private static AndroidFullNextProto.SlimMetadataButton slimButton(int icon, String text) {
        return slimButton(icon, text, null);
    }

    private static AndroidFullNextProto.SlimMetadataButton slimButton(int icon, String text,
                                                                     AndroidFullNextProto.ButtonEndpoint endpoint) {
        AndroidFullNextProto.ButtonRenderer button = new AndroidFullNextProto.ButtonRenderer()
                .setText(nextRuns(text))
                .setIcon(new AndroidFullNextProto.IconContainer().setIconType(icon));
        if (endpoint != null) {
            button.setServiceEndpoint(endpoint).setNavigationEndpoint(endpoint);
        }
        return new AndroidFullNextProto.SlimMetadataButton().addButton(
                new AndroidFullNextProto.SlimMetadataButton.SlimMetadataButtonRenderer().setButton(
                        new AndroidFullNextProto.SlimMetadataButton.SlimMetadataButtonRenderer
                                .ButtonContainer().setButton(button)));
    }

    /**
     * The watch metadata of the 2017 apps: title, the collapsed and the expanded subtitle, the
     * like and dislike buttons, the channel row and the description (the apps show it after the
     * panel is expanded).
     */
    private static AndroidFullNextProto.SlimVideoMetadataRenderer slimMetadata(
            InnertubeEndpoint.WatchPage page,
            AndroidFullNextProto.AuthorRendererData.SubscribeButtonData.SubContents subscribe,
            long now) {
        InnertubeEndpoint.Video video = page.video;
        long views = parseCount(video.views);
        long likes = parseCount(video.likes);
        String published = publishedOn(video.published, page.dateText);
        String expanded = fullCount(views, "view", "views");
        if (!published.isEmpty()) expanded = expanded + " \u00b7 " + published;

        AndroidFullNextProto.SlimVideoMetadataRenderer slim =
                new AndroidFullNextProto.SlimVideoMetadataRenderer()
                        .setTitle(nextRuns(video.title))
                        .setCollapsedSubtitle(nextRuns(shortCount(views, "views")))
                        .setExpandedSubtitle(nextRuns(expanded))
                        .setVideoId(video.id);
        if (!video.description.isEmpty()) slim.setDescription(nextRuns(video.description));
        slim.addButtons(slimButton(ICON_LIKE, likes > 0 ? shortNumber(likes) : "Like"));
        slim.addButtons(slimButton(ICON_DISLIKE, "Dislike"));
        slim.addButtons(slimButton(ICON_SHARE, "Share", shareEndpoint(video.id, video.title)));
        slim.addButtons(slimButton(ICON_ADDTO, "Add to"));

        AndroidFullNextProto.SlimOwner.SlimOwnerRenderer owner =
                new AndroidFullNextProto.SlimOwner.SlimOwnerRenderer()
                        .setAvatar(nextThumbnails(avatarOrDefault(page.ownerAvatar), 88, 88))
                        .setTitle(nextRuns(video.channel));
        if (!video.channelId.isEmpty()) owner.setNavigation(nextChannelNavigation(video.channelId));
        owner.setSubscribeButton(new AndroidFullNextProto.SlimOwner.SlimOwnerRenderer
                .SubscribeContainer().addButton(subscribe));
        slim.setOwner(new AndroidFullNextProto.SlimOwner().addOwner(owner));
        return slim;
    }

    private static AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
            .SingleColumnWatchNextResultsContents.Results.ResultsContents.ItemSectionRenderer nextSection() {
        return new AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                .SingleColumnWatchNextResultsContents.Results.ResultsContents.ItemSectionRenderer();
    }

    private static AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
            .SingleColumnWatchNextResultsContents.Results.ResultsContents.ItemSectionRenderer
            .ItemSectionRendererContents nextSectionContents() {
        return new AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                .SingleColumnWatchNextResultsContents.Results.ResultsContents.ItemSectionRenderer
                .ItemSectionRendererContents();
    }

    // ------------------------------------------------------------------ player (android_player)

    static final class PlayerInfo {
        String videoId = "";
        String title = "";
        int lengthSeconds;
        String channelId = "";
        String author = "";
        String description = "";
        long viewCount;
        /** Set when there is nothing to play; the app shows it instead of starting the player. */
        String error = "";
        final java.util.List<StreamFormat> formats = new java.util.ArrayList<StreamFormat>();
    }

    static final class StreamFormat {
        final int itag;
        final String url;
        final int width;
        final int height;
        final String quality;
        final String label;
        final String mimeType;
        /** Set when the format belongs in the adaptive list of the answer instead of the plain one. */
        boolean adaptive;
        int bitrate;
        long contentLength;
        int[] initRange;
        int[] indexRange;

        StreamFormat(int itag, String url, int width, int height, String quality, String label,
                     String mimeType) {
            this.itag = itag;
            this.url = url;
            this.width = width;
            this.height = height;
            this.quality = quality;
            this.label = label;
            this.mimeType = mimeType;
        }
    }

    static StreamFormat progressive360p(String url) {
        return new StreamFormat(18, url, 640, 360, "medium", "360p",
                "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"");
    }

    /**
     * The audio-only file the apps ask for when they play a video in the background. Without one
     * they log "No streaming data available for background playback" and stop at the lock screen.
     */
    static StreamFormat audioOnly(String url) {
        return new StreamFormat(140, url, 0, 0, "tiny", "",
                "audio/mp4; codecs=\"mp4a.40.2\"");
    }

    /**
     * The sound of the video as an adaptive stream. The apps keep two lists of formats: playing
     * a video on screen reads the plain one, playing it in the background reads the adaptive one
     * and only accepts itag 139 or 140 in it ("Audio not supported/available" otherwise), so the
     * same file is offered in both.
     */
    static StreamFormat adaptiveAudio(int itag, String url, String mimeType, int bitrate,
                                      long contentLength, int[] initRange, int[] indexRange) {
        StreamFormat format = new StreamFormat(itag == 139 ? 139 : 140, url, 0, 0, "tiny", "",
                mimeType.isEmpty() ? "audio/mp4; codecs=\"mp4a.40.2\"" : mimeType);
        format.adaptive = true;
        format.bitrate = bitrate;
        format.contentLength = contentLength;
        format.initRange = initRange;
        format.indexRange = indexRange;
        return format;
    }

    static StreamFormat muxed720p(String url) {
        return muxedQuality(720, url);
    }

    /**
     * One quality of the stream this proxy muxes. The itag and the quality name are YouTube's
     * own for that height - 17, 36, 18, 59, 22, 37 - because that is the table these apps were
     * written against; a number they do not know is dropped from their quality menu.
     */
    static StreamFormat muxedQuality(int height, String url) {
        int itag;
        String quality;
        if (height >= 1080) {
            itag = 37;
            quality = "hd1080";
        } else if (height >= 720) {
            itag = 22;
            quality = "hd720";
        } else if (height >= 480) {
            itag = 59;
            quality = "large";
        } else if (height >= 360) {
            itag = 18;
            quality = "medium";
        } else if (height >= 240) {
            itag = 36;
            quality = "small";
        } else {
            itag = 17;
            quality = "tiny";
        }
        return new StreamFormat(itag, url, height * 16 / 9, height, quality, height + "p",
                "video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"");
    }

    static byte[] player(PlayerInfo info) {
        AndroidPlayerProto.Root root = new AndroidPlayerProto.Root();
        AndroidPlayerProto.Root.PlayabilityStatus playability = new AndroidPlayerProto.Root.PlayabilityStatus();
        if (info.error.isEmpty() && !info.formats.isEmpty()) {
            playability.setStatus(0).setPlayableInEmbed(1).setContentParams("CAESAggB");
        } else {
            // Innertube status 2 is UNPLAYABLE; the reason is shown to the user.
            playability.setStatus(2).setError(info.error.isEmpty()
                    ? "This video is not available on this device" : info.error);
        }
        root.addPlayability(playability);

        if (!info.formats.isEmpty()) {
            AndroidPlayerProto.Root.PlayerFormats formats = new AndroidPlayerProto.Root.PlayerFormats()
                    .setExpiresInSeconds(21540);
            for (StreamFormat format : info.formats) {
                AndroidPlayerProto.Root.PlayerFormats.Format entry =
                        new AndroidPlayerProto.Root.PlayerFormats.Format()
                                .setFormatId(format.itag)
                                .setUrl(format.url)
                                .setMimeType(format.mimeType)
                                .setVideoWidth(format.width)
                                .setVideoHeight(format.height)
                                .setVideoQuality(format.quality)
                                .setQualityLabel(format.label)
                                .setSortKey(format.height);
                if (!format.adaptive) {
                    formats.addNondashFormat(entry);
                    continue;
                }
                entry.setTotalBitrate(format.bitrate).setContentLength(format.contentLength);
                if (format.initRange != null) {
                    entry.addInitRange(new AndroidPlayerProto.NumberRange()
                            .setStart(format.initRange[0]).setEnd(format.initRange[1]));
                }
                if (format.indexRange != null) {
                    entry.addIndexRange(new AndroidPlayerProto.NumberRange()
                            .setStart(format.indexRange[0]).setEnd(format.indexRange[1]));
                }
                formats.addDashFormat(entry);
            }
            root.addFormats(formats);
        }

        root.addVideoMetadata(new AndroidPlayerProto.Root.Metadata()
                .setId(info.videoId)
                .setTitle(info.title)
                .setVideoLength(info.lengthSeconds)
                .setChannelId(info.channelId)
                .setDescription(info.description)
                .addThumbnails(new AndroidPlayerProto.Root.Metadata.ThumbList().addThumbnail(
                        new AndroidPlayerProto.Root.Metadata.ThumbList.Thumb()
                                .setUrl(hqThumbnail(info.videoId)).setWidth(480).setHeight(360)))
                .setViewCount(Long.toString(info.viewCount))
                .setAuthorName(info.author)
                .setIsOwnerViewing(0)
                .setIsCrawlable(0)
                .setAllowRatings(1)
                .setIsPrivate(0)
                .setIsUnpluggedCorpus(0)
                .setIsLiveContent(0));
        root.setPbVarious69("CAA%3D");
        return root.toByteArray();
    }

    // ------------------------------------------------------------------ guide and resolve_url

    private static AndroidGuideProto.BrowseNavigation guideNavigation(String browseId) {
        return new AndroidGuideProto.BrowseNavigation().addData(
                new AndroidGuideProto.BrowseNavigation.BrowseData().setBrowseId(browseId));
    }

    private static AndroidGuideProto.TextRuns guideRuns(String text) {
        return new AndroidGuideProto.TextRuns().addRun(new AndroidGuideProto.TextRuns.TextRun().setText(text));
    }

    private static AndroidGuideProto.PivotBarItem pivotItem(String identifier, String title, int icon) {
        return new AndroidGuideProto.PivotBarItem().addItem(
                new AndroidGuideProto.PivotBarItem.PivotBarItemRenderer()
                        .setPivotIdentifier(identifier)
                        .addNavigation(guideNavigation(identifier))
                        .addTitle(guideRuns(title))
                        .addIcon(new AndroidGuideProto.PivotBarItem.PivotBarItemRenderer.IconContainer()
                                .setIconType(icon)));
    }

    /**
     * The classic bottom bar of the 2017 apps, as an item of the guide answer: Home, Trending,
     * Library and Account. The app reads the pivot bar out of the guide response it keeps on
     * disk, so it appears on the next start of the app.
     */
    private static AndroidGuideProto.Root.Items.PivotBarRenderer pivotBar() {
        return new AndroidGuideProto.Root.Items.PivotBarRenderer()
                .addItems(pivotItem("FEwhat_to_watch", "Home", ICON_PIVOT_HOME))
                .addItems(pivotItem("FEtrending", "Trending", ICON_PIVOT_TRENDING))
                .addItems(pivotItem("FEsubscriptions", "Subscriptions", ICON_PIVOT_SUBSCRIPTIONS))
                .addItems(pivotItem("FElibrary", "Library", ICON_PIVOT_LIBRARY));
    }

    static byte[] guide(int clientMajorVersion) {
        AndroidGuideProto.Root root = new AndroidGuideProto.Root();
        AndroidGuideProto.GuideEntry home = new AndroidGuideProto.GuideEntry()
                .setTitle("What to Watch")
                .addIcon(new AndroidGuideProto.GuideEntry.IconContainer().setIconType(5))
                .addNavigation(guideNavigation("FEwhat_to_watch"));
        root.addItem(new AndroidGuideProto.Root.Items().addGuideSection(
                new AndroidGuideProto.Root.Items.GuideSectionRenderer().addContent(
                        new AndroidGuideProto.Root.Items.GuideSectionRenderer.Contents().addEntry(home))));

        AndroidGuideProto.Root.Items.GuideSectionRenderer best = new AndroidGuideProto.Root.Items.GuideSectionRenderer()
                .setHeader("Best of YouTube");
        // The reference server linked Music to a browse id it did not serve; FEmusic is served here.
        String[][] entries = {{"Popular on YouTube", "FEtrending"}, {"Music", "FEmusic"}};
        for (String[] entry : entries) {
            best.addContent(new AndroidGuideProto.Root.Items.GuideSectionRenderer.Contents().addEntry(
                    new AndroidGuideProto.GuideEntry().setTitle(entry[0]).addNavigation(guideNavigation(entry[1]))));
        }
        root.addItem(new AndroidGuideProto.Root.Items().addGuideSection(best));
        if (clientMajorVersion >= PIVOT_BAR_VERSION) {
            root.addItem(new AndroidGuideProto.Root.Items().addPivotBar(pivotBar()));
        }
        return root.toByteArray();
    }

    static byte[] resolveVideo(String videoId) {
        return new AndroidResolveProto.Root().addContent(new AndroidResolveProto.Root.Contents()
                .addVideo(new AndroidResolveProto.VideoNavigation().setId(videoId))).toByteArray();
    }

    static byte[] resolveBrowse(String browseId) {
        return new AndroidResolveProto.Root().addContent(new AndroidResolveProto.Root.Contents()
                .addBrowse(new AndroidResolveProto.BrowseNavigation().setBrowseId(browseId))).toByteArray();
    }

    static byte[] resolveSearch(String query) {
        return new AndroidResolveProto.Root().addContent(new AndroidResolveProto.Root.Contents()
                .addSearch(new AndroidResolveProto.SearchNavigation().setQuery(query))).toByteArray();
    }
}
