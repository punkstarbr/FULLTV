"""Harden the lxml parser entry points used by the bundled Streamlink resolver.

Streamlink 8.6.3 directly requires lxml. The bundled Chaquopy index currently supplies
lxml 5.3.0, whose iterparse and ETCompatXMLParser defaults were later fixed upstream in
lxml 6.1.0. Keep the application boundary safe until a corrected Android wheel exists.
"""

from functools import wraps

from lxml import etree


def harden_lxml() -> None:
    """Disable external entity resolution, DTD loading, and network access."""
    if getattr(etree, "_fulltv_xxe_hardened", False):
        return

    original_iterparse = etree.iterparse

    @wraps(original_iterparse)
    def secure_iterparse(*args, **kwargs):
        kwargs["resolve_entities"] = False
        kwargs["load_dtd"] = False
        kwargs["no_network"] = True
        return original_iterparse(*args, **kwargs)

    original_et_compat = etree.ETCompatXMLParser

    class SecureETCompatXMLParser(original_et_compat):
        def __init__(self, *args, **kwargs):
            kwargs["resolve_entities"] = False
            kwargs["load_dtd"] = False
            kwargs["no_network"] = True
            super().__init__(*args, **kwargs)

    etree.iterparse = secure_iterparse
    etree.ETCompatXMLParser = SecureETCompatXMLParser
    etree._fulltv_xxe_hardened = True
