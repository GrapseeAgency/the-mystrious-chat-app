package app.pulse.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.reflect.KProperty

/** A delayed server receipt owns an edit, not every future draft with the same text. */
internal class ReceiptOwnedDraft {
    private var text by mutableStateOf("")
    private var revision = 0L
    private val owner = Any()
    class Receipt internal constructor(internal val owner: Any, internal val revision: Long)

    fun capture() = Receipt(owner, revision)
    fun matches(receipt: Receipt) = receipt.owner === owner && receipt.revision == revision

    operator fun getValue(thisRef: Any?, property: KProperty<*>): String = text
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
        if (text != value) {
            revision++
            text = value
        }
    }
}
