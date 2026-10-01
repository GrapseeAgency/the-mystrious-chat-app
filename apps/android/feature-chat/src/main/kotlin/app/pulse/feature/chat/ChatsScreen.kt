package app.pulse.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.core.time.PulseTime
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.MessageHit
import app.pulse.domain.model.StoryCell
import app.pulse.feature.chat.R
import app.pulse.ui.EmberPalette
import app.pulse.ui.EmberGlassButton
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulseGlass
import app.pulse.ui.PulseIcons
import app.pulse.ui.pulseFolderGlyph
import app.pulse.ui.pulseInfiniteFloat
import app.pulse.ui.PulseMonoFamily
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import app.pulse.ui.emberBackdrop
import app.pulse.ui.isPulseDarkTheme
import app.pulse.ui.pulseGlass
import app.pulse.ui.shimmer
import app.pulse.ui.update.UpdaterBanner
import androidx.hilt.navigation.compose.hiltViewModel
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── zinc shades the Material scheme does not carry (web token mirrors) ──
private val Zinc300 = Color(0xFFD4D4D8)
private val Zinc400 = Color(0xFFA1A1AA)
private val Zinc500 = Color(0xFF71717A)
private val Zinc600 = Color(0xFF52525B)
private val Amber500 = Color(0xFFF59E0B)
private val Amber600 = Color(0xFFD97706)
private val Rose400 = Color(0xFFFB7185)

// ── EMB-B ember text tokens (dark language) ──
private val EmberText = Color.White
private val EmberText80 = Color.White.copy(alpha = 0.80f)
private val EmberText55 = Color.White.copy(alpha = 0.55f)
private val EmberText45 = Color.White.copy(alpha = 0.45f)
private val EmberText40 = Color.White.copy(alpha = 0.40f)
// EMB-B: the old green quartet rides the ember language now (the shared
// PulsePalette.Emerald pair is retargeted upstream; these file-local shades
// follow: light glow ring, mid accent, readable deep for light surfaces).
private val Emerald400 = Color(0xFFFFD9A8)
private val Emerald500 = PulsePalette.Emerald
private val Emerald600 = PulsePalette.EmeraldDeep
private val Teal500 = Color(0xFFFF7A3D)
private val Teal600 = Color(0xFFD96A2B)

/** Web `streakHeatLevel` - 2-4 → warm, 5-9 → hot, 10+ → blazing. */
private fun streakHeat(count: Int): Int = when {
    count >= 10 -> 3
    count >= 5 -> 2
    else -> 1
}

private fun countLabel(count: Int): String = if (count > 99) "99+" else "$count"

/**
 * R1-W2H D24 - ChatsViewModel's export-success notice is the only surface the
 * VM (owned upstream this round) exposes the exported file name on:
 * `notify("Chat exported - Saved $it")` where `it` is the file written under
 * cacheDir/exports (PulseRepositoryImpl.exportChat). The screens parse THAT
 * contract, rebuild the absolute path and fire MediaSupport's FileProvider
 * ACTION_SEND chooser - D24's share sheet with zero VM edits.
 */
private const val EXPORT_NOTICE_PREFIX = "Chat exported - Saved "

