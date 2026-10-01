"""Release smoke driver - boots the SIGNED release APK on the CI emulator,
walks onboarding like a real user, drives every nav path (dock quick-switcher,
dock drag, header buttons), and captures screenshots + logcat at every stage.

Exit codes: 0 = smoke clean, 2 = crash captured (full logcat in artifacts),
3 = driver could not establish the environment (adb device missing etc.).
"""
import os
import re
import subprocess
import sys
import time

PKG = "app.pulse.chat"
ACT = PKG + "/app.pulse.android.MainActivity"
OUT = "release-smoke"
os.makedirs(OUT, exist_ok=True)
REPORT = []
W = 0
H = 0
SINCE = ""  # logcat -T marker so each scan reads only fresh lines

def sh(*args, timeout=60):
    return subprocess.run(list(args), capture_output=True, text=True, timeout=timeout)

def adb(*args, timeout=60):
    return sh("adb", *args, timeout=timeout)

def adb_ok(*args, timeout=60):
    r = adb(*args, timeout=timeout)
    if r.returncode != 0:
        print("ADB FAIL:", args, r.stdout[-400:] if r.stdout else "", r.stderr[-400:] if r.stderr else "")
    return r

def note(line):
    print("[smoke]", line, flush=True)
    REPORT.append(line)

def wm_size():
    out = adb_ok("shell", "wm", "size").stdout
    m = re.search(r"(\d+)x(\d+)", out)
    return int(m.group(1)), int(m.group(2))

def screen(name):
    os.makedirs(OUT, exist_ok=True)
    r = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=90)
    if r.returncode == 0 and len(r.stdout) > 1000:
        with open(OUT + "/" + name, "wb") as f:
            f.write(r.stdout)
        return
    adb_ok("shell", "screencap", "-p", "/sdcard/" + name)
    adb_ok("pull", "/sdcard/" + name, OUT + "/" + name)

def dump_logcat(name):
    """Full logcat snapshot, ALWAYS written to the evidence dir."""
    r = adb("logcat", "-d", timeout=120)
    with open(OUT + "/" + name, "w", encoding="utf-8", errors="replace") as f:
        f.write(r.stdout or "")

def uiax_xml():
    """Dump the accessibility tree. Compose infinite animations can block
    idle detection, so retry a few times and accept late dumps."""
    for _ in range(6):
        r = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=90)
        if r.returncode == 0 and r.stdout and "ERROR" not in r.stdout:
            pull = adb("pull", "/sdcard/ui.xml", OUT + "/ui.xml", timeout=60)
            if pull.returncode == 0:
                try:
                    with open(OUT + "/ui.xml", "r", encoding="utf-8", errors="replace") as f:
                        return f.read()
                except OSError:
                    pass
        time.sleep(1.5)
    return ""

