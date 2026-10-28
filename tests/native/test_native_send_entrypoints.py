"""Architectural tripwires; these complement, never replace native runtime tests."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class NativeSendEntrypoints(unittest.TestCase):
    def test_android_direct_message_http_sinks_stay_in_durable_repository(self):
        calls = []
        for path in (ROOT / 'apps/android').rglob('*.kt'):
            if '/src/main/' not in str(path):
                continue
            count = len(re.findall(r'\bapi\.sendMessage\(', path.read_text()))
            calls.extend([path.relative_to(ROOT).as_posix()] * count)
        self.assertEqual(calls, ['apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt'] * 3)

    def test_checkpoint_fault_injection_targets_real_room_tables(self):
        schema = (ROOT / 'apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt').read_text()
        tables = set(re.findall(r'@Entity\(\s*tableName\s*=\s*"([^"]+)"', schema))
        source = (ROOT / 'apps/android/data/src/androidTest/kotlin/app/pulse/data/repository/DraftCheckpointJournalTest.kt').read_text()
        targets = re.findall(r'CREATE TRIGGER \w+ BEFORE INSERT ON (\w+)', source)
        self.assertGreaterEqual(len(targets), 2)
        for table in targets:
            self.assertIn(table, tables)

    def test_mini_chat_keeps_text_until_owned_durable_result(self):
        source = (ROOT / 'apps/android/feature-chat/src/main/kotlin/app/pulse/feature/chat/PipOverlay.kt').read_text()
        self.assertIn('repository.stageDraft(pane.conversationId, value)', source)
        self.assertIn('repository.observeDraftSnapshot(pane.conversationId).collect', source)
        self.assertIn('current?.revision == snapshot.revision', source)
        self.assertIn('SendMessageUseCase(owned)(conversationId, body, draftToken = token)', source)
        self.assertIn('paneMayClearAfterSend(result, text, paneDraft, token, draftToken)', source)
        self.assertNotRegex(source, r'paneDraft\s*=\s*""\s*\n\s*onSend\(')

    def test_small_rich_pickers_do_not_discard_before_local_acceptance(self):
        source = (ROOT / 'apps/android/app/src/main/java/app/pulse/android/mirror/MirrorRoom.kt').read_text()
        start = source.index('    if (stickerOpen) {', source.index('    if (pollOpen) {'))
        section = source[start:source.index('    infoFor?.let', start)]
        self.assertEqual(section.count('val owned = repository.boundToCurrentAccount()'), 2)
        self.assertEqual(section.count('.getOrThrow()'), 2)
        self.assertNotIn('runCatching', section)
        self.assertNotIn('repository.sendRichMessage(', section)


if __name__ == '__main__':
    unittest.main()
