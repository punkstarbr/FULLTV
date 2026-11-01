"""Small Chaquopy bridge from Kotlin to Streamlink's Python API."""

import json

from streamlink import Streamlink
from streamlink.plugin.api import useragents


def resolve_best(channel_url: str) -> str:
    """Resolve a channel's active YouTube live stream using Streamlink's `best` selector."""
    session = Streamlink()
    session.set_option("stream-timeout", 30)
    streams = session.streams(channel_url)
    best = streams.get("best")
    if best is None:
        raise RuntimeError("No current live stream was found for this YouTube channel.")

    # Streamlink's HTTP/HLS stream classes expose the playable URL as `url`.
    stream_url = getattr(best, "url", None)
    if not stream_url:
        stream_url = best.to_url()
    stream_url = str(stream_url)
    if not stream_url.startswith("https://"):
        raise RuntimeError("Streamlink did not return a secure HTTPS stream URL.")

    return json.dumps({"url": stream_url, "user_agent": useragents.CHROME})
