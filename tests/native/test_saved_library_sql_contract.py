"""Execute the actual Swift saved-library SELECT with a minimal SQLite fixture.
This is a SQL contract check, NOT GRDB migration/native UI verification.
"""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT / "apps/ios/Pulse/Core/Storage/PulseStore.swift").read_text()
method = source.split("public func cachedSavedItems()", 1)[1].split("/// Every currently-saved", 1)[0]
QUERY = re.search(r'Row.fetchAll\(db, sql: """(.*?)"""', method, re.S).group(1)


class SavedLibrarySqlContract(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            CREATE TABLE message(id TEXT PRIMARY KEY, conversationId TEXT, body TEXT);
            CREATE TABLE savedMessages(messageId TEXT PRIMARY KEY, conversationId TEXT, savedAt TEXT);
            CREATE TABLE conversation(id TEXT PRIMARY KEY, kind TEXT, title TEXT);
            INSERT INTO message VALUES ('m1','c1','offline body'),('m2','c2','another body'),('m3','c1','not saved');
            INSERT INTO savedMessages VALUES ('m1','c1','2026-10-19T10:00:00Z'),('m2','c2','2026-10-19T11:00:00Z');
            INSERT INTO conversation VALUES ('c1','GROUP','Group title');
        """)

    def tearDown(self):
        self.db.close()

    def test_cached_bodies_not_just_ids_and_missing_metadata_does_not_hide_items(self):
        rows = self.db.execute(QUERY).fetchall()
        self.assertEqual([row['id'] for row in rows], ['m2', 'm1'])
        self.assertEqual(rows[1]['body'], 'offline body')
        self.assertEqual(rows[1]['savedConversationName'], 'Group title')
        self.assertIsNone(rows[0]['savedConversationName'])

    def test_removed_marker_hides_item_without_deleting_original_message(self):
        self.db.execute("DELETE FROM savedMessages WHERE messageId='m1'")
        self.assertEqual([row['id'] for row in self.db.execute(QUERY)], ['m2'])
        self.assertEqual(self.db.execute("SELECT body FROM message WHERE id='m1'").fetchone()[0], 'offline body')


if __name__ == '__main__':
    unittest.main()
