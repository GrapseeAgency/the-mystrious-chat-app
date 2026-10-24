"""Expose bounded compiler/test diagnostics via Checks annotations, not private logs."""
from pathlib import Path
import re
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
                lines.append(f"{case.get('classname')}::{case.get('name')}: {failure.get('message', '')[:500]}")
    except (ET.ParseError, OSError):
        continue
device_log = Path('/tmp/pulse-native-instrumented.log')
if device_log.exists():
    lines.extend(line for line in device_log.read_text(errors='replace').splitlines()
                 if line.startswith(('FAILURE:', '> ', 'Execution failed')))
if not lines:
    # Gradle/dependency/daemon failures can occur before a compiler or JUnit
    # report exists. Surface their bounded summary instead of only "exit 1".
    for name in ('/tmp/pulse-native-jvm.log', '/tmp/pulse-native-build.log'):
        log = Path(name)
        if log.exists():
            summary = [line for line in log.read_text(errors='replace').splitlines()
                       if line.startswith(('FAILURE:', 'Execution failed', '* What went wrong:'))
                       or (line.startswith('> ') and not line.startswith('> Task'))
                       or 'daemon disappeared' in line.lower()]
            lines.extend(summary[-30:])
if lines:
    text = '\n'.join(lines)[:10000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::error title=Native compile/test diagnostics::' + text)
