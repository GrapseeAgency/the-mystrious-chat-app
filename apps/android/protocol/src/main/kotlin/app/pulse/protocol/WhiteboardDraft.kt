package app.pulse.protocol

// ─────────────────────────────────────────────────────────────
// R2-C item 4 — the whiteboard PENDING-stroke draft logic (pure).
//
// iOS parity port of apps/ios/Pulse/Core/Support/PulseWhiteboardDraft.swift
// (R1-W2G D44): a stroke is persisted the moment it is drawn (BEFORE any
// sync attempt), namespaced per conversation in the device prefs store and
// JSON-encoded as [WhiteboardStrokePostDto] — the stroke model encoding
// itself, not a bespoke shape. A crash/kill mid-draw therefore loses
// nothing: the sheet restores the draft on open, re-syncs it, and a stroke
// leaves the store only when the server verdicts the POST (or the board is
// reset server-side). The DATA-side storage lives in PulsePrefsLocalStore
// (`whiteboard.draft:<conversationId>` keys); this object carries the pure
// encode/decode/restore/purge math so it stays JVM-testable.
// ─────────────────────────────────────────────────────────────

object PulseWhiteboardDraftLogic {

    /** Draft encode — the pending queue as a JSON array (oldest first). */
    fun encode(strokes: List<WhiteboardStrokePostDto>): String =
        PulseJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(WhiteboardStrokePostDto.serializer()),
            strokes,
        )

    /**
     * Draft decode — tolerant like every Pulse wire decode: null/blank/garbage
     * degrades to an EMPTY draft (never a crash, never junk rows).
     */
    fun decode(raw: String?): List<WhiteboardStrokePostDto> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            PulseJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(WhiteboardStrokePostDto.serializer()),
                raw,
            )
        }.getOrDefault(emptyList()).filter { it.points.isNotEmpty() }
    }

    /**
     * Pure restore-time dedupe (iOS `droppingSynced` port) — a draft stroke
     * that the full snapshot ALREADY carries (identical color, width and
     * points) reached the server before the crash; the draft copy is
     * redundant, not lost. Feed order preserved.
     */
    fun droppingSynced(
        draft: List<WhiteboardStrokePostDto>,
        snapshot: List<WhiteboardStrokeDto>,
    ): List<WhiteboardStrokePostDto> {
        if (draft.isEmpty()) return emptyList()
        return draft.filter { stroke ->
            snapshot.none { remote ->
                remote.color == stroke.color &&
                    remote.width == stroke.width &&
                    remote.points == stroke.points
            }
        }
    }

    /**
     * Purge math for the flush-verdict path — the first [count] entries (the
     * batch the server just accepted) leave the front of the queue. Negative
     * counts and over-drops are no-ops/clamped (never throws).
     */
    fun droppingFirst(draft: List<WhiteboardStrokePostDto>, count: Int): List<WhiteboardStrokePostDto> {
        if (count <= 0 || draft.isEmpty()) return draft
        return draft.drop(count.coerceAtMost(draft.size))
    }

    /** Purge math for undo — the NEWEST pending stroke leaves the queue tail. */
    fun droppingLast(draft: List<WhiteboardStrokePostDto>): List<WhiteboardStrokePostDto> =
        if (draft.isEmpty()) draft else draft.dropLast(1)
}
