# Architecture

This document explains the decisions that are not obvious from reading the code, and in
particular the ones made in direct response to how apps in this category typically fail.

## The shape of it

```
app/src/main/java/app/opentv/
├── core/           ServiceLocator — the whole dependency graph, one file
├── data/
│   ├── model/      Domain models, which double as Room entities
│   ├── db/         Room database and DAOs
│   ├── remote/     XtreamApi — the provider panel client
│   ├── parser/     M3uParser, XmltvParser — pure, streaming, heavily tested
│   ├── repo/       SourceRepository, CatalogRepository, EpgRepository
│   └── work/       SyncWorker — periodic background refresh
├── player/         PlayerController, PlaybackErrors
└── ui/             Compose screens and view models
```

Single module, single Gradle build. A multi-module setup would build faster in CI and is what
a team of ten would do. This is not a team of ten; it is a project whose survival depends on a
stranger with a broken channel being able to find the relevant code in under five minutes.

## No backend. At all.

OpenTV has no server. There is no account, no login, no cloud sync, no web dashboard for
editing your channel list from a browser.

This is the single most important architectural decision in the project, and it is worth being
explicit about why, because a web dashboard is a genuinely nice feature and users ask for it.

**A cloud dashboard has to be paid for every month, forever, per user.** An app funded by a
one-off payment or by donations cannot carry that. Every player that has tried has arrived at
the same place: the hosting bill grows with success, the revenue does not, and eventually the
servers go quiet — taking the app with them, because by then the app cannot start without
them. Users who paid discover that their "lifetime" purchase bought them a client for a
service that no longer exists.

So OpenTV stores everything on the device, in SQLite. The consequences are accepted honestly:

- Setting up a second TV means entering your details again. This is a real cost.
- Favourites and channel ordering do not follow you between devices.

And what is bought with it:

- The app costs nothing to run, so it cannot be switched off.
- Provider credentials never leave the device. Nobody has to trust the maintainers with them.
- It works with no internet connection to anything except your own provider.

Multi-device transfer is available as an explicit **export/import of a `.fulltv` file**. It uses
the system document picker and AES-256-GCM with a key derived from the user's passphrase; there is
still no server to fund. The encrypted snapshot contains sources/credentials, portable settings,
user-added EPG feeds and the existing stable-key sync bundle. Import merges sources, refreshes
catalogues and the guide, then applies favourites and watch positions. It deliberately excludes
catalogue/EPG caches, local video recordings, device IDs, filesystem paths and USB SAF grants; those
are either re-downloaded or must be selected again on the new device.

## The EPG, and why it is written the way it is

A guide that quietly stops working is the defining bug of this app category. It is almost
always caused by the same implementation:

```
delete everything for this source
download the new guide
insert it
```

That is fine when it works. When the download stalls, the provider rate-limits, the box sleeps
mid-sync, or the XML is truncated, the user is left with an empty guide — and the only advice
anyone can give is "reinstall the app", which appears to help because it restarts the sync from
scratch. It fixes nothing. The next refresh fails the same way.

`EpgRepository` never deletes before it has the replacement:

1. Programmes are **upserted in batches of 500** as they stream out of the parser. The unique
   index on `(sourceId, epgChannelId, startUtcMillis)` makes this idempotent, so a sync that
   dies at 60% leaves 60% of a *fresher* guide — strictly better than what was there.
2. Pruning happens **by age** (`deleteEndedBefore`), never by source, and only **after** a sync
   reports success.
3. A failed sync leaves the previous guide completely intact and returns the reason.

The future cutoff is user-configurable from 2 to 14 days (2 days by default). The same setting is
used while parsing and pruning, so series rules can reserve matching airings as far ahead as the
provider's XMLTV feed actually publishes. Increasing the setting cannot create guide data which
the feed does not contain; the Recording settings screen offers an explicit refresh and rescan.

`XmltvParser` streams via `XmlPullParser` and emits programmes to a callback. A week of guide
data for a large provider is comfortably 150 MB of XML; nothing about that fits in the heap of
a cheap TV stick, and `String`-based parsing is an OOM waiting to happen.

