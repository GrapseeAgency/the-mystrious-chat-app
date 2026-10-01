"""Release smoke driver - boots the SIGNED release APK on the CI emulator,
walks onboarding like a real user, drives every nav path (dock quick-switcher,
dock drag, header buttons), and captures screenshots + logcat at every stage.

Exit codes: 0 = smoke clean, 2 = crash captured (full logcat in artifacts),
3 = driver could not establish the environment (adb device missing etc.).
"""
import re
import subprocess
import sys
import time

PKG = "app.pulse.chat"
ACT = PKG + "/app.pulse.android.MainActivity"
OUT = "release-smoke"
REPORT = []

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
    r = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=90)
    if r.returncode == 0 and len(r.stdout) > 1000:
        with open(OUT + "/" + name, "wb") as f:
            f.write(r.stdout)
        return
    adb_ok("shell", "screencap", "-p", "/sdcard/" + name)
    adb_ok("pull", "/sdcard/" + name, OUT + "/" + name)

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

CRASH_MARKERS = re.compile(r"FATAL EXCEPTION|AndroidRuntime: |Force finishing activity|has died unexpectedly|Signal 11|SIGSEGV", re.IGNORECASE)

def crash_scan(stage):
    log = adb("logcat", "-d", "-t", "600", timeout=90).stdout or ""
    hits = [ln for ln in log.splitlines() if CRASH_MARKERS.search(ln)]
    if hits:
        note("CRASH MARKERS at stage '" + stage + "': " + " | ".join(hits[:12]))
        full = adb("logcat", "-d", timeout=120).stdout or ""
        with open(OUT + "/crash-logcat.txt", "w", encoding="utf-8", errors="replace") as f:
            f.write(full)
        screen("crash-" + stage + ".png")
        return True
    return False

def launch():
    adb_ok("shell", "am", "force-stop", PKG)
    adb_ok("shell", "am", "start", "-W", "-n", ACT)
    time.sleep(7)

def open_switcher():
    """Tap the floating dock pill (icon-only, bottom-center). The exact
    vertical position varies with nav-bar insets, so scan candidate rows
    until the quick-switcher menu (a 'Hub' item) is visible."""
    for frac in (0.075, 0.06, 0.09, 0.045):
        y = H - int(H * frac)
        tap((W // 2, y))
        time.sleep(2.0)
        if node_bounds(uiax_xml(), "Hub") is not None:
            return True
    note("MISS dock pill: quick-switcher did not open at any candidate row")
    return False

def main():
    W, H = wm_size()
    note("device %dx%d" % (W, H))
    dock_y = H - int(H * 0.075)
    failed = False

    def stage(name, fn):
        nonlocal failed
        note("stage: " + name)
        fn()
        time.sleep(1.0)
        if not alive():
            note("PROCESS DEAD after stage: " + name)
            failed = True
        elif crash_scan(name):
            failed = True
        return not failed

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

    # Nav path 1: dock pill tap -> quick-switcher menu items (the visible nav bar).
    def nav_via_switcher():
        open_switcher()
        screen("03-switcher-open.png")
        for label in ("Hub", "Contacts", "Profile"):
            tap_text(label, wait=3.0)
            screen("04-tab-%s.png" % label.lower())
            note("switched to " + label)
            open_switcher()

    stage("nav-switcher", nav_via_switcher)

    # Nav path 2: horizontal drag on the dock (prev/next tab).
    def nav_via_drag():
        y = dock_y
        adb_ok("shell", "input", "swipe", str(W // 2 - 180), str(y), str(W // 2 + 180), str(y), "250")
        time.sleep(3.0)
        screen("05-after-drag.png")

    stage("nav-drag", nav_via_drag)

    # Settings entry from the chats header (icon-only button).
    def open_settings():
        tap_text("Settings", wait=3.5)
        screen("06-settings.png")

    stage("settings", open_settings)

    # Back to chats, settle, final logcat sweep (deeper window).
    def back_home():
        adb_ok("shell", "input", "keyevent", "4")
        time.sleep(2)
        open_switcher()
        tap_text("Chats", wait=3)
        screen("07-back-chats.png")

    stage("back-home", back_home)

    note("ALL STAGES DONE - scanning deep logcat")
    if not failed and crash_scan("final"):
        failed = True

    with open(OUT + "/report.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(REPORT) + "\n")
    note("smoke finished, crash=" + str(failed))
    sys.exit(2 if failed else 0)

if __name__ == "__main__":
    main()
