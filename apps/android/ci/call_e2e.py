"""Two-app 1:1 call E2E driver (R58 - section 7 items 3 + 11/13, emulator half).

Boots TWO Pulse app identities on ONE emulator against a REAL web backend +
REAL socket relay (both running on the CI runner, reached via 10.0.2.2):
  • PKG_A (caller, "Alpha") - release flavor, app.pulse.chat.native
  • PKG_B (callee, "Beta")  - debug flavor,   app.pulse.chat.debug

Drives like a real user, with evidence at every step:
  onboarding (server URL + name) -> manifest socket override adoption ->
  Contacts -> "Call Beta" -> Beta answers -> both CONNECTED ->
  callee process death mid-call (item 3: honest peer-gone + clean rejoin) ->
  redial -> `adb emu gsm call` interruption mid-call (item 11's Android
  audio-focus half) -> hangup -> final crash sweep.

Exit codes: 0 = all stages clean, 2 = failure or crash captured.
"""
import os
import re
import subprocess
import sys
import time

PKG_A = os.environ.get("E2E_PKG_A", "app.pulse.chat.native")
PKG_B = os.environ.get("E2E_PKG_B", "app.pulse.chat.debug")
ACT = "/app.pulse.android.MainActivity"
GW = os.environ.get("E2E_GATEWAY", "http://10.0.2.2:3000")
NAME_A = "Alpha"
NAME_B = "Beta"
OUT = "call-e2e"
os.makedirs(OUT, exist_ok=True)
REPORT = []
W = 0
H = 0
SINCE = ""

def sh(*args, timeout=60):
    return subprocess.run(list(args), capture_output=True, text=True, timeout=timeout)

def adb(*args, timeout=90):
    try:
        return sh("adb", *args, timeout=timeout)
    except subprocess.TimeoutExpired:
        print("ADB TIMEOUT:", args, flush=True)
        class R: returncode = 1; stdout = ""; stderr = "adb timeout"
        return R()
    except Exception as e:
        print("ADB ERROR:", args, e, flush=True)
        class R: returncode = 1; stdout = ""; stderr = str(e)
        return R()

def adb_ok(*args, timeout=60):
    r = adb(*args, timeout=timeout)
    if r.returncode != 0:
        print("ADB FAIL:", args, r.stdout[-300:] if r.stdout else "", r.stderr[-300:] if r.stderr else "")
    return r

def note(line):
    print("[e2e]", line, flush=True)
    REPORT.append(line)

def wm_size():
    out = adb_ok("shell", "wm", "size").stdout
    m = re.search(r"Override size: (\d+)x(\d+)", out) or re.search(r"Physical size: (\d+)x(\d+)", out)
    return int(m.group(1)), int(m.group(2))

def screen(name):
    r = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=90)
    if r.returncode == 0 and len(r.stdout) > 1000:
        with open(OUT + "/" + name, "wb") as f:
            f.write(r.stdout)
        return
    adb_ok("shell", "screencap", "-p", "/sdcard/" + name)
    adb_ok("pull", "/sdcard/" + name, OUT + "/" + name)

def dump_logcat(name):
    r = adb("logcat", "-d", timeout=120)
    with open(OUT + "/" + name, "w", encoding="utf-8", errors="replace") as f:
        f.write(r.stdout or "")

def uiax_xml():
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

def node_bounds_all(xml, needle, exact=False):
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

def tap(node):
    adb_ok("shell", "input", "tap", str(node[0]), str(node[1]))

def tap_text(needle, wait=2.0, exact=False):
    pos = node_bounds(uiax_xml(), needle, exact=exact)
    if pos is None:
        note("MISS node: " + needle)
        return False
    tap(pos)
    time.sleep(wait)
    return True

def wait_node(needle, timeout_s=15, exact=False):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        pos = node_bounds(uiax_xml(), needle, exact=exact)
        if pos is not None:
            return pos
        time.sleep(1.2)
    return None

def wait_text(needle, timeout_s=15, exact=False):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if node_bounds(uiax_xml(), needle, exact=exact) is not None:
            return True
        time.sleep(1.2)
    return False

