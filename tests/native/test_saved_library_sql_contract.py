"""Execute the actual Swift saved-library SELECT with a minimal SQLite fixture.
This is a SQL contract check, NOT GRDB migration/native UI verification.
"""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT / "apps/ios/Pulse/Core/Storage/PulseStore.swift").read_text()
method = source.split("private static func savedItems(in db:", 1)[1].split("/// Every currently-saved", 1)[0]
QUERY = re.search(r'Row.fetchAll\(db, sql: """(.*?)"""', method, re.S).group(1).replace(r'\(selection)', '')


class SavedLibrarySqlContract(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            CREATE TABLE message(id TEXT PRIMARY KEY, conversationId TEXT, body TEXT, parentId TEXT, deletedAt TEXT, expiresAtEpoch REAL);
            CREATE TABLE wave7Cache(key TEXT PRIMARY KEY, json TEXT, updatedAt REAL);
            CREATE TABLE savedMessages(messageId TEXT PRIMARY KEY, conversationId TEXT, savedAt TEXT);
            CREATE TABLE conversation(id TEXT PRIMARY KEY, kind TEXT, title TEXT);
            INSERT INTO message(id,conversationId,body) VALUES ('m1','c1','offline body'),('m2','c2','another body'),('m3','c1','not saved');
            INSERT INTO savedMessages VALUES ('m1','c1','2026-10-19T10:00:00Z'),('m2','c2','2026-10-19T11:00:00Z');
            INSERT INTO conversation VALUES ('c1','GROUP','Group title');
        """)

    def rows(self, limit=-1):
        return self.db.execute(QUERY, {"now": 1000, "pageLimit": limit}).fetchall()

    def tearDown(self):
        self.db.close()

    def test_cached_bodies_not_just_ids_and_missing_metadata_does_not_hide_items(self):
        rows = self.rows()
        self.assertEqual([row['id'] for row in rows], ['m2', 'm1'])
        self.assertEqual(rows[1]['body'], 'offline body')
        self.assertEqual(rows[1]['savedConversationName'], 'Group title')
        self.assertIsNone(rows[0]['savedConversationName'])

    def test_removed_marker_hides_item_without_deleting_original_message(self):
        self.db.execute("DELETE FROM savedMessages WHERE messageId='m1'")
        self.assertEqual([row['id'] for row in self.rows()], ['m2'])
        self.assertEqual(self.db.execute("SELECT body FROM message WHERE id='m1'").fetchone()[0], 'offline body')

    def test_query_keeps_page_bound_and_excludes_expired_or_deleted_bodies(self):
        self.assertEqual([row['id'] for row in self.rows(limit=1)], ['m2'])
        self.db.execute("UPDATE message SET expiresAtEpoch=1000 WHERE id='m2'")
        self.assertEqual([row['id'] for row in self.rows()], ['m1'])
        self.db.execute("UPDATE message SET deletedAt='deleted' WHERE id='m1'")
        self.assertEqual(self.rows(), [])

    def test_unavailable_root_cannot_leak_saved_reply(self):
        self.db.execute("INSERT INTO message(id,conversationId,body,expiresAtEpoch) VALUES ('root','c1','root',1000)")
        self.db.execute("UPDATE message SET parentId='root' WHERE id='m1'")
        self.assertEqual([row['id'] for row in self.rows()], ['m2'])
        self.db.execute("UPDATE message SET expiresAtEpoch=2000 WHERE id='root'")
        self.assertEqual([row['id'] for row in self.rows()], ['m2', 'm1'])
        self.db.execute("INSERT INTO wave7Cache VALUES ('thread-access-denied:v1|root','{}',0)")
        self.assertEqual([row['id'] for row in self.rows()], ['m2'])


if __name__ == '__main__':
    unittest.main()
