import socket
import unittest
from collections import OrderedDict
from urllib.parse import urlsplit

from fulltv_network_guard import (
    GuardedAdapter,
    NetworkPolicyError,
    guard_streamlink_session,
    is_public_address,
)


class FakeRequest:
    def __init__(self, url):
        self.url = url


class FakeAdapter:
    def __init__(self):
        self.calls = 0
        self.addresses = None
        self.closed = False

    def send(self, request, **kwargs):
        self.calls += 1
        parsed = urlsplit(request.url)
        self.addresses = socket.getaddrinfo(
            parsed.hostname,
            parsed.port or 443,
            socket.AF_UNSPEC,
            socket.SOCK_STREAM,
            socket.IPPROTO_TCP,
        )
        return "ok"

    def close(self):
        self.closed = True


class FakeHttp:
    def __init__(self):
        self.trust_env = True
        self.adapters = OrderedDict(
            (
                ("https://", FakeAdapter()),
                ("http://", FakeAdapter()),
                ("file://", FakeAdapter()),
            )
        )

    def mount(self, prefix, adapter):
        self.adapters[prefix] = adapter


class FakeStreamlinkSession:
    def __init__(self):
        self.http = FakeHttp()


class StreamlinkNetworkGuardTest(unittest.TestCase):
    def public_resolver(self, host, port, family, socktype, proto):
        return [
            (socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", ("8.8.8.8", port)),
        ]

    def test_only_global_addresses_are_allowed(self):
        for address in ("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"):
            with self.subTest(address=address):
                self.assertTrue(is_public_address(address))
        for address in (
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.10.2",
            "192.168.1.1",
            "::1",
            "::ffff:192.168.1.1",
            "64:ff9b::c0a8:0101",
            "2002:c0a8:0101::1",
            "2001::1",
        ):
            with self.subTest(address=address):
                self.assertFalse(is_public_address(address))

    def test_adapter_pins_the_checked_dns_answer_for_the_actual_socket_lookup(self):
        delegate = FakeAdapter()
        adapter = GuardedAdapter(delegate, resolver=self.public_resolver)

        result = adapter.send(FakeRequest("https://cdn.example/live/playlist.m3u8"))

        self.assertEqual("ok", result)
        self.assertEqual(1, delegate.calls)
        self.assertEqual(["8.8.8.8"], [record[4][0] for record in delegate.addresses])

    def test_adapter_rejects_mixed_public_and_private_dns_answers(self):
        delegate = FakeAdapter()

        def mixed_resolver(host, port, family, socktype, proto):
            return self.public_resolver(host, port, family, socktype, proto) + [
                (socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", ("192.168.1.2", port)),
            ]

        adapter = GuardedAdapter(delegate, resolver=mixed_resolver)
        with self.assertRaises(NetworkPolicyError):
            adapter.send(FakeRequest("https://rebinding.example/playlist.m3u8"))
        self.assertEqual(0, delegate.calls)

    def test_adapter_rejects_http_userinfo_and_zero_port_before_connecting(self):
        delegate = FakeAdapter()
        adapter = GuardedAdapter(delegate, resolver=self.public_resolver)
        for url in (
            "http://8.8.8.8/playlist.m3u8",
            "https://user:password@8.8.8.8/playlist.m3u8",
            "https://8.8.8.8:0/playlist.m3u8",
            "file:///etc/passwd",
        ):
            with self.subTest(url=url), self.assertRaises(NetworkPolicyError):
                adapter.send(FakeRequest(url))
        self.assertEqual(0, delegate.calls)

    def test_streamlink_session_forbids_environment_proxy_and_file_transport(self):
        session = FakeStreamlinkSession()
        guard_streamlink_session(session)

        self.assertFalse(session.http.trust_env)
        self.assertEqual({"http://", "https://"}, set(session.http.adapters))
        self.assertIsInstance(session.http.adapters["http://"], GuardedAdapter)
        self.assertIsInstance(session.http.adapters["https://"], GuardedAdapter)
        with self.assertRaises(NetworkPolicyError):
            session.http.mount("file://", FakeAdapter())


if __name__ == "__main__":
    unittest.main()
