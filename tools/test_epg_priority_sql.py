#!/usr/bin/env python3
"""Exercise the Room programme-selection SQL using Python's portable SQLite runtime.

Robolectric's bundled SQLite native library does not support this project's Linux/aarch64
build host, so these tests execute the actual @Query SQL extracted from Daos.kt.
"""
from __future__ import annotations

import re
import sqlite3
import unittest
from pathlib import Path


DAOS = Path(__file__).resolve().parents[1] / "app/src/main/java/app/opentv/data/db/Daos.kt"
QUERY_PATTERN = re.compile(
    r'@Query\(\s*"""\s*(.*?)\s*"""\s*\)\s*(?:fun|suspend fun)\s+(\w+)',
    re.DOTALL,
)


def programme_queries() -> dict[str, str]:
    source = DAOS.read_text(encoding="utf-8")
    return {name: sql.strip() for sql, name in QUERY_PATTERN.findall(source)}


class EpgPrioritySqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.queries = programme_queries()
        for name in ("observeWindow", "search", "observeNow", "upcoming"):
            if name not in cls.queries:
                raise AssertionError(f"Could not extract ProgrammeDao.{name} @Query from {DAOS}")

    def setUp(self) -> None:
        self.db = sqlite3.connect(":memory:")
        self.db.execute(
            "CREATE TABLE epg_feeds (id INTEGER PRIMARY KEY, enabled INTEGER NOT NULL, priority INTEGER NOT NULL)"
        )
        self.db.execute(
            "CREATE TABLE programmes ("
            "id INTEGER PRIMARY KEY, feedId INTEGER NOT NULL, epgChannelId TEXT NOT NULL, "
            "startUtcMillis INTEGER NOT NULL, endUtcMillis INTEGER NOT NULL, title TEXT NOT NULL, "
            "description TEXT, category TEXT)"
        )
        self.db.execute(
            "CREATE INDEX index_programmes_epgChannelId_startUtcMillis "
            "ON programmes(epgChannelId, startUtcMillis)"
        )
        self.db.executemany(
            "INSERT INTO epg_feeds(id, enabled, priority) VALUES (?, ?, ?)",
            [(1, 1, 0), (2, 1, 1), (3, 0, -1)],
        )
        self.db.executemany(
            "INSERT INTO programmes(id, feedId, epgChannelId, startUtcMillis, endUtcMillis, title) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            [
                (1, 1, "same-channel", 1_000, 2_000, "Primary title"),
                (2, 2, "same-channel", 1_000, 2_000, "Fallback duplicate"),
                (3, 3, "same-channel", 1_000, 2_000, "Disabled duplicate"),
                (4, 2, "same-channel", 2_000, 3_000, "Fallback-only show"),
            ],
        )
        self.db.commit()

    def tearDown(self) -> None:
        self.db.close()

    @staticmethod
    def titles(rows: list[tuple]) -> list[str]:
        return [row[5] for row in rows]

    def run_query(self, name: str, parameters: dict) -> list[tuple]:
        return self.db.execute(self.queries[name], parameters).fetchall()

    def test_active_highest_priority_wins_and_fallback_fills_missing_schedule(self) -> None:
        self.assertEqual(
            self.titles(self.run_query("observeWindow", {"fromUtcMillis": 0, "toUtcMillis": 4_000})),
            ["Primary title", "Fallback-only show"],
        )
        self.assertEqual(
            self.titles(self.run_query("observeNow", {"nowUtcMillis": 1_500})),
            ["Primary title"],
        )
        self.assertEqual(
            self.titles(self.run_query("upcoming", {"channelId": "same-channel", "nowUtcMillis": 0, "limit": 10})),
            ["Primary title", "Fallback-only show"],
        )

    def test_search_deduplicates_before_limit_and_reordering_changes_the_winner(self) -> None:
        search_args = {"query": "Fallback", "fromUtcMillis": 0, "toUtcMillis": 4_000, "limit": 10}
        self.assertEqual(self.titles(self.run_query("search", search_args)), ["Fallback-only show"])

        self.db.execute("UPDATE epg_feeds SET priority = 0 WHERE id = 2")
        self.db.execute("UPDATE epg_feeds SET priority = 1 WHERE id = 1")
        self.db.commit()

        self.assertEqual(
            self.titles(self.run_query("observeWindow", {"fromUtcMillis": 0, "toUtcMillis": 4_000})),
            ["Fallback duplicate", "Fallback-only show"],
        )
        self.assertEqual(
            self.titles(self.run_query("search", search_args)),
            ["Fallback duplicate", "Fallback-only show"],
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
