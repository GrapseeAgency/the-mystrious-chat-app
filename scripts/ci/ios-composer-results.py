"""Fail closed on missing/skipped XCTest cases; publish explicit section-3 evidence.

This scoped check supplements, and never replaces, the full iOS workflow.
Only terminal raw XCTest records count, not compilation or an absent error.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
FILES = (
    'ComposerJournalTests', 'DraftHandoffTests', 'DraftJournalTests',
    'DraftReceiptTests', 'ThreadDraftHandoffTests', 'PulseOutboxTests',
    'TextDeliveryTests', 'ScheduledAcceptanceTests', 'ScheduledCreationRetryTests',
    'ComposerAttentionTests',
)


def expected_tests(root=ROOT):
    expected = set()
    for name in FILES:
        source = (root / 'apps/ios/PulseTests' / (name + '.swift')).read_text()
        suite = None
        for match in re.finditer(r'\bclass\s+(\w+)\s*:\s*XCTestCase\b|\bfunc\s+(test\w+)\s*\(', source):
            if match[1]:
                suite = match[1]
            elif suite:
                expected.add(suite + '.' + match[2])
        if not suite:
            raise ValueError('No XCTest suite found in ' + name)
    return expected


def results(text):
    observed = {}
    # Serial XCTest and xcodebuild's parallel test-summary formats respectively.
    patterns = (
        r"Test Case '-\[(?:\w+\.)?(\w+) (test\w+)\]' (passed|failed|skipped)\b",
        r"Test case '(?:\w+\.)?(\w+)\.(test\w+)\(\)' (passed|failed|skipped)\b",
    )
    for pattern in patterns:
        for suite, method, status in re.findall(pattern, text):
            observed.setdefault(suite + '.' + method, set()).add(status)
    return observed


def evaluate(expected, observed):
    # A retry cannot hide an earlier failure/skip. An empty run is never green.
    return {name: sorted(observed.get(name, {'missing'})) for name in sorted(expected)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--selectors', action='store_true')
    parser.add_argument('--log', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    expected = expected_tests()
    if args.selectors:
        print('\n'.join('-only-testing:PulseTests/' + suite for suite in sorted({n.split('.')[0] for n in expected})))
        return 0
    if not args.log or not args.output:
        parser.error('--log and --output are required for a report')
    cases = evaluate(expected, results(args.log.read_text(errors='replace') if args.log.exists() else ''))
    passed = sum(status == ['passed'] for status in cases.values())
    report = {
        'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'scope': 'section3-ios-composer', 'expected': len(expected), 'passed': passed,
        'verified': bool(expected) and passed == len(expected), 'cases': cases,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    # One compact, bounded annotation is retrievable through the Checks API,
    # even when artifact/log download hosts are unreachable. Contains test IDs
    # and outcomes only, never a raw log tail or test message contents.
    message = json.dumps(report, separators=(',', ':'))
    escaped = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    level = 'notice' if report['verified'] else 'error'
    print(f'::{level} title=Section 3 explicit iOS results::{escaped}')
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as f:
            f.write(f"## Section 3 iOS tests\n\nSource: `{report['sourceCommit']}`\n\n")
            f.write(f"Explicitly passed: **{passed}/{len(expected)}**. Full iOS suite is a separate check.\n\n")
            f.write('| Test | Observed terminal outcomes |\n| --- | --- |\n')
            for name, status in cases.items():
                f.write(f'| `{name}` | {", ".join(status)} |\n')
    return 0 if report['verified'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
