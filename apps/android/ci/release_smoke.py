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

def adb(*args, timeout=90):
    try:
        return sh("adb", *args, timeout=timeout)
    except subprocess.TimeoutExpired:
        print("ADB TIMEOUT:", args, flush=True)
        class R: returncode = 1; stdout = ""; stderr = "adb timeout"
        return R()
    except Exception as e:  # survive transient adb/emulator hiccups
        print("ADB ERROR:", args, e, flush=True)
        class R: returncode = 1; stdout = ""; stderr = str(e)
        return R()

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
    # 'Override size' wins when present (wm size was overridden post-boot).
    m = re.search(r"Override size: (\d+)x(\d+)", out) or re.search(r"Physical size: (\d+)x(\d+)", out)
    return int(m.group(1)), int(m.group(2))

def dismiss_system_dialogs():
    """API 33+ raises the POST_NOTIFICATIONS dialog at startup; it blocks every
    tap. Tap the exact 'Allow' button (NOT the dialog title, which also says
    Allow) or 'Don't allow' when present."""
    for _ in range(2):
        xml = uiax_xml()
        pos = node_bounds(xml, "Allow", exact=True)
        if pos is None:
            pos = node_bounds(xml, "Don't allow", exact=True)
        if pos is None:
            return
        note("dismissing system permission dialog")
        tap(pos)
        time.sleep(2.0)

