package app.pulse.feature.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pulse.core.PulseEndpoints
import app.pulse.domain.model.ProfilePatch
import app.pulse.domain.repository.PulseRepository
import app.pulse.ui.PulseAvatar
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** The 8 profile colours the server whitelist accepts (web parity). */
private val EDIT_COLORS = listOf(
    "emerald" to "#10B981", "rose" to "#FB7185", "amber" to "#F59E0B", "violet" to "#8B5CF6",
    "teal" to "#14B8A6", "orange" to "#F97316", "pink" to "#EC4899", "cyan" to "#06B6D4",
)

/** The 11 fixed status glyphs the web stores (raw values, rendered as text). */
private val STATUS_GLYPHS = listOf("🔥", "✨", "🎯", "☕", "🎧", "🌙", "💡", "🚀", "😴", "🍽️", "vacation")
private val GLYPH_LABELS = mapOf(
    "🔥" to "🔥", "✨" to "✨", "🎯" to "🎯", "☕" to "☕", "🎧" to "🎧", "🌙" to "🌙",
    "💡" to "💡", "🚀" to "🚀", "😴" to "😴", "🍽️" to "🍽️", "vacation" to "🏝️",
)

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    data class HandleCheck(val state: String, val suggestion: String? = null)

    data class State(
        val loading: Boolean = true,
        val userId: String? = null,
        val name: String = "",
        val bio: String = "",
        val handle: String = "",
        val color: String = "emerald",
        val avatar: String? = null,
        val statusEmoji: String = "",
        val statusText: String = "",
        val saving: Boolean = false,
        val notice: String? = null,
        val error: String? = null,
        val handleCheck: HandleCheck = HandleCheck("idle"),
        val uploading: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val me = repo.me()
            _state.value = State(
                loading = false,
                userId = repo.viewerId,
                name = me?.name.orEmpty(),
                bio = me?.bio.orEmpty(),
                handle = me?.handle.orEmpty(),
                color = me?.color ?: "emerald",
                avatar = me?.avatar,
                statusEmoji = me?.statusEmoji.orEmpty(),
                statusText = me?.statusText.orEmpty(),
            )
        }
    }

    fun set(field: String, value: String) {
        val s = _state.value
        _state.value = when (field) {
            "name" -> s.copy(name = value.take(32))
            "bio" -> s.copy(bio = value.take(140))
            "statusText" -> s.copy(statusText = value.take(48))
            "statusEmoji" -> s.copy(statusEmoji = value.take(8))
            "color" -> s.copy(color = value)
            else -> s
        }
    }

    fun setHandle(raw: String) {
        val sanitized = raw.filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(20)
        _state.value = _state.value.copy(handle = sanitized, handleCheck = checkLocally(sanitized))
        if (sanitized.length >= 3) {
            viewModelScope.launch {
                delay(350)
                if (_state.value.handle == sanitized && sanitized.isNotBlank()) {
                    val current = _state.value
                    if (sanitized == current.handle) {
                        repo.checkHandle(sanitized)
                            .onSuccess { check ->
                                if (current.handle == sanitized) {
                                    _state.value = _state.value.copy(
                                        handleCheck = if (check.available) {
                                            HandleCheck("free", check.suggestion)
                                        } else {
                                            HandleCheck("taken", check.suggestion)
                                        },
                                    )
                                }
                            }
                            .onFailure {
                                if (current.handle == sanitized) {
                                    _state.value = _state.value.copy(handleCheck = HandleCheck("error"))
                                }
                            }
                    }
                }
            }
        }
    }

    private fun checkLocally(handle: String): HandleCheck = when {
        handle.isBlank() -> HandleCheck("idle")
        handle.length !in 3..20 -> HandleCheck("short")
        else -> HandleCheck("checking")
    }

    fun uploadAvatar(dataUrl: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(uploading = true, error = null)
            repo.uploadMedia(dataUrl)
                .onSuccess { path ->
                    _state.value = _state.value.copy(uploading = false, avatar = path)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(uploading = false, error = e.message ?: "Upload failed — try again")
                }
        }
    }

    fun save() {
        val s = _state.value
        val userId = s.userId ?: return
        if (s.saving) return
        viewModelScope.launch {
            _state.value = s.copy(saving = true, error = null)
            val patch = ProfilePatch(
                name = s.name.trim().takeIf { it.isNotEmpty() },
                about = s.bio.ifBlank { null },
                color = s.color,
                avatar = s.avatar,
                statusEmoji = s.statusEmoji,
                statusText = s.statusText,
                username = s.handle,
            )
            repo.patchProfile(patch)
                .onSuccess {
                    _state.value = _state.value.copy(saving = false, notice = "Profile updated")
                }
                .onFailure { e ->
                    val message = e.message ?: "Could not save the profile — try again"
                    _state.value = _state.value.copy(saving = false, error = message)
                    if (message.contains("already taken") || message.contains("taken")) {
                        _state.value = _state.value.copy(handleCheck = HandleCheck("taken"))
                        // Re-check for a suggestion
                        repo.checkHandle(s.handle)
                            .onSuccess { check ->
                                _state.value = _state.value.copy(
                                    handleCheck = HandleCheck("taken", check.suggestion),
                                )
                            }
                    }
                }
        }
    }

    fun consumeTransient() {
        _state.value = _state.value.copy(notice = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    onBack: () -> Unit,
    viewModel: ProfileEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching {
                val source = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
                val side = minOf(source.width, source.height)
                val left = (source.width - side) / 2
                val top = (source.height - side) / 2
                val square = Bitmap.createBitmap(source, left, top, side, side)
                val scaled = if (square.width > 512) {
                    Bitmap.createScaledBitmap(square, 512, 512, true)
                } else {
                    square
                }
                val bytes = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
                "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.getOrNull()?.let(viewModel::uploadAvatar)
        }
    }

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(2400)
            viewModel.consumeTransient()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Edit profile", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(12.dp))

        // Avatar
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                val path = state.avatar
                if (path != null && path.startsWith("/api/uploads/")) {
                    AsyncImage(
                        model = PulseEndpoints.http(path),
                        contentDescription = "Your avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(CircleShape),
                    )
                } else {
                    PulseAvatar(name = state.name.ifBlank { "You" }, colorHex = state.color, size = 84.dp)
                }
                if (state.uploading) {
                    Box(
                        Modifier.size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = Color.White) }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Tap the avatar to change it", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Square crop, ≤512 px — stored on the server",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.name,
            onValueChange = { viewModel.set("name", it) },
            label = { Text("Display name") },
            supportingText = { Text("${state.name.length}/32") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.bio,
            onValueChange = { viewModel.set("bio", it) },
            label = { Text("Bio") },
            placeholder = { Text("Hey there! I'm using Pulse.") },
            supportingText = { Text("${state.bio.length}/140") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        // Handle
        Text("Your @handle", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(
            "3–20 characters: lowercase letters, digits, underscore. Friends can find you by it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.handle,
            onValueChange = viewModel::setHandle,
            prefix = { Text("@") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val check = state.handleCheck
        if (check.state == "checking") HandleHint("Checking @${state.handle}…", MaterialTheme.colorScheme.onSurfaceVariant)
        if (check.state == "free") HandleHint("@${state.handle} is free", MaterialTheme.colorScheme.primary)
        if (check.state == "short") HandleHint("3–20 characters: a-z, 0-9, underscore.", MaterialTheme.colorScheme.onSurfaceVariant)
        if (check.state == "taken") {
            HandleHint("@${state.handle} is taken", MaterialTheme.colorScheme.error)
            check.suggestion?.let { suggestion ->
                TextButton(onClick = { viewModel.setHandle(suggestion) }) {
                    Text("Use @$suggestion", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // Status
        Text("Status", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            STATUS_GLYPHS.forEach { glyph ->
                val selected = state.statusEmoji == glyph
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.clickable {
                        viewModel.set("statusEmoji", if (selected) "" else glyph)
                    },
                ) {
                    Text(
                        GLYPH_LABELS[glyph] ?: glyph,
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 16.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.statusText,
            onValueChange = { viewModel.set("statusText", it) },
            label = { Text("Status text") },
            placeholder = { Text("What's happening? (optional)") },
            supportingText = { Text("${state.statusText.length}/48") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))

        // Colour
        Text("Colour", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EDIT_COLORS.forEach { (name, hex) ->
                val selected = state.color == name
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(hex)))
                        .clickable { viewModel.set("color", name) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Surface(shape = CircleShape, color = Color.White, modifier = Modifier.size(12.dp)) {}
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Changes appear everywhere instantly — profile, chats and mentions.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))

        state.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = viewModel::save,
            enabled = !state.saving && !state.uploading && state.name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.saving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Save profile")
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun HandleHint(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
}
