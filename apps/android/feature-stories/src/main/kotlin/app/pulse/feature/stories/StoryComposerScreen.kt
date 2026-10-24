package app.pulse.feature.stories

import app.pulse.ui.PulseIcons
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.core.media.PulseMedia
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelChildren

private val Zinc700 = Color(0xFF3F3F46)
private val Zinc800 = Color(0xFF27272A)
private val Emerald600 = Color(0xFFC9762B) // EMB-B ember
private val Rose500 = Color(0xFFEF4444)

/**
 * Image → downscaled JPEG data-URL - the EXACT ≤1280px q0.82 wire policy.
 * Delegates to the same bounded, orientation-aware preparation as chat and camera.
 * Policy constants remain in [PulseMedia]; decoder ownership is shared in :ui.
 */
object StoryMedia {
    suspend fun imageToDataUrl(context: Context, uri: Uri): Result<String> =
        app.pulse.ui.media.ImagePreparation.imageToDataUrl(context, uri)
}

/**
 * Full-screen story composer (Wave 4): Text/Photo pill toggle, hard 280-char
 * caption with live counter, 8 gradient swatches (text mode), explicit Post
 * button (Enter inserts a newline - never send-on-Enter), photo pick →
 * compress → upload → preview + optional caption, spinner states and
 * user-visible errors. Success closes via [onPublished].
 */
@Composable
fun StoryComposerScreen(
    onPublished: () -> Unit,
    onClose: () -> Unit,
    storiesVm: StoriesViewModel,
) {
    val owner = storiesVm.composerSession()?.owner
    androidx.compose.runtime.key(owner) {
        OwnedStoryComposerScreen(onPublished, onClose, storiesVm)
    }
}

@Composable
private fun OwnedStoryComposerScreen(onPublished: () -> Unit, onClose: () -> Unit, storiesVm: StoriesViewModel) {
    var cs by remember { mutableStateOf(ComposerState()) }
    var localPreview by remember { mutableStateOf<ByteArray?>(null) }
    var photoJob by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { storiesVm.composerSession() }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !cs.posting && session != null) {
            photoJob?.cancel()
            cs = cs.withUploading(true)
            photoJob = scope.launch {
                try {
                    val dataUrl = StoryMedia.imageToDataUrl(context, uri).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    val path = storiesVm.uploadStoryImage(dataUrl, session).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (!storiesVm.acceptsComposer(session)) return@launch
                    localPreview = android.util.Base64.decode(dataUrl.substringAfter(','), android.util.Base64.DEFAULT)
                    cs = cs.withImage(path).withUploading(false)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    if (storiesVm.acceptsComposer(session)) cs = cs.withUploading(false)
                        .withError(failure.message ?: "Couldn't upload that photo. Any previous photo is kept.")
                }
            }
        }
    }

    fun post() {
        if (!cs.canPost || session == null) return
        val submitted = cs
        cs = cs.withPosting(true)
        scope.launch {
            try {
                storiesVm.publishStory(submitted.caption,
                    if (submitted.mode == ComposerState.Mode.TEXT) submitted.background else null,
                    if (submitted.mode == ComposerState.Mode.PHOTO) submitted.imagePath else null, session).getOrThrow()
                currentCoroutineContext().ensureActive()
                if (storiesVm.acceptsComposer(session)) onPublished()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (storiesVm.acceptsComposer(session)) cs = cs.withPosting(false).withError(failure.message ?: "Could not post your status")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF09090B))) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(14.dp),
        ) {
            // Header: close + title + explicit Post.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Zinc800)
                        .clickable { scope.coroutineContext.cancelChildren(); onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PulseIcons.X, contentDescription = "Close composer", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "New status",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { post() },
                    enabled = cs.canPost && session != null,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald600, contentColor = Color.White),
                ) {
                    if (cs.posting) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Post", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Text / Photo pill toggle.
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Zinc800)
                    .padding(4.dp),
            ) {
                ModePill("Text", PulseIcons.Pencil, cs.mode == ComposerState.Mode.TEXT) {
                    if (!cs.posting) {
                        photoJob?.cancel()
                        cs = cs.withUploading(false).withMode(ComposerState.Mode.TEXT)
                    }
                }
                ModePill("Photo", PulseIcons.Camera, cs.mode == ComposerState.Mode.PHOTO) {
                    if (!cs.posting) {
                        cs = cs.withMode(ComposerState.Mode.PHOTO)
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Stage.
            when (cs.mode) {
                ComposerState.Mode.TEXT -> Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(brush = StoryPalette.gradient(cs.background)),
                    contentAlignment = Alignment.Center,
                ) {
                    TextField(
                        value = cs.caption,
                        onValueChange = { if (!cs.posting) cs = cs.withCaption(it) },
                        enabled = !cs.posting,
                        placeholder = {
                            Text(
                                "Type your status…",
                                color = Color.White.copy(alpha = 0.75f),
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 22.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    )
                }

                ComposerState.Mode.PHOTO -> Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Zinc800),
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = localPreview ?: cs.imagePath?.let { storyImageUrl(it) }
                    when {
                        cs.uploading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                            Spacer(Modifier.height(10.dp))
                            Text("Uploading…", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                        }
                        preview != null -> AsyncImage(
                            model = preview,
                            contentDescription = "Story photo preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> Text(
                            "Pick a photo to share",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Caption (photo mode: optional) + live 280 counter.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = cs.caption,
                    onValueChange = { if (!cs.posting) cs = cs.withCaption(it) },
                        enabled = !cs.posting,
                    placeholder = {
                        Text(
                            if (cs.mode == ComposerState.Mode.TEXT) "Type your status…" else "Add a caption (optional)…",
                            color = Color(0xFFA1A1AA),
                        )
                    },
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Zinc800,
                        unfocusedContainerColor = Zinc800,
                        cursorColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                val remaining = ComposerState.CAPTION_MAX - cs.caption.length
                Text(
                    "$remaining",
                    color = if (remaining <= 20) Rose500 else Color(0xFFA1A1AA),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            // 8 gradient swatches - text-story affordance (wire: background only for text).
            if (cs.mode == ComposerState.Mode.TEXT) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    StoryPalette.keys.forEach { key ->
                        val selected = key == cs.background
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(brush = Brush.linearGradient(StoryPalette.swatchColors(key)))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) Color.White else Color.White.copy(alpha = 0.25f),
                                    shape = CircleShape,
                                )
                                .clickable { cs = cs.withBackground(key) },
                            contentAlignment = Alignment.Center,
                        ) {}
                    }
                }
            }

            cs.error?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    message,
                    color = Rose500,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Rose500.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Emerald600 else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
