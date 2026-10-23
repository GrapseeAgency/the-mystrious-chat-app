/** Keep the existing UTF-16 size budget without splitting a surrogate pair.
 * This does not validate arbitrary input or preserve whole grapheme clusters. */
export function clipUtf16(value: string, limit: number): string {
  if (!Number.isSafeInteger(limit) || limit < 0) throw new RangeError('Invalid text limit')
  if (value.length <= limit) return value
  let end = limit
  if (end > 0) {
    const last = value.charCodeAt(end - 1), next = value.charCodeAt(end)
    if (last >= 0xd800 && last <= 0xdbff && next >= 0xdc00 && next <= 0xdfff) end--
  }
  return value.slice(0, end)
}
