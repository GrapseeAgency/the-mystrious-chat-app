import importlib.util
import json
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

    def test_annotation_preserves_suite_counts_without_truncating_at_checks_limit(self):
        expected = report.expected_tests()
        for status in ['passed', 'missing']:
            cases = {name: [status] for name in sorted(expected)}
            payload = {'sourceCommit': 'a' * 40, 'scope': 'section3-ios-composer',
                       'expected': len(expected), 'passed': len(expected) if status == 'passed' else 0,
                       'verified': status == 'passed', 'cases': cases}
            notice = report.annotation_report(payload)
            self.assertLess(len(json.dumps(notice).encode()), 3500)
            self.assertEqual(sum(suite['expected'] for suite in notice['suites'].values()), len(expected))
            self.assertEqual(sum(suite['passed'] for suite in notice['suites'].values()), payload['passed'])
            self.assertEqual(notice['unverifiedCount'], len(expected) - payload['passed'])
            self.assertLessEqual(len(notice['firstUnverified']), 5)

    def test_empty_log_is_not_evidence(self):
        self.assertEqual(report.evaluate({'Suite.testOne'}, report.results('')), {'Suite.testOne': ['missing']})


if __name__ == '__main__':
    unittest.main()