def type_into(field_needle, text, fallback_y_frac=None):
    """Tap the field (by needle text/placeholder, else a blind fraction) and type."""
    pos = node_bounds(uiax_xml(), field_needle)
    if pos is None and fallback_y_frac is not None:
        pos = (W // 2, int(H * fallback_y_frac))
        note("field '" + field_needle + "' not in tree; blind tap at " + str(pos))
    if pos is None:
        note("MISS field: " + field_needle)
        return False
    tap(pos)
    time.sleep(1.2)
    adb_ok("shell", "input", "text", text)
    time.sleep(0.8)
    adb_ok("shell", "input", "keyevent", "111")  # ESC cancels any suggestion bar
    ime = adb("shell", "dumpsys", "input_method").stdout or ""
    if "mInputShown=true" in ime:
        adb_ok("shell", "input", "keyevent", "4")  # BACK closes the IME only
        time.sleep(1.2)
    return True

def alive(pkg):
    return bool(adb("shell", "pidof", pkg).stdout.strip())

def launch(pkg):
    adb_ok("shell", "am", "force-stop", pkg)
    time.sleep(1)
    adb_ok("shell", "am", "start", "-W", "-n", pkg + ACT)
    time.sleep(7)

def foreground(pkg):
    adb_ok("shell", "am", "start", "-n", pkg + ACT)
    time.sleep(4)

def dismiss_system_dialogs():
    for _ in range(3):
        xml = uiax_xml()
        pos = node_bounds(xml, "Allow", exact=True)
        if pos is None:
            pos = node_bounds(xml, "Don't allow", exact=True)
        if pos is None:
            pos = node_bounds(xml, "Wait", exact=True)
        if pos is None:
            return
        note("dismissing system dialog")
        tap(pos)
        time.sleep(2.0)

CRASH_MARKERS = re.compile(
    r" E AndroidRuntime: |FATAL EXCEPTION|Force finishing activity"
    r"|ANR in |am_anr|am_kill|lowmemorykiller|Killing .*app\.pulse\.chat"
    r"|Process app\.pulse\.chat[^ ]* has died|has died unexpectedly"
    r"|SIGSEGV|SIGABRT|tombstone",
    re.IGNORECASE,
)

def crash_scan(stage):
    cmd = ["logcat", "-d"]
    if SINCE:
        cmd += ["-T", SINCE]
    log = adb(*cmd, timeout=120).stdout or ""
    hits = [ln for ln in log.splitlines() if CRASH_MARKERS.search(ln)]
    r = adb("shell", "date", "+%m-%d %H:%M:%S.000", timeout=30)
    if r.returncode == 0 and r.stdout.strip():
        globals()["SINCE"] = r.stdout.strip()
    if hits:
        note("CRASH MARKERS at stage '" + stage + "' (" + str(len(hits)) + " lines):")
        for h in hits[:15]:
            note("    " + h.strip()[:220])
        dump_logcat("crash-logcat.txt")
        screen("crash-" + stage + ".png")
        return True
    return False

def onboard(pkg, name):
    """Server URL -> Connect -> display name -> skip @handle -> dock shell."""
    launch(pkg)
    dismiss_system_dialogs()
    if not type_into("your-pulse-gateway", "http://10.0.2.2:3000", fallback_y_frac=0.418):
        screen("onboard-fail-" + name + "-server-field.png")
        return False
    screen("onboard-" + name + "-server.png")
    if not tap_text("Connect", wait=3.0, exact=True):
        screen("onboard-fail-" + name + "-connect.png")
        return False
    # step 1: display name (the field auto-focuses; type either way)
    if not wait_text("What should people call you", timeout_s=20):
        screen("onboard-fail-" + name + "-name-step.png")
        return False
    pos = node_bounds(uiax_xml(), "What should people call you")
    if pos is not None:
        tap(pos)
        time.sleep(1)
    adb_ok("shell", "input", "text", name)
    time.sleep(0.6)
    adb_ok("shell", "input", "keyevent", "111")
    ime = adb("shell", "dumpsys", "input_method").stdout or ""
    if "mInputShown=true" in ime:
        adb_ok("shell", "input", "keyevent", "4")
        time.sleep(1.2)
    screen("onboard-" + name + "-name.png")
    # advance: Continue -> (@handle step) Skip for now / Start chatting
    for _ in range(4):
        xml = uiax_xml()
        pos = (node_bounds(xml, "Continue", exact=True)
               or node_bounds(xml, "Skip for now", exact=True)
               or node_bounds(xml, "Start chatting", exact=True))
        if pos is None:
            time.sleep(1.5)
            continue
        tap(pos)
        time.sleep(3.0)
        xml2 = uiax_xml()
        if (node_bounds(xml2, "What should people call you") is None
                and node_bounds(xml2, "Pick your handle") is None):
            break
    if not wait_text("Chats", timeout_s=15, exact=True):
        screen("onboard-fail-" + name + "-shell.png")
        return False
    screen("onboard-" + name + "-shell.png")
    note(name + " onboarded (shell visible)")
    return True

def assert_socket_override(pkg, label):
    """The relay override is adopted from {gateway}/update-manifest.json."""
    launch(pkg)
    dismiss_system_dialogs()
    time.sleep(3)
    log = adb("logcat", "-d", "-s", "ManifestEndpoints", timeout=60).stdout or ""
    with open(OUT + "/override-" + label + ".log", "w") as f:
        f.write(log)
    ok = "socket=http://10.0.2.2:3003" in log.replace(" ", "")
    note(label + " socket override " + ("ADOPTED" if ok else "NOT FOUND in ManifestEndpoints log"))
    return ok

def open_contacts():
    """Dock Chats (default) -> header kebab -> Contacts."""
    foreground(PKG_A)
    if not tap_text("More options", wait=2.0, exact=True):
        # kebab is top-row; blind tap fallback (right edge of the header)
        tap((W - int(W * 0.07), int(H * 0.055)))
        time.sleep(2.0)
    if not tap_text("Contacts", wait=3.5, exact=True):
        note("MISS Contacts menu item")
        screen("contacts-open-fail.png")
        return False
    if not wait_text("Find people", timeout_s=12):
        screen("contacts-fail.png")
        return False
    return True

def call_peer(name):
    """In Contacts: filter to the peer, tap the row's call button."""
    if not type_into("Find people", name):
        return False
    time.sleep(2.5)
    pos = node_bounds(uiax_xml(), "Call " + name, exact=True)
    if pos is None:
        screen("call-button-fail-" + name + ".png")
        note("MISS call button for " + name)
        return False
    tap(pos)
    time.sleep(2.5)
    screen("alpha-ringing.png")
    note("Alpha dials " + name + " (outgoing overlay up)")
    return True

def beta_answer():
    """Bring Beta foreground; its shell raises the incoming overlay; Accept."""
    foreground(PKG_B)
    pos = wait_node("Accept call", timeout_s=25, exact=True)
    if pos is None:
        screen("beta-accept-fail.png")
        dump_logcat("beta-accept-fail-logcat.txt")
        note("MISS Accept call on Beta")
        return False
    screen("beta-ringing.png")
    tap(pos)
    time.sleep(3)
    screen("beta-connected.png")
    note("Beta accepted")
    return True

def wait_connected(pkg, label, timeout_s=20):
    """CONNECTED surfaces the running duration (m:ss) in the status line."""
    deadline = time.time() + timeout_s
    dur = re.compile(r"\b\d{1,2}:\d\d\b")
    while time.time() < deadline:
        xml = uiax_xml()
        for chunk in xml.split("<node")[1:]:
            tm = re.search(r'text="([^"]*)"', chunk)
            if tm and dur.search(tm.group(1)):
                note(label + " CONNECTED (" + tm.group(1) + ")")
                return True
        time.sleep(1.5)
    screen(label.lower() + "-connected-fail.png")
    dump_logcat(label.lower() + "-connected-fail-logcat.txt")
    note("MISS connected duration on " + label)
    return False

def end_call(pkg, label):
    foreground(pkg)
    pos = node_bounds(uiax_xml(), "End call", exact=True)
    if pos is None:
        pos = wait_node("End call", timeout_s=8, exact=True)
    if pos is not None:
        tap(pos)
        time.sleep(3)
        note(label + " ended the call")
        return True
    note("MISS End call on " + label + " (call may already be down)")
    return False

def main():
    global W, H
    W, H = wm_size()
    note("device %dx%d" % (W, H))
    adb_ok("logcat", "-c")

    def bail(code, why):
        note("FAIL: " + why)
        dump_logcat("logcat-full.txt")
        with open(OUT + "/report.txt", "w") as f:
            f.write("\n".join(REPORT) + "\n")
        sys.exit(code)

    # Grant everything the call path touches so no permission dialog racies the UI drive.
    for pkg in (PKG_A, PKG_B):
        for perm in ("android.permission.RECORD_AUDIO", "android.permission.CAMERA",
                     "android.permission.POST_NOTIFICATIONS"):
            adb_ok("shell", "pm", "grant", pkg, perm)

    if not onboard(PKG_A, NAME_A):
        bail(2, "Alpha onboarding failed")
    if crash_scan("alpha-onboard"):
        bail(2, "crash after Alpha onboarding")

    if not onboard(PKG_B, NAME_B):
        bail(2, "Beta onboarding failed")
    if crash_scan("beta-onboard"):
        bail(2, "crash after Beta onboarding")

    # Cold restart BOTH: the persisted socket override (manifest) lands here,
    # and the relay logs two joins (realtime is provably up for both users).
    ok_a = assert_socket_override(PKG_A, "alpha")
    ok_b = assert_socket_override(PKG_B, "beta")
    if not (ok_a and ok_b):
        bail(2, "socket override adoption failed")
    time.sleep(5)
    foreground(PKG_A)

    # ── Call 1: ring, answer, connect ──────────────────────────────────────
    if not open_contacts():
        bail(2, "Contacts did not open")
    if not call_peer(NAME_B):
        bail(2, "Alpha could not dial Beta")
    if crash_scan("alpha-dial"):
        bail(2, "crash after dialing")

    # Ring evidence on the CALLEE side: notification + incoming overlay.
    notif = adb("shell", "dumpsys", "notification", "--noredact", timeout=60).stdout or ""
    with open(OUT + "/beta-ring-notification.txt", "w") as f:
        f.write(notif)
    note("ring notification snapshot saved (" + str(len(notif)) + " bytes)")

    if not beta_answer():
        bail(2, "Beta could not answer")
    if crash_scan("beta-accept"):
        bail(2, "crash after Beta accepted")

    if not wait_connected(PKG_A, "Alpha"):
        bail(2, "Alpha never reached CONNECTED")
    if not wait_connected(PKG_B, "Beta"):
        bail(2, "Beta never reached CONNECTED")
    screen("both-connected.png")

    # ── Item 3: callee process death mid-call -> honest teardown + rejoin ──
    adb_ok("shell", "am", "force-stop", PKG_B)
    note("Beta force-stopped mid-call (rejoin race)")
    ended = False
    deadline = time.time() + 20
    while time.time() < deadline:
        if node_bounds(uiax_xml(), "Dismiss", exact=True) is not None:
            ended = True
            break
        time.sleep(1.5)
    screen("alpha-peer-gone.png")
    if not ended:
        bail(2, "Alpha did not surface the peer-gone teardown in 20s")
    note("Alpha shows the call ended after Beta died (item 3 protocol-visible half)")
    if crash_scan("peer-gone"):
        bail(2, "crash on peer-gone teardown")

    launch(PKG_B)
    dismiss_system_dialogs()
    if not wait_text("Chats", timeout_s=15, exact=True):
        bail(2, "Beta did not relaunch cleanly after the mid-call death")
    note("Beta relaunched cleanly into the shell")
    if crash_scan("beta-rejoin"):
        bail(2, "crash on Beta rejoin")

    # ── Call 2 (redial) + item 11: real OS interruption mid-call ──────────
    foreground(PKG_A)
    if not open_contacts():
        bail(2, "Contacts did not open (redial)")
    if not call_peer(NAME_B):
        bail(2, "Alpha could not redial Beta")
    if not beta_answer():
        bail(2, "Beta could not answer the redial")
    if not wait_connected(PKG_A, "Alpha-redial"):
        bail(2, "Alpha never reached CONNECTED on the redial")
    screen("redial-connected.png")

    note("GSM interruption: incoming emulator call takes the audio session")
    adb("emu", "gsm", "call", "15551230001", timeout=30)
    time.sleep(9)
    screen("interruption-alpha.png")
    dump_logcat("interruption-logcat.txt")
    if crash_scan("gsm-interruption"):
        bail(2, "crash during GSM interruption (item 11)")
    for pkg in (PKG_A, PKG_B):
        if not alive(pkg):
            bail(2, pkg + " died during the GSM interruption")
    note("both apps alive through the GSM interruption (item 11 emulator half)")
    adb("emu", "gsm", "cancel", timeout=30)
    time.sleep(3)

    # Clean hangup ends the redial on both sides.
    end_call(PKG_B, "Beta")
    time.sleep(2)
    foreground(PKG_A)
    time.sleep(2)
    screen("after-hangup-alpha.png")

    note("ALL STAGES DONE - final crash sweep")
    if crash_scan("final"):
        bail(2, "crash in the final sweep")
    dump_logcat("logcat-full.txt")
    with open(OUT + "/report.txt", "w") as f:
        f.write("\n".join(REPORT) + "\n")
    note("E2E finished clean")
    sys.exit(0)

if __name__ == "__main__":
    main()
