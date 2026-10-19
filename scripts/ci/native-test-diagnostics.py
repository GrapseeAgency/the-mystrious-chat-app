"""Expose bounded compiler/test diagnostics via Checks annotations, not private logs."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

lines = []
log = Path('/tmp/pulse-native-jvm.log')
if log.exists():
    lines.extend(line for line in log.read_text(errors='replace').splitlines()
                 if re.match(r'^e: .*\.kt:', line))
for report in Path('.').glob('**/build/test-results/**/TEST-*.xml'):
    try:
        for case in ET.parse(report).iter('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                lines.append(f"{case.get('classname')}::{case.get('name')}: {failure.get('message', '')[:500]}")
    except (ET.ParseError, OSError):
        continue
if lines:
    text = '\n'.join(lines)[:10000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::error title=Native compile/test diagnostics::' + text)
