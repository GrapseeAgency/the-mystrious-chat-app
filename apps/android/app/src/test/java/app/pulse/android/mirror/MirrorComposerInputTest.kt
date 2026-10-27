package app.pulse.android.mirror

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class MirrorComposerInputTest {
    @Test fun oversizedInputIsRejectedWholeAndNeverSplitsTheLastEmoji() {
        val prefix = "a".repeat(1999)
        assertFalse(mirrorComposerAcceptsChange(prefix, prefix + "🌿"))
        assertTrue(mirrorComposerAcceptsChange("", "🌿".repeat(1000)))
        assertFalse(mirrorComposerAcceptsChange("keep", "x".repeat(2001)))
        assertTrue(mirrorComposerAcceptsChange("x".repeat(2200), "x".repeat(2100)))
    }

    @Test fun transformedTextIsCompleteOrRefusedBeforeSend() {
        val text = "🌿".repeat(999)
        assertEquals("_${text}_", (mirrorSlashSendBody("/me $text") as MirrorSlashBody.Send).content)
        for (input in listOf("/me ${text}a", "/me " + "a".repeat(2000), "/shrug " + "a".repeat(1995)))
            assertTrue(input.take(20), mirrorSlashSendBody(input) is MirrorSlashBody.Error)
    }

    @Test fun mountedComposerUsesTheSameSafeReminderParserAsTheSheet() {
        val now = ZonedDateTime.parse("2026-01-15T07:00:00+06:00[Asia/Dhaka]")
        assertNull(mirrorParseRelativeReminder("note in 999999999999999999999h", now))
        assertNull(mirrorParseRelativeReminder("note 9223372036854775807w", now))
        val next = mirrorParseRelativeReminder("note tomorrow", now)!!
        assertEquals("note", next.first)
        assertEquals(now.plusDays(1).withHour(9).toInstant(), ZonedDateTime.parse(next.second).toInstant())
        assertEquals(now.plusHours(1).toInstant(), ZonedDateTime.parse(mirrorParseRelativeReminder("note 1H", now)!!.second).toInstant())
        val late = now.withHour(23)
        assertEquals(late.plusDays(1).withHour(20).toInstant(), ZonedDateTime.parse(mirrorParseRelativeReminder("note tonight", late)!!.second).toInstant())
    }
}
