# Changelog

## 0.41 - 2026-11-02

- **A list of Invidious instances** can be set in Settings -> Playback: a link to a JSON list (its
  `invidious` field), with `http://144.31.189.129/notPipe.json` filled in by default. For playback,
  muxing and audio the proxy tries the instances one after another - the one that last worked first,
  then the manually entered one, then the list - and skips any that does not answer in time or
  returns an error, so a dead instance no longer stops a video or a song from playing. Links an
  instance gives relative to itself are made absolute so they open.

## 0.4 - 2026-11-01

- **The old Google Play Music app plays again**, backed by YouTube Music. A separate **Play Music
  API** switch turns it on; the app (patched to reach this proxy) searches, browses Explore and
  plays, with the account token ignored so any signed-in account gets through.
- **Search and Explore come from YouTube Music**, not plain YouTube, so a result carries its real
  title, artist, album and square album art instead of a video thumbnail and an "official video"
  title.
- **The first play waits less.** The audio of the top search result is built ahead of the tap,
  the whole file is fetched once and remuxed from memory into the classic MP4 the old player can
  prepare, and a repeated request for the same track reuses that build.
- **Audio quality** is chosen in Settings -> Playback (high, normal or low); it sets the bitrate
  served for music and for background and audio-only play.
- The playback links of an Invidious instance are now fetched at the same time as YouTube's own
  answer instead of after it, the request log keeps 8 KB of a body instead of 48 KB, and the
  connection pool is large enough for the lookups a feed fans out to - so a video starts and a
  feed arrives sooner.
- **One screen and a Settings screen.** The app now shows the proxy switch and a way to its
  settings; the settings open as a list of categories - Playback, Watch page, Data, Storage - each of them a
  page of its own, and every category and every option says what it is for.
- **Less memory, fewer crashes on old phones.** How many lookups run at once and how many
  answers are kept are now read from the heap the phone gives the app, the proxy lets go of
  everything it holds when Android says it is short of memory, reading pages ahead can no longer
  hold up a request, and the answers kept on disk are written as bytes instead of base64 text.

- **Playlists play on.** A video opened from a playlist now comes with the whole queue, so the
  apps from version 12 show the list above the related videos, jump to an entry when it is
  tapped, and start the next one when a video ends.
- **Playing the next video by itself**, inside a playlist and, outside one, the first related
  video. It is turned on and off with the app's own **Autoplay** switch, in the "Up next" line
  above the related videos, which the apps from version 11 get.
- The buttons that step forwards and backwards beside the play button now appear only while a
  playlist is being played, as they do on YouTube.
- The apps from **version 14** get a **Download** button in the row under a video.
- The **home feed follows what has been searched for**: after three searches it is built out of
  them, the video last opened and the chart together, instead of the chart alone. What it is
  built from stays on the phone and **Clear cache** forgets it.
- **Select in app** no longer needs an Invidious instance: minting the token unlocks it too, the
  apps then get every quality YouTube has for a video, and the one chosen there is muxed out of
  YouTube's own streams instead of falling back to 360p.
- **A proof-of-origin token**, minted on the phone from Android 6 on: YouTube's own program is
  run in a hidden WebView and the token it mints goes with the player and media requests, which
  is what the server wants before it serves more than the first minute of a video.
- **Subtitles** can be turned off in Settings -> Watch page; a video no longer crashes the app
  from version 12 when it starts (the tracks were sent without the audio track they belong to).
- The Community tab loads ten posts instead of twenty and their pictures at the size the apps
  draw them, which is what made that tab crawl, and the proxy keeps its request threads between
  requests instead of starting one per connection.
- **Subtitles.** The player answer now carries the tracks of a video and they are served from
  the proxy at `/api/timedtext`, turned from what YouTube answers with today into the transcript
  these apps read; the same tracks are also served as the GData caption feed.
- A **Community tab** on channel pages for the apps from version 11: the posts of the channel,
  with their pictures, drawn by the view those apps use for comments.
- The comments scroll on for as long as YouTube has them, and reaching them no longer makes the
  phone stutter: pages are read from where the last one stopped instead of from the beginning,
  and the next page is read while the one on screen is being looked at.
- **Comments are on by default** and sit at the end of the watch page, under a "Comments" line of
  their own. The related videos then stay at one page: scrolling to the bottom of a video now
  arrives at the comments and goes on through them instead of loading more related videos.

## 0.3 - 2026-10-20

The release that serves the 2017 YouTube apps. Those apps (version 12 and up)
already understood most of what this proxy sent, but had no view for it, so
their screens stayed empty; they now get the pages they were written for.

### The 2017 apps (YouTube 12 and up)

- The classic **bottom bar** - Home, Trending, Library, Account - instead of the
  tab strip above the feed. It comes from the guide answer, so it appears on the
  next start of the app.
- A **watch page** with the title, the view count, the upload date, the
  description that opens, and the channel with its picture and a **SUBSCRIBE**
  button. The collapsed line shows the short subscriber count (390K), the
  expanded one the full number.
- **Home feed cards** as the app's own: a wide thumbnail, the title, the channel,
  views and age on a lighter line under it, and the channel picture beside them.
- **Comments** under the video, off by default - **Show comments** in the
  settings turns them on. Twenty per page, and the next ones load on scrolling.
- **Share** on the watch page opens the system share sheet with the video link.

### Lists that do not end

- **Search results, channel uploads and related videos** load the next page when
  the list is scrolled to its end, instead of stopping after the first twenty.
- **Search** answers with channels and playlists next to the videos.

### Playback

- An **Invidious instance** can be entered in the settings. With one set, the
  playback links come from it, which gives 720p with sound in a single file -
  the instance's links keep working past the first minute, which YouTube's own
  no longer do. Without an instance nothing changes.
- **Select in app**, a new playback format available while an instance is
  entered: the player answer lists every quality the video has (144p to 1080p)
  and the choice is made in the app's own quality menu. 360p comes straight from
  YouTube, the higher qualities are muxed by the proxy.
- The **original audio track** is used. A video with dubs lists one stream per
  language and the answer does not name them, so a Polish video used to play
  with its English AI dub.
- **Playing in the background** works again: the answer now carries the sound of
  the video as an adaptive stream, with the ranges and the length the background
  player needs. The apps used to stop at the lock screen with "No streaming data
  available for background playback" or "Audio not supported/available".

### Settings and speed

- A **Clear cache** button: it forgets the feeds, videos and search results kept
  on the phone, and keeps the settings. The settings screen scrolls, so the
  buttons at its bottom can be reached on a small screen.
- Answers are built from fewer lookups and cached in memory, feeds carry the
  channel pictures they already contain instead of asking for them one by one,
  and requests that cannot be read are answered instead of failing with HTTP 400.

## 0.21 and earlier

See the commit history: the protobuf Innertube API for the 2015-2016 apps,
HTTPS on rooted devices, playlists, Shorts in uploads, the activity feed,
search paging and the live Most Popular feed.
