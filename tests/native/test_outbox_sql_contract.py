"""Run actual Kotlin migration/query SQL against the checked-in Room v10 schema.
This is SQLite contract coverage, not Android instrumentation or a native build.
"""
import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / 'apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt').read_text()
SCHEMA = json.loads((ROOT / 'apps/android/data/schemas/app.pulse.data.local.PulseDatabase/10.json').read_text())['database']


def literals(expression):
    return ''.join(json.loads(item) for item in re.findall(r'"(?:[^"\\]|\\.)*"', expression))


def query(method):
    for match in re.finditer(r'@Query\((.*?)\)\s*(?:suspend )?fun (\w+)\(', SOURCE, re.S):
        if match[2] == method:
            return literals(match[1])
    raise AssertionError(f'No query found for {method}')


class FailedIntentSQLTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        for entity in SCHEMA['entities']:
            self.db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
            for index in entity['indices']:
                self.db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        self.seed('old', kind='STICKER')
        self.db.execute("INSERT INTO draft VALUES ('room', 'my next thought', 'now')")
        block = SOURCE.split('val MIGRATION_10_11:', 1)[1].split('val MIGRATION_9_10:', 1)[0]
        statements = re.findall(r'db.execSQL\((.*?)\)\s*\n', block, re.S)
        self.assertEqual(len(statements), 3)
        for statement in statements:
            self.db.execute(literals(statement))

    def tearDown(self):
        self.db.close()

    def seed(self, client, kind='TEXT'):
        entity = next(e for e in SCHEMA['entities'] if e['tableName'] == 'messages')
        row = {f['columnName']: ('' if f['affinity'] == 'TEXT' else 0)
               for f in entity['fields'] if f['notNull']}
        row.update(id='local_' + client, conversationId='room', authorId='owner',
                   kind=kind, body='original intent', createdAt='2026-10-19T10:00:00Z')
        self.db.execute(f"INSERT INTO messages ({','.join(row)}) VALUES ({','.join('?' for _ in row)})", tuple(row.values()))
        self.db.execute("INSERT INTO outbox (conversationId,clientId,content,kind,createdAt,attempts) VALUES ('room',?,'original intent','text','now',0)", (client,))

    def test_upgrade_preserves_content_and_repairs_rich_kind(self):
        row = self.db.execute('SELECT * FROM outbox').fetchone()
        self.assertEqual(row['kind'], 'sticker')
        self.assertIsNone(row['failureReason'])
        self.assertIsNone(row['wirePayload'])
        self.assertEqual(row['content'], 'original intent')
        self.assertEqual(self.db.execute('SELECT text FROM draft').fetchone()[0], 'my next thought')

    # Room expands list parameters; this fixture uses a non-room empty sentinel
    # to represent no excluded rooms without claiming Room binding coverage.
    def test_rejection_hides_echo_without_deleting_intent_or_new_draft(self):
        self.db.execute(query('markFailed'), {'clientId': 'old', 'reason': 'forbidden'})
        self.assertEqual(len(self.db.execute(query('observeFor'), {'conversationId': 'room'}).fetchall()), 0)
        self.assertEqual(len(self.db.execute(query('allForOwner'), {'owner': 'owner', 'excludedRooms': ''}).fetchall()), 0)
        self.assertEqual(self.db.execute('SELECT failureReason FROM outbox').fetchone()[0], 'forbidden')
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM messages').fetchone()[0], 1)
        self.assertEqual(self.db.execute('SELECT text FROM draft').fetchone()[0], 'my next thought')
        self.db.execute(query('markFailed'), {'clientId': 'old', 'reason': 'later verdict'})
        self.assertEqual(self.db.execute('SELECT failureReason FROM outbox').fetchone()[0], 'forbidden')

    def test_failed_backlog_does_not_starve_the_next_pending_operation(self):
        for i in range(55):
            self.seed(str(i))
        self.db.execute("UPDATE outbox SET failureReason='held'")
        self.seed('next')
        pending = self.db.execute(query('allForOwner'), {'owner': 'owner', 'excludedRooms': ''}).fetchall()
        self.assertEqual([r['clientId'] for r in pending], ['next'])
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM outbox').fetchone()[0], 57)

    def test_discarded_snapshot_entry_is_not_pending_anymore(self):
        self.assertIsNotNone(self.db.execute(query('pendingByClientId'), {'clientId': 'old'}).fetchone())
        self.db.execute('DELETE FROM outbox WHERE clientId=?', ('old',))
        self.assertIsNone(self.db.execute(query('pendingByClientId'), {'clientId': 'old'}).fetchone())


if __name__ == '__main__':
    unittest.main()