/**
 * The Chats tab - the native home page. Design + logic parity with the web
 * `chats-tab.tsx` per apps/HOMEPAGE-SPEC.md (binding spec).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    viewerId: String?,
    viewerName: String?,
    viewerColor: String?,
    /** Opens a room; `messageId` (when non-null) jumps + flashes that message. */
    onOpenRoom: (String, String?) -> Unit,
    onNeedIdentity: () -> Unit,
    onSwitchTab: (String) -> Unit,
    onOpenArchived: () -> Unit,
    onCycleTheme: () -> Unit,
    /** Wave 4 - ring tap on a cell with a live story; arg = author id (viewer start group). */
    onOpenStoriesViewer: (String?) -> Unit = {},
    /** Wave 4 - the own-cell "+" affordance (or empty "My status" cell) → composer. */
    onOpenStoriesComposer: () -> Unit = {},
    /** Wave 6 - mentions / channels surfaces + the folders manage sheet. */
    onOpenMentions: () -> Unit = {},
    onOpenChannels: () -> Unit = {},
    // R2-A item 2/1 - the header phone icon opens the calls history page and
    // the pencil icon opens the REAL new-chat composer (web chats header).
    onOpenCalls: () -> Unit = {},
    onOpenNewChat: () -> Unit = {},
    /** Incremented by the dock's More → Search action to open search mode. */
    searchRequest: Int = 0,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val rawChats by viewModel.chats.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val typing by viewModel.typing.collectAsStateWithLifecycle()
    val listFilter by viewModel.listFilter.collectAsStateWithLifecycle()
    val stories by viewModel.stories.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val mentionCount by viewModel.mentionCount.collectAsStateWithLifecycle()
    val searchHits by viewModel.searchHits.collectAsStateWithLifecycle()
    val searchRunning by viewModel.searching.collectAsStateWithLifecycle()
    // R2-C item 7 - recent searches for the search bar (last 5).
    val spotlightRecents by viewModel.spotlightRecents.collectAsStateWithLifecycle()
    // R14 gap 14 - the spotlight check-in flight flag (Actions block).
    val checkin by viewModel.checkin.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var actionTarget by remember { mutableStateOf<Conversation?>(null) }
    var activeFolderId by remember { mutableStateOf<String?>(null) }
    var foldersSheet by remember { mutableStateOf(false) }
    // R1-W2H D24 - the exported file path awaiting the share-sheet launch.
    var pendingShareFile by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(viewerId) {
        if (viewerId != null) {
            viewModel.refresh()
            viewModel.loadChrome()
        }
    }
    // Audit D25 fix - poll ONLY while this surface is visible AND the app is
    // foregrounded: start on enter + ON_START, stop on leave + ON_STOP.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startPolling()
                Lifecycle.Event.ON_STOP -> viewModel.stopPolling()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        viewModel.startPolling()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            viewModel.stopPolling()
        }
    }
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        // R1-W2H D24 - intercept the export-success notice (VM contract, see
        // EXPORT_NOTICE_PREFIX) and arm the ACTION_SEND share sheet launch.
        n.text.removePrefix(EXPORT_NOTICE_PREFIX).takeIf { it != n.text }?.let { fileName ->
            pendingShareFile = File(context.cacheDir, "exports/$fileName").absolutePath
        }
        snackbar.showSnackbar(n.text, withDismissAction = false)
        viewModel.consumeNotice()
    }
    // R1-W2H D24 - fire the system chooser for the exported .txt via
    // MediaSupport's FileProvider intent (state cleared first so a rejected
    // launch can't loop; the toast still told the user where it saved).
    LaunchedEffect(pendingShareFile) {
        val path = pendingShareFile ?: return@LaunchedEffect
        pendingShareFile = null
        val file = File(path)
        if (!file.exists()) return@LaunchedEffect
        runCatching {
            context.startActivity(MediaSupport.buildShareIntent(context, path, "text/plain", null))
        }
    }
    LaunchedEffect(searchRequest) {
        if (searchRequest > 0) {
            search = true
            focused = true
        }
    }
    // R2-C item 7 - the debounced server message search (the VM owns the
    // 250ms rhythm; the screen only feeds it). Chats filter live above it.
    LaunchedEffect(search, query) {
        if (search) viewModel.search(query)
    }
    LaunchedEffect(selectMode, selectedIds) {
        if (selectMode && selectedIds.isEmpty()) selectMode = false
    }
    LaunchedEffect(search, selectMode) {
        if (search && selectMode) {
            selectMode = false
            selectedIds = emptySet()
        }
    }

    val dark = isPulseDarkTheme()
    // "Local wins" draft merge (spec row 1): the local DraftDao row shadows
    // the server myDraft on every list surface (rows, sheets, search).
    val chats = remember(rawChats, drafts) { rawChats.map { it.withLocalDraft(drafts[it.id]) } }
    val all = chats
    val activeRows = all.filter { !it.isArchived && !it.isSelf }
    val archivedRows = all.filter { it.isArchived }
    val selfConv = all.firstOrNull { it.isSelf }
    val unreadTotal = activeRows.sumOf { it.unreadCount }
    val channelCount = all.count { it.isGroupish && it.isChannel }
    val archivedUnread = archivedRows.sumOf { it.unreadCount }

    val q = query.trim().lowercase()
    val locallyFiltered = if (q.isEmpty()) activeRows else activeRows.filter { c ->
        c.title.lowercase().contains(q) ||
            (c.myDraft ?: "").lowercase().contains(q) ||
            (!c.lastMessageDeleted && (c.lastMessagePreview ?: "").lowercase().contains(q))
    }
    val folderFiltered = when {
        activeFolderId != null -> {
            val ids = folders.firstOrNull { it.id == activeFolderId }?.conversationIds.orEmpty().toSet()
            locallyFiltered.filter { it.id in ids }
        }
        listFilter == "unread" -> locallyFiltered.filter { it.unreadCount > 0 }
        listFilter == "groups" -> locallyFiltered.filter { it.isGroupish }
        else -> locallyFiltered
    }
    val pinnedRows = folderFiltered.filter { it.isPinned }
    val unpinnedRows = folderFiltered.filter { !it.isPinned }

    fun honest(message: String) {
        scope.launch { snackbar.showSnackbar(message, withDismissAction = false) }
    }

    // Clear the system nav bar (gesture pill / 3-button strip) that draws over
    // the app under edge-to-edge - the dock lifts by the same amount.
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // EMB-A: the home tab sits on the warm sunset-blur backdrop (dark mode).
    Box(
        Modifier
            .fillMaxSize()
            .then(if (dark) Modifier.emberBackdrop() else Modifier.background(MaterialTheme.colorScheme.background)),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (search) {
                SearchHeader(
                    query = query,
                    focused = focused,
                    onQuery = { query = it },
                    onFocusChange = { focused = it },
                    onClear = { query = "" },
                    onClose = {
                        search = false
                        focused = false
                        query = ""
                    },
                )
            } else {
                HomeHeader(
                    viewerName = viewerName,
                    viewerColor = viewerColor,
                    dark = dark,
                    onAvatar = { onSwitchTab("profile") },
                    // R2-A item 2 - real calls history (route owned by the shell).
                    onCalls = onOpenCalls,
                    // R2-A item 1 - real new-chat composer (shell-hosted sheet).
                    onCompose = onOpenNewChat,
                    onTheme = onCycleTheme,
                    onSearch = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        search = true
                    },
                )
            }

            UpdaterBanner(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp))

            if (!search) {
                FilterChipsRow(
                    active = listFilter,
                    unreadTotal = unreadTotal,
                    onSelect = { filter ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setListFilter(filter)
                    },
                )
                StoriesRail(
                    viewerName = viewerName ?: "Me",
                    viewerColor = viewerColor,
                    cells = stories,
                    // Wave 4 D1: ring = viewer (seeded at the tapped author),
                    // "+" = composer, empty own cell = composer.
                    onOpenViewer = onOpenStoriesViewer,
                    onOpenComposer = onOpenStoriesComposer,
                )
                FolderRail(
                    folders = folders,
                    activeFolderId = activeFolderId,
                    folderCounts = { id ->
                        val ids = folders.firstOrNull { it.id == id }?.conversationIds.orEmpty().toSet()
                        locallyFiltered.count { it.id in ids }
                    },
                    onSelect = { next ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        activeFolderId = if (activeFolderId == next) null else next
                    },
                    onManage = { foldersSheet = true },
                )
            }

            // Wave 6 - the folders manage sheet (create/rename/emoji/membership/delete).
            if (foldersSheet) {
                FoldersManageSheet(onDismiss = { foldersSheet = false })
            }

            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    viewerId == null -> EmptyStateCard(
                        title = "No identity yet",
                        body = "Pick who you are on this device to open your live inbox.",
                        actionLabel = "Choose identity",
                        onAction = onNeedIdentity,
                    )
                    state.error != null && all.isEmpty() -> EmptyStateCard(
                        title = "Could not reach the gateway",
                        body = state.error ?: "Network error - the inbox will retry.",
                        actionLabel = "Retry",
                        onAction = { viewModel.refresh() },
                    )
                    state.loading && all.isEmpty() -> SkeletonList()
                    search -> SearchResults(
                        rows = locallyFiltered,
                        query = query,
                        hits = searchHits,
                        searching = searchRunning,
                        typing = typing,
                        presence = presence,
                        // R2-C item 7 - recent-search suggestions + push-on-open.
                        recents = spotlightRecents,
                        onPickRecent = { picked ->
                            query = picked
                            focused = false
                        },
                        onClearRecents = { viewModel.clearSpotlightRecents() },
                        onPress = { conv ->
                            if (query.isNotBlank()) viewModel.pushSpotlightRecent(query.trim())
                            openConversation(viewModel, conv, onOpenRoom)
                        },
                        onOpenHit = { hit ->
                            if (query.isNotBlank()) viewModel.pushSpotlightRecent(query.trim())
                            onOpenRoom(hit.conversationId, hit.id)
                        },
                        // R14 gap 14 - the Actions block atop the empty-query
                        // surface (web spotlight.tsx:237-291).
                        checkinPending = checkin.pending,
                        onNewChat = {
                            search = false
                            onOpenNewChat()
                        },
                        onCheckin = viewModel::checkinToHub,
                        onToggleTheme = onCycleTheme,
                    )
                    all.isEmpty() -> EmptyStateCard(
                        title = "No conversations yet",
                        body = "Your next great chat is one tap away. Find someone and break the ice.",
                        actionLabel = "Say hi to someone",
                        onAction = { onSwitchTab("contacts") },
                        showImage = true,
                        showArrow = true,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 128.dp + navBottom),
                    ) {
                        item(key = "note-to-self") {
                            NoteToSelfCard(
                                exists = selfConv != null,
                                onOpen = { selfConv?.let { openConversation(viewModel, it, onOpenRoom) } },
                                onCreate = { viewModel.createSelfChat { id -> onOpenRoom(id, null) } },
                            )
                        }
                        item(key = "pill-mentions") {
                            EntryPill(
                                icon = PulseIcons.AtSign,
                                label = "Mentions",
                                badge = mentionCount.takeIf { it > 0 },
                                trailing = if (mentionCount == 1) "1 mention" else "$mentionCount mentions",
                                dark = dark,
                                onClick = onOpenMentions,
                            )
                        }
                        item(key = "pill-channels") {
                            EntryPill(
                                icon = PulseIcons.Radio,
                                label = "Channels",
                                badge = null,
                                trailing = if (channelCount == 1) "1 channel" else "$channelCount channels",
                                dark = dark,
                                onClick = onOpenChannels,
                            )
                        }
                        item(key = "pill-archived") {
                            EntryPill(
                                icon = PulseIcons.Archive,
                                label = "Archived",
                                badge = archivedUnread.takeIf { it > 0 },
                                trailing = if (archivedRows.size == 1) "1 chat" else "${archivedRows.size} chats",
                                dark = dark,
                                onClick = onOpenArchived,
                            )
                        }
                        if (pinnedRows.isNotEmpty()) {
                            item(key = "header-pinned") { SectionHeader("Pinned", pinnedRows.size) }
                            items(pinnedRows, key = { it.id }) { conv ->
                                Box(Modifier.animateItem()) {
ConversationRowItem(
                                        conversation = conv,
                                        typing = typing[conv.id] != null,
                                        online = !conv.isGroupish && conv.otherUserId != null && conv.otherUserId in presence,
                                        selectMode = selectMode,
                                        selected = conv.id in selectedIds,
                                        entranceIndex = null,
                                        inArchived = false,
                                        onPress = { openConversation(viewModel, conv, onOpenRoom) },
                                        onLongPress = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectMode = true
                                            selectedIds = setOf(conv.id)
                                        },
                                        onToggleSelect = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            selectedIds = if (conv.id in selectedIds) selectedIds - conv.id else selectedIds + conv.id
                                        },
                                        onPin = { viewModel.togglePin(conv.id, !conv.isPinned) },
                                        onArchive = { viewModel.archive(conv.id, !conv.isArchived) },
                                    )
                                }
                            }
                            item(key = "header-all") { SectionHeader("All chats", unpinnedRows.size) }
                        }
                        items(unpinnedRows, key = { it.id }) { conv ->
                            Box(Modifier.animateItem()) {
ConversationRowItem(
                                    conversation = conv,
                                    typing = typing[conv.id] != null,
                                    online = !conv.isGroupish && conv.otherUserId != null && conv.otherUserId in presence,
                                    selectMode = selectMode,
                                    selected = conv.id in selectedIds,
                                    entranceIndex = null,
                                    inArchived = false,
                                    onPress = { openConversation(viewModel, conv, onOpenRoom) },
                                    onLongPress = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectMode = true
                                        selectedIds = setOf(conv.id)
                                    },
                                    onToggleSelect = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedIds = if (conv.id in selectedIds) selectedIds - conv.id else selectedIds + conv.id
                                    },
                                    onPin = { viewModel.togglePin(conv.id, !conv.isPinned) },
                                    onArchive = { viewModel.archive(conv.id, !conv.isArchived) },
                                )
                            }
                        }
                        if (folderFiltered.isEmpty() && activeRows.isNotEmpty()) {
                            item(key = "filter-empty") {
                                Text(
                                    text = when {
                                        activeFolderId != null -> "This folder is empty - tap the folder button on the rail to add chats."
                                        listFilter == "unread" -> "No unread chats - you are all caught up."
                                        else -> "No groups yet - start one from Contacts."
                                    },
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    color = Zinc400,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                                )
                            }
                        } else if (activeRows.isEmpty() && archivedRows.isNotEmpty()) {
                            item(key = "all-archived") {
                                Text(
                                    text = "Every chat is archived.\nNew messages bring chats back here.",
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    color = Zinc400,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Telegram-style multi-select floating bar (spec §7.5)
        AnimatedVisibility(
            visible = selectMode,
            enter = slideInVertically(PulseMotion.snappy()) { it / 2 } + fadeIn(),
            exit = slideOutVertically(PulseMotion.snappy()) { it / 2 } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp + navBottom),
        ) {
            MultiSelectBar(
                count = selectedIds.size,
                dark = dark,
                onArchive = {
                    viewModel.batchArchive(selectedIds.toList(), archived = true)
                    selectMode = false
                    selectedIds = emptySet()
                },
                onMute8h = { viewModel.batchMute8h(selectedIds.toList()) },
                onMarkRead = {
                    viewModel.batchMarkRead(selectedIds.toList()) { failed ->
                        if (failed.isEmpty()) {
                            selectMode = false
                            selectedIds = emptySet()
                        } else {
                            selectedIds = selectedIds - failed.toSet()
                        }
                    }
                },
                onExit = {
                    selectMode = false
                    selectedIds = emptySet()
                },
            )
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 112.dp + navBottom),
        ) { data ->
            Snackbar(
                containerColor = if (dark) Color(0xFF27272A) else Color(0xFF18181B),
                contentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
            ) { Text(data.visuals.message, fontSize = 13.sp) }
        }
    }

    actionTarget?.let { target ->
        ConversationActionSheet(
            conversation = target,
            isTyping = typing[target.id] != null,
            onDismiss = { actionTarget = null },
            onPin = {
                viewModel.togglePin(target.id, !target.isPinned)
                actionTarget = null
            },
            onArchive = {
                viewModel.archive(target.id, !target.isArchived)
                actionTarget = null
            },
            onMarkUnread = {
                viewModel.markUnread(target.id, !target.myManualUnread)
                actionTarget = null
            },
            onMute = { until ->
                viewModel.setMutedUntil(target.id, until)
                actionTarget = null
            },
            onExport = {
                viewModel.exportChat(target.id)
                actionTarget = null
            },
            onClear = {
                viewModel.clearChat(target.id)
                actionTarget = null
            },
        )
    }
}

