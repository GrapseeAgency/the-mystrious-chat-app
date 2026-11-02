package app.pulse.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull

/**
 * Wave 8 - user preferences wire shape. The single source of truth is the
 * web app's src/lib/prefs-defaults.ts (PulsePrefs + DEFAULT_PREFERENCES +
 * mergePrefs); this DTO and [PulseWave8Logic.mergePrefs] are a 1:1 port so
 * the server blob decodes identically on both platforms.
 *
 * Tolerance rules (house standard):
 *  - unknown keys are dropped (PulseJson ignoreUnknownKeys);
 *  - every field is nullable/optional - a missing field falls back to the
 *    DEFAULT (never a half-zeroed object);
 *  - enum fields that arrive as unknown strings are DISCARDED (default wins),
 *    exactly like the web mergePrefs whitelist - never surface junk.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class WirePulsePrefs(
    /** Chat bubble corner style - 'md' | 'lg' | 'pill'. */
    val bubbleRadius: String? = null,
    /** Message list density - 'cozy' | 'compact'. */
    val density: String? = null,
    /** Chat wallpaper token - 'none' | 'aurora' | 'dusk' | 'forest' | 'mono'. */
    val wallpaper: String? = null,
    /** Show message text in notification-style toasts. */
    val notifPreviews: Boolean? = null,
    /** Play a soft pop on incoming messages. */
    val notifSound: Boolean? = null,
    /** Vibrate on incoming messages (where supported). */
    val notifVibrate: Boolean? = null,
    /** Broadcast last-seen to other users (privacy - server-enforced). */
    val lastSeenVisible: Boolean? = null,
    /** Send read receipts (privacy - server-enforced). */
    val readReceipts: Boolean? = null,
    /** Section 9 item 3: disclose STORY view receipts to story authors
     * (privacy - server-enforced; off = view without ever appearing in the
     * author's viewer list or counts). */
    val storyViewReceipts: Boolean? = null,
    /** Broadcast typing indicators (privacy - server-enforced). */
    val typingVisible: Boolean? = null,
    /** Reduce non-essential motion (effects/parallax) app-wide. */
    val reducedMotion: Boolean? = null,
    /** WebGL ambient field mode - 'off'|'aurora'|'caustics'|'mesh'|'stars'|'liquid'. */
    @kotlinx.serialization.SerialName("fx.webglMode")
    @kotlinx.serialization.json.JsonNames("fxWebglMode")
    val fxWebglMode: String? = null,
    /**
     * Section 13 item 4 - per-conversation themes under the web's EXACT prefs
     * blob key (src/lib/conv-theme.ts CONV_THEMES_KEY, a map of conversation
     * id → { wallpaper, tint? }). Raw JSON on the wire: the protocol layer
     * stays domain-free; PulsePrefsLocalStore sanitizes it like the web's
     * sanitizeConvThemeMap. Absent (null) = untouched, exactly like the other
     * carried fields.
     */
    @kotlinx.serialization.SerialName("chat.convThemes")
    val convThemes: JsonObject? = null,
)

/**
 * GET /api/settings → { preferences: PulsePrefs, revision, fieldStamps } and
 * PATCH /api/settings → { preferences, revision, applied, superseded,
 * replayed? } (Section 13). Every receipt field is additive + tolerant: an
 * old server sends none of them and the decode still succeeds.
 */
@Serializable
data class SettingsEnvelopeDto(
    val preferences: WirePulsePrefs? = null,
    /** Section 13 - the per-user monotonic settings revision (server counter). */
    val revision: Long? = null,
    /** Fields the server DID apply from this patch (LWW winners). */
    val applied: List<String> = emptyList(),
    /** Fields that LOST the last-write-wins comparison - the response
     * preferences carry the WINNING value for these; adopt it. */
    val superseded: List<String> = emptyList(),
    /** Durable receipt replay - the server answered from its stored result. */
    val replayed: Boolean? = null,
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
    /** 64 hex chars, shown once, stored hashed server-side - persist in Keystore. */
    val token: String? = null,
)

