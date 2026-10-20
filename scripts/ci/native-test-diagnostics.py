"""Expose bounded compiler/test diagnostics via Checks annotations, not private logs."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

lines = []
log = Path('/tmp/pulse-native-jvm.log')
if log.exists():
    lines.extend(line for line in log.read_text(errors='replace').splitlines()
                 if re.match(r'^e: .*\.kt:', line))
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
if lines:
    text = '\n'.join(lines)[:10000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::error title=Native compile/test diagnostics::' + text)