/** Freeze "where was I" semantics live in the room; the list just marks read + opens. */
private fun openConversation(viewModel: ChatsViewModel, conv: Conversation, onOpenRoom: (String, String?) -> Unit) {
    viewModel.markRead(conv.id)
    onOpenRoom(conv.id, null)
}

/** Local DraftDao row wins over the server-mirrored myDraft (spec row 1). */
private fun Conversation.withLocalDraft(local: String?): Conversation =
    if (local.isNullOrBlank()) this else copy(myDraft = local)

/**
 * Archived sub-page (spec §9) - same rows, same swipe/sheet actions, the
 * Archive chip reads "Unarchive". Real hash-sub-page parity on the web.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArchivedScreen(
    viewerId: String?,
    onOpenRoom: (String, String?) -> Unit,
    onBack: () -> Unit,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val rawChats by viewModel.chats.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val typing by viewModel.typing.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var actionTarget by remember { mutableStateOf<Conversation?>(null) }
    // R1-W2H D24 - same export share hand-off as the main tab.
    var pendingShareFile by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    LaunchedEffect(viewerId) {
        if (viewerId != null) viewModel.refresh()
    }
    // Audit D25 fix (archived page): same visible-only polling contract.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startPolling()
                Lifecycle.Event.ON_STOP -> viewModel.stopPolling()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        viewModel.startPolling()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            viewModel.stopPolling()
        }
    }
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        // R1-W2H D24 - intercept the export-success notice (see ChatsScreen).
        n.text.removePrefix(EXPORT_NOTICE_PREFIX).takeIf { it != n.text }?.let { fileName ->
            pendingShareFile = File(context.cacheDir, "exports/$fileName").absolutePath
        }
        snackbar.showSnackbar(n.text, withDismissAction = false)
        viewModel.consumeNotice()
    }
    // R1-W2H D24 - same ACTION_SEND chooser launch as the main tab.
    LaunchedEffect(pendingShareFile) {
        val path = pendingShareFile ?: return@LaunchedEffect
        pendingShareFile = null
        val file = File(path)
        if (!file.exists()) return@LaunchedEffect
        runCatching {
            context.startActivity(MediaSupport.buildShareIntent(context, path, "text/plain", null))
        }
    }

    // Same "local wins" merge as the main tab (spec row 1).
    val chats = remember(rawChats, drafts) { rawChats.map { it.withLocalDraft(drafts[it.id]) } }
    val archived = chats.filter { it.isArchived }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(PulseIcons.ChevronLeft, contentDescription = "Back")
                }
                Column {
                    Text(
                        "Archived",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        if (archived.size == 1) "1 chat" else "${archived.size} chats",
                        fontSize = 12.sp,
                        color = Zinc400,
                    )
                }
            }
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    state.loading && archived.isEmpty() -> SkeletonList()
                    archived.isEmpty() -> Text(
                        "No archived chats.\nChats you archive will appear here.",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = Zinc400,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 80.dp),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 128.dp + navBottom),
                    ) {
                        items(archived, key = { it.id }) { conv ->
                            Box(Modifier.animateItem()) {
ConversationRowItem(
                                    conversation = conv,
                                    typing = typing[conv.id] != null,
                                    online = !conv.isGroupish && conv.otherUserId != null && conv.otherUserId in presence,
                                    selectMode = false,
                                    selected = false,
                                    entranceIndex = null,
                                    inArchived = true,
                                    onPress = { openConversation(viewModel, conv, onOpenRoom) },
                                    onLongPress = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        actionTarget = conv
                                    },
                                    onToggleSelect = null,
                                    onPin = { viewModel.togglePin(conv.id, !conv.isPinned) },
                                    onArchive = { viewModel.archive(conv.id, !conv.isArchived) },
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp + navBottom),
        ) { data ->
            Snackbar(
                containerColor = Color(0xFF18181B),
                contentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
            ) { Text(data.visuals.message, fontSize = 13.sp) }
        }
    }

    actionTarget?.let { target ->
        ConversationActionSheet(
            conversation = target,
            isTyping = typing[target.id] != null,
            onDismiss = { actionTarget = null },
            onPin = {
                viewModel.togglePin(target.id, !target.isPinned)
                actionTarget = null
            },
            onArchive = {
                viewModel.archive(target.id, !target.isArchived)
                actionTarget = null
            },
            onMarkUnread = {
                viewModel.markUnread(target.id, !target.myManualUnread)
                actionTarget = null
            },
            onMute = { until ->
                viewModel.setMutedUntil(target.id, until)
                actionTarget = null
            },
            onExport = {
                viewModel.exportChat(target.id)
                actionTarget = null
            },
            onClear = {
                viewModel.clearChat(target.id)
                actionTarget = null
            },
        )
    }
}

// ── header ───────────────────────────────────────────────────────────

@Composable
private fun HomeHeader(
    viewerName: String?,
    viewerColor: String?,
    dark: Boolean,
    onAvatar: () -> Unit,
    onCalls: () -> Unit,
    onCompose: () -> Unit,
    onTheme: () -> Unit,
    onSearch: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // EMB-A: the big display title; avatar/profile stays on the dock tab.
            Text(
                "Chats",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                color = if (dark) EmberText else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            // EMB-A glass cluster: search, new chat, kebab (menu absorbs the
            // calls + theme actions that used to be header icon buttons).
            var headerMenuOpen by remember { mutableStateOf(false) }
            val glassTint = if (dark) EmberText else MaterialTheme.colorScheme.onBackground
            EmberGlassButton(
                icon = PulseIcons.Search,
                label = "Search chats and messages",
                onClick = onSearch,
                tint = glassTint,
            )
            Spacer(Modifier.width(8.dp))
            EmberGlassButton(
                icon = PulseIcons.Pencil,
                label = "New chat",
                onClick = onCompose,
                tint = glassTint,
            )
            Spacer(Modifier.width(8.dp))
            Box {
                EmberGlassButton(
                    icon = PulseIcons.KebabVertical,
                    label = "More options",
                    onClick = { headerMenuOpen = true },
                    tint = glassTint,
                )
                DropdownMenu(
                    expanded = headerMenuOpen,
                    onDismissRequest = { headerMenuOpen = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = if (dark) Color(0xFF241A13) else Color.White,
                ) {
                    DropdownMenuItem(
                        text = { Text("Calls", fontSize = 14.sp) },
                        leadingIcon = { Icon(PulseIcons.Phone, contentDescription = null, tint = if (dark) EmberPalette.Amber else Emerald600, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            headerMenuOpen = false
                            onCalls()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (dark) "Light theme" else "Dark theme", fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(
                                if (dark) PulseIcons.Sun else PulseIcons.Moon,
                                contentDescription = "Toggle theme (system cycles on press)",
                                tint = if (dark) EmberPalette.Amber else Emerald600,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            headerMenuOpen = false
                            onTheme()
                        },
                    )
                }
            }
        }
        // EMB-A: the header search pill is gone - search lives in the glass
        // button; the title row keeps the same rhythm without the duplicate.
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SearchHeader(
    query: String,
    focused: Boolean,
    onQuery: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val dark = isPulseDarkTheme()
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val fieldFocused by interaction.collectIsFocusedAsState()
    LaunchedEffect(fieldFocused) { onFocusChange(fieldFocused) }
    val halo by animateFloatAsState(if (focused && query.isNotEmpty()) 1f else 0f, label = "searchHalo")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(44.dp)) {
            // EMB-A glass pill: white 8% fill, white 12% border, radius 24.
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (dark) Color.White.copy(alpha = 0.08f) else Color.White)
                    .border(1.dp, if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFFE4E4E7), RoundedCornerShape(24.dp)),
            )
            if (halo > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                        .border(2.dp, EmberPalette.Amber.copy(alpha = 0.5f * halo), RoundedCornerShape(24.dp))
                        .background(EmberPalette.Amber.copy(alpha = 0.08f * halo)),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = {
                    Text(
                        "Search chats and messages…",
                        fontSize = 14.sp,
                        color = if (dark) EmberText40 else Zinc400,
                    )
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = if (dark) EmberText else MaterialTheme.colorScheme.onBackground,
                ),
                leadingIcon = {
                    Icon(
                        PulseIcons.Search,
                        contentDescription = null,
                        tint = if (dark) EmberText45 else Zinc400,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = EmberPalette.Amber,
                ),
                interactionSource = interaction,
                modifier = Modifier.fillMaxSize(),
            )
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (dark) Color.White.copy(alpha = 0.14f) else Zinc300)
                        .clickable(onClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PulseIcons.X,
                        contentDescription = "Clear search",
                        tint = if (dark) EmberText80 else Zinc600,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(
                PulseIcons.X,
                contentDescription = "Close search",
                tint = if (halo > 0.5f) EmberPalette.Amber else (if (dark) EmberText45 else Zinc500),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── filter chips ─────────────────────────────────────────────────────

@Composable
private fun FilterChipsRow(active: String, unreadTotal: Int, onSelect: (String) -> Unit) {
    val dark = isPulseDarkTheme()
    val chips = listOf("all" to "All", "unread" to "Unread", "groups" to "Groups")
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { (key, label) ->
            val isActive = active == key
            // EMB-B reference language: the active chip is the white pill with
            // ink text (ink pill on light), inactive chips are glass.
            Row(
                Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            isActive && dark -> Color.White
                            isActive -> Color(0xFF1C1410)
                            dark -> Color.White.copy(alpha = 0.09f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        },
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (key == "unread" && unreadTotal > 0 && !isActive) {
                    Text(
                        countLabel(unreadTotal),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(EmberPalette.Signal)
                            .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        isActive && dark -> Color(0xFF1C1410)
                        isActive -> Color.White
                        dark -> EmberText55
                        else -> Zinc500
                    },
                )
            }
        }
    }
}

// ── stories rail (spec §5) ───────────────────────────────────────────

@Composable
private fun StoriesRail(
    viewerName: String,
    viewerColor: String?,
    cells: List<StoryCell>,
    /** Ring tap on a cell with a live story - arg = author userId (viewer start). */
    onOpenViewer: (String?) -> Unit,
    /** The "+" affordance / empty own cell - opens the composer (D1). */
    onOpenComposer: () -> Unit,
) {
    val dark = isPulseDarkTheme()
    val mine = cells.firstOrNull { it.mine }
    val others = cells.filter { !it.mine }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // D1: the own cell carries BOTH affordances when a live story
        // exists - tile tap = viewer seeded at my group, "+" tap =
        // composer; with NO live story the whole cell is the composer
        // entry (web "My status" parity).
        StoryTile(
            name = viewerName,
            color = viewerColor,
            unseen = mine != null && mine.unseen,
            isOwn = true,
            dark = dark,
            onPress = { if (mine != null) onOpenViewer(mine.userId) else onOpenComposer() },
            onPlus = if (mine != null) ({ onOpenComposer() }) else null,
        )
        others.forEach { cell ->
            StoryTile(
                name = cell.name,
                color = cell.color,
                unseen = cell.unseen,
                isOwn = false,
                dark = dark,
                onPress = { onOpenViewer(cell.userId) },
            )
        }
    }
}