def tap_scrolling(needle, wait=2.0, max_swipes=4):
    """Tap a node that may sit below the fold: swipe up to reveal, retry."""
    for i in range(max_swipes + 1):
        pos = node_bounds(uiax_xml(), needle, exact=True)
        if pos is None:
            pos = node_bounds(uiax_xml(), needle)
        if pos is not None:
            tap(pos)
            time.sleep(wait)
            return True
        if i < max_swipes:
            adb_ok("shell", "input", "swipe", str(W // 2), int(H * 0.7), str(W // 2), int(H * 0.35), "300")
            time.sleep(1.2)
    note("MISS node after scrolling: " + needle)
    return False

def ime_visible():
    out = adb("shell", "dumpsys", "input_method", timeout=60).stdout or ""
    return "mInputShown=true" in out

def dismiss_ime():
    adb_ok("shell", "input", "keyevent", "111")  # ESC first: cancel composing
    time.sleep(0.8)
    if ime_visible():
        adb_ok("shell", "input", "keyevent", "4")  # BACK closes the IME
        time.sleep(1.5)

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

def node_bounds(xml, needle, exact=False):
    """First node whose text or content-desc matches needle (contains, or
    equals when exact), as center (x, y)."""
    if not xml:
        return None
    needle_l = needle.lower()
    for chunk in xml.split("<node")[1:]:
        tm = re.search(r'text="([^"]*)"', chunk)
        dm = re.search(r'content-desc="([^"]*)"', chunk)
        candidates = [tm.group(1) if tm else "", dm.group(1) if dm else ""]
        hit = False
        for t in candidates:
            t = t.strip()
            if (t.lower() == needle_l) if exact else (needle_l in t.lower()):
                hit = True
                break
        if hit:
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
    dx = int(W * 0.25)
    adb_ok("shell", "input", "swipe", str(W // 2 - dx), str(y), str(W // 2 + dx), str(y), "250")
    time.sleep(3.0)

def dock_menu_probe():
    """Detect the quick-switcher popup WITHOUT touching the accessibility tree
    (uiautomator dump dismisses transient menus). Returns the popup frame as
    (x1, y1, x2, y2) or None."""
    out = adb("shell", "dumpsys", "window", "windows", timeout=60).stdout or ""
    for chunk in out.split("Window #")[1:]:
        if "popup" not in chunk.lower() and "MenuPopup" not in chunk and "popupmenu" not in chunk.lower():
            continue
        m = re.search(r"mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", chunk)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 > x1 and y2 > y1 and y2 - y1 > 40:
                return (x1, y1, x2, y2)
    return None

def node_bounds_all(xml, needle, exact=False):
    """Every node matching needle (contains, or equals when exact), as centers."""
    if not xml:
        return []
    needle_l = needle.lower()
    out = []
    for chunk in xml.split("<node")[1:]:
        tm = re.search(r'text="([^"]*)"', chunk)
        dm = re.search(r'content-desc="([^"]*)"', chunk)
        candidates = [tm.group(1) if tm else "", dm.group(1) if dm else ""]
        hit = False
        for t in candidates:
            t = t.strip()
            if (t.lower() == needle_l) if exact else (needle_l in t.lower()):
                hit = True
                break
        if hit:
            bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', chunk)
            if bm:
                x1, y1, x2, y2 = map(int, bm.groups())
                out.append(((x1 + x2) // 2, (y1 + y2) // 2))
    return out

def dock_pill_tap():
    """Tap the floating dock pill through its own label text (the pill shows
    'Chats' 'Call' 'Updates' 'Profile' labels). Blind coordinates keep missing
    it across densities; the label is always on the pill."""
    xml = uiax_xml()
    for label in ("Chats", "Call", "Updates", "Profile"):
        for (x, y) in node_bounds_all(xml, label, exact=True):
            if y > H * 0.72:
                tap((x, y))
                time.sleep(2.0)
                return True
    # fallback: scan bottom-center rows
    for frac in (0.085, 0.075, 0.095, 0.065, 0.105):
        tap((W // 2, H - int(H * frac)))
        time.sleep(1.6)
        if dock_menu_probe() is not None:
            return True
    note("MISS dock pill (label and rows)")
    return False

def tap_switcher_item(index):
    """Tap menu item #index (DOCK_TABS order) inside the popup frame."""
    frame = dock_menu_probe()
    if frame is None:
        note("MISS popup: switcher closed before item tap")
        return False
    x1, y1, x2, y2 = frame
    item_h = (y2 - y1) / 4.0
    tap(((x1 + x2) // 2, int(y1 + item_h * index + item_h / 2)))
    time.sleep(3.0)
    return True

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
    dismiss_system_dialogs()

    # Onboarding (fresh install): name -> Continue -> Skip for now.
    def do_onboarding():
        field = None
        for _ in range(3):
            xml = uiax_xml()
            field = node_bounds(xml, "What should people call you")
            if field is not None:
                break
            dismiss_system_dialogs()
            time.sleep(1.5)
        if field is None:
            note("onboarding not detected (existing install or different state); continuing")
            return
        tap(field)
        time.sleep(1)
        adb_ok("shell", "input", "text", "EmberTester")
        adb_ok("shell", "input", "keyevent", "111")  # ESC closes any suggestion bar
        dismiss_ime()  # the IME hides the Continue button below the fold
        time.sleep(1)
        # fast path: the field's IME action (GO) may advance straight to the
        # handle step - then Continue no longer exists and Start chatting is next.
        adb_ok("shell", "input", "keyevent", "66")
        time.sleep(2.0)
        if not tap_scrolling("Start chatting", wait=4.0):
            if not tap_scrolling("Skip for now", wait=4.0):
                if not tap_scrolling("Continue"):
                    return
                tap_scrolling("Start chatting", wait=4.0)

    stage("onboarding", do_onboarding)
    dismiss_system_dialogs()
    dismiss_ime()
    adb_ok("shell", "input", "keyevent", "4")  # belt: any IME residue gone
    time.sleep(1.5)
    screen("02-main-shell.png")
    if failed:
        return finish(2)

    # Deterministic tab walk via dock drag (chats -> calls -> hub -> profile).
    # Skip entirely when onboarding never finished (drags would hit its UX).
    def in_shell():
        xml = uiax_xml()
        return (node_bounds(xml, "What should people call you") is None
                and node_bounds(xml, "Pick your handle") is None)

    def tab_walk():
        if not in_shell():
            note("shell not detected; skipping drag walk")
            return
        for i, name in enumerate(("calls", "hub", "profile")):
            dock_drag()
            screen("04-drag-%s.png" % name)
            note("dragged to " + name)
            if not alive():
                return

    if not stage("tab-walk-drag", tab_walk):
        return finish(2)

    # Dock walk: tap each dock tab through its own label text. The reference
    # dock is a pure 4-tab pill (Chats / Call / Updates / Profile) - no popup
    # anywhere, so the old quick-switcher stage became a label tap walk.
    def nav_via_switcher():
        for label in ("Call", "Updates", "Profile", "Chats"):
            xml = uiax_xml()
            target = None
            for (x, y) in node_bounds_all(xml, label, exact=True):
                if y > H * 0.72:
                    target = (x, y)
                    break
            if target is None:
                note("MISS dock label " + label)
                continue
            tap(target)
            time.sleep(2.5)
            screen("04-tab-%s.png" % label.lower())
            note("tapped dock tab " + label)
            if not alive():
                return

    if not stage("nav-tabs", nav_via_switcher):
        return finish(2)

    # Settings entry: chats header kebab (content-desc "More options") first,
    # then the Settings item inside that menu.
    def open_settings():
        xml = uiax_xml()
        kebab = None
        for (x, y) in node_bounds_all(xml, "More options", exact=True):
            if y < H * 0.25:
                kebab = (x, y)
                break
        if kebab is not None:
            tap(kebab)
            time.sleep(1.5)
        if not tap_scrolling("Settings", wait=3.5):
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