`M3uParser` scans comment/directive lines throughout the playlist for repeated `url-tvg` and
`x-tvg-url` attributes, splits multiple URLs in one value, and de-duplicates in first-seen order.
The first URL remains the source guide; every other unique URL is imported and enabled
automatically. Existing feed toggles are preserved, and a URL removed from the playlist also loses
its stored guide rows. This can generate large network and storage use for multi-region lists;
Settings provides a confirmed action to pause and clear extra discovered feeds. Plain HTTP remains
blocked by the manifest security policy.

**Times are stored as UTC epoch millis, always.** `XmltvParser.parseXmltvTime` normalises the
`+0100`-style offsets that XMLTV uses, treats a missing offset as UTC rather than guessing the
device zone, and converts to local time only at draw time. `XmltvTimeTest` changes the JVM's
default zone between assertions specifically to catch the "works on my machine, an hour out on
yours" bug.

## Catalogue refresh preserves what the user did

`ChannelDao.replaceCatalogue` merges rather than wipes: it reads existing rows, carries
`favourite`, `hidden` and `sortIndex` across to the incoming rows by `streamId`, upserts, and
only then deletes channels the provider no longer lists.

This is why `M3uParser` works so hard to produce a **stable** `streamId` for playlists with no
`tvg-id` — an FNV-1a hash of the URL rather than `String.hashCode()`. An unstable id means
every refresh looks like a completely new set of channels, and the user's favourites silently
vanish.

## SQLite's bound-variable limit is a real constraint here

Two queries are written in a non-obvious way because of it, and both are marked in the code.

SQLite caps the number of bound variables in a statement — 999 on older Android versions,
32766 on newer. A real IPTV catalogue has thousands of channels and a large playlist has tens
of thousands. So neither of these works, despite being the natural thing to write:

- `DELETE FROM channels WHERE streamId NOT IN (:everyCurrentId)` — replaced by a sync-stamp
  comparison (`Channel.lastSeenMillis`), which has no limit.
- `SELECT * FROM programmes WHERE epgChannelId IN (:everyChannelId)` — replaced by a
  time-window query with grouping done in memory by the caller.

Both would pass every test written against a small synthetic dataset and fail on exactly the
providers where it matters most.

## Startup and storage budget

The process does not read every movie and series row during startup. The VOD shelf is loaded only
when Movies/Shows is opened, and a periodic worker refreshes live channels and the guide without
downloading a large VOD catalogue. VOD uses a 12-hour on-demand freshness policy. Startup prunes
old guide rows but never runs SQLite `VACUUM`; a storage settings screen reports APK/data/database,
model and recording sizes. Database compaction is user-triggered, requires free-space headroom, and
does not delete recordings. A separately confirmed action can clear programmes from playlist-
discovered guide feeds.

## Playback

Each `PlayerController` owns exactly one `ExoPlayer` for the lifetime of its playback surface and
only ever swaps its media item. Full-screen playback and the guide preview each use one controller;
the multi-channel mosaic is the explicit exception and owns one controller per selected tile.

The naive implementation of "change channel" releases the player and builds a new one. On a
low-end box that is slow enough that a user holding channel-up queues several constructions and
teardowns, and the codec ends up in a state where nothing plays until the app is force-stopped.
That is the entire mechanism behind "changing channels too quickly causes streams to fail", and
it is self-inflicted.

So: requests are debounced (350 ms), an in-flight switch is cancelled the moment a newer one
arrives, and the player is `stop()`ed rather than released. Holding channel-up costs one actual
tune — the one you stopped on.

### Multi-channel mosaic

`MosaicScreen` can start up to four independent live controllers after the user selects channels
and confirms a warning; merely opening the tab never opens provider connections. Each tile uses the
shallow preview buffer, has subtitles disabled, and only the focused tile plays audio. The screen
stops all tile controllers before handing a channel to the normal full-screen player. Four streams
still mean four provider connections and video decoders: some providers limit concurrent streams,
and some low-end TV boxes cannot sustain four decoders. The in-app warning makes that limitation
explicit; support depends on the provider and device.

### YouTube channel handles

M3U entries whose playback URL is a YouTube `@handle` or live video URL are resolved immediately
before playback. The APK bundles Streamlink's Python library through Chaquopy; its short-lived media
URL is passed to the existing Media3 player and is never stored in the catalogue. Full-screen
playback and guide preview share this path; other providers keep their existing path. The resolver
starts lazily, so ordinary channels do not initialize Python.

### YouTube live search and recording

