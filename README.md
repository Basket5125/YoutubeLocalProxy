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

## Crash report receiver

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
| `POST /youtubei/v1/browse` `FEwhat_to_watch` | Home. Apps from version 11 get Home, Trending and Account tabs; older apps get Home and Music. |
| `browse` `FEtrending`, `FEmusic` | The popular chart and the region's music chart. |
| `browse` `UC...` (or `@handle`) | Channel header (avatar, banner, subscribers), latest uploads, Videos and Playlists tabs. |
| `browse` `VL...` | Playlist header and up to 100 videos. |
| `POST /youtubei/v1/next` | Watch page: title, views, date, likes, description, channel with avatar and subscribers, related videos. |
| `POST /youtubei/v1/player` | Video details and the 360p MP4 stream (direct googlevideo.com URL). With the experimental 720p mode, the stream muxed by the proxy is added as itag 22. |
| `POST /youtubei/v1/search` | 20 video results per page; `SearchRequestParams.index` selects the page. |
| `POST /youtubei/v1/guide`, `navigation/resolve_url` | Side menu; links to videos, channels, handles, playlists and searches. |
| `config`, `deviceregistration/v1/devices`, `c2dm/register3`, `device_204` | Start-up stubs. |

The message layouts come from the gdatav2innertube server. Its `.proto` files are
in `app/src/main/proto/`, and `tools/protoc_java.py` compiles them to plain Java
classes in `com.viitube.proxy.proto` (no protobuf library, so they run on Android
2.3). After changing a `.proto` file, run `python3 tools/protoc_java.py` and
commit the generated files. The encoding matches google-protobuf's: fields in
declaration order, proto3 defaults omitted, `optional` fields written when set.

Answers are cached for 10 minutes (watch pages) to an hour (home and charts).
Continuation requests (`browse` with a continuation token) get an empty answer,
because the reference server has no message for them. Signing in, subscribing
and rating are not supported.

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