/**
 * PATCH /api/settings body builder - serializes ONLY the fields the patch
 * actually carries (null = "leave untouched" on the server's shallow merge).
 *
 * Section 13 item 1 - the durable-receipt identity rides ONLY when the caller
 * has one: [clientId] (16-128 url-safe chars), [intentAtMs] (the patch-level
 * stage moment) and [fieldTimesMs] (per-field stage moments). Legacy callers
 * that omit them keep the exact pre-Section-13 body.
 */
fun WirePulsePrefs.toPatchJson(
    userId: String,
    clientId: String? = null,
    intentAtMs: Long? = null,
    fieldTimesMs: Map<String, Long> = emptyMap(),
): JsonObject {
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
    prefs.storyViewReceipts?.let { patch["storyViewReceipts"] = JsonPrimitive(it) }
    prefs.typingVisible?.let { patch["typingVisible"] = JsonPrimitive(it) }
    prefs.reducedMotion?.let { patch["reducedMotion"] = JsonPrimitive(it) }
    prefs.fxWebglMode?.let { patch["fx.webglMode"] = JsonPrimitive(it) }
    // Section 13 item 4 - the conv-theme map rides the SAME staged patch.
    prefs.convThemes?.let { patch["chat.convThemes"] = it }
    body["preferences"] = JsonObject(patch)
    if (clientId != null) body["clientId"] = JsonPrimitive(clientId)
    if (intentAtMs != null) body["intentAt"] = JsonPrimitive(isoInstant(intentAtMs))
    if (fieldTimesMs.isNotEmpty()) {
        body["fieldTimes"] = JsonObject(fieldTimesMs.mapValues { (_, ms) -> JsonPrimitive(isoInstant(ms)) })
    }
    return JsonObject(body)
}

/** ISO-8601 instant of an epoch-ms stage moment (java.time.ISO_INSTANT). */
private fun isoInstant(epochMs: Long): String =
    java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(epochMs))

/** A raw wire JSON object → [WirePulsePrefs] with only the fields present (no clamping - see mergePrefs). */
fun JsonObject.toWirePrefsOrNull(): WirePulsePrefs? = runCatching {
    // kotlinx.serialization accepts quoted booleans. They are not boolean
    // acknowledgements under this API's contract; reject rather than opt in.
    for (key in listOf("notifPreviews", "notifSound", "notifVibrate", "lastSeenVisible", "readReceipts", "storyViewReceipts", "typingVisible", "reducedMotion")) {
        val value = this[key]
        if (value != null && value != kotlinx.serialization.json.JsonNull) {
            require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { "Invalid settings boolean" }
        }
    }
    PulseJson.decodeFromJsonElement(WirePulsePrefs.serializer(), this)
}.getOrNull()

/** Preserve field presence. A missing acknowledgement is not a default-valued
 * confirmation (especially for privacy flags). Resolve only when projecting UI.
 * Section 13 receipt fields (revision/applied/superseded/replayed) parse
 * tolerantly field-by-field: a server that omits any of them (or sends junk)
 * keeps the preferences decode intact. */
fun String.decodeSettingsEnvelope(): SettingsEnvelopeDto = runCatching {
    val root = PulseJson.parseToJsonElement(this) as? JsonObject ?: return@runCatching SettingsEnvelopeDto()
    val fields = if (root.containsKey("preferences")) root["preferences"] as? JsonObject else root
    SettingsEnvelopeDto(
        preferences = fields?.toWirePrefsOrNull(),
        revision = (root["revision"] as? JsonPrimitive)?.content?.toLongOrNull(),
        applied = root.stringListOrEmpty("applied"),
        superseded = root.stringListOrEmpty("superseded"),
        replayed = (root["replayed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
    )
}.getOrDefault(SettingsEnvelopeDto())

private fun JsonObject.stringListOrEmpty(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content } ?: emptyList()

/** UI modules can inspect field identity without depending on serialization types. */
fun WirePulsePrefs.settingsFieldNames(): Set<String> = settingsFields().keys

fun WirePulsePrefs.settingsFields(): JsonObject = toPatchJson("")["preferences"]!!.jsonObject

/** Only fields in this request may be acknowledged, never the entire reply. */
fun WirePulsePrefs.selectSettingsFields(keys: Set<String>): WirePulsePrefs =
    JsonObject(settingsFields().filterKeys { it in keys }).toWirePrefsOrNull()
        ?: error("Invalid settings fields")
