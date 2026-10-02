package app.pulse.domain.model

/**
 * The on-device demo companion (user ask: "at least give us a demo user ...
 * that we can actually see the inside the chat box").
 *
 * A fresh install with no gateway configured used to land on an honest but
 * empty inbox ("Could not reach the gateway") - there was nobody to chat
 * with, so the whole chat surface stayed invisible. Nova fixes that: a REAL
 * local conversation (Room rows, same pipeline as every other chat) with a
 * rule-based on-device companion that replies to every send. It is clearly
 * labeled on-device in its opening line - it is a first-run tour guide, not
 * fake server data. Once a gateway is configured the demo room still works,
 * and every other surface (contacts, groups, calls) comes alive through the
 * normal remote-first stack.
 */
object PulseDemo {
    /** Stable ids - never collide with local_ / server ids. */
    const val USER_ID = "demo_nova_companion"
    const val USER_NAME = "Nova"
    const val COLOR = "violet"
    const val CONVERSATION_ID = "demo_conversation_nova"

    fun isDemoConversation(conversationId: String): Boolean =
        conversationId == CONVERSATION_ID

    /**
     * Opening messages (authored by Nova). Index 0 is a SYSTEM row so the
     * room states its own nature before the first bubble.
     */
    fun openingLines(viewerName: String): List<Pair<String, String>> = listOf(
        "SYSTEM" to "Demo companion - Nova lives on this device. Real chats open up once a server is connected in Profile, Connection.",
        "USER" to "Hey ${viewerName.ifBlank { "there" }}! I am Nova - your Pulse tour guide.",
        "USER" to "Send me anything and I will answer right here, even fully offline. Try asking for effects: type /confetti or /hearts.",
    )

    /**
     * Rule-based companion reply. `turn` = number of Nova replies already in
     * the room (drives the rotating default bank). Warm, short, useful - it
     * points at real app features instead of pretending to be a cloud LLM.
     */
    fun reply(raw: String, turn: Int): String {
        val text = raw.trim()
        val lower = text.lowercase()
        return when {
            lower.isEmpty() -> "Say the word - I am listening."
            lower.contains("hi") || lower.contains("hello") || lower.contains("hey") ->
                "Hey! Great to see you in Pulse. Tap the compose button anytime to start real chats once your server is connected."
            lower.contains("how are you") ->
                "Running warm and glitch-free on-device. More importantly - how is your day going?"
            lower.contains("thank") ->
                "Anytime. That is what demo companions are for."
            lower.contains("effect") || lower.contains("confetti") || lower.contains("heart") || lower.contains("star") ->
                "Effects are the fun part - try /confetti, /lasers, /hearts or /sparkles in the composer and watch the burst."
            lower.startsWith("/") ->
                "Slash commands ride the live gateway for most actions. Offline, the effects ones (/confetti /lasers /hearts /sparkles) still burst right here."
            lower.endsWith("?") ->
                "Good question. I am a small on-device brain, so I will not bluff an answer - connect a server in Profile, Connection and the full assistant takes over."
            lower.contains("server") || lower.contains("connect") || lower.contains("offline") ->
                "Open Profile, then Connection, and paste your Pulse web origin. Until then I have got you covered right here."
            lower.contains("bye") || lower.contains("good night") || lower.contains("goodnight") ->
                "Talk soon. I will be right here on this device."
            else -> DEFAULT_BANK[turn % DEFAULT_BANK.size]
        }
    }

    private val DEFAULT_BANK = listOf(
        "Noted! Anything else you want to explore while we are here?",
        "I hear you. Try the dock styles in Settings, Appearance - there are thirteen of them.",
        "Interesting. Send /confetti if you want to see this room celebrate.",
        "Got it. Pulse keeps every word on this device until a server joins the picture.",
        "Love it. Swipe between dock tabs and everything stays right where you left it.",
        "That lands. The wallpaper picker in Settings, Chat gives this room a fresh coat too.",
    )
}
