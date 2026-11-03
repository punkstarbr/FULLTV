"""Fail-closed network policy for Streamlink's requests/urllib3 transport.

Every HTTP adapter send is restricted to HTTPS, public Internet addresses, and direct
connections. DNS answers are validated before connecting and pinned in a thread-local
scope so urllib3 uses the exact checked answers rather than resolving the host again.
"""
from __future__ import annotations

import ipaddress
import socket
import threading
from collections.abc import MutableMapping
from urllib.parse import urlsplit


class NetworkPolicyError(OSError):
    """The request does not comply with FullTV's outbound network policy."""


_ORIGINAL_GETADDRINFO = socket.getaddrinfo
_DNS_STATE = threading.local()
_INSTALL_LOCK = threading.Lock()
_DNS_GUARD_INSTALLED = False
_SPECIAL_IPV6 = (
    ipaddress.ip_network("64:ff9b::/96"),
    ipaddress.ip_network("64:ff9b:1::/48"),
    ipaddress.ip_network("2001::/23"),
    ipaddress.ip_network("2002::/16"),
)


def _host_key(host: str) -> str:
    return host.strip("[]").rstrip(".").lower()


def is_public_address(value: str) -> bool:
    """Return true only for globally routable IPs, excluding translation/tunnel ranges."""
    try:
        address = ipaddress.ip_address(str(value).split("%", 1)[0])
    except ValueError:
        return False
    if isinstance(address, ipaddress.IPv6Address):
        if address.ipv4_mapped is not None:
            return is_public_address(str(address.ipv4_mapped))
        if any(address in network for network in _SPECIAL_IPV6):
            return False
    return address.is_global


def _validate_url(raw_url: str) -> tuple[str, int]:
    if not isinstance(raw_url, str) or not 9 <= len(raw_url) <= 8192:
        raise NetworkPolicyError("Streamlink network destination is not allowed.")
    if any(ord(char) <= 0x20 or ord(char) == 0x7F for char in raw_url) or "#" in raw_url:
        raise NetworkPolicyError("Streamlink network destination is not allowed.")
    try:
        parsed = urlsplit(raw_url)
        host = parsed.hostname
        explicit_port = parsed.port
        port = 443 if explicit_port is None else explicit_port
    except ValueError as error:
        raise NetworkPolicyError("Streamlink network destination is not allowed.") from error
    if (
        parsed.scheme.lower() != "https"
        or not host
        or parsed.username is not None
        or parsed.password is not None
        or not 1 <= port <= 65535
        or len(host) > 253
    ):
        raise NetworkPolicyError("Streamlink network destination is not allowed.")
    return _host_key(host), port


def _record_key(host: str, port: object) -> tuple[str, str]:
    return _host_key(host), str(port)


def _filter_records(records: tuple, family: int, socktype: int, proto: int) -> list:
    return [
        record
        for record in records
        if (not family or record[0] == family)
        and (not socktype or record[1] == socktype)
        and (not proto or record[2] == proto)
    ]


def _guarded_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    pinned = getattr(_DNS_STATE, "pinned", None)
    if pinned is not None:
        records = pinned.get(_record_key(str(host), port))
        if records is not None:
            filtered = _filter_records(records, family, type, proto)
            if filtered:
                return filtered
            raise socket.gaierror("No validated address matches this socket request.")
    return _ORIGINAL_GETADDRINFO(host, port, family, type, proto, flags)


def _install_dns_guard() -> None:
    global _DNS_GUARD_INSTALLED
    if _DNS_GUARD_INSTALLED:
        return
    with _INSTALL_LOCK:
        if not _DNS_GUARD_INSTALLED:
            # Outside a guarded send this is a transparent pass-through. Inside one it returns
            # the already-validated answer set to urllib3, closing the DNS-rebinding window.
            socket.getaddrinfo = _guarded_getaddrinfo
            _DNS_GUARD_INSTALLED = True


class GuardedAdapter:
    """A small requests adapter wrapper that preserves Streamlink's underlying TLS adapter."""

    def __init__(self, delegate, resolver=None):
        self._delegate = delegate
        self._resolver = resolver or _ORIGINAL_GETADDRINFO

    def send(self, request, **kwargs):
        _install_dns_guard()
        host, port = _validate_url(getattr(request, "url", ""))
        proxies = kwargs.get("proxies") or {}
        if any(value for value in proxies.values()) or kwargs.get("verify") is False:
            raise NetworkPolicyError("Streamlink proxy or certificate bypass is not allowed.")

        try:
            records = tuple(
                self._resolver(host, port, socket.AF_UNSPEC, socket.SOCK_STREAM, socket.IPPROTO_TCP)
            )
        except OSError as error:
            raise NetworkPolicyError("Streamlink host could not be safely resolved.") from error
        if not records or any(
            record[0] not in (socket.AF_INET, socket.AF_INET6)
            or not is_public_address(record[4][0])
            for record in records
        ):
            raise NetworkPolicyError("Streamlink host resolved to a non-public address.")

        previous = getattr(_DNS_STATE, "pinned", None)
        pinned = dict(previous or {})
        pinned[_record_key(host, port)] = records
        _DNS_STATE.pinned = pinned
        try:
            return self._delegate.send(request, **kwargs)
        finally:
            if previous is None:
                try:
                    del _DNS_STATE.pinned
                except AttributeError:
                    pass
            else:
                _DNS_STATE.pinned = previous

    def close(self):
        close = getattr(self._delegate, "close", None)
        if callable(close):
            return close()

    def __getattr__(self, name):
        return getattr(self._delegate, name)


def guard_streamlink_session(session):
    """Install the policy on every Streamlink adapter, or fail closed if the API changed."""
    http = getattr(session, "http", None)
    adapters = getattr(http, "adapters", None)
    mount = getattr(http, "mount", None)
    if not isinstance(adapters, MutableMapping) or not callable(mount):
        raise NetworkPolicyError("Streamlink HTTP session could not be secured.")

    http.trust_env = False  # Do not inherit proxies or TLS overrides from the process environment.
    _install_dns_guard()
    for scheme in ("http://", "https://"):
        adapter = adapters.get(scheme)
        if adapter is None:
            raise NetworkPolicyError("Streamlink HTTP adapter could not be secured.")
        if not isinstance(adapter, GuardedAdapter):
            mount(scheme, GuardedAdapter(adapter))

    # Streamlink installs a file:// adapter by default; it is never needed for YouTube playback.
    # Remove every non-HTTP transport so redirects cannot read local files or use another scheme.
    for prefix in tuple(getattr(http, "adapters", {}).keys()):
        if prefix not in ("http://", "https://"):
            adapter = http.adapters.pop(prefix, None)
            close = getattr(adapter, "close", None)
            if callable(close):
                close()

    # Future mounts from plugin code may not replace the guards or add file:// transports.
    if not getattr(http, "_fulltv_guard_mount_installed", False):
        original_mount = http.mount

        def guarded_mount(prefix, adapter):
            normalized = str(prefix).lower()
            if normalized not in ("http://", "https://"):
                close = getattr(adapter, "close", None)
                if callable(close):
                    close()
                raise NetworkPolicyError("Streamlink transport scheme is not allowed.")
            guarded = adapter if isinstance(adapter, GuardedAdapter) else GuardedAdapter(adapter)
            return original_mount(normalized, guarded)

        http.mount = guarded_mount
        http._fulltv_guard_mount_installed = True
    return session
