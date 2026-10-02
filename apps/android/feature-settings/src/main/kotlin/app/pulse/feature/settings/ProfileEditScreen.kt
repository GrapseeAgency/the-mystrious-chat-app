package app.pulse.feature.settings

import app.pulse.ui.PulseIcons
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import app.pulse.protocol.STATUS_ICON_IDS
import app.pulse.protocol.statusIconIdOrNull
import app.pulse.ui.PulseAvatar
import app.pulse.ui.pulseStatusLabel
import app.pulse.ui.pulseStatusGlyph
import app.pulse.ui.pulseStatusGlyphFor
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

/**
 * R18 icon-id contract - the status picker persists stable icon ids
 * (app.pulse.protocol.STATUS_ICON_IDS, mirroring web icon-ids.ts), never raw
 * emoji. Glyphs/labels resolve through the ui registry (pulseStatusGlyph).
 */

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val repo: PulseRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
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
        /** R39 - profile cover picture: server path, "local:cover" marker, or null. */
        val coverImage: String? = null,
        val statusEmoji: String = "",
        val statusText: String = "",
        val saving: Boolean = false,
        val notice: String? = null,
        val error: String? = null,
        val handleCheck: HandleCheck = HandleCheck("idle"),
        val uploading: Boolean = false,
        /** R14 gap 8 - the remove-photo PATCH in flight. */
        val removing: Boolean = false,
        /** R39 - the cover upload/remove round-trip. */
        val coverBusy: Boolean = false,
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
                // Normalize on read: stale/unknown values (legacy emoji) never
                // ride along - the editor seeds a registry id or nothing.
                statusEmoji = statusIconIdOrNull(me?.statusEmoji).orEmpty(),
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
            // Persist registry ids only; blank clears the status. Stale raw
            // emoji can never round-trip through the editor.
            "statusEmoji" -> s.copy(statusEmoji = statusIconIdOrNull(value).orEmpty())
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
                    _state.value = _state.value.copy(uploading = false, error = e.message ?: "Upload failed - try again")
                }
        }
    }

    // ------------------------------------------------------------------
    // R39 - profile cover picture (web parity: User.coverImage + upload).

    /**
     * Cover pick: upload through /api/uploads when a gateway answers; fully
     * offline the cropped image is kept on this device (filesDir) and the
     * UI says so honestly. Server covers ride the next successful save.
     */
    fun uploadCover(dataUrl: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(coverBusy = true, error = null)
            repo.uploadMedia(dataUrl)
                .onSuccess { path ->
                    _state.value = _state.value.copy(coverBusy = false, coverImage = path, notice = "Cover updated")
                }
                .onFailure {
                    val kept = runCatching { writeLocalCover(dataUrl) }.getOrDefault(false)
                    _state.value = _state.value.copy(
                        coverBusy = false,
                        coverImage = if (kept) "local:cover" else _state.value.coverImage,
                        notice = if (kept) "Cover saved on this device - it uploads when Pulse reconnects" else null,
                        error = if (kept) null else "Could not set the cover - try again",
                    )
                }
        }
    }

    /** Decode a data URL cover onto this device (offline fallback). */
    private fun writeLocalCover(dataUrl: String): Boolean {
        val base64 = dataUrl.substringAfter("base64;", "").ifBlank { dataUrl.substringAfter("base64,", "") }
        if (base64.isBlank()) return false
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        java.io.File(appContext.filesDir, "pulse_cover.jpg").writeBytes(bytes)
        return true
    }

    private fun deleteLocalCover() {
        runCatching { java.io.File(appContext.filesDir, "pulse_cover.jpg").delete() }
    }

    /** Cover removal mirrors removeAvatar: PATCH "" clears the column. */
    fun removeCover() {
        val s = _state.value
        if (s.userId == null || s.coverBusy) return
        viewModelScope.launch {
            _state.value = s.copy(coverBusy = true, error = null)
            repo.patchProfile(ProfilePatch(coverImage = ""))
                .onSuccess {
                    deleteLocalCover()
                    _state.value = _state.value.copy(coverBusy = false, coverImage = null, notice = "Cover removed")
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        coverBusy = false,
                        error = e.message ?: "Could not remove the cover - try again when connected",
                    )
                }
        }
    }

    /**
     * R14 gap 8 - "Remove photo" (web avatar-editor.tsx runRemovePhoto):
     * an immediate PATCH /api/users/{id} with avatar:"" - the server nulls
     * the column - with the same optimistic-preview + rollback contract the
     * set/replace path honors (failure restores the previous avatar).
     */
    fun removeAvatar() {
        val s = _state.value
        if (s.userId == null || s.saving || s.removing || s.uploading) return
        val previous = s.avatar
        viewModelScope.launch {
            _state.value = s.copy(removing = true, error = null, avatar = null)
            repo.patchProfile(ProfilePatch(avatar = ""))
                .onSuccess {
                    _state.value = _state.value.copy(removing = false, notice = "Profile photo removed")
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        removing = false,
                        avatar = previous,
                        error = e.message ?: "Could not remove your photo",
                    )
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
                // Only real server paths ride the wire - the offline
                // "local:cover" marker is a device-side render hint.
                coverImage = s.coverImage?.takeIf { it.startsWith("/api/uploads/") },
                statusEmoji = s.statusEmoji,
                statusText = s.statusText,
                username = s.handle,
            )
            repo.patchProfile(patch)
                .onSuccess {
                    _state.value = _state.value.copy(saving = false, notice = "Profile updated")
                }
                .onFailure { e ->
                    val message = e.message ?: "Could not save the profile - try again"
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
            IconButton(onClick = onBack) { Icon(PulseIcons.ChevronLeft, contentDescription = "Back") }
            Text("Edit profile", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(12.dp))

        // R39 - profile cover picture (web parity): tap to pick, uploads
        // through /api/uploads when connected, kept on-device otherwise.
        val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                runCatching {
                    val source = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
                        ?: return@runCatching null
                    // Center-crop to 2:1 at 1024 wide - the banner aspect.
                    val targetW = 1024
                    val targetH = 512
                    val scale = maxOf(
                        targetW.toFloat() / source.width,
                        targetH.toFloat() / source.height,
                    )
                    val scaledW = (source.width * scale).toInt().coerceAtLeast(targetW)
                    val scaledH = (source.height * scale).toInt().coerceAtLeast(targetH)
                    val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
                    val left = ((scaledW - targetW) / 2).coerceAtLeast(0)
                    val top = ((scaledH - targetH) / 2).coerceAtLeast(0)
                    val cropped = Bitmap.createBitmap(scaled, left, top, targetW, targetH)
                    val bytes = ByteArrayOutputStream().use { out ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        out.toByteArray()
                    }
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }.getOrNull()?.let(viewModel::uploadCover)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Cover picture", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                .clickable { coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            contentAlignment = Alignment.Center,
        ) {
            when (state.coverImage) {
                null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(PulseIcons.ImageIcon, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                        Spacer(Modifier.height(6.dp))
                        Text("Add a cover picture", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                    }
                }
                "local:cover" -> {
                    AsyncImage(
                        model = java.io.File(context.filesDir, "pulse_cover.jpg"),
                        contentDescription = "Your cover picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    AsyncImage(
                        model = PulseEndpoints.http(state.coverImage ?: ""),
                        contentDescription = "Your cover picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (state.coverBusy) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp) }
            }
        }
        if (state.coverImage != null) {
            TextButton(onClick = viewModel::removeCover) {
                Text("Remove cover", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

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
                    "Square crop, ≤512 px - stored on the server",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                // R14 gap 8 - "Remove photo" (web avatar-editor remove branch):
                // only while a photo exists; immediate PATCH avatar:"".
                if (state.avatar != null) {
                    TextButton(
                        onClick = viewModel::removeAvatar,
                        enabled = !state.removing && !state.uploading && !state.saving,
                    ) {
                        Text("Remove photo", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
                }
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
            STATUS_ICON_IDS.forEach { id ->
                val selected = state.statusEmoji == id
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.clickable {
                        viewModel.set("statusEmoji", if (selected) "" else id)
                    },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            pulseStatusGlyphFor(id),
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            pulseStatusLabel(id),
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
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
            "Changes appear everywhere instantly - profile, chats and mentions.",
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
