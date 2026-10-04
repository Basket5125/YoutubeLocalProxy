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
- Local HTTP server on port `8080`, with an optional rooted-device redirect
  from port `80`.
- English app interface.

Some legacy endpoints are placeholders. YouTube can change or restrict Innertube
responses, and playback may not work when a format requires a signature that
cannot be resolved by this app.

## Requirements

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
does not require root. Without root, configure the client to use
`http://127.0.0.1:8080`. From another device on the same network, use the
Android device's LAN IP, for example:

```text
http://192.168.1.25:8080
```

Example endpoints:

```text
/feeds/api/videos?q=search+terms
/feeds/api/users/UC_CHANNEL_ID
/feeds/api/users/UC_CHANNEL_ID/uploads
/feeds/api/users/UC_CHANNEL_ID/playlists
/feeds/api/videos/VIDEO_ID/related
/feeds/api/videos/VIDEO_ID/comments
/feeds/api/standardfeeds/US/most_popular
/video/VIDEO_ID
```

## Configuration

The app copies `app/src/main/assets/config.json` into its private app storage on
first launch. The generated private `config.json` can be edited on a rooted or
debuggable device to change the listening port or Innertube settings.

The optional YouTube Data API v3 key can be entered in the app. It is stored
locally and can be used for popular feeds and batched upload titles, descriptions,
and view counts. Without a valid key, uploads request missing title, description,
and view-count details from Innertube in parallel, with a short overall wait
limit and a local cache to keep responses suitable for older devices. YouTube may
still omit metadata or reject a request, in which case unavailable fields remain
blank or use the values present in the channel feed.

Do not put personal API keys, signing keys, or other secrets into source files
or commit them to GitHub.

## Project layout

- `app/src/main/java/` — Android app and proxy implementation.
- `app/src/main/assets/` — default configuration and XML feed templates.
- `gradle/wrapper/` — pinned Gradle Wrapper.

## License

This project is licensed under the MIT License - see the LICENSE.MD file for details.
