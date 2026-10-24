package app.pulse.feature.settings

import app.pulse.ui.PulseIcons
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    repository: PulseRepository,
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
        /** Confirmed server cover path, or null. */
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

    private val repo = repository.boundToCurrentAccount()
    private val owner = repo.viewerId
    private val session = repo.attachmentSessionKey()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var baseline: app.pulse.domain.model.UserProfile? = null
    private var editRevision = 0L
    private var handleRevision = 0L
    private var handleJob: kotlinx.coroutines.Job? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private var pendingPicker: Boolean? = null // false = avatar, true = cover; belongs to this VM's original session

    private fun owned(): Boolean {
        val active = owner != null && session != null && repo.viewerId == owner && repo.attachmentSessionKey() == session
        if (!active) {
            baseline = null; pendingPicker = null
            _state.value = State(loading = false, error = "Reopen the profile in its original account.")
        }
        return active
    }
    private suspend fun requireOwner() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!owned()) throw kotlinx.coroutines.CancellationException("Profile session ended")
    }
    private fun busy() = _state.value.let { it.loading || it.saving || it.uploading || it.removing || it.coverBusy }
    init { reload() }

    fun reload() {
        if (loadJob?.isActive == true || baseline != null) return
        if (!owned()) { _state.value = State(loading = false, error = "Reopen the profile in its original account."); return }
        _state.value = _state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                requireOwner()
                val fresh = repo.userProfile(checkNotNull(owner)).getOrThrow()
                requireOwner()
                check(fresh.id == owner) { "Profile response did not match this account" }
                baseline = fresh
                _state.value = State(loading = false, userId = owner, name = fresh.name, bio = fresh.about.orEmpty(),
                    handle = fresh.handle.orEmpty(), color = fresh.color ?: "emerald", avatar = fresh.avatar,
                    coverImage = fresh.coverImage, statusEmoji = statusIconIdOrNull(fresh.statusEmoji).orEmpty(), statusText = fresh.statusText.orEmpty())
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (owned()) _state.value = _state.value.copy(loading = false, error = error.message ?: "Could not load profile")
            }
        }
    }

    private fun clip(text: String, maximum: Int): String {
        if (text.length <= maximum) return text
        val breaks = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
        breaks.setText(text)
        return text.substring(0, breaks.preceding(maximum + 1).coerceAtLeast(0))
    }
    fun set(field: String, value: String) {
        if (!owned() || baseline == null) return
        val old = _state.value
        val next = when (field) {
            "name" -> old.copy(name = clip(value, 32))
            "bio" -> old.copy(bio = clip(value, 140))
            "statusText" -> old.copy(statusText = clip(value, 48))
            "statusEmoji" -> old.copy(statusEmoji = statusIconIdOrNull(value).orEmpty())
            "color" -> old.copy(color = value)
            else -> old
        }
        if (next != old) { editRevision++; _state.value = next }
    }
    fun setHandle(raw: String) {
        if (!owned() || baseline == null) return
        val value = raw.lowercase(java.util.Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(20)
        if (value != _state.value.handle) editRevision++
        handleRevision++; val ticket = handleRevision
        handleJob?.cancel()
        _state.value = _state.value.copy(handle = value, handleCheck = HandleCheck(when {
            value.isBlank() -> "idle"
            value == baseline?.handle -> "current"
            value.length < 3 -> "short"
            else -> "checking"
        }))
        if (_state.value.handleCheck.state != "checking") return
        handleJob = viewModelScope.launch {
            try {
                delay(350); requireOwner()
                val result = repo.checkHandle(value).getOrThrow()
                requireOwner()
                if (handleRevision == ticket && _state.value.handle == value) {
                    _state.value = _state.value.copy(handleCheck = HandleCheck(if (result.available) "free" else "taken", result.suggestion))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) {
                if (owned() && handleRevision == ticket) _state.value = _state.value.copy(handleCheck = HandleCheck("error"))
            }
        }
    }

    fun beginPhotoSelection(cover: Boolean): Boolean {
        if (!owned() || busy() || baseline == null) return false
        pendingPicker = cover
        _state.value = _state.value.copy(uploading = !cover, coverBusy = cover, error = null, notice = null)
        return true
    }
    fun photoPickerFailed(cover: Boolean) {
        if (pendingPicker != cover) return
        pendingPicker = null
        if (owned()) _state.value = _state.value.copy(uploading = false, coverBusy = false, error = "Photo picker is unavailable")
    }
    fun photoSelected(context: android.content.Context, uri: android.net.Uri?, cover: Boolean) {
        if (pendingPicker != cover || !owned()) return
        pendingPicker = null
        if (uri == null) { _state.value = _state.value.copy(uploading = false, coverBusy = false); return }
        changeMedia(cover) {
            require(uri.scheme == "content") { "Choose a photo from the system picker" }
            app.pulse.ui.media.ImagePreparation.imageToDataUrl(context, uri).getOrThrow()
        }
    }
    // Prepared-image callers share the same transaction boundary as picker results.
    fun uploadAvatar(dataUrl: String) { if (beginPhotoSelection(false)) { pendingPicker = null; changeMedia(false) { dataUrl } } }
    fun uploadCover(dataUrl: String) { if (beginPhotoSelection(true)) { pendingPicker = null; changeMedia(true) { dataUrl } } }
    fun removeAvatar() { if (beginPhotoSelection(false)) { pendingPicker = null; changeMedia(false) { null } } }
    fun removeCover() { if (beginPhotoSelection(true)) { pendingPicker = null; changeMedia(true) { null } } }

    private fun changeMedia(cover: Boolean, prepare: suspend () -> String?) {
        viewModelScope.launch {
            try {
                requireOwner()
                val dataUrl = prepare()
                requireOwner()
                val path = if (dataUrl == null) "" else repo.uploadMedia(dataUrl).getOrThrow()
                requireOwner()
                val confirmed = repo.patchProfile(if (cover) ProfilePatch(coverImage = path) else ProfilePatch(avatar = path)).getOrThrow()
                requireOwner()
                check(confirmed.id == owner) { "Profile response did not match this account" }
                if (cover) {
                    baseline = baseline?.copy(coverImage = confirmed.coverImage)
                    _state.value = _state.value.copy(coverImage = confirmed.coverImage, notice = "Cover update confirmed")
                } else {
                    baseline = baseline?.copy(avatar = confirmed.avatar)
                    _state.value = _state.value.copy(avatar = confirmed.avatar, notice = "Photo update confirmed")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (owned()) _state.value = _state.value.copy(error = error.message ?: "Photo was not confirmed. Choose it again to retry.", notice = null)
            } finally { if (owned()) _state.value = _state.value.copy(uploading = false, coverBusy = false, removing = false) }
        }
    }

    fun save() {
        if (!owned() || busy()) return
        val original = baseline ?: return
        val submitted = _state.value
        if (submitted.name.trim().isEmpty()) { _state.value = submitted.copy(error = "Enter a display name"); return }
        val revision = editRevision
        val patch = ProfilePatch(
            name = submitted.name.trim().takeIf { it != original.name.trim() },
            about = submitted.bio.trim().takeIf { it != original.about.orEmpty().trim() },
            color = submitted.color.takeIf { it != (original.color ?: "emerald") },
            statusEmoji = submitted.statusEmoji.takeIf { it != statusIconIdOrNull(original.statusEmoji).orEmpty() },
            statusText = submitted.statusText.takeIf { it != original.statusText.orEmpty() },
            username = submitted.handle.takeIf { it != original.handle.orEmpty() })
        if (patch == ProfilePatch()) {
            _state.value = submitted.copy(error = null, notice = "No changes to save")
            return
        }
        _state.value = submitted.copy(saving = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                requireOwner()
                val confirmed = repo.patchProfile(patch).getOrThrow()
                requireOwner()
                check(confirmed.id == owner) { "Profile response did not match this account" }
                baseline = original.copy(name = if (patch.name != null) confirmed.name else original.name,
                    about = if (patch.about != null) confirmed.about else original.about,
                    color = if (patch.color != null) confirmed.color else original.color,
                    statusEmoji = if (patch.statusEmoji != null) confirmed.statusEmoji else original.statusEmoji,
                    statusText = if (patch.statusText != null) confirmed.statusText else original.statusText,
                    handle = if (patch.username != null) confirmed.handle else original.handle)
                _state.value = _state.value.copy(notice = if (editRevision == revision) "Profile updated" else "Submitted changes saved. Newer edits are kept.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (owned()) _state.value = _state.value.copy(error = error.message ?: "Could not confirm the profile update")
            } finally { if (owned()) _state.value = _state.value.copy(saving = false) }
        }
    }
    fun consumeTransient() { _state.value = _state.value.copy(notice = null) }
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
        viewModel.photoSelected(context.applicationContext, uri, cover = false)
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.photoSelected(context.applicationContext, uri, cover = true)
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
        if (state.userId == null) {
            if (state.loading) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!state.loading) TextButton(onClick = viewModel::reload) { Text("Retry loading profile") }
            return@Column
        }

        // Profile cover: bounded preparation followed by upload and confirmed PATCH.
        // Failure keeps the previous cover; there is no unimplemented offline queue.

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
                .clickable(enabled = !state.loading && !state.coverBusy && !state.uploading && !state.saving) {
                    if (viewModel.beginPhotoSelection(true)) {
                        try { coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        catch (_: Exception) { viewModel.photoPickerFailed(true) }
                    }
                },
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
            TextButton(onClick = viewModel::removeCover, enabled = !state.coverBusy && !state.uploading && !state.saving) {
                Text("Remove cover", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Avatar
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(enabled = !state.loading && !state.coverBusy && !state.uploading && !state.saving) {
                if (viewModel.beginPhotoSelection(false)) {
                    try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    catch (_: Exception) { viewModel.photoPickerFailed(false) }
                }
            }) {
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
                    "Bounded photo preparation; updated only after server confirmation",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                // R14 gap 8 - "Remove photo" (web avatar-editor remove branch):
                // only while a photo exists; immediate PATCH avatar:"".
                if (state.avatar != null) {
                    TextButton(
                        onClick = viewModel::removeAvatar,
                        enabled = !state.removing && !state.uploading && !state.coverBusy && !state.saving,
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
        if (check.state == "error") HandleHint("Could not check this handle. Edit it to retry.", MaterialTheme.colorScheme.error)
        if (check.state == "current") HandleHint("That's your current handle", MaterialTheme.colorScheme.primary)
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
            "Confirmed changes appear in your profile, chats and mentions.",
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
            enabled = !state.loading && !state.saving && !state.uploading && !state.coverBusy && state.name.isNotBlank(),
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
