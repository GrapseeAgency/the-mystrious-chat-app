package app.pulse.protocol

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * REAL Socket.IO round-trip — spawns the Wave-0 node relay
 * (protocol/src/test/resources/pulse-relay) and drives the same
 * io.socket:socket.io-client artifact the Android data layer ships,
 * following the production client contract (join re-emitted on EVERY
 * EVENT_CONNECT):
 *
 *   connect → join → joined ack → presence snapshot (two sockets) →
 *   typing relay → POST /notify message:new with a real message payload →
 *   server-side transport drop → AUTO-reconnect → join re-emitted.
 *
 * Skipped (never failed) when `node`/`npm` are missing or the relay
 * cannot start — but CI runners ship node, so this runs there.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SocketRoundTripTest {

    private lateinit var relayDir: File
    private var server: Process? = null

    private lateinit var clientA: io.socket.client.Socket
    private lateinit var clientB: io.socket.client.Socket
    private lateinit var joinedA: EventSink
    private lateinit var presenceA: EventSink
    private lateinit var typingA: EventSink
    private lateinit var messageNewA: EventSink

    // HTTP_1_1 is REQUIRED: the JDK client's default cleartext request carries
    // `Upgrade: h2c`, and engine.io (socket.io) swallows such requests — the
    // connection is accepted but zero response bytes ever arrive.
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    @BeforeAll
    fun startRelay() {
        assumeTrue(runCommand("node", "--version"), "node absent — skipping the live relay round-trip")
        assumeTrue(runCommand("npm", "--version"), "npm absent — skipping the live relay round-trip")

        relayDir = File(javaClass.getResource("/pulse-relay/server.js")!!.toURI()).parentFile
        if (!File(relayDir, "node_modules/socket.io").exists()) {
            val installed = runCommand(
                "npm", "install", "--prefix", relayDir.absolutePath, "--no-audit", "--no-fund",
                timeoutSeconds = 240,
            )
            assumeTrue(installed, "npm install failed — skipping the live relay round-trip")
        }

        server = ProcessBuilder("node", File(relayDir, "server.js").absolutePath)
            .apply { environment()["PORT"] = PORT.toString() }
            .redirectErrorStream(true)
            .start()

        // Health probe — up to 30s, then skip (e.g. port already claimed).
        var healthy = false
        var attempts = 0
        while (!healthy && attempts < 30) {
            attempts += 1
            if (probeHealth()) healthy = true else Thread.sleep(1_000)
        }
        assumeTrue(healthy, "relay did not come up on :$PORT — skipping")
    }

    @AfterAll
    fun stopRelay() {
        runCatching { if (this::clientA.isInitialized) clientA.disconnect() }
        runCatching { if (this::clientB.isInitialized) clientB.disconnect() }
        server?.destroy()
        server?.waitFor(5, TimeUnit.SECONDS)
    }

    @Test
    @Order(1)
    fun `connect auto-joins and delivers the joined ack`() {
        clientA = newClient()
        joinedA = EventSink()
        presenceA = EventSink()
        typingA = EventSink()
        messageNewA = EventSink()
        registerProductionContract(clientA, userId = "alice", joined = joinedA, presence = presenceA, typing = typingA, messageNew = messageNewA)

        val ack = checkNotNull(joinedA.poll(20)) { "no joined ack for alice" }
        val payload = PulseJson.decodeFromString(JoinedAck.serializer(), ack.toString())
        assertTrue("alice" in payload.onlineUserIds, "joined ack must list alice: ${payload.onlineUserIds}")
    }

    @Test
    @Order(2)
    fun `second join rebroadcasts presence with both users`() {
        // Drop the stale snapshot alice got from her OWN join broadcast.
        presenceA.drain()

        clientB = newClient()
        val joinedB = EventSink()
        registerProductionContract(clientB, userId = "bob", joined = joinedB, presence = EventSink(), typing = EventSink(), messageNew = EventSink())

        val ackB = checkNotNull(joinedB.poll(20)) { "no joined ack for bob" }
        val ackBPayload = PulseJson.decodeFromString(JoinedAck.serializer(), ackB.toString())
        assertEquals(setOf("alice", "bob"), ackBPayload.onlineUserIds.toSet())

        // Stale snapshots ({alice}-only from alice's own join, in flight across
        // the drain) can legitimately arrive first — keep polling until a
        // broadcast carrying BOTH users lands or the deadline expires.
        var presence: PresenceSnapshotPayload? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val raw = presenceA.poll(2) ?: continue
            val snap = PulseJson.decodeFromString(PresenceSnapshotPayload.serializer(), raw.toString())
            if (snap.onlineUserIds.toSet() == setOf("alice", "bob")) {
                presence = snap
                break
            }
        }
        checkNotNull(presence) { "alice never saw a presence:snapshot with both users" }
    }

    @Test
    @Order(3)
    fun `typing relays between sockets`() {
        clientB.emit(
            SocketEvents.TYPING,
            JSONObject()
                .put("recipients", org.json.JSONArray(listOf("alice")))
                .put("conversationId", "c-wave0")
                .put("userId", "bob")
                .put("userName", "Bob")
                .put("isTyping", true),
        )
        val raw = checkNotNull(typingA.poll(15)) { "alice never saw bob's typing relay" }
        val typing = PulseJson.decodeFromString(TypingPayload.serializer(), raw.toString())
        assertEquals("c-wave0", typing.conversationId)
        assertEquals("bob", typing.userId)
        assertTrue(typing.isTyping)
    }

    @Test
    @Order(4)
    fun `post notify delivers message new with the authoritative row`() {
        val messageRow = JSONObject()
            .put("id", "m-wave0")
            .put("conversationId", "c-wave0")
            .put("senderId", "bob")
            .put("content", "hello from the relay")
            .put("kind", "text")
            .put("createdAt", "2026-02-14T10:00:00.000Z")
        val body = JSONObject()
            .put("event", SocketEvents.MESSAGE_NEW)
            .put(
                "payload",
                JSONObject()
                    .put("type", SocketEvents.MESSAGE_NEW)
                    .put("message", messageRow)
                    .put("recipientIds", org.json.JSONArray(listOf("alice")))
                    .put("conversationId", "c-wave0"),
            )
            .put("recipientIds", org.json.JSONArray(listOf("alice")))

        val request = HttpRequest.newBuilder(URI.create("$BASE/notify"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertTrue(response.statusCode() in 200..299, "/notify → ${response.statusCode()}")

        val raw = checkNotNull(messageNewA.poll(15)) { "alice never received message:new via /notify" }
        val envelope = PulseJson.decodeFromString(SocketMessageEnvelope.serializer(), raw.toString())
        assertEquals(SocketEvents.MESSAGE_NEW, envelope.type)
        assertEquals("c-wave0", envelope.conversationId)
        val row = checkNotNull(envelope.message)
        assertEquals("m-wave0", row.id)
        assertEquals("hello from the relay", row.content)
        assertEquals("bob", row.senderId)
    }

    @Test
    @Order(5)
    fun `server-dropped transport auto-reconnects and re-emits join`() {
        // A manual client.disconnect() never auto-reconnects (socket.io
        // semantics) — so this simulates a REAL network blip: the relay
        // kills alice's transport server-side. reconnection=true must
        // restore the socket, and the EVENT_CONNECT handler (the production
        // PulseSocketClient contract) must re-emit `join` — a FRESH ack
        // with a NEW socket entry is the proof.
        val kicked = URI.create("$BASE/kick?userId=alice").toURL()
            .openStream().bufferedReader().use { it.readText() }
        println("DBG kick → $kicked")
        assertTrue(kicked.contains("\"kicked\":1"), "relay did not drop alice: $kicked")

        val ack = checkNotNull(joinedA.poll(25)) { "no joined ack after reconnect — join was not re-emitted" }
        val payload = PulseJson.decodeFromString(JoinedAck.serializer(), ack.toString())
        assertTrue("alice" in payload.onlineUserIds)
    }

    // ── plumbing ────────────────────────────────────────────────

    private fun newClient(): io.socket.client.Socket =
        io.socket.client.IO.socket(
            BASE,
            io.socket.client.IO.Options().apply {
                reconnection = true
                reconnectionDelay = 200L
                reconnectionDelayMax = 2_000L
            },
        )

    /**
     * The production PulseSocketClient contract: on EVERY EVENT_CONNECT
     * (first connect AND every reconnect) emit `join {userId}`.
     */
    private fun registerProductionContract(
        socket: io.socket.client.Socket,
        userId: String,
        joined: EventSink,
        presence: EventSink,
        typing: EventSink,
        messageNew: EventSink,
    ) {
        socket.on(io.socket.client.Socket.EVENT_CONNECT) {
            socket.emit(SocketEvents.JOIN, JSONObject().put("userId", userId))
        }
        socket.on(SocketEvents.JOINED) { args -> joined.offer(args.firstOrNull()) }
        socket.on(SocketEvents.PRESENCE_SNAPSHOT) { args -> presence.offer(args.firstOrNull()) }
        socket.on(SocketEvents.TYPING) { args -> typing.offer(args.firstOrNull()) }
        socket.on(SocketEvents.MESSAGE_NEW) { args -> messageNew.offer(args.firstOrNull()) }
        socket.connect()
    }

    /** One event name → a small blocking queue of raw payloads. */
    private class EventSink {
        private val queue = ArrayBlockingQueue<Any?>(64)

        fun offer(value: Any?) {
            while (!queue.offer(value)) {
                queue.poll() // drop oldest on overflow — a test sink never backpressures
            }
        }

        fun poll(timeoutSeconds: Long = 15): Any? = queue.poll(timeoutSeconds, TimeUnit.SECONDS)

        fun drain() {
            // Discard the current backlog (poll exactly size() elements —
            // queue.poll() can itself return a legit null payload).
            var remaining = queue.size
            while (remaining > 0) {
                queue.poll()
                remaining -= 1
            }
        }
    }

    private fun probeHealth(): Boolean = try {
        val request = HttpRequest.newBuilder(URI.create("$BASE/"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        val res = http.send(request, HttpResponse.BodyHandlers.ofString())
        println("DBG health ${res.statusCode()} ${res.body().take(60)}")
        res.statusCode() in 200..299 && res.body().contains("pulse-wave0-relay")
    } catch (e: Exception) {
        println("DBG health EX ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun runCommand(vararg command: String, timeoutSeconds: Long = 30): Boolean = try {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        println("DBG run ${command.joinToString(" ")} done=$done exit=${if (done) process.exitValue() else -1}")
        done && process.exitValue() == 0
    } catch (e: Exception) {
        println("DBG run ${command.joinToString(" ")} EX ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private companion object {
        const val PORT = 3990
        const val BASE = "http://127.0.0.1:$PORT"
    }
}
