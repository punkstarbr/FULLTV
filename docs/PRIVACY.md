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

Your provider, and only at the address you entered, is the normal network destination. Channel
logos and posters are fetched from whatever URLs your provider puts in its catalogue, which are
usually its own servers but may be third parties — that is your provider's choice, not ours.

If you open a playlist channel whose URL is a YouTube `@handle`, the bundled Streamlink resolver
contacts YouTube and its video CDN directly to find the current live stream. This happens on the
device, only for that channel, and no request is sent to a FullTV server. An internet connection
and a currently available live broadcast are required.

If you enable automatic Portuguese captions, FullTV downloads the Whisper speech model from
Hugging Face (about 78 MB) and the required Google ML Kit translation model (about 30 MB) on first
use. Audio is decoded, transcribed and translated on the device; FullTV does not upload audio or
transcripts. The downloaded models remain in app-private storage until the app is uninstalled.

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
