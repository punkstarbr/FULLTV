#!/usr/bin/env python3
"""Run the actual v12->v13 SQL migration against Python's SQLite runtime."""
from __future__ import annotations

import re
import sqlite3
import unittest
from pathlib import Path


DATABASE_SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/app/opentv/data/db/OpenTvDatabase.kt"


def migration_statements() -> list[str]:
    source = DATABASE_SOURCE.read_text(encoding="utf-8")
    match = re.search(
        r"MIGRATION_12_13\s*=\s*object\s*:\s*Migration\(12,\s*13\).*?"
        r"override fun migrate\(db: SupportSQLiteDatabase\)\s*\{(?P<body>.*?)\n\s{12}\}\n\s{8}\}",
        source,
        re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"Could not find MIGRATION_12_13 in {DATABASE_SOURCE}")

    body = match.group("body")
    statements: list[str] = []
    cursor = 0
    marker = "db.execSQL("
    while True:
        start = body.find(marker, cursor)
        if start < 0:
            break
        index = start + len(marker)
        depth = 1
        in_string = False
        escaped = False
        while index < len(body) and depth:
            char = body[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
            elif char == '"':
                in_string = True
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            index += 1
        if depth:
            raise AssertionError("Unclosed db.execSQL call in MIGRATION_12_13")
        expression = body[start + len(marker):index - 1]
        parts = re.findall(r'"((?:\\.|[^"\\])*)"', expression)
        if not parts:
            raise AssertionError("Could not extract SQL literal from migration")
        statements.append("".join(parts))
        cursor = index
    return statements


class EpgPriorityMigrationTest(unittest.TestCase):
    def test_migration_preserves_existing_feeds_and_assigns_dense_old_order(self) -> None:
        statements = migration_statements()
        self.assertEqual(len(statements), 3)

        db = sqlite3.connect(":memory:")
        try:
            db.execute(
                "CREATE TABLE epg_feeds (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                "name TEXT NOT NULL, url TEXT, providerSourceId INTEGER, playlistSourceId INTEGER, "
                "builtIn INTEGER NOT NULL, enabled INTEGER NOT NULL, lastSyncMillis INTEGER NOT NULL, "
                "lastResult TEXT NOT NULL)"
            )
            db.execute(
                "CREATE TABLE programmes (id INTEGER PRIMARY KEY, feedId INTEGER NOT NULL, "
                "epgChannelId TEXT NOT NULL, startUtcMillis INTEGER NOT NULL, endUtcMillis INTEGER NOT NULL)"
            )
            db.executemany(
                "INSERT INTO epg_feeds(id, name, builtIn, enabled, lastSyncMillis, lastResult) "
                "VALUES (?, ?, 0, 1, 123, 'ok')",
                [(7, "First"), (21, "Second")],
            )
            db.execute(
                "INSERT INTO programmes(id, feedId, epgChannelId, startUtcMillis, endUtcMillis) "
                "VALUES (1, 7, 'xml-id', 100, 200)"
            )

            for sql in statements:
                db.execute(sql)

            self.assertEqual(
                db.execute("SELECT id, priority, lastResult FROM epg_feeds ORDER BY id").fetchall(),
                [(7, 0, "ok"), (21, 1, "ok")],
            )
            self.assertEqual(db.execute("SELECT COUNT(*) FROM programmes").fetchone()[0], 1)
            index_names = {row[1] for row in db.execute("PRAGMA index_list(programmes)")}
            self.assertIn("index_programmes_epgChannelId_startUtcMillis", index_names)
        finally:
            db.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