/**
 * EMB-B story tile - the reference rounded-rect card: a warm gradient tile
 * with the author initial, an ember sweep ring while unseen, and the name
 * below in white. The own cell is glass with the ember "+" badge.
 */
@Composable
private fun StoryTile(
    name: String,
    color: String?,
    unseen: Boolean,
    isOwn: Boolean,
    dark: Boolean,
    onPress: () -> Unit,
    /** Non-null ⇒ the "+" badge is its own tap target (D1). */
    onPlus: (() -> Unit)? = null,
) {
    val angle = pulseInfiniteFloat(
        initialValue = 0f,
        targetValue = 360f,
        durationMillis = 6000,
        label = "storySpin",
    )
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 62.dp, height = 82.dp)
                .scale(scale.value)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .then(
                    if (unseen) {
                        Modifier
                            .graphicsLayer { rotationZ = angle }
                            .border(
                                2.dp,
                                Brush.sweepGradient(
                                    listOf(
                                        EmberPalette.Amber,
                                        EmberPalette.Deep,
                                        Color(0xFFFFE3C2),
                                        EmberPalette.Deep,
                                        EmberPalette.Amber,
                                    ),
                                ),
                                RoundedCornerShape(18.dp),
                            )
                    } else {
                        Modifier
                    },
                )
                .then(
                    // Inner tile: gradient wash from the author accent (own cell
                    // stays glass with the + badge like the reference "You" tile).
                    if (isOwn) {
                        Modifier
                            .padding(3.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(EmberPalette.GlassFill)
                            .border(1.dp, EmberPalette.GlassBorder, RoundedCornerShape(15.dp))
                    } else {
                        // Public gradient helper: author accent (or hashed
                        // fallback) as a two-stop vertical wash.
                        val wash = PulsePalette.gradientFor(name, color)
                        Modifier
                            .padding(3.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                Brush.linearGradient(wash),
                            )
                    },
                )
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        scope.launch {
                            scale.animateTo(0.94f, tween(70))
                            scale.animateTo(1f, PulseMotion.snappy())
                        }
                        onPress()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isOwn) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(EmberPalette.Deep)
                        .clickable(enabled = onPlus != null) { onPlus?.invoke() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    name.trim().take(1).uppercase().ifEmpty { "?" },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.92f),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            if (isOwn) "You" else name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (dark) EmberText80 else Zinc600,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(62.dp),
        )
    }
}

