"""Chaquopy bridge for retrieving current live results from a public YouTube search URL."""

import json
import re

from yt_dlp import YoutubeDL

_VIDEO_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
_MAX_FETCH = 50


def search_live(search_url: str, max_results: int = 5) -> str:
    """Return up to five active-live entries; never download video data here."""
    max_results = max(1, min(int(max_results), 5))
    if not search_url.startswith("https://www.youtube.com/results?"):
        raise ValueError("Expected a canonical YouTube results URL")

    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "extract_flat": "in_playlist",
        "playlistend": _MAX_FETCH,
        "ignoreerrors": True,
        "socket_timeout": 25,
    }
    with YoutubeDL(options) as ydl:
        result = ydl.extract_info(search_url, download=False)

    items = []
    for entry in (result or {}).get("entries") or []:
        if not entry:
            continue
        if not (entry.get("is_live") is True or entry.get("live_status") == "is_live"):
            continue
        video_id = str(entry.get("id") or "")
        if not _VIDEO_ID.fullmatch(video_id):
            continue

        title = str(entry.get("title") or "").strip()
        if not title:
            continue
        channel = entry.get("channel") or entry.get("uploader")
        thumbnail = entry.get("thumbnail")
        if not thumbnail:
            thumbnails = entry.get("thumbnails") or []
            if thumbnails:
                thumbnail = thumbnails[-1].get("url")
        items.append(
            {
                "id": video_id,
                "title": title,
                "channel": str(channel).strip() if channel else "",
                "thumbnail": str(thumbnail) if thumbnail else "",
            }
        )
        if len(items) >= max_results:
            break

    return json.dumps({"items": items}, ensure_ascii=False)
