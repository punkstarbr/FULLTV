import io
import tempfile
import unittest
from pathlib import Path

from lxml import etree

from fulltv_lxml_guard import harden_lxml


class LxmlEntitySecurityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        harden_lxml()

    def setUp(self):
        self.secret = "FULLTV-XXE-MUST-NOT-LEAK"
        self.secret_file = tempfile.NamedTemporaryFile("w", delete=False)
        self.secret_file.write(self.secret)
        self.secret_file.close()
        escaped = self.secret_file.name.replace("&", "&amp;").replace('"', "&quot;")
        self.payload = (
            f'<!DOCTYPE doc [<!ENTITY xxe SYSTEM "file://{escaped}">]>'
            "<doc>&xxe;</doc>"
        ).encode("utf-8")

    def tearDown(self):
        Path(self.secret_file.name).unlink(missing_ok=True)

    def test_iterparse_never_expands_external_entities(self):
        parsed = etree.iterparse(
            io.BytesIO(self.payload),
            events=("end",),
            resolve_entities=True,
            load_dtd=True,
            no_network=False,
        )
        text = "".join(value for _, element in parsed for value in element.itertext())
        self.assertNotIn(self.secret, text)

    def test_etcompat_parser_never_expands_external_entities(self):
        parser = etree.ETCompatXMLParser(
            resolve_entities=True,
            load_dtd=True,
            no_network=False,
        )
        root = etree.fromstring(self.payload, parser=parser)
        self.assertNotIn(self.secret, "".join(root.itertext()))


if __name__ == "__main__":
    unittest.main()
