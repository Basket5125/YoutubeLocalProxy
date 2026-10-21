# YouTube Proxy for Android

An Android HTTP proxy that provides legacy YouTube Data API-style Atom feeds and
redirects supported playback requests to YouTube's media servers. It is designed
to run on-device, including older phones. Video data is streamed directly from
YouTube; the app does not download or transcode video.

## Features

- Innertube-backed search, channel profiles, uploads, playlists, related videos,
  and comments.
- YouTube Data API v3-backed popular feeds and optional upload metadata.
- Playback redirects for supported progressive MP4 formats.
- A second API for the 2015-2016 YouTube Android apps: the protobuf Innertube
  API (`/youtubei/v1/...`), see below.
- Local HTTP server on port `8080`, with an optional rooted-device redirect
  from port `80`.
- English app interface.

Some legacy endpoints are placeholders. YouTube can change or restrict Innertube
responses, and playback may not work when a format requires a signature that
cannot be resolved by this app.

## Requirements

- Android 2.3 (API 9) or newer.
- JDK 11 or 17.
- Android SDK with Android SDK Platform 33 and Build Tools installed.
- Internet access to download Gradle and Maven dependencies on the first build.

The Gradle Wrapper pins Gradle 7.5. The Android Gradle Plugin is 7.4.2.

## Build a release APK

On macOS or Linux:

```sh
./gradlew :app:assembleRelease
```

The build runs on Gradle 7.5, which reads the build files with the JDK it is
started with and understands JDK 17 at the newest. On a machine whose default
`java` is newer than that, the build stops at "Unsupported class file major
version"; point it at an older JDK for that run:

```sh
JAVA_HOME=/path/to/jdk-11 ./gradlew :app:assembleRelease
```

On Windows:

```bat
gradlew.bat :app:assembleRelease
```

The unsigned APK is written to:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

The generated release APK is **not signed**. Sign it with your own private
release keystore before distributing it. Never commit the keystore or its
passwords to this repository. For local testing, build the debug variant with
`./gradlew :app:assembleDebug`.

To install a signed APK using Android Debug Bridge:

```sh
adb install path/to/signed.apk
```

## Run the proxy

Install and launch the app, then leave **Proxy enabled** turned on. By default,
the HTTP server listens on port `8080`.

On a rooted device, the service can try to redirect local port `80` to `8080`.
Root is only used to add or remove this iptables rule; the HTTP server itself
does not require root. If root access is unavailable, the redirect is skipped
and the proxy remains available on port `8080`; configure the client to use
`http://127.0.0.1:8080`. From another device on the same network, use the Android
device's LAN IP, for example:

```text
http://192.168.1.25:8080
```

For HTTP 500 errors, inspect the proxy's server-side exception with:

```sh
adb logcat -s ProxyServer
```

After an app crash, the next launch offers to send a report. Nothing is uploaded
unless **Send logs** is selected. A report includes the crash stack, bounded
YouTube HTTP request/response logs, app version, device model, and Android
version. API keys, visitor data, and request signatures are redacted. Reports
are stored locally until upload succeeds; selecting **Not now** keeps them on
the device. The HTTPS host can see the sender's IP address while handling an
upload.

## Video search

Video search (`/feeds/api/videos?q=`) supports `start-index` and `max-results`
(default 20, up to 50) and links the next page; while one page is shown, the
next page and its metadata are loaded in the background. Loaded search pages,
playlist pages and uploads are kept in memory for 10-30 minutes, so later pages
continue where the previous request stopped. Finished feeds are cached on disk:
search 30 minutes, uploads and activity 10 minutes, playlists 15 minutes,
channel playlists 30 minutes.

Example endpoints:

```text
/feeds/api/videos?q=search+terms
/feeds/api/users/UC_CHANNEL_ID
/feeds/api/users/UC_CHANNEL_ID/uploads
/feeds/api/users/UC_CHANNEL_ID/playlists
/feeds/api/users/UC_CHANNEL_ID/playlists/PLAYLIST_ID
/feeds/api/playlists/PLAYLIST_ID
/feeds/api/videos/VIDEO_ID/related
/feeds/api/videos/VIDEO_ID/comments
/feeds/api/standardfeeds/US/most_popular
/feeds/api/standardfeeds/US/most_popular?category=10
/feeds/api/videoCategories
/feeds/api/channels?q=music
/feeds/api/users/UC_CHANNEL_ID/activity
/feeds/api/events?author=UC_CHANNEL_ID
/video/VIDEO_ID
```