// ── folder rail (spec §6) ────────────────────────────────────────────

@Composable
private fun FolderRail(
    folders: List<app.pulse.domain.model.FolderSummary>,
    activeFolderId: String?,
    folderCounts: (String) -> Int,
    onSelect: (String?) -> Unit,
    onManage: () -> Unit,
) {
    val dark = isPulseDarkTheme()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RailPill(
            label = "All",
            icon = null,
            count = 0,
            active = activeFolderId == null,
            dark = dark,
            onClick = { onSelect(null) },
        )
        folders.forEach { folder ->
            RailPill(
                label = folder.name,
                icon = pulseFolderGlyph(folder.emoji),
                count = folderCounts(folder.id),
                active = activeFolderId == folder.id,
                dark = dark,
                onClick = { onSelect(folder.id) },
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .pulseGlass(dark, CircleShape)
                .clickable(onClick = onManage),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PulseIcons.Folder,
                contentDescription = "Manage chat folders",
                tint = Zinc500,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RailPill(label: String, icon: ImageVector?, count: Int, active: Boolean, dark: Boolean, onClick: () -> Unit) {
    // EMB-B: the active rail pill is the white/ink pill (reference language);
    // inactive pills stay glass with warm-deep glyph tint.
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    active && dark -> Color.White
                    active -> Color(0xFF1C1410)
                    dark -> Color.White.copy(alpha = 0.08f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (icon == null) 16.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // R18 icon-id contract - the folder glyph is the registry vector for
        // the stored icon id (stale values normalize to the default).
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) (if (dark) Color(0xFF1C1410) else Color.White) else EmberPalette.Amber,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                active && dark -> Color(0xFF1C1410)
                active -> Color.White
                dark -> EmberText55
                else -> Zinc600
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        if (count > 0) {
            Text(
                countLabel(count),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(EmberPalette.Signal)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

// ── Note to Self card (spec §7.1) ────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteToSelfCard(exists: Boolean, onOpen: () -> Unit, onCreate: () -> Unit) {
    val dark = isPulseDarkTheme()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .pulseGlass(dark, RoundedCornerShape(16.dp), deep = true)
            .combinedClickable(onClick = if (exists) onOpen else onCreate)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Emerald400, Teal600))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PulseIcons.Pencil, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "Note to Self",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Your private space - notes, links, ideas",
                fontSize = 11.sp,
                color = Zinc500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (exists) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Emerald600)
                Icon(PulseIcons.ChevronRight, contentDescription = null, tint = Emerald600, modifier = Modifier.size(14.dp))
            }
        } else {
            Text(
                "Create",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Emerald500)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// ── entry pills - Mentions / Channels / Archived (spec §7.2) ─────────

@Composable
private fun EntryPill(
    icon: ImageVector,
    label: String,
    badge: Int?,
    trailing: String,
    dark: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(44.dp)
            .pulseGlass(dark, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Emerald600, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        if (badge != null && badge > 0) {
            Text(
                countLabel(badge),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Emerald500)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(trailing, fontSize = 12.sp, color = Zinc400)
        Icon(PulseIcons.ChevronRight, contentDescription = null, tint = Zinc400, modifier = Modifier.size(14.dp))
    }
}

// ── section headers (spec §7.3) ──────────────────────────────────────

@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = Zinc400,
        )
        Text(
            countLabel(count),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Emerald600,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Emerald500.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        )
    }
}

