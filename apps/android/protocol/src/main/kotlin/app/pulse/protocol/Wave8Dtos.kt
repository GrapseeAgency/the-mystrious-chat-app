package app.pulse.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Wave 8 — user preferences wire shape. The single source of truth is the
 * web app's src/lib/prefs-defaults.ts (PulsePrefs + DEFAULT_PREFERENCES +
 * mergePrefs); this DTO and [PulseWave8Logic.mergePrefs] are a 1:1 port so
 * the server blob decodes identically on both platforms.
 *
 * Tolerance rules (house standard):
 *  - unknown keys are dropped (PulseJson ignoreUnknownKeys);
 *  - every field is nullable/optional — a missing field falls back to the
 *    DEFAULT (never a half-zeroed object);
 *  - enum fields that arrive as unknown strings are DISCARDED (default wins),
 *    exactly like the web mergePrefs whitelist — never surface junk.
 */
@Serializable
data class WirePulsePrefs(
    /** Chat bubble corner style — 'md' | 'lg' | 'pill'. */
    val bubbleRadius: String? = null,
    /** Message list density — 'cozy' | 'compact'. */
    val density: String? = null,
    /** Chat wallpaper token — 'none' | 'aurora' | 'dusk' | 'forest' | 'mono'. */
    val wallpaper: String? = null,
    /** Show message text in notification-style toasts. */
    val notifPreviews: Boolean? = null,
    /** Play a soft pop on incoming messages. */
    val notifSound: Boolean? = null,
    /** Vibrate on incoming messages (where supported). */
    val notifVibrate: Boolean? = null,
    /** Broadcast last-seen to other users (privacy — server-enforced). */
    val lastSeenVisible: Boolean? = null,
    /** Send read receipts (privacy — server-enforced). */
    val readReceipts: Boolean? = null,
    /** Broadcast typing indicators (privacy — server-enforced). */
    val typingVisible: Boolean? = null,
    /** Reduce non-essential motion (effects/parallax) app-wide. */
    val reducedMotion: Boolean? = null,
    /** WebGL ambient field mode — 'off'|'aurora'|'caustics'|'mesh'|'stars'|'liquid'. */
    val fxWebglMode: String? = null,
)

/** GET /api/settings → { preferences: PulsePrefs } (defaults merged server-side). */
@Serializable
data class SettingsEnvelopeDto(
    val preferences: WirePulsePrefs? = null,
)

/**
 * POST /api/users → 201 { user, token } and POST /api/users/login →
 * 200 { user, token }. The token rides TOP-LEVEL next to `user` (verified
 * against the live routes); `user` itself may sit at the root on pre-Wave-8
 * servers, so decode goes through unwrapOrRoot("user") at the call site.
 */
@Serializable
data class UserAuthEnvelopeDto(
    val user: UserDto? = null,
    /** 64 hex chars, shown once, stored hashed server-side — persist in Keystore. */
    val token: String? = null,
)

/**
 * PATCH /api/settings body builder — serializes ONLY the fields the patch
 * actually carries (null = "leave untouched" on the server's shallow merge).
 */
fun WirePulsePrefs.toPatchJson(userId: String): JsonObject {
    val prefs = this
    val body = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
    body["userId"] = JsonPrimitive(userId)
    val patch = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
    prefs.bubbleRadius?.let { patch["bubbleRadius"] = JsonPrimitive(it) }
    prefs.density?.let { patch["density"] = JsonPrimitive(it) }
    prefs.wallpaper?.let { patch["wallpaper"] = JsonPrimitive(it) }
    prefs.notifPreviews?.let { patch["notifPreviews"] = JsonPrimitive(it) }
    prefs.notifSound?.let { patch["notifSound"] = JsonPrimitive(it) }
    prefs.notifVibrate?.let { patch["notifVibrate"] = JsonPrimitive(it) }
    prefs.lastSeenVisible?.let { patch["lastSeenVisible"] = JsonPrimitive(it) }
    prefs.readReceipts?.let { patch["readReceipts"] = JsonPrimitive(it) }
    prefs.typingVisible?.let { patch["typingVisible"] = JsonPrimitive(it) }
    prefs.reducedMotion?.let { patch["reducedMotion"] = JsonPrimitive(it) }
    prefs.fxWebglMode?.let { patch["fx.webglMode"] = JsonPrimitive(it) }
    body["preferences"] = JsonObject(patch)
    return JsonObject(body)
}

/** A raw wire JSON object → [WirePulsePrefs] with only the fields present (no clamping — see mergePrefs). */
fun JsonObject.toWirePrefsOrNull(): WirePulsePrefs? = runCatching {
    PulseJson.decodeFromJsonElement(WirePulsePrefs.serializer(), this)
}.getOrNull()

/** Server settings response → prefs (tolerant of a missing envelope field). */
fun String.decodeSettingsEnvelope(): SettingsEnvelopeDto = runCatching {
    val root = PulseJson.parseToJsonElement(this)
    if (root is JsonObject && root.containsKey("preferences")) {
        val raw = PulseJson.decodeFromJsonElement(SettingsEnvelopeDto.serializer(), root)
        SettingsEnvelopeDto(preferences = raw.preferences?.let { PulseWave8Logic.resolved(it) })
    } else {
        // Some proxies wrap — accept the prefs object at the root too.
        SettingsEnvelopeDto(preferences = root.jsonObject.toWirePrefsOrNull()?.let { PulseWave8Logic.resolved(it) })
    }
}.getOrDefault(SettingsEnvelopeDto(preferences = null))
