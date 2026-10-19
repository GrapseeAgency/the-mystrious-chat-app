#!/usr/bin/env python3
"""Index every production Kotlin/Swift file; never equate indexing with review.

Run from any directory. Counts are triage signals, not confirmed defects. Tests,
Android build/generated outputs and iOS projects outside Pulse are out of scope.
The companion review document records targeted inspection/repair evidence.
"""
from pathlib import Path
import csv
import hashlib
import re

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "docs/NATIVE-SOURCE-INVENTORY.csv"


def sources():
    android = ROOT / "apps/android"
    for module in sorted(android.iterdir()):
        for source in sorted((module / "src/main").rglob("*.kt")):
            yield source, "Android/" + module.name
    for source in sorted((ROOT / "apps/ios/Pulse").rglob("*.swift")):
        parts = source.relative_to(ROOT / "apps/ios/Pulse").parts
        tree = "/".join(parts[:2]) if parts[0] in {"Features", "Core", "Domain"} else parts[0]
        yield source, "iOS/" + tree


def main():
    count = 0
    with OUTPUT.open("w", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow([
            "path", "feature_tree", "lines", "sha256", "coverage",
            "detached_android_scope_occurrences", "swift_task_occurrences",
            "silent_error_pattern_occurrences", "declared_symbols",
        ])
        for path, tree in sources():
            source = path.read_text()
            # Approximate lexical signals include comments; they deliberately do not certify safety.
            symbols = re.findall(r"\b(?:fun|func|class|struct|object|enum|interface|protocol)\s+([A-Za-z_][A-Za-z_0-9]*)", source)
            writer.writerow([
                path.relative_to(ROOT).as_posix(), tree, len(source.splitlines()),
                hashlib.sha256(path.read_bytes()).hexdigest(), "indexed; see targeted-flow ledger",
                len(re.findall(r"CoroutineScope\s*\(", source)),
                len(re.findall(r"\bTask\s*(?:\{|\.detached)", source)),
                len(re.findall(r"try\?|runCatching|getOrNull\(|getOrDefault\(", source)),
                "; ".join(dict.fromkeys(symbols)),
            ])
            count += 1
    print(f"Indexed {count} production source files into {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
