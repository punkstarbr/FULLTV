# Privacy

OpenTV collects nothing. There is no analytics SDK, no crash reporter, no telemetry, and no
network call to any domain the project controls — because the project controls none.

## What is stored, and where

Everything lives in a SQLite database in the app's private storage on your device:

- Your provider's address, username and password
- The channel, movie and series catalogue downloaded from your provider
- The guide (EPG)
- Your favourites, hidden channels and resume positions

None of it is uploaded anywhere. There is no account to create and no server to sync with.

## Who your device talks to

Your provider is the normal destination. Channel logos and posters use the URLs supplied in its
catalogue, which may point to third parties. Playlist EPG URLs are also requested as described
below.

When an M3U contains `url-tvg` or `x-tvg-url` attributes, FullTV discovers each unique XMLTV URL
across the playlist and requests all of them automatically when refreshing the guide. Those guide
hosts can see the device network request and any query parameters included in the URL. Do not post
or share a playlist containing credentials or private tokens. Plain HTTP guide URLs are blocked;
HTTPS is required.

If you open a playlist channel whose URL is a YouTube `@handle` (or a live YouTube watch URL), the
bundled Streamlink resolver contacts YouTube and its video CDN directly to find the current live
stream. If you add a YouTube `/results` page as a Live Search source, refreshing it sends that
user-provided search URL/query directly to YouTube through the bundled yt-dlp extractor and reads
public result metadata on-device; no API key or FullTV server is used. Results are limited to
currently live videos. YouTube may rate-limit or block automated requests, so search and playback
depend on public-page availability, network access, and a live broadcast.

If you record a YouTube live, Streamlink reads the HLS media bytes on the device and the existing
recording service writes them to the storage destination you selected. FullTV does not relay or
store those bytes on a FullTV server.

If you enable automatic Portuguese captions and open a channel, FullTV downloads the multilingual
Whisper tiny Q5 speech model from Hugging Face (about 31 MiB). It is loaded into memory only while
the player is open with captions enabled, and is released when the player closes. Short audio
windows pass through a local energy-based voice-activity detector; silent windows are not sent to
Whisper. A Google ML Kit translation model (about 30 MB) is downloaded only when needed for speech
in a supported non-Portuguese language. Audio is decoded, transcribed and translated on the device;
FullTV does not upload audio or transcripts. Downloaded model files stay in app-private storage
until removed from Settings or the app is uninstalled.

## Cleartext HTTP

The app rejects plain HTTP (`usesCleartextTraffic="false"`) and requires provider, EPG, logo,
poster and stream URLs to use HTTPS. This prevents provider credentials and media requests from
being read or modified by an observer on the network. Older HTTP-only IPTV panels are not
supported by the hardened build; use the HTTPS endpoint supplied by the provider.

Phone pairing and device sync use short-lived, LAN-only sockets. They are inbound local services,
not internet endpoints, and are stopped automatically when their session ends.

Automatic Android backup is disabled. Provider credentials normally remain in app-private storage;
if you explicitly export a `.fulltv` transfer file, its contents (including provider and NAS
credentials) are compressed and encrypted with AES-256-GCM using a passphrase-derived key before
they are written to the destination you choose. FullTV does not upload that file or the passphrase.
The passphrase cannot be recovered if lost. Video recording files and device-specific USB folder
permissions are not included; choose the USB folder again on the receiving device.

## Uninstalling

Removing the app removes the database and everything in it.

## Reporting a privacy problem

Open an issue. If it involves something you would rather not post publicly, say so in the issue
without the details and a maintainer will arrange somewhere better.
