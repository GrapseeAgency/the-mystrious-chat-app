package app.pulse.data.remote

import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Ktor REST client — wire parity with the Next.js API (through the gateway).
 * Every route the web app calls is addressed here with the same shape,
 * so native and web speak one protocol (packages/protocol).
 */
@Serializable
data class ApiEnvelope<T>(val ok: Boolean, val data: T? = null, val error: String? = null)

@Serializable
data class SendMessageRequest(
    val body: String,
    @SerialName("replyToId") val replyToId: String? = null,
)

class PulseApi(
    baseUrl: String,
    authTokenProvider: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // Ktor HttpClient is wired in N2 with OkHttp engine + ContentNegotiation.
    // Kept as a constructor stub so DI + repository can land without network churn.
    @Suppress("unused")
    private val baseUrlInternal: String = baseUrl.trimEnd('/')

    @Suppress("unused")
    private val auth: () -> String? = authTokenProvider

    @Suppress("unused")
    private val codec: Json = json

    suspend fun me(): User = TODO("N2: GET /api/auth/session")
    suspend fun conversations(query: String = ""): List<Conversation> = TODO("N2: GET /api/conversations?q=")
    suspend fun messages(conversationId: String): List<Message> = TODO("N2: GET /api/conversations/{id}/messages")
    suspend fun sendMessage(conversationId: String, body: String, replyToId: String?): Message =
        TODO("N2: POST /api/conversations/{id}/messages")
}