// ── conversation row (spec §7.4) ─────────────────────────────────────

private const val SWIPE_REVEAL_DP = 112
private const val SWIPE_OPEN_THRESHOLD_DP = 56

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRowItem(
    conversation: Conversation,
    typing: Boolean,
    online: Boolean,
    selectMode: Boolean,
    selected: Boolean,
    entranceIndex: Int?,
    inArchived: Boolean,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: (() -> Unit)?,
    onPin: () -> Unit,
    onArchive: () -> Unit,
) {
    val dark = isPulseDarkTheme()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val revealPx = with(density) { SWIPE_REVEAL_DP.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    var swipeOpen by remember { mutableStateOf(false) }

    // First-load entrance stagger - 28ms × index capped at 12 items (web parity).
    val entranceAlpha = remember { Animatable(if (entranceIndex != null) 0f else 1f) }
    val entranceY = remember { Animatable(if (entranceIndex != null) 14f else 0f) }
    LaunchedEffect(entranceIndex) {
        if (entranceIndex != null) {
            delay((entranceIndex % 13) * 28L)
            launch { entranceAlpha.animateTo(1f, tween(320)) }
            entranceY.animateTo(0f, tween(320))
        }
    }

    val hasUnread = conversation.unreadCount > 0 || conversation.myManualUnread
    val muted = conversation.isMuted
    val heat = if (!conversation.isGroupish && conversation.streakAtRiskCount == 0 && conversation.streakCount >= 2) {
        streakHeat(conversation.streakCount)
    } else {
        0
    }

    Box(Modifier.fillMaxWidth()) {
        // swipe-left glass chips (Pin / Archive)
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .alpha(if (swipeOpen && !selectMode) 1f else 0f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SwipeChip(
                icon = if (conversation.isPinned) PulseIcons.Pin else PulseIcons.Pin,
                tint = if (conversation.isPinned) Amber500 else Emerald600,
                label = if (conversation.isPinned) "Unpin" else "Pin",
                dark = dark,
                onClick = {
                    scope.launch { offsetX.animateTo(0f, PulseMotion.snappy()) }
                    swipeOpen = false
                    onPin()
                },
            )
            SwipeChip(
                icon = if (inArchived || conversation.isArchived) PulseIcons.Archive else PulseIcons.Archive,
                tint = if (inArchived || conversation.isArchived) Amber500 else Zinc500,
                label = if (inArchived || conversation.isArchived) "Unarchive" else "Archive",
                dark = dark,
                onClick = {
                    scope.launch { offsetX.animateTo(0f, PulseMotion.snappy()) }
                    swipeOpen = false
                    onArchive()
                },
            )
        }

        Row(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .graphicsLayer {
                    alpha = entranceAlpha.value
                    translationY = entranceY.value
                    scaleX = if (selectMode) 0.985f else 1f
                    scaleY = if (selectMode) 0.985f else 1f
                }
                .pointerInput(conversation.id, selectMode) {
                    if (selectMode) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val target = if (offsetX.value < -(SWIPE_OPEN_THRESHOLD_DP.dp.toPx())) {
                                    swipeOpen = true
                                    -revealPx
                                } else {
                                    swipeOpen = false
                                    0f
                                }
                                offsetX.animateTo(target, PulseMotion.snappy())
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-revealPx, 0f))
                            }
                        },
                    )
                }
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                // EMB-B: rows sit flat on the sunset gradient in dark (the
                // reference list has no card chrome); light keeps the glass.
                .then(if (dark) Modifier else Modifier.pulseGlass(dark, RoundedCornerShape(16.dp)))
                .combinedClickable(
                    onClick = {
                        when {
                            selectMode -> onToggleSelect?.invoke()
                            swipeOpen -> scope.launch {
                                offsetX.animateTo(0f, PulseMotion.snappy())
                                swipeOpen = false
                            }
                            else -> onPress()
                        }
                    },
                    onLongClick = { if (!selectMode) onLongPress() },
                )
                .padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // avatar block (48dp) - presence halo, streak heat ring, squircle groups
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                if (online && !conversation.isGroupish) PresenceGlow()
                if (heat > 0) StreakHeatRing(heat)
                PulseAvatar(
                    name = conversation.title,
                    colorHex = conversation.accentColor,
                    size = 48.dp,
                    online = online && !conversation.isGroupish,
                    isGroup = conversation.isGroupish,
                )
                if (selectMode) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(if (selected) Emerald500 else Color.Black.copy(alpha = 0.35f))
                            .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(PulseIcons.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Column(Modifier.weight(1f)) {
                // title line
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.isPinned) {
                        Icon(
                            PulseIcons.Pin,
                            contentDescription = "Pinned",
                            tint = Amber500,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        conversation.title,
                        fontSize = 15.sp,
                        fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    // streak chips - priority: at-risk > live > lost (never two)
                    if (conversation.streakAtRiskCount > 0) {
                        StreakChip(icon = PulseIcons.Clock, text = "ends tonight", amber = true)
                    } else if (conversation.streakCount > 0) {
                        StreakChip(icon = PulseIcons.Flame, text = "${conversation.streakCount}", amber = true)
                    } else if (conversation.streakLost) {
                        StreakChip(icon = PulseIcons.Flame, text = "streak lost", amber = false)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        PulseTime.listStamp(conversation.lastActivityAt),
                        fontFamily = PulseMonoFamily,
                        fontSize = 11.sp,
                        fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (hasUnread) {
                            if (dark) EmberText80 else Color(0xFF1C1410)
                        } else {
                            if (dark) EmberText45 else Zinc400
                        },
                    )
                }
                Spacer(Modifier.height(2.dp))
                // preview line - typing > draft > preview
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        typing -> TypingDots()
                        conversation.myDraft != null -> {
                            Icon(PulseIcons.Pencil, contentDescription = null, tint = Amber500, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Draft:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Amber600)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                conversation.myDraft ?: "",
                                fontSize = 13.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = Zinc500,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        else -> {
                            val prefix = previewPrefix(conversation)
                            if (prefix.isNotEmpty()) {
                                Text(prefix, fontSize = 13.sp, color = Zinc400, maxLines = 1)
                            }
                            Text(
                                previewText(conversation),
                                fontSize = 13.sp,
                                fontWeight = if (hasUnread) FontWeight.Medium else FontWeight.Normal,
                                color = if (hasUnread) Zinc600 else Zinc500,
                                fontStyle = if (conversation.lastMessageDeleted) {
                                    androidx.compose.ui.text.font.FontStyle.Italic
                                } else {
                                    androidx.compose.ui.text.font.FontStyle.Normal
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // trailing status
                    when {
                        hasUnread && !muted && conversation.unreadCount > 0 -> UnreadBadge(conversation.unreadCount)
                        hasUnread && !muted -> Box(
                            Modifier
                                .padding(horizontal = 3.dp)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(EmberPalette.Signal)
                                .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                        )
                        muted -> MutedChip(hasUnread = hasUnread, unread = conversation.unreadCount)
                    }
                }
            }
        }
        // hairline divider indented past the avatar (64dp) - dark sits flat
        // on the gradient with no dividers (reference list).
        if (!dark) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 64.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color(0xFFF4F4F5)),
            )
        }
    }
}

