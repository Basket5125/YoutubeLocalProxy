# Changelog

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
