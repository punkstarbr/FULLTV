# Privacy

OpenTV collects nothing. There is no analytics SDK, no crash reporter, no telemetry, and no
network call to any domain the project controls — because the project controls none.

## What is stored, and where

The catalogue lives in a SQLite/Room database in the app's private storage on your device:

- Your provider's address, username and password
- The channel, movie and series catalogue downloaded from your provider
- The guide (EPG)
- Your favourites, hidden channels and resume positions

The Room database is not additionally encrypted with SQLCipher. Android's app sandbox and device
encryption protect ordinary devices, but a rooted/unlocked device or forensic access can expose
provider fields and URLs stored in that database. Do not treat the app PIN as encryption. Selected
settings secrets (SMB credentials, TMDB key and Stremio add-on URLs) are separately AES-GCM-encrypted
with a non-exportable Android Keystore key; the parental PIN verifier is a Keystore-keyed HMAC.
None of the database contents is uploaded to a FullTV server. There is no FullTV account.

## Who your device talks to

Your provider is the normal destination. Channel logos and posters use the URLs supplied in its
catalogue, which may point to third parties. Playlist EPG URLs are also requested as described
below.

When automatic catalogue refresh is enabled, a delayed EPG-only background job may request enabled
guide feeds after app startup if the retained guide is empty or a feed has passed the configured
refresh interval. It requires network connectivity and does not download the channel catalogue as
part of this startup job. Android may defer background work; manual refresh remains available.

When an M3U contains `url-tvg` or `x-tvg-url` attributes, FullTV discovers each unique XMLTV URL
across the playlist and requests all of them automatically when refreshing the guide. Those guide
hosts can see the device network request and any query parameters included in the URL. Do not post
or share a playlist containing credentials or private tokens. For the provider/discovered feeds tied
to that M3U, FullTV stores programme rows only when their XMLTV channel ID is present in the playlist
or its channel name has one unambiguous match. It still downloads each accepted full guide. To handle
XMLTV channels and programmes in any order, one compressed feed at a time is briefly cached in the
app-private cache (maximum 256 MiB, with a 64 MiB free-space reserve), scanned once for channel aliases,
then parsed again to persist only matching programme rows and aliases. The temporary file is deleted
after success, failure or cancellation; an abandoned file is cleaned at the next EPG sync. This saves
programme-database space, not network transfer. The previous guide snapshot is kept until a non-empty
filtered replacement succeeds. A URL shared by different playlists gets a separate filtered feed
for each list; manually configured and built-in feeds remain global. If both scopes use the same URL,
the URL may be downloaded more than once.
When the playlist's channels or first guide URL changes, its feeds are marked for
the next EPG refresh, so later channel additions and guide URL changes are considered without hardcoded
IDs, even when the M3U address stays the same.
Plain HTTP guide URLs are blocked; HTTPS is required.

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

If you enable Portuguese subtitle translation, FullTV translates only readable text cues emitted
by a subtitle track supplied by the channel and selected in the player. If the stream has no text
subtitle track (or supplies bitmap-only captions), this feature does not create captions.

The separate **Live captions** option is off by default and starts only after an explicit choice in
the fullscreen player. You may select a spoken language manually or try **Automatic**, which reads
the language declared by the selected audio track when the provider supplies a supported tag. It
does not acoustically classify audio; if that metadata is absent, the app asks you to select a
language. The mode transcribes PCM audio already decoded for that player with a Vosk model stored in
the app-private directory, then translates recognized text to Portuguese with on-device Google ML
Kit. It does not request microphone permission, save audio, or send audio or transcripts to a FullTV
server. Speech and translation models are downloaded on demand over HTTPS; Google Play services
manages ML Kit translation models, which may not be included in FullTV's app-private storage
measurement. Small Vosk models can use roughly 300 MB of memory while loaded, so FullTV does not
offer this mode on Android low-RAM devices or devices reporting an app memory class below 256 MB.

## Cleartext HTTP

The app rejects plain HTTP (`usesCleartextTraffic="false"`) and requires provider, EPG, logo,
poster and stream URLs to use HTTPS. This prevents provider credentials and media requests from
being read or modified by an observer on the network. Older HTTP-only IPTV panels are not
supported by the hardened build; use the HTTPS endpoint supplied by the provider.

Phone pairing, Web Manager and device sync use TLS 1.2 on short-lived LAN-only sockets; there is no
HTTP downgrade. Pairing and Web Manager use an app-generated certificate, so a browser may show a
self-signed-certificate warning. Compare the browser's certificate fingerprint with the value
shown on the TV before entering provider credentials. Device sync additionally requires an explicit
short code and pinned public-key fingerprint. The listeners are inbound local services, not internet
endpoints, and are stopped automatically when their session ends.

NAS sync files contain keyed HMAC identifiers rather than raw provider stream URLs or credentials.
The shared HMAC key is exchanged directly between paired devices over pinned TLS and is included
only inside a user-encrypted backup, never in a NAS sync file. A legacy v3 sync file may contain
older raw URLs; FullTV migrates compatible entries in memory and rewrites the file in the newer
format after successful processing.

Automatic Android backup is disabled. If you explicitly export a `.fulltv` transfer file, its
contents (including provider and NAS credentials) are encrypted with AES-256-GCM using a
passphrase-derived key before they are written to the destination you choose. New exports use a
stronger PBKDF2 work factor and authenticate the format/KDF header; legacy backups remain readable.
FullTV does not upload that file or the passphrase.
The passphrase cannot be recovered if lost. Video recording files and device-specific USB folder
permissions are not included; choose the USB folder again on the receiving device.

## Uninstalling

Removing the app removes the database and everything in it.

## Reporting a privacy problem

Open an issue. If it involves something you would rather not post publicly, say so in the issue
without the details and a maintainer will arrange somewhere better.
