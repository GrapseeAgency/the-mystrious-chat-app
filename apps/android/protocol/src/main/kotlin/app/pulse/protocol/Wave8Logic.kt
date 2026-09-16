package app.pulse.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Wave 8 logic — the canonical port of the web's prefs pipeline:
 *   - src/lib/prefs-defaults.ts (DEFAULT_PREFERENCES + mergePrefs clamp rules)
 *   - src/lib/pulse-settings.ts (minutesOf + isQuietHoursNow, lines 60-83)
 * Every rule below mirrors the web line-for-line so a server blob decodes
 * identically on both platforms; the Wave8LogicTest locks the parity.
 */
object PulseWave8Logic {

    // ── whitelists (web prefs-defaults.ts RADIUS/DENSITY/WALLPAPER/WEBGL_MODES_OK) ──
    val RADIUS = listOf("md", "lg", "pill")
    val DENSITY = listOf("cozy", "compact")
    val WALLPAPER = listOf("none", "aurora", "dusk", "forest", "mono")
    val WEBGL_MODES = listOf("off", "aurora", "caustics", "mesh", "stars", "liquid")

    /** Defaults — web DEFAULT_PREFERENCES verbatim. */
    val DEFAULTS: WirePulsePrefs = WirePulsePrefs(
        bubbleRadius = "lg",
        density = "cozy",
        wallpaper = "none",
        notifPreviews = true,
        notifSound = true,
        notifVibrate = false,
        lastSeenVisible = true,
        readReceipts = true,
        typingVisible = true,
        reducedMotion = false,
        fxWebglMode = null,
    )

    /**
     * Shallow-merge a stored/partial JSON object over the defaults, silently
     * discarding anything malformed (never throws, never returns junk).
     * Port of web mergePrefs — type-checked field-by-field, not via a
     * serializer, so wrong-typed junk ("bubbleRadius": 7) falls back exactly
     * like the web's typeof checks.
     */
    fun mergePrefs(raw: String?): WirePulsePrefs {
        if (raw.isNullOrBlank()) return DEFAULTS
        val obj = runCatching { PulseJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return DEFAULTS
        return mergePrefs(obj)
    }

    /** Object overload — same rules for an already-parsed element. */
    fun mergePrefs(raw: JsonObject?): WirePulsePrefs {
        if (raw == null) return DEFAULTS
        fun str(key: String, allowed: List<String>): String? {
            val v = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return v.takeIf { it in allowed }
        }
        fun bool(key: String): Boolean? = (raw[key] as? JsonPrimitive)?.booleanOrNull
        return WirePulsePrefs(
            bubbleRadius = str("bubbleRadius", RADIUS) ?: DEFAULTS.bubbleRadius,
            density = str("density", DENSITY) ?: DEFAULTS.density,
            wallpaper = str("wallpaper", WALLPAPER) ?: DEFAULTS.wallpaper,
            notifPreviews = bool("notifPreviews") ?: DEFAULTS.notifPreviews,
            notifSound = bool("notifSound") ?: DEFAULTS.notifSound,
            notifVibrate = bool("notifVibrate") ?: DEFAULTS.notifVibrate,
            lastSeenVisible = bool("lastSeenVisible") ?: DEFAULTS.lastSeenVisible,
            readReceipts = bool("readReceipts") ?: DEFAULTS.readReceipts,
            typingVisible = bool("typingVisible") ?: DEFAULTS.typingVisible,
            reducedMotion = bool("reducedMotion") ?: DEFAULTS.reducedMotion,
            // fx.webglMode is optional on the wire (absent = native keeps its own local FX pref)
            fxWebglMode = str("fx.webglMode", WEBGL_MODES),
        )
    }

    /** A partially-carried blob (missing fields = null) resolved against the defaults. */
    fun resolved(partial: WirePulsePrefs): WirePulsePrefs = mergePatch(DEFAULTS, partial)

    /** Shallow-merge a partial patch over resolved prefs (local optimistic path). */
    fun mergePatch(current: WirePulsePrefs, patch: WirePulsePrefs): WirePulsePrefs = WirePulsePrefs(
        bubbleRadius = patch.bubbleRadius ?: current.bubbleRadius,
        density = patch.density ?: current.density,
        wallpaper = patch.wallpaper ?: current.wallpaper,
        notifPreviews = patch.notifPreviews ?: current.notifPreviews,
        notifSound = patch.notifSound ?: current.notifSound,
        notifVibrate = patch.notifVibrate ?: current.notifVibrate,
        lastSeenVisible = patch.lastSeenVisible ?: current.lastSeenVisible,
        readReceipts = patch.readReceipts ?: current.readReceipts,
        typingVisible = patch.typingVisible ?: current.typingVisible,
        reducedMotion = patch.reducedMotion ?: current.reducedMotion,
        fxWebglMode = patch.fxWebglMode ?: current.fxWebglMode,
    )

    /**
     * 'HH:MM' → minutes since midnight; NaN-safe (falls back to 0).
     * Verbatim port of web pulse-settings.ts minutesOf — the regex demands
     * 1-2 hour digits and EXACTLY 2 minute digits, then clamps (23h/59m).
     */
    fun minutesOf(hhmm: String): Int {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm.trim()) ?: return 0
        val h = (m.groupValues[1].toIntOrNull() ?: 0).coerceIn(0, 23)
        val min = (m.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
        return h * 60 + min
    }

    /**
     * True when the given minute-of-day sits inside the quiet window.
     * Verbatim port of web isQuietHoursNow: start==end → false (degenerate
     * window = off); start<end → cur in [start, end); else wrap (overnight).
     */
    fun isQuietHoursNow(
        quietHoursOn: Boolean,
        quietStart: String,
        quietEnd: String,
        nowMinutes: Int,
    ): Boolean {
        if (!quietHoursOn) return false
        val cur = nowMinutes
        val start = minutesOf(quietStart)
        val end = minutesOf(quietEnd)
        if (start == end) return false
        return if (start < end) cur >= start && cur < end else cur >= start || cur < end
    }

    /**
     * Runtime overload — derives "now" from the device's LOCAL clock
     * (java.util.Calendar, API 21-safe; web uses Date.getHours/getMinutes).
     */
    fun isQuietHoursNow(quietHoursOn: Boolean, quietStart: String, quietEnd: String): Boolean {
        if (!quietHoursOn) return false
        val cal = java.util.Calendar.getInstance()
        val cur = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return isQuietHoursNow(quietHoursOn, quietStart, quietEnd, cur)
    }

    // ── incoming attention gate (web pulse-realtime-provider.tsx:601-607 parity) ──
    // Web: `if (!muted && !quiet) { playIncomingPing(); haptic(20) }` where the
    // ping honors soundOn and the haptic honors hapticsOn.

    /** Sound+attention allowed: outside quiet hours AND at least one channel armed. */
    fun incomingAttentionAllowed(
        notifSound: Boolean,
        notifVibrate: Boolean,
        quietNow: Boolean,
    ): Boolean = !quietNow && (notifSound || notifVibrate)

    /** Preview line for an incoming toast — notifPreviews=false hides the body. */
    fun incomingPreviewText(notifPreviews: Boolean, senderName: String, body: String): String =
        if (notifPreviews) {
            // body-only cap at 80 chars; the sender prefix rides on top so
            // preview text always answers "who" before "what"
            val trimmed = body.replace('\n', ' ').trim()
            val capped = if (trimmed.length > 80) trimmed.take(80) + "…" else trimmed
            "$senderName: $capped"
        } else {
            "New message"
        }
}
