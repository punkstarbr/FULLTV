"""Small Chaquopy bridge from Kotlin to Streamlink's Python API."""

import base64
import json
import threading
import uuid
from urllib.parse import urlsplit

from fulltv_lxml_guard import harden_lxml
from fulltv_network_guard import guard_streamlink_session

harden_lxml()

from streamlink import Streamlink
from streamlink.plugin.api import useragents

_recordings = {}
_recordings_lock = threading.RLock()


def _youtube_url(channel_url: str) -> str:
    """Accept only Kotlin's canonical HTTPS YouTube URL, never an arbitrary plugin URL."""
    if not isinstance(channel_url, str) or not 16 <= len(channel_url) <= 2048:
        raise ValueError("Expected a canonical YouTube live URL.")
    parsed = urlsplit(channel_url)
    if (
        parsed.scheme != "https"
        or parsed.hostname != "www.youtube.com"
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or parsed.port not in (None, 443)
    ):
        raise ValueError("Expected a canonical HTTPS YouTube live URL.")
    return channel_url


def _session():
    session = Streamlink()
    session.set_option("stream-timeout", 30)
    return guard_streamlink_session(session)


def _close_session(session) -> None:
    """Close Streamlink's HTTP pool; Streamlink itself has no public session.close()."""
    try:
        http = getattr(session, "http", None)
        close = getattr(http, "close", None)
        if callable(close):
            close()
    except Exception:
        pass


def resolve_best(channel_url: str) -> str:
    """Resolve a current YouTube live stream using Streamlink's `best` selector."""
    channel_url = _youtube_url(channel_url)
    session = _session()
    try:
        streams = session.streams(channel_url)
        best = streams.get("best")
        if best is None:
            raise RuntimeError("No current live stream was found for this YouTube URL.")

        # Streamlink's HTTP/HLS stream classes expose the playable manifest as `url`.
        stream_url = getattr(best, "url", None)
        if not stream_url:
            stream_url = best.to_url()
        stream_url = str(stream_url)
        if not stream_url.startswith("https://"):
            raise RuntimeError("Streamlink did not return a secure HTTPS stream URL.")

        return json.dumps({"url": stream_url, "user_agent": useragents.CHROME})
    finally:
        _close_session(session)


def open_recording(channel_url: str) -> str:
    """Open a guarded Streamlink reader and return an opaque handle for Kotlin."""
    channel_url = _youtube_url(channel_url)
    session = _session()
    reader = None
    try:
        streams = session.streams(channel_url)
        best = streams.get("best")
        if best is None:
            raise RuntimeError("No current live stream was found for this YouTube URL.")
        # The session adapters enforce HTTPS, public-only DNS and per-connection DNS pinning.
        reader = best.open()
        token = uuid.uuid4().hex
        with _recordings_lock:
            _recordings[token] = (session, reader)
        return json.dumps({"token": token})
    except BaseException:
        if reader is not None:
            try:
                reader.close()
            finally:
                _close_session(session)
        else:
            _close_session(session)
        raise


def read_recording(token: str, size: int) -> str:
    """Return the next media chunk as base64 so Chaquopy can pass it safely to Java."""
    with _recordings_lock:
        entry = _recordings.get(str(token))
    if entry is None:
        return ""
    _session, reader = entry
    data = reader.read(max(1, min(int(size), 1024 * 1024))) or b""
    return base64.b64encode(data).decode("ascii")


def close_recording(token: str) -> None:
    """Close a reader; safe to call more than once, including during cancellation."""
    with _recordings_lock:
        entry = _recordings.pop(str(token), None)
    if entry is None:
        return
    session, reader = entry
    try:
        reader.close()
    finally:
        _close_session(session)
