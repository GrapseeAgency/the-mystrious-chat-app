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

    def test_alternate_schedulers_capture_owner_before_async_submission(self):
        android = (ROOT / 'apps/android/app/src/main/java/app/pulse/android/mirror/MirrorRoom.kt').read_text()
        section = android[android.index('private fun MirrorOwnedScheduleComposerSheet('):]
        section = section[:section.index('    MirrorSheet(title = "Schedule message"')]
        self.assertLess(section.index('val owned = repository.boundToCurrentAccount()'), section.index('scope.launch'))
        self.assertIn('owned.attachmentSessionKey() != epoch', section)
        self.assertIn('owned.scheduleMessage(', section)
        self.assertIn('owned.acknowledgeScheduledCreation(item)', section)
        self.assertNotIn('repository.scheduleMessage(', section)
        ios = (ROOT / 'apps/ios/Pulse/Features/Mirror/MirrorRootView.swift').read_text()
        section = ios[ios.index('private struct MirrorScheduleBuilder: View'):]
        section = section[:section.index('private struct MirrorPhrasesBuilder:')]
        launched = section[section.index('        Task {'):]
        self.assertLess(launched.index('guard !Task.isCancelled, session.sessionGeneration == epoch, request == ticket'),
                        launched.index('try await session.scheduleMessage('))

    def test_managers_keep_snapshot_and_account_ownership_for_queued_actions(self):
        source = (ROOT / 'apps/android/feature-settings/src/main/kotlin/app/pulse/feature/settings/SettingsViewModel.kt').read_text()
        section = source[source.index('    fun deleteDraft('):source.index('    // Data & Storage')]
        self.assertNotIn('repo.clearDraft(', section)
        self.assertNotIn('repo.clearAllDrafts(', section)
        self.assertNotIn('repo.clearOutbox(', section)
        self.assertNotIn('repo.retryOutboxEntry(', section)
        self.assertNotIn('drafts.value.toList()', section)
        self.assertNotIn('outbox.value.toList()', section)
        screen = (ROOT / 'apps/android/feature-settings/src/main/kotlin/app/pulse/feature/settings/SettingsRootScreen.kt').read_text()
        self.assertIn('viewModel.clearDrafts(displayedDrafts)', screen)
        self.assertIn('viewModel.clearOutbox(displayedOutbox)', screen)
        self.assertNotIn('captureDraftToken(', section, 'Delete must not recapture authority after a row was displayed')
        self.assertIn('row.snapshot.clearIfCurrent(repo)', section)
        self.assertIn('row.owner.repository.discardOutboxEntryIfUnchanged(row.snapshot)', section)
        self.assertIn('if (!owner.isCurrent(repo)) return@launch', section)
        recovery_action = section[section.index('    fun recoverComposer(row:'):section.index('    private val retryingOutbox')]
        self.assertNotIn('recoveryOwner', recovery_action)
        self.assertEqual(recovery_action.count('row.canAct(repo, _composerRecovery.value)'), 2)
        self.assertIn('target.recoverComposer(row.entry,action)', recovery_action)
        self.assertIn('viewModel.recoveryText(row)', screen)
        self.assertIn('Pair<ComposerManagerRecoverySnapshot,String>', screen)
        observed = source[source.index('    val drafts: StateFlow'):source.index('    fun deleteDraft(')]
        self.assertEqual(observed.count('observeComposerManagerRows(repo)'), 2)
        self.assertIn('owner.draft(conversationId, text)', observed)
        self.assertNotIn('combine(repo.observe', observed)
        ios = (ROOT / 'apps/ios/Pulse/Features/Settings/DraftsOutboxManagerView.swift').read_text()
        section = ios[ios.index('    private func performRecovery('):ios.index('    // drafts')]
        self.assertIn('guard ownedStore(ticket: ticket) != nil else { return }', section)
        self.assertIn('snapshotFence.accepts(ticket)', section)
        self.assertLess(section.index('guard !Task.isCancelled, session.sessionGeneration == epoch'),
                        section.index('try await session.recoverComposer('))

    def test_schedule_cancellation_is_owned_before_launch(self):
        android = (ROOT / 'apps/android/app/src/main/java/app/pulse/android/mirror/MirrorRoomTools.kt').read_text()
        section = android[android.index('    fun cancel(id: String)'):]
        section = section[:section.index('    val loaded = items')]
        self.assertLess(section.index('val owned = repository.boundToCurrentAccount()'), section.index('scope.launch'))
        self.assertIn('owned.cancelScheduled(id)', section)
        ios = (ROOT / 'apps/ios/Pulse/Features/Chat/MessagingSurfaces.swift').read_text()
        section = ios[ios.index('    private func cancel(_ item: WireScheduledItem)'):]
        self.assertLess(section.index('guard !Task.isCancelled, session.sessionGeneration == epoch, request == ticket'),
                        section.index('try await session.cancelScheduled('))

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