/** Web conversationPreviewPrefix - You: / Name: / ↩ rules. */
private fun previewPrefix(c: Conversation): String = when {
    c.lastMessageDeleted -> ""
    c.lastMessageIsReply && c.lastMessageMine -> "↩ You: "
    c.lastMessageIsReply && c.isGroupish && !c.lastMessageAuthorName.isNullOrBlank() -> "↩ ${c.lastMessageAuthorName}: "
    c.lastMessageIsReply -> "↩ "
    c.lastMessageMine -> "You: "
    c.isGroupish && !c.lastMessageAuthorName.isNullOrBlank() -> "${c.lastMessageAuthorName}: "
    else -> ""
}

/** Web conversationPreview: kind-based label substitution (R35 Neo: no glyphs). */
private fun previewText(c: Conversation): String = when {
    c.lastMessageDeleted -> "Message deleted"
    c.lastMessageIsImage && c.lastMessagePreview.isNullOrBlank() -> "Photo"
    c.lastMessageIsAudio && c.lastMessagePreview.isNullOrBlank() -> "Voice message"
    c.lastMessageIsFile -> "Document - ${c.lastMessageFileName ?: "file"}"
    !c.lastMessagePreview.isNullOrBlank() -> c.lastMessagePreview ?: "No messages yet"
    else -> "No messages yet"
}

@Composable
private fun StreakChip(icon: ImageVector, text: String, amber: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (amber) Amber500.copy(alpha = 0.10f) else Rose400.copy(alpha = 0.07f),
            )
            .border(1.dp, if (amber) Amber500.copy(alpha = 0.25f) else Rose400.copy(alpha = 0.2f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (amber) Amber600 else Rose400, modifier = Modifier.size(12.dp))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (amber) Amber600 else Rose400)
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = PulseMotion.bouncy(),
        label = "unreadPop",
    )
    Box(
        Modifier
            .scale(scale)
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(RoundedCornerShape(50))
            // EMB-B: the unread signal is the ember red (reference badges).
            .background(EmberPalette.Signal)
            .border(2.dp, MaterialTheme.colorScheme.background, RoundedCornerShape(50))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(countLabel(count), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun MutedChip(hasUnread: Boolean, unread: Int) {
    val dark = isPulseDarkTheme()
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    hasUnread && !dark -> Zinc300
                    hasUnread -> Color(0xFF3F3F46)
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            PulseIcons.BellOff,
            contentDescription = "Muted",
            tint = if (hasUnread) (if (dark) Color(0xFFD4D4D8) else Zinc600) else Zinc400,
            modifier = Modifier.size(12.dp),
        )
        if (hasUnread) {
            Text(countLabel(unread), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (dark) Color(0xFFD4D4D8) else Zinc600)
        } else {
            Text("Muted", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Zinc400, letterSpacing = 0.5.sp)
        }
    }
}

/** Pulsing warm presence halo behind online DM avatars (EMB-B: ember orange). */
@Composable
private fun PresenceGlow() {
    val pulse = pulseInfiniteFloat(
        initialValue = 0.65f,
        targetValue = 0.18f,
        durationMillis = 1200,
        repeatMode = RepeatMode.Reverse,
        label = "presenceGlow",
    )
    Box(
        Modifier
            .size(56.dp)
            .graphicsLayer { scaleX = 1.10f; scaleY = 1.10f }
            .clip(CircleShape)
            .border(2.dp, EmberPalette.Online.copy(alpha = pulse), CircleShape),
    )
}

/** Snapchat-style streak heat ring - conic amber→rose arc, width scales with heat. */
@Composable
private fun StreakHeatRing(heat: Int) {
    val inset = when (heat) {
        3 -> 0f
        2 -> 1f
        else -> 2f
    }
    val width = when (heat) {
        3 -> 3.dp
        2 -> 2.5.dp
        else -> 2.dp
    }
    Canvas(Modifier.size(56.dp).padding((inset).dp)) {
        drawArc(
            brush = Brush.sweepGradient(
                listOf(
                    Amber500.copy(alpha = 0.9f),
                    Rose400.copy(alpha = 0.85f),
                    Amber500.copy(alpha = 0.35f),
                    Color.Transparent,
                ),
            ),
            startAngle = 140f,
            sweepAngle = 306f,
            useCenter = false,
            style = Stroke(width = width.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Three bouncing amber dots + italic "typing…" (EMB-B ember language). */
@Composable
private fun TypingDots() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            val y = pulseInfiniteFloat(
                initialValue = 0f,
                targetValue = -2.5f,
                durationMillis = 450,
                repeatMode = RepeatMode.Reverse,
                startOffsetMillis = i * 150,
                label = "typingDot$i",
            )
            Box(
                Modifier
                    .offset(y = (y).dp)
                    .size(3.5.dp)
                    .clip(CircleShape)
                    .background(Amber500),
            )
        }
    }
    Spacer(Modifier.width(6.dp))
    Text(
        "typing…",
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        color = EmberPalette.Amber,
    )
}

@Composable
private fun SwipeChip(icon: ImageVector, tint: Color, label: String, dark: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .size(48.dp)
            .pulseGlass(dark, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Zinc500)
    }
}

// ── multi-select bar (spec §7.5) ─────────────────────────────────────

@Composable
private fun MultiSelectBar(
    count: Int,
    dark: Boolean,
    onArchive: () -> Unit,
    onMute8h: () -> Unit,
    onMarkRead: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        Modifier
            .pulseGlass(dark, RoundedCornerShape(50), deep = true)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "$count selected",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (dark) Color(0xFFD4D4D8) else Zinc600,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        BarAction(PulseIcons.Archive, "Archive selected chats", onArchive)
        BarAction(PulseIcons.BellOff, "Mute selected chats for 8 hours", onMute8h)
        BarAction(PulseIcons.Checks, "Mark selected chats read", onMarkRead)
        BarAction(PulseIcons.X, "Exit multi-select", onExit, tint = Zinc400)
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Zinc600) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
    }
}

// ── long-press action sheet (spec §8) ────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationActionSheet(
    conversation: Conversation,
    isTyping: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onMarkUnread: () -> Unit,
    onMute: (String?) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseAvatar(conversation.title, conversation.accentColor, size = 40.dp, isGroup = conversation.isGroupish)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(conversation.title, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        isTyping -> "typing…"
                        conversation.myDraft != null -> "Draft: ${conversation.myDraft}"
                        conversation.isGroupish -> "${conversation.memberNames.size} members"
                        else -> "Direct message"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SheetAction(if (conversation.isPinned) PulseIcons.Pin else PulseIcons.Pin, if (conversation.isPinned) "Unpin" else "Pin to top", onPin)
        SheetAction(
            if (conversation.isArchived) PulseIcons.Archive else PulseIcons.Archive,
            if (conversation.isArchived) "Unarchive" else "Archive",
            onArchive,
        )
        SheetAction(PulseIcons.Checks, if (conversation.myManualUnread) "Mark as read" else "Mark as unread", onMarkUnread)
        SheetAction(PulseIcons.BellOff, "Mute for 8 hours") { onMute("8h") }
        SheetAction(PulseIcons.BellOff, "Mute for 1 week") { onMute("1w") }
        SheetAction(PulseIcons.BellOff, "Mute always") { onMute("always") }
        if (conversation.isMuted) SheetAction(PulseIcons.BellOff, "Unmute") { onMute(null) }
        SheetAction(PulseIcons.Download, "Export as .txt", onExport)
        SheetAction(PulseIcons.Trash, "Clear my messages", onClear)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Emerald600, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 15.sp)
    }
}