Popular-video feeds can be filtered by a YouTube video category ID or category
name, for example `category=10` or `category=Music`. With a YouTube Data API v3
key, the proxy requests `videos.list` with `chart=mostPopular` and
`videoCategoryId` (cached for a day). Without a key, or when the Data API fails,
the feed comes from Innertube (cached for an hour); YouTube's Trending page no
longer exists, so the proxy lists the most viewed videos uploaded this week and
adds a music video from the region's "Trending 20" chart (YouTube Music) as
every fifth entry. The Music category uses
the region's "Top 100 Music Videos" chart, and other categories list the most
viewed videos of this month matching the category name. Live streams and Shorts
are left out. Legacy chart names (`top_rated`, `most_viewed`,
`recently_featured`, ...) and paths without a region return the same feed. When
YouTube cannot be reached, the last feed is served, or an empty feed if there is
none. The proxy also accepts legacy category
paths such as `/feeds/api/standardfeeds/PL/most_popular_Autos`. The category
catalog is available at `/feeds/api/videoCategories`. Channel search accepts
`q`, channel activity (`/feeds/api/events?author=`, as requested by the legacy
apps' channel Activity tab, or `/feeds/api/users/ID/activity`) returns the seven
newest uploads as `video_uploaded` events with an embedded video entry, and
`/feeds/api/charts/live/events/live_now` returns live videos.

Channel upload feeds are read from the channel's `UU` uploads playlist, so they
include Shorts and past live streams in upload order; the Videos tab is used as a
fallback. Playlist feeds follow yt2009's layout: channel playlist entries link to
`/feeds/api/users/ID/playlists/PLAYLIST_ID`, and playlist feeds start with the
playlist title, author, video count and thumbnail, followed by video entries with
`yt:position`. Both playlist routes support `start-index` and `max-results`.
Usernames and handles are resolved to channel IDs. Channel search results are cached for
30 minutes and include the subscriber count, avatar and uploads/playlists links,
so clients do not need to load every profile; profiles
(`/feeds/api/users/ID`, also `/feeds/api/channels/ID`) are cached for an hour.
Activity entries show the channel name, not its ID.

The **Playback format** setting controls all video redirect endpoints and takes
effect on the next video. It defaults to 360p video with audio; the other
options redirect to the best H.264 video-only stream up to 1080p, or to the
highest-bitrate AAC audio stream of the original audio track. When the selected
format is unavailable, the 360p stream is used.

**Experimental: 720p with audio.** YouTube offers 720p only as separate video and
audio streams. In this mode the proxy muxes the 720p H.264 video-only stream and
the AAC audio stream on the phone while the video plays, without transcoding.
Both streams are fragmented MP4: as in the DASH manifest Invidious builds from
the player's `adaptiveFormats`, the `initRange` and `indexRange` fields give the
stream header (`moov`) and its fragment index (`sidx`). For videos with up to 480
fragments (roughly 30 minutes), the proxy first reads every fragment header
(about 2 KB each, in parallel) and writes a classic MP4: one `moov` with complete
sample tables (`stts`, `ctts`, `stss`, `stsc`, `stsz`, `stco`) followed by the
raw samples of both tracks, interleaved every half second like YouTube's own
progressive MP4s (Samsung's Android 4.x player cannot buffer files with
5-10 second chunks). This plays on any Android version that
can decode 720p H.264 and seeks by index. Longer videos are sent as fragmented
MP4 (both headers merged into one file with two tracks, fragments in time order
with rewritten track and sequence numbers), which needs a player with fragmented
MP4 support. The file size is known up front, so the response has an exact
`Content-Length` and supports `Range` requests; a few chunks are downloaded
ahead. If muxing fails, the 360p stream is used.

Limitation: without a PO token (generated by YouTube's BotGuard script in a
browser, which this app cannot run), YouTube serves most adaptive stream URLs
only for about the first minute and answers HTTP 403 beyond that. Before muxing,
the proxy therefore checks that the end of both streams is reachable, trying
several clients (older ANDROID_VR versions sometimes get complete files, but
YouTube often answers them with a bot check). When no client gets complete
streams, the video plays in 360p instead of stopping after a minute. Diagnostics are logged under
the `Mp4Mux` tag (`adb logcat -s Mp4Mux VideoRedirect`). Redirects use `302` with
`Cache-Control: no-store` so players do not reuse a previous format's stream. Player responses
are kept in a bounded in-memory cache and cleared when the app task is removed
or the proxy service stops; they are not written to disk.

Work that does not belong on the answer path is kept off it: the request log of the crash
reporter and the disk copies of the protobuf answers are written by background threads (logging
a response used to hold up every YouTube call under one lock), the home feed looks up the most
viewed videos and the region's music chart at the same time instead of one after the other, and
the page after the one just answered is loaded in the background once a list has been scrolled.
Answers already built are kept in memory (96 of them), so scrolling back costs nothing.

Feeds wait at most two seconds for extra metadata (full descriptions, publish
dates, categories, like counts). Lookups still running after that finish in the
background and are cached (feed metadata for 24 hours, without stream URLs), so
the next request for the same videos is answered with complete data at once.
HTTPS connections are kept alive and responses are gzip-compressed. All dates
are written as UTC timestamps with milliseconds (`2024-05-01T15:00:00.000Z`),
the form legacy clients parse.

## Protobuf Innertube API (2015-2016 YouTube apps)

YouTube apps from about version 10 onwards no longer use the GData feeds; they
send protobuf requests to `/youtubei/v1/...`. The proxy answers these on the same
port, with the same data as the GData feeds:

| Request | Answer |
| --- | --- |
| `POST /youtubei/v1/browse` `FEwhat_to_watch` | Home. Apps from version 12 get one Home tab and navigate with the bottom bar (below); version 11 gets Home, Trending and Account tabs above the feed, older apps Home and Music. |
| `browse` `FEtrending`, `FEmusic` | The popular chart and the region's music chart. |
| `browse` `FEsubscriptions`, `FElibrary`, `FEaccount` | The pages behind the bottom bar's other items: links to the feeds this proxy serves, since signing in is not supported. |
| `browse` `UC...` (or `@handle`) | Channel header (avatar, banner, subscribers), latest uploads, Videos and Playlists tabs. |
| `browse` `VL...` | Playlist header and up to 100 videos. |
| `POST /youtubei/v1/next` | Watch page: title, views, date, likes, description, channel with avatar and subscribers, related videos. |
| `POST /youtubei/v1/player` | Video details and the 360p MP4 stream (direct googlevideo.com URL). With the experimental 720p mode, the stream muxed by the proxy is added as itag 22. |
| `POST /youtubei/v1/search` | 20 results per page, with the channels and playlists YouTube found first; `SearchRequestParams.index` selects a page and a continuation token the next one. |
| `POST /youtubei/v1/guide`, `navigation/resolve_url` | Side menu; links to videos, channels, handles, playlists and searches. Apps from version 12 also get the bottom bar from the guide answer. |
| `config`, `deviceregistration/v1/devices`, `c2dm/register3`, `device_204` | Start-up stubs. |

### The 2017 apps (version 12 and newer)

These apps ignore two of the messages the 2015-2016 apps used, so they are answered differently:

- **The bottom bar.** YouTube 12 navigates with the classic bar at the bottom of the screen
  (Home, Trending, Subscriptions, Library), not with tabs above the feed. The app takes it from
  the `guide` answer: an item with a `pivotBarRenderer` (field `117866661`), whose entries
  (`pivotBarItemRenderer`, `117501096`) each carry an identifier, a browse endpoint, a title and
  an icon - the app reads the icon without checking for null, so every entry has one
  (`PIVOT_HOME` 406, `PIVOT_TRENDING` 407, `PIVOT_SUBSCRIPTIONS` 408, `PIVOT_LIBRARY` 410).
  Only the `IconType` values this version has a drawable for are usable: the enum is in
  `ajlr.smali` and the drawables it maps to in `gsu.smali`, and an icon that is missing there is
  drawn as an empty image (`PIVOT_ACCOUNT` 411 is one of them, so the Account page uses
  `TAB_ACCOUNT` 69). The home
  answer then has a single tab, because the app only puts a tab strip above the feed when a
  browse answer has more than one tab. The app keeps the guide answer on disk, so the bar
  appears the next time the app starts.
- **The feed cards.** Home and Trending use `videoWithContextRenderer` (`89018663`, presenter
  `hzx`, layout `video_feed_entry`): a wide thumbnail with the duration over it, the title under
  it (field 1) and one line of "Channel - views - age" (field 2), with the watch endpoint in
  field 7 and the video id in field 14. The 2015-2016 apps keep the compact renderer, whose card
  is a small thumbnail next to the title.
- **The watch page.** Version 12 parses `videoMetadataRenderer` but has no view for it, which is
  why its watch page showed nothing but the related videos. It displays
  `slimVideoMetadataRenderer` (`119167901`) instead: title, the collapsed subtitle ("1.2M
  views"), the expanded subtitle (full view count and publish date), the buttons under the video
  (like with its count, dislike, share, add to), the channel row (`slimOwnerRenderer`, `119170535`, with avatar, name, subscriber
  count and subscribe button) and the description, which the app shows once the panel is
  expanded with the arrow next to the title.

Older apps keep the previous messages; the version comes from the request context, with the
`x-youtube-client-version` header as a fallback.

The message layouts come from the gdatav2innertube server. Its `.proto` files are
in `app/src/main/proto/`, and `tools/protoc_java.py` compiles them to plain Java
classes in `com.viitube.proxy.proto` (no protobuf library, so they run on Android
2.3). After changing a `.proto` file, run `python3 tools/protoc_java.py` and
commit the generated files. The encoding matches google-protobuf's: fields in
declaration order, proto3 defaults omitted, `optional` fields written when set.

Answers are cached for 10 minutes (watch pages) to an hour (home and charts).
Signing in, subscribing and rating are not supported.

### Endless lists

Search results, a channel's uploads and the related videos under a video are loaded in pages
while scrolling: when the last entry of a list is on screen, the app sends the continuation token
of that list back and appends what comes in. The token travels in the browse request's field 7,
the search request's field 8 and the `next` request's field 8; the answer is a
`continuationContents.sectionListContinuation` (renderer id `49399797`) in field 10 of a browse
answer, field 7 of a search answer and field 8 of a `next` answer, holding the next entries and,
unless the list ends there, the token after them.

The tokens are written by the proxy, not by YouTube: `vtp1|s|40|cats` means "the search for cats
from result 40 on", with `u` for a channel's uploads and `r` for related videos. Nothing is kept
per token, so they survive a restart of the proxy, and a token this proxy did not write is
answered with an empty message, which ends the list in the app. Lists stop after 1000 entries.
Search pages come from the search cache (the next page is already being loaded while one is
shown), uploads from the channel's uploads playlist, and related videos by following YouTube's
own continuations. The popular feeds (home, Trending, Music) are a fixed chart and are not paged.

## HTTPS on rooted devices

Newer YouTube apps refuse plain HTTP, so the proxy can also serve HTTPS on the
loopback interface. This only happens on a rooted device; without root nothing
is attempted and nothing on the device changes (the plain HTTP server runs as
before).

When `https_enabled` is set and root is available, on start the proxy:

- generates a self-signed certificate for `localhost` (SAN `localhost` and
  `127.0.0.1`) on first run, kept in the app's private storage. The key pair is
  created on the device, is never shipped in the APK, and never leaves the phone;
- runs an HTTPS server on `https_port` (default `8443`) using that certificate;
- redirects port `443` to `https_port` with `iptables`, so patched apps can reach
  `https://localhost`;
- copies the certificate into the system trust store as
  `/system/etc/security/cacerts/<hash>.0` so the app trusts the local server.

On stop it removes the `iptables` rule, stops the HTTPS server, and deletes the
certificate from the system trust store. Every install and removal is logged
under the `SystemCertStore` tag (`adb logcat -s SystemCertStore ProxyService`).
Start the proxy before opening YouTube so the app picks up the certificate.

Security notes: the certificate is a trust anchor while it is installed, so it is
removed again when the proxy stops rather than left on the device. The private
key stays in app-private storage; keep the device's root access trusted. On
Android 10+ the `/system` partition may be read-only even with root, so the
install can fail; the proxy then logs the failure and serves plain HTTP only.

## Configuration

**Invidious instance** in the app is optional; with one set, playback comes from it. The proxy
asks the instance for `/api/v1/videos/{id}?local=true`, which answers with links the instance
serves itself. YouTube rarely offers itag 22 (720p in one file) any more, so 720p is muxed here
from the instance's 720p H.264 stream and its AAC audio, the same way the experimental 720p mode
does - but the instance's links keep working past the first minute, which YouTube's own adaptive
links do not without a PO token. The single file the instance does have (usually 360p) is the
fallback and is what the app gets as the small format. The answer is used for both the video
redirects and the protobuf player answer, and is kept for ten minutes. Leave the field empty to
use YouTube directly, as before. Public instances often refuse requests from outside their own
network, so an instance of your own is the reliable choice.

The **Playback format** setting gains **Select in app** while an instance is entered: the player
answer then lists every quality the instance has (360p straight from YouTube, the higher ones
muxed by this proxy), so the choice is made in the app's own quality menu. A link this proxy
writes carries the quality in `?q=720`; the legacy clients can ask with `fmt=22` instead. The
itags and quality names are YouTube's own for each height (17, 36, 18, 59, 22, 37). The formats
are listed from 144p upwards and carry the sort key (field 31) the apps order them by: they sort
the list and then drop every entry whose quality is lower than the one before it, so a list that
falls leaves a single item and no menu at all. With a fixed format in the settings only that one
format is sent, so the app cannot land on anything else.

Every player answer also carries the sound of the video on its own, because without it the apps
stop at the lock screen. They keep two lists of formats and read a different one for each case:
playing on screen uses the plain list (`streamingData` field 2), playing in the background the
adaptive list (field 3), where only itag 139 or 140 is accepted - with an audio file in the plain
list alone the log says `Audio not supported/available. supportedAudioItags=[139, 140]
formatStreams=[]`. So the answer names it in both: in the adaptive list with the ranges of its
header and of its index and with its length (fields 9, 10 and 12), which is what the background
player reads the file with. The stream is the instance's when an instance is set, otherwise
YouTube's own audio stream; if neither has one, the 360p file is named instead, which plays even
though it brings its video along. The audio is always the original track - an answer lists one
stream per dubbed language and only the link says which is which (`xtags=acont=original` against
`acont=dubbed:lang=pl`), so a Polish video is no longer played with its English AI dub.

**Show comments** in the app adds them under the video for apps from version 12. It is off by
default: those apps have no place of their own for comments, so they end up in a section under
the video, and loading them makes a watch page slower. They are fetched next to the watch page
and waited for at most four seconds, so a video with slow comments still opens at once.

**Comments** are answered on the protobuf API as well. They ride along with the watch page, in a
section of their own - a section that carries only a continuation token is never asked for,
because nothing of it is on screen to trigger it. The token for the next twenty goes with them,
and the answer to that token is an `itemSectionContinuation` of `commentThreadRenderer`
(`62285833`), each holding a `commentRenderer` (`62285947`) whose author is field 1, the picture
field 2, the author's channel field 3, the text field 4 and the time field 28 - the numbers come
from the app's own presenter. Twenty comments per page, with the token for the next ones; the
data is the same lookup the GData comment feed uses, which today means YouTube's comment
entities rather than the old renderers.

The **Share** button under a video opens the system share sheet: its endpoint (`106983893`) is an
ACTION_SEND intent spelled out as a message - the extras, the type and the chooser title - which
the app puts together itself.

**Clear cache** in the app empties everything the proxy keeps: the feeds, search results,
playlists, channel profiles, player answers and protobuf answers in memory, and the files under
the app's cache directory. Settings and a crash report waiting to be sent are kept, so the next
request simply asks YouTube again. Use it when a feed shows outdated or broken data.

The app copies `app/src/main/assets/config.json` into its private app storage on
first launch. The generated private `config.json` can be edited on a rooted or
debuggable device to change the listening port or Innertube settings.

The optional YouTube Data API v3 key can be entered in the app. It is stored
locally and can be used for popular feeds and batched titles, full descriptions,
view counts, and like counts. Without a valid key, video feeds request full
metadata from Innertube in parallel and load like counts from the public Return
YouTube Dislike API, with local caching and a short wait limit to keep responses
suitable for older devices. Channel upload feeds also preserve the channel name
provided by the video when an uploads response omits channel metadata. YouTube
may still omit metadata or reject a request, in which case unavailable fields
remain blank or use the values present in the channel feed.

Malformed feed results are skipped: video IDs must be 11 valid YouTube ID
characters, and each entry must have a title and channel identity. A standard
thumbnail URL is used when the response omits a thumbnail. Video descriptions
and counts may legitimately be empty or zero.

Do not put personal API keys, signing keys, or other secrets into source files
or commit them to GitHub.

## Project layout

- `app/src/main/java/` — Android app and proxy implementation.
- `app/src/main/assets/` — default configuration and XML feed templates.
- `app/src/main/proto/` — protobuf messages of the Innertube API.
- `app/src/main/java/com/viitube/proxy/proto/` — Java code generated from them.
- `tools/protoc_java.py` — the generator.
- `gradle/wrapper/` — pinned Gradle Wrapper.

## License

This project is licensed under the MIT License - see the LICENSE.MD file for details.
