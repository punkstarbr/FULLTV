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

Only your provider, and only at the address you entered. Channel logos and posters are fetched
from whatever URLs your provider puts in its catalogue, which are usually its own servers but
may be third parties — that is your provider's choice, not ours.

## Cleartext HTTP

The app rejects plain HTTP (`usesCleartextTraffic="false"`) and requires provider, EPG, logo,
poster and stream URLs to use HTTPS. This prevents provider credentials and media requests from
being read or modified by an observer on the network. Older HTTP-only IPTV panels are not
supported by the hardened build; use the HTTPS endpoint supplied by the provider.

Phone pairing and device sync use short-lived, LAN-only sockets. They are inbound local services,
not internet endpoints, and are stopped automatically when their session ends.

Provider credentials remain in the app's private storage and Android backup is disabled so they
are not copied to cloud or device backups.

## Uninstalling

Removing the app removes the database and everything in it.

## Reporting a privacy problem

Open an issue. If it involves something you would rather not post publicly, say so in the issue
without the details and a maintainer will arrange somewhere better.
