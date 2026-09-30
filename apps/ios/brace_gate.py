#!/usr/bin/env python3
"""Brace/paren/bracket balance gate for Swift sources.

Method (same as prior waves): strip comments and string literals first,
then count (), {}, [] on the residue. Strings handle backslash escapes and
the triple-quote multiline form; comments handle // and /* */ with nesting.
The stripper also removes string interpolation braces by consuming the
whole string, so interpolation contents never skew the counts.
"""
import sys

def strip_swift(src: str) -> str:
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and nxt == "*":
            depth = 1
            i += 2
            while i < n and depth > 0:
                if src[i] == "/" and i + 1 < n and src[i + 1] == "*":
                    depth += 1
                    i += 2
                elif src[i] == "*" and i + 1 < n and src[i + 1] == "/":
                    depth -= 1
                    i += 2
                else:
                    i += 1
            continue
        if c == '"':
            if src.startswith('"""', i):
                i += 3
                while i < n and not src.startswith('"""', i):
                    if src[i] == "\\":
                        i += 1
                    i += 1
                i += 3
            else:
                i += 1
                while i < n and src[i] != '"':
                    if src[i] == "\\":
                        i += 1
                    i += 1
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)

BALANCE = {"(": ")", "[": "]", "{": "}"}
CLOSERS = {")", "]", "}"}

def balance_report(src: str):
    code = strip_swift(src)
    stack = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in BALANCE:
            stack.append((ch, line))
        elif ch in CLOSERS:
            if not stack:
                return False, f"line {line}: unmatched closer {ch!r}"
            opener, oline = stack.pop()
            if BALANCE[opener] != ch:
                return False, f"line {line}: {ch!r} closes {opener!r} from line {oline}"
    if stack:
        opener, oline = stack[-1]
        return False, f"unclosed {opener!r} from line {oline}"
    return True, "BALANCED"

def main(paths):
    fails = 0
    for p in paths:
        with open(p) as f:
            ok, msg = balance_report(f.read())
        tag = "BALANCED" if ok else "UNBALANCED"
        print(f"{p}: {tag} ({msg})")
        fails += 0 if ok else 1
    print(f"{len(paths) - fails}/{len(paths)} BALANCED")
    return 1 if fails else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
