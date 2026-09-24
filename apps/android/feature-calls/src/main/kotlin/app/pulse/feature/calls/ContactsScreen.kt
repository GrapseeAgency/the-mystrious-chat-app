package app.pulse.feature.calls

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import app.pulse.domain.model.User
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * Contacts tab — native directory of Pulse accounts with live presence,
 * one-tap DM creation, and safety actions (block/report) in native menus.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    onOpenRoom: (String) -> Unit,
    onCallUser: (User) -> Unit = {},
    onVideoCallUser: (User) -> Unit = {},
    onOpenCalls: () -> Unit = {},
    onOpenUser: (String) -> Unit = {},
    onOpenAdd: () -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val users by viewModel.users.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val dmResult by viewModel.dmResult.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    var filter by remember { mutableStateOf("") }
    var safetyTarget by remember { mutableStateOf<User?>(null) }
    var reportTarget by remember { mutableStateOf<User?>(null) }
    // Wave 3 — outgoing calls start here. RECORD_AUDIO must be live before
    // the engine touches the mic; denial keeps the call unstarted (honest).
    var callTarget by remember { mutableStateOf<User?>(null) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = callTarget
        callTarget = null
        if (granted && target != null) onCallUser(target)
    }
    // Wave R1-W2D — outgoing VIDEO calls: mic AND camera in one prompt
    // (camera denial is handled honestly by the engine's audio-only fallback).
    var videoCallTarget by remember { mutableStateOf<User?>(null) }
    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val target = videoCallTarget
        videoCallTarget = null
        if (grants[android.Manifest.permission.RECORD_AUDIO] == true && target != null) {
            onVideoCallUser(target)
        }
    }

    LaunchedEffect(dmResult) {
        when (val result = dmResult) {
            is ContactsViewModel.DmResult.Ready -> {
                viewModel.consumeDmResult()
                onOpenRoom(result.conversationId)
            }
            else -> Unit
        }
    }

    val visible = if (filter.isBlank()) users else users.filter {
        it.name.contains(filter, true) || it.handle.contains(filter, true)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Contacts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "${presence.size} online on Pulse",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Find people") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onOpenAdd) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(15.dp), tint = PulsePalette.Emerald)
                    Spacer(Modifier.width(6.dp))
                    Text("Add contact", color = PulsePalette.Emerald)
                }
            }
        }

        when {
            state.error != null && users.isEmpty() -> Text(
                state.error ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(20.dp),
            )
            state.loading && users.isEmpty() -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(6) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)))
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(width = 120.dp, height = 12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)))
                            Box(Modifier.size(width = 80.dp, height = 10.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)))
                        }
                    }
                }
            }
            else -> {
                // R5-B ITEM 2 — web contacts-tab.tsx parity: A–Z sections with
                // STICKY letter headers + a right-edge index rail. While a
                // search filter is active the list stays flat (web behavior).
                val searching = filter.isNotBlank()
                val sections = remember(visible, searching) { if (searching) emptyList() else groupContactsByLetter(visible) }
                val scope = rememberCoroutineScope()
                val listState = rememberLazyListState()
                // Flattened item index of each sticky header (tap/drag targets).
                val headerIndexes = remember(sections) {
                    var idx = 0
                    val map = LinkedHashMap<String, Int>()
                    sections.forEach { section ->
                        map[section.letter] = idx
                        idx += 1 + section.people.size
                    }
                    map
                }
                var activeLetter by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(listState, headerIndexes) {
                    snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
                        activeLetter = headerIndexes.entries.lastOrNull { it.value <= index }?.key
                    }
                }
                val jumpTo: (String) -> Unit = { letter ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    headerIndexes[letter]?.let { target ->
                        scope.launch { listState.animateScrollToItem(target) }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (searching) {
                            items(visible, key = { it.id }) { user ->
                                ContactRow(
                                    user = user,
                                    online = presence.contains(user.id),
                                    onMessage = { viewModel.openDm(user) },
                                    onCall = { callTarget = user; micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                                    onVideoCall = {
                                        videoCallTarget = user
                                        videoLauncher.launch(
                                            arrayOf(
                                                android.Manifest.permission.RECORD_AUDIO,
                                                android.Manifest.permission.CAMERA,
                                            ),
                                        )
                                    },
                                    onSafety = { safetyTarget = user },
                                    onOpenProfile = { onOpenUser(user.id) },
                                )
                            }
                        } else {
                            sections.forEach { section ->
                                stickyHeader(key = "section_${section.letter}") {
                                    LetterHeader(section.letter, section.people.size)
                                }
                                items(section.people, key = { it.id }) { user ->
                                    ContactRow(
                                        user = user,
                                        online = presence.contains(user.id),
                                        onMessage = { viewModel.openDm(user) },
                                        onCall = { callTarget = user; micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                                        onVideoCall = {
                                            videoCallTarget = user
                                            videoLauncher.launch(
                                                arrayOf(
                                                    android.Manifest.permission.RECORD_AUDIO,
                                                    android.Manifest.permission.CAMERA,
                                                ),
                                            )
                                        },
                                        onSafety = { safetyTarget = user },
                                        onOpenProfile = { onOpenUser(user.id) },
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                    // Kinetic index rail — web hides it while ≤1 letter section
                    // exists and while searching (flat results).
                    if (!searching && sections.size > 1) {
                        IndexRail(
                            letters = sections.map { it.letter },
                            activeLetter = activeLetter,
                            onJump = jumpTo,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
        }
    }

    safetyTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { safetyTarget = null }, sheetState = rememberModalBottomSheetState()) {
            Text(
                "@${target.handle.ifBlank { target.name.lowercase().replace(" ", "_") }}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            SheetRow(icon = Icons.AutoMirrored.Filled.Chat, label = "Open chat") {
                safetyTarget = null
                viewModel.openDm(target)
            }
            SheetRow(icon = Icons.Filled.Person, label = "View profile") {
                safetyTarget = null
                onOpenUser(target.id)
            }
            SheetRow(icon = Icons.Filled.Block, label = "Block", tint = MaterialTheme.colorScheme.error) {
                safetyTarget = null
                viewModel.block(target)
            }
            SheetRow(icon = Icons.Filled.Flag, label = "Report", tint = MaterialTheme.colorScheme.error) {
                safetyTarget = null
                reportTarget = target
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    reportTarget?.let { target ->
        // Wave 6 — the six-reason private report panel (server enum contract).
        ReportPanel(
            firstName = target.name.trim().split(" ").first(),
            priorReasons = emptyList(),
            busy = false,
            onDismiss = { reportTarget = null },
            onSubmit = { reason, details ->
                viewModel.report(target, reason, details.ifBlank { null })
                reportTarget = null
            },
        )
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = tint)
    }
}

@Composable
private fun ContactRow(
    user: User,
    online: Boolean,
    onMessage: () -> Unit,
    onCall: () -> Unit = {},
    onVideoCall: () -> Unit = {},
    onSafety: () -> Unit,
    onOpenProfile: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseAvatar(name = user.name, colorHex = user.color, size = 44.dp, online = online)
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier.weight(1f).clickable(onClick = onOpenProfile),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (user.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = PulsePalette.Emerald, modifier = Modifier.size(13.dp))
                    }
                }
                Text(
                    user.statusText ?: "@${user.handle.ifBlank { user.name.lowercase().replace(" ", "_") }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onMessage) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp), tint = PulsePalette.Emerald)
                Spacer(Modifier.width(6.dp))
                Text("Chat", color = PulsePalette.Emerald)
            }
            IconButton(onClick = onCall) {
                Icon(
                    Icons.Filled.Phone,
                    contentDescription = "Call ${user.name}",
                    tint = PulsePalette.Emerald,
                    modifier = Modifier.size(18.dp),
                )
            }
            // Wave R1-W2D — video call entry (wire kind 'video').
            IconButton(onClick = onVideoCall) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = "Video call ${user.name}",
                    tint = PulsePalette.Emerald,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Open chat") }, onClick = { menuOpen = false; onMessage() })
                    DropdownMenuItem(text = { Text("View profile") }, onClick = { menuOpen = false; onOpenProfile() })
                    DropdownMenuItem(text = { Text("Block / report") }, onClick = { menuOpen = false; onSafety() })
                }
            }
        }
    }
}

/**
 * R5-B ITEM 2 — the sticky glass letter header (web contacts-tab.tsx:301-309):
 * uppercase letter + a per-section count chip.
 */
@Composable
private fun LetterHeader(letter: String, count: Int) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                letter,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * R5-B ITEM 2 — the kinetic index rail (web contacts-tab.tsx:330-373):
 * sticky letter bubbles pinned to the right edge. TAP a letter jumps to its
 * section; DRAGGING along the rail keeps jumping live across letters
 * (pointerInput vertical-drag maps the pointer Y onto the letter list).
 * The active letter lights up emerald, mirroring the web's bubble.
 */
@Composable
private fun IndexRail(
    letters: List<String>,
    activeLetter: String?,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var railHeightPx by remember { mutableStateOf(0) }
    fun letterAt(y: Float): String? {
        if (railHeightPx <= 0 || letters.isEmpty()) return null
        val index = ((y / railHeightPx) * letters.size).toInt().coerceIn(0, letters.size - 1)
        return letters[index]
    }
    Column(
        modifier
            .onSizeChanged { railHeightPx = it.height }
            .pointerInput(letters) {
                // tap → jump
                detectTapGestures { offset -> letterAt(offset.y)?.let(onJump) }
            }
            .pointerInput(letters) {
                // drag along the rail → live jump across letters
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    letterAt(change.position.y)?.let(onJump)
                }
            }
            .padding(horizontal = 3.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            val active = letter == activeLetter
            Text(
                letter,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) PulsePalette.Emerald else MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(vertical = 0.5.dp)
                    .semantics {
                        contentDescription = "Jump to contacts under $letter"
                    },
            )
        }
    }
}