// ── search results (spec §10) ────────────────────────────────────────

@Composable
private fun SearchResults(
    rows: List<Conversation>,
    query: String,
    hits: List<MessageHit>,
    searching: Boolean,
    typing: Map<String, ChatsViewModel.TypingState>,
    presence: Set<String>,
    onPress: (Conversation) -> Unit,
    onOpenHit: (MessageHit) -> Unit,
    // R2-C item 7 - recent searches (web spotlight.tsx recents parity).
    recents: List<String> = emptyList(),
    onPickRecent: (String) -> Unit = {},
    onClearRecents: () -> Unit = {},
    // R14 gap 14 - the Actions block (web spotlight.tsx:237-291: New chat /
    // Check in to Hub via POST /api/hub/wallet/checkin / Toggle theme).
    checkinPending: Boolean = false,
    onNewChat: () -> Unit = {},
    onCheckin: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
) {
    val q = query.trim()
    val darkSurface = isPulseDarkTheme()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 128.dp)) {
        // R14 gap 14 - ACTIONS (web runs this block atop the search results;
        // on the empty-query surface it sits above Recents, like the web's
        // actions-before-recents flatten order).
        if (q.isEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ACTIONS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = Zinc400)
            }
            SpotlightActionRow(
                icon = PulseIcons.Pencil,
                label = "New chat",
                hint = "Pick someone to message",
                tint = Emerald600,
                onClick = onNewChat,
            )
            SpotlightActionRow(
                icon = PulseIcons.Flame,
                label = if (checkinPending) "Checking in…" else "Check in to Hub",
                hint = "Daily Pulse Coins reward",
                tint = Emerald600,
                enabled = !checkinPending,
                onClick = onCheckin,
            )
            SpotlightActionRow(
                icon = if (darkSurface) PulseIcons.Sun else PulseIcons.Moon,
                label = if (darkSurface) "Switch to light theme" else "Switch to dark theme",
                hint = "Appearance",
                tint = Emerald600,
                onClick = onToggleTheme,
            )
        }
        // R2-C item 7 - recent searches surface while the query is EMPTY
        // (last 5, deduped, newest first; tap refills the field, Clear wipes).
        if (q.isEmpty() && recents.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("RECENT SEARCHES", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = Zinc400)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearRecents) { Text("Clear", fontSize = 11.sp, color = Zinc500) }
            }
            recents.forEach { recent ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = { onPickRecent(recent) })
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(PulseIcons.Clock, contentDescription = null, tint = Zinc400, modifier = Modifier.size(18.dp))
                    Text(recent, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (rows.isNotEmpty()) {
            SearchSectionHeader("Chats", rows.size)
            rows.forEachIndexed { index, conv ->
                ConversationRowItem(
                    conversation = conv,
                    typing = typing[conv.id] != null,
                    online = !conv.isGroupish && conv.otherUserId != null && conv.otherUserId in presence,
                    selectMode = false,
                    selected = false,
                    entranceIndex = null,
                    inArchived = false,
                    onPress = { onPress(conv) },
                    onLongPress = { },
                    onToggleSelect = null,
                    onPin = { },
                    onArchive = { },
                )
            }
        }
        when {
            q.length >= 2 && searching -> Row(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Emerald500, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Searching messages…", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Zinc400)
            }
            q.length >= 2 && hits.isNotEmpty() -> {
                SearchSectionHeader("Messages", hits.size)
                hits.forEach { hit -> SearchHitRow(hit = hit, query = q, onOpen = { onOpenHit(hit) }) }
            }
            q.length == 1 -> Text(
                "Keep typing to search inside messages…",
                fontSize = 12.sp,
                color = Zinc400,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (rows.isEmpty() && (q.length < 2 || (!searching && hits.isEmpty()))) {
            Column(
                Modifier.fillMaxWidth().padding(top = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(PulseIcons.Search, contentDescription = null, tint = Zinc400, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text("No matches", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Zinc500)
                Text(
                    "Nothing here for \u201C$q\u201D.",
                    fontSize = 12.sp,
                    color = Zinc400,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp).padding(horizontal = 32.dp),
                )
            }
        }
    }
}

/**
 * R14 gap 14 - one row of the spotlight Actions block (web spotlight.tsx
 * SpotlightRow 'action' rendering): icon tile + label + hint, tap to run.
 */
@Composable
private fun SpotlightActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    hint: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Emerald500.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, fontSize = 11.5.sp, color = Zinc400)
        }
        Icon(PulseIcons.ChevronRight, contentDescription = null, tint = Zinc400, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SearchSectionHeader(label: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = Zinc400)
        Text(
            countLabel(count),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Emerald600,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Emerald500.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Spacer(Modifier.weight(1f))
    }
}

/** One server-side message hit - sender avatar, chat title, highlighted snippet. */
@Composable
private fun SearchHitRow(hit: MessageHit, query: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp)) {
            PulseAvatar(name = hit.senderName.ifBlank { "?" }, colorHex = hit.senderColor, size = 40.dp)
            if (hit.isGroup) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PulseIcons.Users, contentDescription = "Group", tint = Zinc500, modifier = Modifier.size(9.dp))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.conversationName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(PulseTime.listStamp(hit.createdAt), fontSize = 11.sp, color = Zinc400)
            }
            when {
                hit.deleted -> Text(
                    "Deleted message",
                    fontSize = 13.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Zinc400,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.imageOnly -> Text("Photo", fontSize = 13.sp, color = Zinc500, maxLines = 1)
                else -> Text(
                    snippetAnnotated(
                        if (hit.isFile && !hit.content.contains(query, ignoreCase = true)) {
                            "Document - ${hit.fileName ?: "file"}"
                        } else {
                            hit.content
                        },
                        query,
                    ),
                    fontSize = 13.sp,
                    color = Zinc500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── skeleton + empty/error cards (spec §11) ──────────────────────────

@Composable
private fun SkeletonList() {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(6) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).shimmer())
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(120.dp).height(14.dp).clip(RoundedCornerShape(6.dp)).shimmer())
                    Box(Modifier.width(220.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).shimmer())
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    showImage: Boolean = false,
    showArrow: Boolean = false,
) {
    val dark = isPulseDarkTheme()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
            // soft emerald radial glow blooming behind the illustration -
            // drawn first so the glass card paints over it
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-30).dp)
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Emerald500.copy(alpha = 0.28f), Color.Transparent))),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .pulseGlass(dark, RoundedCornerShape(28.dp), deep = true)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showImage) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.empty_chats),
                        contentDescription = "No conversations illustration",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .size(144.dp)
                            .clip(RoundedCornerShape(24.dp)),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    body,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Zinc500,
                    textAlign = TextAlign.Center,
                )
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = onAction,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Emerald600,
                        ),
                        border = BorderStroke(1.dp, Emerald500.copy(alpha = 0.4f)),
                    ) {
                        if (showArrow) {
                            Icon(
                                PulseIcons.ArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(actionLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