def node_bounds(xml, needle):
    """First node whose text or content-desc contains needle, as center (x, y)."""
    if not xml:
        return None
    for chunk in xml.split("<node")[1:]:
        tm = re.search(r'text="([^"]*)"', chunk)
        dm = re.search(r'content-desc="([^"]*)"', chunk)
        texts = ((tm.group(1) if tm else "") + " " + (dm.group(1) if dm else "")).strip()
        if needle.lower() in texts.lower():
            bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', chunk)
            if bm:
                x1, y1, x2, y2 = map(int, bm.groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None

def tap(node):
    adb_ok("shell", "input", "tap", str(node[0]), str(node[1]))

def tap_text(needle, wait=1.5):
    pos = node_bounds(uiax_xml(), needle)
    if pos is None:
        note("MISS node: " + needle)
        return False
    tap(pos)
    time.sleep(wait)
    return True

def alive():
    return bool(adb("shell", "pidof", PKG).stdout.strip())

CRASH_MARKERS = re.compile(
    r" E AndroidRuntime: |FATAL EXCEPTION|Force finishing activity"
    r"|ANR in |am_anr|am_kill|lmkd|lowmemorykiller|Killing .*app\.pulse\.chat"
    r"|Process app\.pulse\.chat[^ ]* has died|has died unexpectedly"
    r"|SIGSEGV|SIGABRT|tombstone",
    re.IGNORECASE,
)

def crash_scan(stage):
    """Scan ONLY the log lines newer than the previous scan; on any hit,
    persist the full logcat + screenshot and report a capture."""
    cmd = ["logcat", "-d"]
    if SINCE:
        cmd += ["-T", SINCE]
    log = adb(*cmd, timeout=120).stdout or ""
    hits = [ln for ln in log.splitlines() if CRASH_MARKERS.search(ln)]
    update_since()
    if hits:
        note("CRASH MARKERS at stage '" + stage + "' (" + str(len(hits)) + " lines):")
        for h in hits[:15]:
            note("    " + h.strip()[:220])
        dump_logcat("crash-logcat.txt")
        screen("crash-" + stage + ".png")
        return True
    return False

def update_since():
    """Mark the logcat cursor at 'now' so the next scan reads only new lines."""
    global SINCE
    r = adb("shell", "date", "+%m-%d %H:%M:%S.000", timeout=30)
    if r.returncode == 0 and r.stdout.strip():
        SINCE = r.stdout.strip()

def death_evidence(stage):
    note("capturing death evidence for stage: " + stage)
    dump_logcat("crash-logcat.txt")
    screen("crash-" + stage + ".png")

def launch():
    adb_ok("logcat", "-c")  # clear the buffer so evidence is only this run
    adb_ok("shell", "am", "force-stop", PKG)
    adb_ok("shell", "am", "start", "-W", "-n", ACT)
    time.sleep(7)
    update_since()

def dock_tap():
    """One tap on the floating dock pill area (bottom-center)."""
    tap((W // 2, H - int(H * 0.075)))
    time.sleep(2.0)

def dock_drag():
    """Horizontal drag across the dock pill: prev/next tab (web edge-swipe parity)."""
    y = H - int(H * 0.075)
    adb_ok("shell", "input", "swipe", str(W // 2 - 150), str(y), str(W // 2 + 150), str(y), "250")
    time.sleep(3.0)

def main():
    global W, H
    W, H = wm_size()
    note("device %dx%d" % (W, H))
    failed = False

    def stage(name, fn):
        nonlocal failed
        note("stage: " + name)
        fn()
        time.sleep(1.0)
        if not alive():
            note("PROCESS DEAD after stage: " + name)
            death_evidence(name)
            failed = True
            return False
        if crash_scan(name):
            failed = True
            return False
        return True

    launch()
    screen("01-launch.png")
    note("launch pid: " + (adb("shell", "pidof", PKG).stdout.strip() or "none"))

    # Onboarding (fresh install): name -> Continue -> Skip for now.
    def do_onboarding():
        xml = uiax_xml()
        field = node_bounds(xml, "What should people call you")
        if field is None:
            note("onboarding not detected (existing install or different state); continuing")
            return
        tap(field)
        time.sleep(1)
        adb_ok("shell", "input", "text", "EmberTester")
        adb_ok("shell", "input", "keyevent", "111")  # ESC closes any suggestion bar
        time.sleep(1)
        tap_text("Continue", wait=2.5)
        tap_text("Skip for now", wait=4.0)

    stage("onboarding", do_onboarding)
    screen("02-main-shell.png")
    if failed:
        return finish(2)

    # Deterministic tab walk via dock drag (chats -> hub -> contacts -> profile).
    def tab_walk():
        for i, name in enumerate(("hub", "contacts", "profile")):
            dock_drag()
            screen("04-drag-%s.png" % name)
            note("dragged to " + name)
            if not alive():
                return

    if not stage("tab-walk-drag", tab_walk):
        return finish(2)

    # Dock quick-switcher: tap the pill, menu opens; tap items by text.
    def nav_via_switcher():
        dock_tap()
        screen("03-switcher-open.png")
        for label in ("Hub", "Contacts", "Profile"):
            if not tap_text(label, wait=3.0):
                return
            screen("04-switch-%s.png" % label.lower())
            note("switched to " + label)
            if not alive():
                return
            dock_tap()

    if not stage("nav-switcher", nav_via_switcher):
        return finish(2)

    # Settings entry from the chats header (icon-only button).
    def open_settings():
        if not tap_text("Settings", wait=3.5):
            return
        screen("06-settings.png")

    if not stage("settings", open_settings):
        return finish(2)

    # Back to chats, settle, final sweep.
    def back_home():
        adb_ok("shell", "input", "keyevent", "4")
        time.sleep(2)
        screen("07-back-chats.png")

    if not stage("back-home", back_home):
        return finish(2)

    note("ALL STAGES DONE - final logcat sweep")
    if crash_scan("final"):
        return finish(2)
    return finish(0)

def finish(code):
    dump_logcat("logcat-full.txt")
    with open(OUT + "/report.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(REPORT) + "\n")
    note("smoke finished with code " + str(code))
    sys.exit(code)

if __name__ == "__main__":
    main()
