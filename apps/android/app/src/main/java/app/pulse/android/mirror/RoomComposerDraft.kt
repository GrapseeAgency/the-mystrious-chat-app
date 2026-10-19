package app.pulse.android.mirror

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.reflect.KProperty

/** Text ownership, not string equality: typing A -> B -> A is a new intent. */
internal class RoomComposerDraft {
    private var value by mutableStateOf(TextFieldValue(""))
    var revision: Long = 0
        private set

    fun update(next: TextFieldValue) {
        if (next.text != value.text) revision++
        value = next
    }

    fun clearIfUnchanged(submittedRevision: Long): Boolean {
        if (revision != submittedRevision) return false
        update(TextFieldValue(""))
        return true
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): TextFieldValue = value
    operator fun setValue(thisRef: Any?, property: KProperty<*>, next: TextFieldValue) = update(next)
}
