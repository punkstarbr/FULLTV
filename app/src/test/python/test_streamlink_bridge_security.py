import ast
import unittest
from pathlib import Path


class StreamlinkRecordingTransportSecurityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        bridge = Path(__file__).parents[2] / "main" / "python" / "streamlink_bridge.py"
        cls.source = bridge.read_text(encoding="utf-8")
        cls.tree = ast.parse(cls.source)
        cls.functions = {
            node.name: node
            for node in cls.tree.body
            if isinstance(node, ast.FunctionDef)
        }

    def test_every_streamlink_session_installs_the_network_guard(self):
        source = ast.get_source_segment(self.source, self.functions["_session"])
        self.assertIn("guard_streamlink_session(session)", source)

    def test_recording_validates_youtube_and_opens_only_guarded_best_stream(self):
        source = ast.get_source_segment(self.source, self.functions["open_recording"])
        self.assertIn("_youtube_url(channel_url)", source)
        self.assertIn("session = _session()", source)
        self.assertIn("reader = best.open()", source)
        self.assertLess(source.index("session = _session()"), source.index("reader = best.open()"))

    def test_bridge_uses_guard_module_and_closes_session_on_failure(self):
        self.assertIn("from fulltv_network_guard import guard_streamlink_session", self.source)
        source = ast.get_source_segment(self.source, self.functions["open_recording"])
        self.assertIn("except BaseException", source)
        self.assertIn("_close_session(session)", source)


if __name__ == "__main__":
    unittest.main()