The user may add a public YouTube `/results` URL as a `YOUTUBE_LIVE_SEARCH` source. A bundled,
version-pinned yt-dlp extractor reads at most 50 search entries, filters for active-live metadata,
and exposes up to five watch URLs as catalogue channels. No YouTube API key or server is involved;
the extractor depends on YouTube's public page and may be affected by rate limits or anti-bot
responses. Selected watch URLs go through Streamlink like handle-based channels.

YouTube live recording uses Streamlink's `stream.open()` reader instead of saving the HLS manifest
text. A capture worker appends media-byte chunks to the selected `RecordingStorage` sink, reports
file size periodically, reconnects when a segment reader ends, and closes its reader when the user
stops or the scheduled end is reached. This behavior is specific to recognized YouTube URLs;
ordinary IPTV recordings keep their HTTP capture path.

### Automatic Portuguese captions

Automatic captions are opt-in and are wired only into the full-screen player. A Media3
`TeeAudioProcessor` copies decoded 16-bit PCM into a bounded, drop-oldest worker queue; five-second
mono 16 kHz Whisper windows overlap by two seconds. The playback thread never waits for inference,
and stale windows are dropped if the device cannot keep up. Whisper runs locally with the
multilingual `tiny` model, downloaded on first use with a pinned SHA-256 check. ML Kit identifies
the recognized text language and downloads the translation model for Portuguese; subsequent
translation is on-device. The audio itself is never uploaded. The current prebuilt Whisper AAR is
arm64-v8a only, the feature declines devices reporting less than 512 MB app memory, and the tap
requires decoded PCM (encoded passthrough streams may not yield captions). The feature is disabled
by default because it adds model storage, CPU and memory pressure.

### Retries are tuned for IPTV, not for CDNs

ExoPlayer's default policy gives up almost immediately on a 403 or a 5xx. That is right for a
CDN and wrong here: a provider under load returns 403, 429 or 503 for a few seconds and then
serves the stream perfectly. Users experience the default as "this channel doesn't work" when
it demonstrably works on the second attempt.

`PlayerController` retries those with exponential backoff (500 ms doubling to 8 s, 5 attempts)
plus up to three silent restarts. `401`, `404` and `410` are **not** retried — those will not
fix themselves.

### Errors say what to do

`PlaybackErrors` turns exceptions into sentences. `ERROR_CODE_IO_BAD_HTTP_STATUS` tells a user
nothing; "the account may already be streaming on another device, or the provider may be
blocking this app's User-Agent — you can change it in the source's advanced settings" tells them
what to try.

This matters more than it sounds. The alternative habit — hiding the error code because users
complained about seeing it — treats the symptom while the stream still fails, and removes the
only diagnostic the user had.

## Defensive parsing of Xtream responses

`XtreamApi` reads `JsonElement` by hand instead of deserialising into `@Serializable` data
classes, and every accessor returns null rather than throwing.

Xtream Codes is not a standard. Different panel builds return the same field as a number, a
quoted number, an empty string, `null`, or omit it. `stream_id` alone flips between `12345` and
`"12345"`. Strict deserialisation means one unusual field aborts the parse of the entire
catalogue, which the user experiences as "the app won't load my channels" with nothing to go on.

It is ugly. It is correct. Do not "clean it up" into typed models without a very large corpus
of real panel responses to test against.

Similarly, `syncXtream` treats VOD as optional: plenty of accounts are live-TV only, and a 404
on `get_vod_streams` must never cost the user their channel list.

## Dependency injection

`ServiceLocator` is a hand-rolled container. Hilt is the conventional choice and is
deliberately not used: it adds an annotation processor, a build-time graph and a class of error
message that is genuinely hard to read, in exchange for wiring about a dozen objects. For a
project hoping for drive-by contributions from people fixing their own bugs, "you can read the
whole graph in one file" is worth more than the ceremony.

## Testing

The parsers are pure functions over strings and streams, which makes them cheap to test
exhaustively — and they are where the bugs that ruin someone's evening actually live. Every
malformed case in `M3uParserTest` is something that should cost the user one channel, never the
whole playlist.

Things worth testing that are not yet covered, and would be very welcome contributions:

- `EpgRepository.sync` against a mid-stream failure, asserting the old guide survives
- `ChannelDao.replaceCatalogue` preserving favourites across a refresh
- `XtreamApi` against captured real-world panel responses (with credentials stripped)
