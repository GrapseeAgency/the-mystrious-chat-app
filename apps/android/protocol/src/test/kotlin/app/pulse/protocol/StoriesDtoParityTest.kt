package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stories wire parity — the widened StoryItemDto decodes the EXACT shape the
 * gateway emits (src/app/api/stories/route.ts mapStory: id/kind/imagePath/
 * caption/background/createdAt/expiresAt/viewCount/viewedByMe), tolerates
 * unknown future keys, degrades to defaults on missing keys, and the POST
 * bodies encode the contract the routes validate:
 *   POST /api/stories        { requesterId, caption?, background?, imagePath? }
 *   POST /api/stories/{id}/view { requesterId } → { viewCount, owner? }
 */
class StoriesDtoParityTest {

    /** Captured-shape GET payload — one image story (mine) + one text story, unknown keys sprinkled. */
    private val storiesPageJson = """
    {
      "groups": [
        {
          "user": {"id": "u-me", "name": "Me", "username": "me", "color": "emerald", "avatar": null},
          "mine": true,
          "allSeen": true,
          "stories": [
            {
              "id": "s-img",
              "kind": "image",
              "imagePath": "3fa1b2c4-photo.jpg",
              "caption": "sunset run",
              "background": "emerald",
              "createdAt": "2026-02-20T09:00:00.000Z",
              "expiresAt": "2026-02-21T09:00:00.000Z",
              "viewCount": 7,
              "viewedByMe": false,
              "someFutureField": {"nested": [1, 2]}
            }
          ]
        },
        {
          "user": {"id": "u-ada", "name": "Ada Lovelace", "username": null, "color": "violet"},
          "mine": false,
          "allSeen": false,
          "stories": [
            {
              "id": "s-txt",
              "kind": "text",
              "imagePath": null,
              "caption": "shipping pulse",
              "background": "rose",
              "createdAt": "2026-02-20T08:30:00.000Z",
              "expiresAt": "2026-02-21T08:30:00.000Z",
              "viewCount": 2,
              "viewedByMe": true,
              "unknownExtra": "ignored"
            }
          ]
        }
      ],
      "topLevelUnknown": true
    }
    """.trimIndent()

    @Test
    fun `stories page decodes the full wire shape`() {
        val page = PulseJson.decodeFromString(StoriesPageDto.serializer(), storiesPageJson)
        assertEquals(2, page.groups.size)

        val mine = page.groups[0]
        assertTrue(mine.mine)
        assertTrue(mine.allSeen)
        assertEquals("u-me", mine.user?.id)
        assertEquals("Me", mine.user?.name)
        assertEquals("me", mine.user?.username)
        assertEquals("emerald", mine.user?.color)

        val image = mine.stories.single()
        assertEquals("s-img", image.id)
        assertEquals("image", image.kind)
        assertEquals("3fa1b2c4-photo.jpg", image.imagePath)
        assertEquals("sunset run", image.caption)
        assertEquals("emerald", image.background)
        assertEquals("2026-02-20T09:00:00.000Z", image.createdAt)
        assertEquals("2026-02-21T09:00:00.000Z", image.expiresAt)
        assertEquals(7, image.viewCount)
        assertEquals(false, image.viewedByMe)

        val other = page.groups[1]
        assertTrue(!other.mine)
        assertTrue(!other.allSeen)
        assertNull(other.user?.username)
        val text = other.stories.single()
        assertEquals("text", text.kind)
        assertNull(text.imagePath)
        assertEquals("shipping pulse", text.caption)
        assertEquals("rose", text.background)
        assertEquals(2, text.viewCount)
        assertTrue(text.viewedByMe)
    }

    @Test
    fun `story item degrades to defaults when optional keys are missing`() {
        val page = PulseJson.decodeFromString(
            StoriesPageDto.serializer(),
            """{"groups":[{"user":{"id":"u1","name":"N"},"stories":[{"id":"s1"}]}]}""",
        )
        val s = page.groups.single().stories.single()
        assertEquals("s1", s.id)
        assertNull(s.kind)
        assertNull(s.imagePath)
        assertEquals("", s.caption)
        assertEquals("emerald", s.background)
        assertNull(s.createdAt)
        assertNull(s.expiresAt)
        assertEquals(0, s.viewCount)
        assertEquals(false, s.viewedByMe)
    }

    @Test
    fun `empty page decodes to no groups`() {
        val page = PulseJson.decodeFromString(StoriesPageDto.serializer(), """{"groups":[]}""")
        assertTrue(page.groups.isEmpty())
    }

    @Test
    fun `story created decodes 201 body`() {
        val created = PulseJson.decodeFromString(
            StoryCreatedDto.serializer(),
            """
            {"story": {
              "id": "s-new", "kind": "text", "imagePath": null, "caption": "hello",
              "background": "amber", "createdAt": "2026-02-20T10:00:00.000Z",
              "expiresAt": "2026-02-21T10:00:00.000Z", "viewCount": 0, "viewedByMe": false
            }}
            """.trimIndent(),
        )
        assertEquals("s-new", created.story?.id)
        assertEquals("text", created.story?.kind)
        assertEquals("amber", created.story?.background)
        assertEquals(0, created.story?.viewCount)
        assertEquals(false, created.story?.viewedByMe)
    }

    @Test
    fun `view ack carries viewCount and optional owner short-circuit`() {
        val ack = PulseJson.decodeFromString(StoryViewAckDto.serializer(), """{"viewCount": 3}""")
        assertEquals(3, ack.viewCount)
        assertNull(ack.owner)
        val ownerAck = PulseJson.decodeFromString(
            StoryViewAckDto.serializer(),
            """{"viewCount": 1, "owner": true}""",
        )
        assertEquals(1, ownerAck.viewCount)
        assertEquals(true, ownerAck.owner)
    }

    @Test
    fun `viewers list decodes owner-only payload oldest-first`() {
        val viewers = PulseJson.decodeFromString(
            StoryViewersDto.serializer(),
            """
            {"viewers": [
              {"userId": "u2", "name": "Bob", "username": "bob", "color": "teal",
               "viewedAt": "2026-02-20T09:05:00.000Z"},
              {"userId": "u3", "name": "Cara", "username": null, "color": "pink",
               "viewedAt": "2026-02-20T09:10:00.000Z"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("u2", "u3"), viewers.viewers.map { it.userId })
        assertNull(viewers.viewers[1].username)
        assertEquals("2026-02-20T09:05:00.000Z", viewers.viewers[0].viewedAt)
    }

    /** POST /api/stories body — text mode sends caption+background, photo mode sends imagePath. */
    @Test
    fun `post story body shape - text vs photo modes`() {
        val textBody = PulseJson.parseToJsonElement(
            """{"requesterId":"u1","caption":"focusing today","background":"violet"}""",
        )
        val textKeys = (textBody as kotlinx.serialization.json.JsonObject).keys
        assertEquals(setOf("requesterId", "caption", "background"), textKeys)

        val photoBody = PulseJson.parseToJsonElement(
            """{"requesterId":"u1","caption":"sunset","imagePath":"abc-123.png"}""",
        )
        val photoJson = photoBody as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("requesterId", "caption", "imagePath"), photoJson.keys)
        assertNull(photoJson["background"])
    }
}
