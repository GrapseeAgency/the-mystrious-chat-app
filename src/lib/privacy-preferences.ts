/** Privacy disclosure is separate from forgiving UI preference defaults.
 * A known SQL NULL is an unset preference (the product default is on).
 * Undefined means the column/owner was not loaded; malformed JSON, shapes or
 * explicitly non-boolean values must not become permission to disclose. */
export function privacyPreferenceEnabled(raw: string | null | undefined,
  key: 'readReceipts' | 'lastSeenVisible' | 'typingVisible' | 'notifPreviews'): boolean {
  if (raw === null) return true
  if (typeof raw !== 'string') return false
  try {
    const value: unknown = JSON.parse(raw)
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
    const row = value as Record<string, unknown>
    return !Object.prototype.hasOwnProperty.call(row, key) || row[key] === true
  } catch { return false }
}

/** Use strict disclosure values when loading/repairing settings too. An
 * unrelated appearance change must not turn a malformed privacy field on. */
export function privacyPreferenceSnapshot(raw: string | null | undefined) {
  return {
    notifPreviews: privacyPreferenceEnabled(raw, 'notifPreviews'),
    readReceipts: privacyPreferenceEnabled(raw, 'readReceipts'),
    lastSeenVisible: privacyPreferenceEnabled(raw, 'lastSeenVisible'),
    typingVisible: privacyPreferenceEnabled(raw, 'typingVisible'),
  }
}
