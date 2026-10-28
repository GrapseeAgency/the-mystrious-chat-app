import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('composer_results', ROOT / 'scripts/ci/ios-composer-results.py')
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ComposerResultEvidenceTests(unittest.TestCase):
    def test_manifest_includes_both_suites_in_shared_file(self):
        names = report.expected_tests()
        self.assertIn('ComposerJournalTests.testFailedEditWriteSurvivesReopenedStoreWithoutRebasingOntoRemoteText', names)
        self.assertIn('ScheduledCommandJournalTests.testAcceptedScheduleReceiptRetainsOnlyBoundMetadataAndRejectsChangedInstructions', names)
        self.assertGreater(len(names), 90)

    def test_serial_and_parallel_xctest_records(self):
        raw = """Test Case '-[PulseTests.DraftJournalTests testClear]' started.
Test Case '-[PulseTests.DraftJournalTests testClear]' passed (0.001 seconds).
Test case 'ScheduledCommandJournalTests.testCancel()' passed on 'Clone 1' (0.001 seconds)
Test case 'PulseTests.PulseOutboxTests.testReplay()' failed on 'Clone 1' (0.001 seconds)
"""
        self.assertEqual(report.results(raw), {
            'DraftJournalTests.testClear': {'passed'},
            'ScheduledCommandJournalTests.testCancel': {'passed'},
            'PulseOutboxTests.testReplay': {'failed'},
        })

    def test_missing_skipped_and_retry_do_not_become_passes(self):
        raw = """Test Case '-[PulseTests.Suite testRetry]' failed (0.001 seconds).
Test Case '-[PulseTests.Suite testRetry]' passed (0.001 seconds).
Test Case '-[PulseTests.Suite testSkip]' skipped (0.001 seconds).
Test Case '-[PulseTests.Suite testMissing]' started.
** BUILD SUCCEEDED **
"""
        actual = report.evaluate({'Suite.testRetry', 'Suite.testSkip', 'Suite.testMissing'}, report.results(raw))
        self.assertEqual(actual['Suite.testRetry'], ['failed', 'passed'])
        self.assertEqual(actual['Suite.testSkip'], ['skipped'])
        self.assertEqual(actual['Suite.testMissing'], ['missing'])
        self.assertFalse(any(status == ['passed'] for status in actual.values()))

    def test_empty_log_is_not_evidence(self):
        self.assertEqual(report.evaluate({'Suite.testOne'}, report.results('')), {'Suite.testOne': ['missing']})


if __name__ == '__main__':
    unittest.main()
