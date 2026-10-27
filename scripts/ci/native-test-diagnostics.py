"""Expose bounded compiler/test diagnostics via Checks annotations, not private logs."""
from pathlib import Path
import re
import os
import xml.etree.ElementTree as ET

lines = []
for name in ('/tmp/pulse-native-jvm.log', '/tmp/pulse-native-build.log'):
    log = Path(name)
    if log.exists():
        lines.extend(clean for line in log.read_text(errors='replace').splitlines()
                     if (clean := re.sub(r'\x1b\[[0-9;]*m', '', line).lstrip()).startswith('e: '))
reports = list(Path('.').glob('**/build/test-results/**/TEST-*.xml'))
reports += list(Path('.').glob('**/build/outputs/androidTest-results/**/TEST-*.xml'))
for report in reports:
    try:
        for case in ET.parse(report).iter('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                detail = failure.get('message') or (failure.text or '').strip()
                lines.append(f"{case.get('classname')}::{case.get('name')}: {detail[:3000]}")
    except (ET.ParseError, OSError):
        continue
device_log = Path('/tmp/pulse-native-instrumented.log')
if device_log.exists():
    lines.extend(line for line in device_log.read_text(errors='replace').splitlines()
                 if line.startswith(('FAILURE:', 'Execution failed', 'e: '))
                 or (line.startswith('> ') and not line.startswith('> Task')))
if not lines:
    # Gradle/dependency/daemon failures can occur before a compiler or JUnit
    # report exists. Surface their bounded summary instead of only "exit 1".
    for name in ('/tmp/pulse-native-jvm.log', '/tmp/pulse-native-build.log', '/tmp/pulse-native-instrumented.log'):
        log = Path(name)
        if log.exists():
            summary = [line for line in log.read_text(errors='replace').splitlines()
                       if line.startswith(('FAILURE:', 'Execution failed', '* What went wrong:'))
                       or (line.startswith('> ') and not line.startswith('> Task'))
                       or 'daemon disappeared' in line.lower()]
            lines.extend(summary[-30:])
for line in lines[:20]:
    text = line[:3500].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::error title=Native compile/test diagnostics::' + text)

if os.environ.get('PULSE_INSTRUMENTED_OUTCOME') == 'failure' and not lines:
    # Do not mislabel a failed emulator action as a failed native assertion.
    # This distinction stays visible even when hosted job-log downloads cannot
    # be retrieved. No environment values, credentials or arbitrary log tail.
    if not device_log.exists():
        print('::error title=Instrumentation did not produce a test log::The emulator action failed before an instrumentation log was created. No native test verdict is available; inspect emulator startup in this job.')
    else:
        cases = failures = 0
        for report in reports:
            try:
                for case in ET.parse(report).iter('testcase'):
                    cases += 1
                    failures += len(case.findall('failure')) + len(case.findall('error'))
            except (ET.ParseError, OSError):
                continue
        print(f'::error title=Instrumentation action failed::Test log exists; parsed {cases} cases and {failures} failures. The action did not complete successfully; do not treat absent assertion details as a pass.')
