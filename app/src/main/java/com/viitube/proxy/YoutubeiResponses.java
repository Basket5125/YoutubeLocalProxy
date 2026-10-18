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

    private YoutubeiResponses() { }

    // ------------------------------------------------------------------ text formatting

    /** "1.2M views", like formatViewCount() in the reference server. */
    static String shortCount(long count, String unit) {
        if (count >= 1_000_000_000L) return oneDecimal(count / 1_000_000_000d) + "B " + unit;
        if (count >= 1_000_000L) return oneDecimal(count / 1_000_000d) + "M " + unit;
        if (count >= 1_000L) return oneDecimal(count / 1_000d) + "K " + unit;
        return count + " " + unit;
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

    private static String avatarOrDefault(String avatar) {
        return avatar == null || avatar.isEmpty() ? DEFAULT_AVATAR : avatar;
    }

    // ------------------------------------------------------------------ browse (video_list_proto)

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
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer renderer =
                new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer()
                        .addContentsEntry(new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer
                                .Tabs.TabRenderer.ContentEntry().addContentArray(
                                        new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer
                                                .Tabs.TabRenderer.ContentEntry.ContentData().addC(content)))
                        .setSelected(selected ? 1 : 0)
                        .setTabIdentifier(identifier)
                        .setTabTitle(title);
        if (icon > 0) {
            renderer.addIconData(new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer
                    .Tabs.TabRenderer.Icon().setIconType(icon));
        }
        return new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs().addTabRender(renderer);
    }

    private static VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
            .ContentEntry.ContentData.ContentActualData tabContent() {
        return new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                .ContentEntry.ContentData.ContentActualData();
    }

    /** One item section per video, as on the reference server's home and trending tabs. */
    private static VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
            .ContentEntry.ContentData.ContentActualData videoSections(List<InnertubeEndpoint.Video> videos,
                                                                       long now) {
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                .ContentEntry.ContentData.ContentActualData content = tabContent();
        for (InnertubeEndpoint.Video video : videos) {
            content.addSection(new VideoListProto.ItemSectionRenderer().addIcsContent(
                    new VideoListProto.ItemSectionRenderer.IcsContents().addVideo(browseVideo(video, null, now))));
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
     * FEwhat_to_watch. Apps from version 11 show bottom tabs (Home, Trending, Account, with
     * icons); older ones get Home and Music tabs under a "What to watch" header.
     */
    static byte[] home(int clientMajorVersion, List<InnertubeEndpoint.Video> home,
                       List<InnertubeEndpoint.Video> second, long now) {
        boolean v11Tabs = clientMajorVersion >= 11;
        VideoListProto.Root root = browseRoot();
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer tabs =
                new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer();
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

    /** A single list of videos (FEtrending, FEmusic). */
    static byte[] videoList(String identifier, String title, List<InnertubeEndpoint.Video> videos, long now) {
        VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer tabs =
                new VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer()
                        .addTab(tab(identifier, title, true, 0, videoSections(videos, now)));
        return finishBrowse(browseRoot(), tabs, textHeader(title));
    }

    /** UC... channel page: header, Home (latest uploads shelf), Videos and Playlists tabs. */
    static byte[] channel(ChannelEndpoint.ChannelInfo info, List<InnertubeEndpoint.Video> uploads,
                          List<ChannelEndpoint.PlaylistSummary> playlists, long now) {
        String channelId = info.id;
        String name = info.name.isEmpty() ? channelId : info.name;
        VideoListProto.Root root = browseRoot();

        VideoListProto.SubscribeButtonData.SubContents subscribe = new VideoListProto.SubscribeButtonData.SubContents()
                .setChannelId(channelId)
                .setInt3(0).setInt4(1).setInt6(0).setInt8(0)
                .addSubscribeButton(browseRuns("Subscribe"))
                .addSubscribedLabel(browseRuns("Subscribed"))
                .addSubscribeLabel(browseRuns("Subscribe"))
                .addUnsubscribeLabel(browseRuns("Unsubscribe"))
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
            VideoListProto.ItemSectionRenderer section = new VideoListProto.ItemSectionRenderer();
            for (InnertubeEndpoint.Video video : uploads) {
                section.addIcsContent(new VideoListProto.ItemSectionRenderer.IcsContents()
                        .addVideo(browseVideo(video, info.avatar, now)));
            }
            VideoListProto.Root.ResponseBody.SingleColumnBrowseResultsRenderer.Tabs.TabRenderer
                    .ContentEntry.ContentData.ContentActualData videos = tabContent();
            videos.addSection(section);
            tabs.addTab(tab("Videos", "Videos", false, 0, videos));
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

    static byte[] search(List<InnertubeEndpoint.Video> videos, long now) {
        AndroidSearchProto.Root.Contents.SearchResults.SearchResultsContents.ItemSectionRender section =
                new AndroidSearchProto.Root.Contents.SearchResults.SearchResultsContents.ItemSectionRender();
        for (InnertubeEndpoint.Video video : videos) {
            section.addContent(new AndroidSearchProto.Root.Contents.SearchResults.SearchResultsContents
                    .ItemSectionRender.ItemSectionRenderContents().addVideo(searchVideo(video, now)));
        }
        return new AndroidSearchProto.Root().addContent(new AndroidSearchProto.Root.Contents()
                .addResultsContainer(new AndroidSearchProto.Root.Contents.SearchResults().addContent(
                        new AndroidSearchProto.Root.Contents.SearchResults.SearchResultsContents()
                                .addSection(section))))
                .toByteArray();
    }

    // ------------------------------------------------------------------ next (android_full_next)

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

    /** The watch page: video metadata, channel with subscribe button, related videos. */
    static byte[] next(InnertubeEndpoint.WatchPage page, long now) {
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
                new AndroidFullNextProto.AuthorRendererData.SubscribeButtonData.SubContents()
                        .setChannelId(channelId)
                        .setInt3(0).setInt4(1).setInt6(0).setInt8(0)
                        .setSubscribeButton(nextRuns("Subscribe"))
                        .setSubscribedLabel(nextRuns("Subscribed"))
                        .setSubscribeLabel(nextRuns("Subscribe"))
                        .setUnsubscribeLabel(nextRuns("Unsubscribe"))
                        .addAction(new AndroidFullNextProto.AuthorRendererData.SubscribeButtonData.SubContents
                                .SubAction().addSubscr1(new AndroidFullNextProto.AuthorRendererData
                                        .SubscribeButtonData.SubContents.SubAction.Sub1()
                                        .setId(channelId).setParams("b")))
                        .addAction(new AndroidFullNextProto.AuthorRendererData.SubscribeButtonData.SubContents
                                .SubAction().addSubscr2(new AndroidFullNextProto.AuthorRendererData
                                        .SubscribeButtonData.SubContents.SubAction.Sub2()
                                        .setId(channelId).setParams("c")))
                        .setIdentifier("watch-subscribe");
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
        sections.addItemSectionRender(nextSection().addContent(nextSectionContents().addVideoMetadata(metadata)));
        sections.addItemSectionRender(nextSection().addContent(nextSectionContents().addAuthorData(author)));
        AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults.SingleColumnWatchNextResultsContents
                .Results.ResultsContents.ItemSectionRenderer related = nextSection();
        for (InnertubeEndpoint.Video item : page.related) {
            related.addContent(nextSectionContents().addNextVideo(nextVideo(item, now)));
        }
        sections.addItemSectionRender(related);

        return new AndroidFullNextProto.Root()
                .addContent(new AndroidFullNextProto.Root.Contents().addNextResults(
                        new AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults().addContent(
                                new AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                                        .SingleColumnWatchNextResultsContents().addResult(
                                        new AndroidFullNextProto.Root.Contents.SingleColumnWatchNextResults
                                                .SingleColumnWatchNextResultsContents.Results()
                                                .addContent(sections)))))
                .setNavigationEndpoint(new AndroidFullNextProto.NavigationData().setNavProperties(
                        new AndroidFullNextProto.NavigationData.NavData().setNavId(video.id)))
                .setPoverlays(new AndroidFullNextProto.Root.PlayerOverlays().setOverlay(
                        new AndroidFullNextProto.Root.PlayerOverlays.PlayerOverlaysRenderer()))
                .toByteArray();
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

    static StreamFormat muxed720p(String url) {
        return new StreamFormat(22, url, 1280, 720, "hd720", "720p",
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
                formats.addNondashFormat(new AndroidPlayerProto.Root.PlayerFormats.Format()
                        .setFormatId(format.itag)
                        .setUrl(format.url)
                        .setMimeType(format.mimeType)
                        .setVideoWidth(format.width)
                        .setVideoHeight(format.height)
                        .setVideoQuality(format.quality)
                        .setQualityLabel(format.label));
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

    static byte[] guide() {
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
