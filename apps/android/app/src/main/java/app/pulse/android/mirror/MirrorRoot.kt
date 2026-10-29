package app.pulse.android.mirror

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import app.pulse.android.SessionViewModel
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.QuickPhrase
import app.pulse.domain.model.StoryGroup
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mirror destinations behind the artboard dock. The web CapsuleNav hard-codes
 * FOUR slots but only three are tabs: Chats / Calls(sub-page action) /
 * Updates(= the Hub tab) / Profile; Contacts rides the kebab (web '#/contacts').
 */
internal enum class MirrorTab { Chats, Hub, Contacts, Profile }

private data class MirrorRoomOpenRequest(val id: String, val ticket: Long, val jumpId: String?,
    val tab: MirrorTab, val previousRoomId: String?, val ownerId: String?, val api: PulseRepository,
    val personal: Boolean = false, val ownerEpoch: String? = api.attachmentSessionKey())

/** One typer in flight - the relay's typing event with a 4s expiry stamp. */
private data class MirrorTyper(val conversationId: String, val userId: String, val userName: String, val expiresAt: Long)

/**
 * R64 - native mirror root with the web's LIVE WIRE: typing events from the
 * relay (4s expiry, web typersIn parity) feed the list rows + the room bubble
 * + the header label; the home kebab menu is the web's three-section dropdown
 * with every destination real; rows long-press into the web ChatOptionsSheet;
 * archived chats leave the main list and live behind the Archived menu; rooms
 * gain the kebab, native calls, mute/TTL controls and the document pipeline.
 * Data rides the same repository flows as the rest of the app - zero mock.
 */
@Composable
internal fun MirrorRoot(
    session: SessionViewModel,
    repository: PulseRepository,
    readingEnabled: Boolean = true,
    onStartCall: ((Conversation, video: Boolean) -> Unit)? = null,
    onActiveCallRoomChanged: (Conversation?) -> Unit = {},
    onOpenLiveRoom: (String, app.pulse.feature.voice.VoiceRoomsEngine.RoomKind) -> Unit,
    // R76 - external entry points (the retired shells owned them): launcher
    // tab shortcuts land here as tab requests; pulse://room deep links as
    // pending room ids. Both work cold and warm, both self-consume.
    tabRequest: MirrorTab? = null,
    onConsumeTabRequest: () -> Unit = {},
    pendingRoomId: String? = null,
    onConsumePendingRoom: () -> Unit = {},
    /** R76 - the jump half of pulse://room/<id>?jump=<mid> reminder links. */
    pendingJumpMessageId: String? = null,
    onConsumePendingJumpMessage: () -> Unit = {},
    /** R76 - pulse://user/<id> deep links open the profile page. */
    pendingUserId: String? = null,
    onConsumePendingUser: () -> Unit = {},
    /** R76 - launcher shortcut actions (new_message / search). */
    shortcutAction: String? = null,
    onConsumeShortcutAction: () -> Unit = {},
) {
    val viewerId by session.viewerId.collectAsStateWithLifecycle()
    val viewerName by session.viewerName.collectAsStateWithLifecycle()
    val conversations by repository.observeConversations().collectAsStateWithLifecycle(initialValue = emptyList())
    val presence by repository.observePresence().collectAsStateWithLifecycle(initialValue = emptySet())
    val lifecycleOwner = LocalLifecycleOwner.current
    var tab by rememberSaveable { mutableStateOf(MirrorTab.Chats) }
    var openRoom by remember { mutableStateOf<Conversation?>(null) }
    var closingRoomId by remember(viewerId) { mutableStateOf<String?>(null) }
    var roomOpenRequest by remember(viewerId) { mutableStateOf<MirrorRoomOpenRequest?>(null) }
    var roomOpenSequence by remember(viewerId) { mutableStateOf(0L) }
    // Exit animations may keep the old room composed during a rapid reopen.
    // A real new visit must not inherit that departed reader's fence/ACK set.
    var roomVisitSequence by remember(viewerId) { mutableStateOf(0L) }
    val navigationContext = androidx.compose.ui.platform.LocalContext.current
    val currentCallRoomCallback by androidx.compose.runtime.rememberUpdatedState(onActiveCallRoomChanged)
    androidx.compose.runtime.DisposableEffect(openRoom?.id, closingRoomId) {
        currentCallRoomCallback(openRoom?.takeUnless { it.id == closingRoomId })
        onDispose { currentCallRoomCallback(null) }
    }
    // R73 - keeps the room composed while the close slide-out plays
    // (openRoom flips null immediately; the exit animation needs the convo).
    var roomShown by remember { mutableStateOf<Conversation?>(null) }
    // R76 - jump-to-message anchor: Spotlight message hits, home search hits
    // and pulse://room/<id>?jump=<mid> deep links all open the room ANCHORED
    // at one message (web jumpToMessage parity).
    var pendingJump by remember(viewerId) { mutableStateOf<MirrorRoomJump?>(null) }

    // R76 - launcher tab shortcuts switch tabs cold or warm, then self-consume.
    LaunchedEffect(tabRequest) {
        val request = tabRequest ?: return@LaunchedEffect
        roomOpenRequest = null
        tab = request
        onConsumeTabRequest()
    }
    var stories by remember(viewerId) { mutableStateOf<List<StoryGroup>>(emptyList()) }
    var folders by remember(viewerId) { mutableStateOf<List<MirrorFolderChip>>(emptyList()) }
    val storyScope = androidx.compose.runtime.rememberCoroutineScope()
    val storyReads = remember(viewerId) { app.pulse.domain.sync.RoomControlGate { repository.attachmentSessionKey() } }

    // R72 - the settings "Default list filter" row now DRIVES this filter
    // (web chats-tab.tsx:795 applies the persisted listFilter on load).
    var filter by remember { mutableStateOf(MirrorFilter.All) }
    var filterSeeded by remember { mutableStateOf(false) }
    val savedListFilter by session.chatsListFilter.collectAsStateWithLifecycle()
    // R74 - web prefs.reducedMotion: gates the full-screen message effects
    val reducedMotion by session.reducedMotion.collectAsStateWithLifecycle()
    // R75 - Spotlight theme action needs the RESOLVED dark state (web resolvedTheme)
    val darkOverride by session.darkOverride.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val darkResolved = when (darkOverride) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    LaunchedEffect(savedListFilter) {
        if (!filterSeeded) {
            filterSeeded = true
            filter = when (savedListFilter) {
                "unread" -> MirrorFilter.Unread
                "groups" -> MirrorFilter.Groups
                else -> MirrorFilter.All
            }
        }
    }
    var activeFolderId by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var kebabOpen by remember { mutableStateOf(false) }
    // R75 - the web SpotlightOverlay (shell-global search palette); the home
    // kebab "Search" opens it, the header icon keeps the in-tab search mode.
    var spotlightOpen by remember { mutableStateOf(false) }
    // R69 - the web PiP mini chat window: floats over any tab, independent of
    // the open room (web startPipChat/closePipChat).
    var pipFor by remember { mutableStateOf<Conversation?>(null) }
    var callsOpen by remember { mutableStateOf(false) }
    var channelsOpen by remember { mutableStateOf(false) }
    var newChatOpen by remember { mutableStateOf(false) }
    var composerOpen by remember { mutableStateOf(false) }
    var viewingStory by remember(viewerId) { mutableStateOf<StoryGroup?>(null) }

    // R64 - kebab destinations + row options (all REAL repository-backed)
    var groupOpen by remember { mutableStateOf(false) }
    var joinOpen by remember { mutableStateOf(false) }
    var archivedOpen by remember { mutableStateOf(false) }
    var mentionsOpen by remember { mutableStateOf(false) }
    var foldersOpen by remember { mutableStateOf(false) }
    val mentionBadgeFlow = remember(repository) { repository.observeMentionBadge() }
    val mentionBadge by mentionBadgeFlow.collectAsStateWithLifecycle(initialValue = null)
    val mentionCount = mentionBadge ?: 0
    var rowOptions by remember { mutableStateOf<Conversation?>(null) }
    // R70 - full room-info page + the classic group manager it hands off to
    var roomInfoFor by remember { mutableStateOf<Conversation?>(null) }
    var managerFor by remember { mutableStateOf<Conversation?>(null) }
    // R72 - the web #/user/:id profile page (member taps, contacts rows)
    var userPageFor by remember { mutableStateOf<String?>(null) }
    // R76 - pulse://user/<id> deep links open the profile page like member rows.
    LaunchedEffect(pendingUserId) {
        val id = pendingUserId ?: return@LaunchedEffect
        userPageFor = id
        onConsumePendingUser()
    }
    // R76 - launcher shortcut actions (web shell parity: new message / search).
    LaunchedEffect(shortcutAction) {
        val action = shortcutAction ?: return@LaunchedEffect
        when (action) {
            "new_message" -> newChatOpen = true
            "search" -> {
                tab = MirrorTab.Chats
                searching = true
            }
        }
        onConsumeShortcutAction()
    }

    // R71 - the web SettingsScreen + profile sub-surfaces (profile-tab.tsx)
    var settingsOpen by remember { mutableStateOf(false) }
    var profileEditOpen by remember { mutableStateOf(false) }
    var profileRefresh by remember { mutableStateOf(0) }
    var profileSavedOpen by remember { mutableStateOf(false) }

    // R89 - the system back contract. The phone back button must listen like
    // WhatsApp: every surface closes back to where it came from, topmost
    // first; a second press on the bare home exits (double-press guard with
    // the 2s disarm window). Before this round the app shipped ZERO
    // BackHandler - the default Activity behavior killed the app on the
    // first press from anywhere.
    val backActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var backExitArmed by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler {
        when {
            roomOpenRequest != null -> roomOpenRequest = null
            viewingStory != null -> viewingStory = null
            spotlightOpen -> spotlightOpen = false
            profileSavedOpen -> profileSavedOpen = false
            profileEditOpen -> { profileRefresh++; profileEditOpen = false }
            settingsOpen -> settingsOpen = false
            userPageFor != null -> userPageFor = null
            managerFor != null -> managerFor = null
            roomInfoFor != null -> roomInfoFor = null
            openRoom != null -> { pendingJump = null; openRoom = null }
            rowOptions != null -> rowOptions = null
            kebabOpen -> kebabOpen = false
            archivedOpen -> archivedOpen = false
            mentionsOpen -> mentionsOpen = false
            foldersOpen -> foldersOpen = false
            newChatOpen -> newChatOpen = false
            composerOpen -> composerOpen = false
            groupOpen -> groupOpen = false
            joinOpen -> joinOpen = false
            callsOpen -> callsOpen = false
            channelsOpen -> channelsOpen = false
            searching -> {
                searching = false
                searchQuery = ""
            }
            tab != MirrorTab.Chats -> tab = MirrorTab.Chats
            else -> {
                if (backExitArmed) {
                    // home + nothing open: the double press exits to the
                    // launcher with the process kept warm (WhatsApp parity)
                    backActivity?.moveTaskToBack(true)
                } else {
                    backExitArmed = true
                    android.widget.Toast.makeText(
                        backActivity,
                        "Press back again to exit",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    LaunchedEffect(backExitArmed) {
        if (backExitArmed) {
            kotlinx.coroutines.delay(2000)
            backExitArmed = false
        }
    }

    // R64 - typing state: relay events - per-conversation typer list (4s TTL).
    var typers by remember { mutableStateOf<List<MirrorTyper>>(emptyList()) }
    LaunchedEffect(viewerId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (viewerId == null) return@repeatOnLifecycle
            launch {
                repository.events().collect { event ->
                    if (event is PulseEvent.Typing && event.userId != viewerId) {
                        typers = if (event.isTyping) {
                            typers
                                .filter { it.userId != event.userId || it.conversationId != event.conversationId }
                                .plus(MirrorTyper(event.conversationId, event.userId, event.userName, System.currentTimeMillis() + 4_000L))
                        } else {
                            typers.filter { !(it.userId == event.userId && it.conversationId == event.conversationId) }
                        }
                    } else if (event is PulseEvent.MessageReceived || event is PulseEvent.MessageUpdated) {
                        // Realtime snappiness: refetch the list when a message lands.
                        runCatching { repository.refreshConversations() }
                    }
                }
            }
            // Expire stale typers on a 1s beat (web ~4s auto-expiry parity).
            while (true) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val stale = typers.any { it.expiresAt <= now }
                if (stale) typers = typers.filter { it.expiresAt > now }
            }
        }
    }

    // Refresh rhythm: conversations + stories + folders + mentions on mount and every 5s.
    LaunchedEffect(viewerId, lifecycleOwner) {
        val account = repository.boundToCurrentAccount()
        val owner = repository.attachmentSessionKey()
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (viewerId != null && owner != null) {
                    val storyTicket = storyReads.beginRead()
                    account.refreshConversations()
                    currentCoroutineContext().ensureActive()
                    if (repository.attachmentSessionKey() != owner) return@repeatOnLifecycle
                    val feed = account.stories()
                    currentCoroutineContext().ensureActive()
                    if (storyTicket != null && storyReads.isCurrent(storyTicket)) {
                        feed.getOrNull()?.let { stories = it }
                        if ((feed.exceptionOrNull() as? app.pulse.domain.model.PulseApiException)?.kind in setOf("AUTH", "FORBIDDEN", "NOT_FOUND")) stories = emptyList()
                    }
                    val loadedFolders = account.folders()
                    currentCoroutineContext().ensureActive()
                    if (repository.attachmentSessionKey() != owner) return@repeatOnLifecycle
                    loadedFolders.getOrNull()?.let { loaded ->
                        folders = loaded.map { f ->
                            MirrorFolderChip(
                                id = f.id, name = f.name, emoji = f.emoji,
                                count = f.conversationIds.count { cid -> conversations.any { it.id == cid } },
                                conversationIds = f.conversationIds,
                            )
                        }
                    }

                }
                delay(5_000)
            }
        }
    }

    // Web parity: the main list EXCLUDES archived chats (they live in the menu)
    // and the Note to Self chat (web R24-a - isSelf rows never render at home).
    val activeConversations = conversations.filter { !it.isArchived && !it.isSelf }
    val archivedConversations = conversations.filter { it.isArchived }

    fun openById(conversationId: String, jumpId: String? = null) {
        if (conversationId.isBlank()) return
        roomOpenRequest = null
        pendingJump = null
        // Use the captured account's canonical cache, not an earlier Compose
        // list emission that may belong to the preceding account activation.
        roomOpenSequence++
        roomOpenRequest = MirrorRoomOpenRequest(conversationId, roomOpenSequence, jumpId, tab, openRoom?.id,
            viewerId, repository.boundToCurrentAccount())
    }

    fun openPersonalChat(peerId: String) {
        if (peerId.isBlank()) return
        pendingJump = null
        roomOpenSequence++
        roomOpenRequest = MirrorRoomOpenRequest(peerId, roomOpenSequence, null, tab, openRoom?.id,
            viewerId, repository.boundToCurrentAccount(), personal = true)
    }
    // Cold links may target a conversation outside the inbox's current page.
    // Enqueue the same exact-ID resolver used by in-app navigation; consuming
    // the launcher payload must not cancel the separately owned open request.
    LaunchedEffect(pendingRoomId, pendingJumpMessageId, viewerId) {
        val id = pendingRoomId ?: return@LaunchedEffect
        if (viewerId.isNullOrBlank()) return@LaunchedEffect
        openById(id, pendingJumpMessageId)
        onConsumePendingRoom()
        onConsumePendingJumpMessage()
    }
    LaunchedEffect(roomOpenRequest, closingRoomId) {
        val closing = closingRoomId ?: return@LaunchedEffect
        if (roomOpenRequest == null) {
            if (openRoom?.id == closing) openRoom = null
            closingRoomId = null
        }
    }

    val openingRequest = roomOpenRequest
    LaunchedEffect(openingRequest, repository, viewerId, tab, openRoom?.id) {
        val request = openingRequest ?: return@LaunchedEffect
        if (request.tab != tab || request.previousRoomId != openRoom?.id) {
            roomOpenRequest = null
            return@LaunchedEffect
        }
        val api = request.api
        val owner = request.ownerId
        fun ownsOwner() = owner != null && request.ownerEpoch != null && viewerId == owner && api.viewerId == owner &&
            api.attachmentSessionKey() == request.ownerEpoch && repository.attachmentSessionKey() == request.ownerEpoch
        if (!ownsOwner()) { roomOpenRequest = null; return@LaunchedEffect }
        try {
            // Read the repository snapshot, not the possibly older Compose list.
            val cached = api.observeConversations().first()
            currentCoroutineContext().ensureActive()
            if (!ownsOwner()) return@LaunchedEffect
            val conversation = if (request.personal) {
                app.pulse.domain.sync.resolvePersonalConversation(requireNotNull(owner), request.id, cached, api::createSelfChat, api::createDm)
            } else app.pulse.domain.sync.resolveConversationForOpen(request.id, cached, api::conversationDetail)
            currentCoroutineContext().ensureActive()
            if (roomOpenRequest == request && ownsOwner() && tab == request.tab &&
                openRoom?.id == request.previousRoomId) {
                pendingJump = request.jumpId?.let { MirrorRoomJump(conversation.id, it) }
                if (request.previousRoomId != conversation.id) roomVisitSequence = request.ticket
                openRoom = conversation
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (roomOpenRequest == request && ownsOwner()) {
                android.widget.Toast.makeText(navigationContext,
                    "Couldn't open this chat. Reconnect and try again.", android.widget.Toast.LENGTH_LONG).show()
            }
        } finally { if (roomOpenRequest == request) roomOpenRequest = null }
    }

    // art-scene ::before breathe: opacity 1 -> 0.86 -> 1 over 7s (globals.css)
    val sceneBreathe = rememberInfiniteTransition(label = "sceneBreathe")
    val breatheAlpha by sceneBreathe.animateFloat(
        initialValue = 1f,
        targetValue = 0.86f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sceneBreatheAlpha",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(MirrorArt.Bg)
            .drawBehind { MirrorScene(this, breatheAlpha) },
    ) {
        when {
            viewingStory != null -> {
                viewingStory?.let { selected ->
                    val current = stories.firstOrNull { it.user?.id == selected.user?.id }
                    if (current != null) {
                        MirrorStoryViewer(group = current, repository = repository, playbackEnabled = readingEnabled,
                            onDismiss = { viewingStory = null }, onDeleted = { id ->
                                storyReads.invalidate()
                                stories = stories.map { it.copy(stories = it.stories.filterNot { row -> row.id == id }) }.filter { it.stories.isNotEmpty() }
                            })
                    } else {
                        LaunchedEffect(selected.user?.id) { viewingStory = null }
                    }
                }
            }
            else -> {
                val myGroup = stories.firstOrNull { it.mine }
                val otherGroups = stories.filter { !it.mine }
                val cards = buildList {
                    add(
                        MirrorStoryCard(
                            label = "You",
                            name = viewerName.orEmpty(),
                            color = "emerald",
                            isYou = true,
                            unseen = myGroup != null,
                            badge = 0,
                            hasPhoto = myGroup != null,
                            background = myGroup?.stories?.firstOrNull()?.background ?: "emerald",
                        ),
                    )
                    for (g in otherGroups) {
                        add(
                            MirrorStoryCard(
                                label = g.user?.name ?: "?",
                                name = g.user?.name ?: "?",
                                color = g.user?.color ?: "emerald",
                                isYou = false,
                                unseen = !g.allSeen,
                                badge = if (g.allSeen) 0 else g.stories.size,
                                hasPhoto = g.stories.any { it.imagePath != null },
                                background = g.stories.firstOrNull()?.background ?: g.user?.color ?: "emerald",
                            ),
                        )
                    }
                }
                // R73 - web tab slide (main-shell.tsx:425-488): direction-aware
                // ±24px slide + fade, 0.22s swift-out, AnimatePresence custom=dir.
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (
                            slideInHorizontally(MirrorMotion.snappy()) { dir * it / 12 } +
                                fadeIn(tween(160))
                            ) togetherWith (
                            slideOutHorizontally(tween(190, easing = FastOutLinearInEasing)) { -dir * it / 12 } +
                                fadeOut(tween(140))
                            )
                    },
                    label = "mirrorTabSlide",
                ) { current ->
                when (current) {
                    MirrorTab.Chats -> {
                        MirrorHome(
                            conversations = activeConversations.map { it.toRow(presence, viewerId) },
                            stories = cards,
                            folders = folders,
                            viewerName = viewerName.orEmpty(),
                            searching = searching,
                            searchQuery = searchQuery,
                            onSearchQuery = { searchQuery = it },
                            onCloseSearch = {
                                searching = false
                                searchQuery = ""
                            },
                            onOpenSearch = { searching = true },
                            onCamera = { composerOpen = true },
                            onKebab = { kebabOpen = true },
                            // R72 - server message search rides the same repository
                            repository = repository,
                            // R76 - hits anchor the room at the message (web jumpToMessage)
                            onOpenConversationId = { conversationId, jumpId ->
                                openById(conversationId, jumpId)
                            },
                            filter = filter,
                            onFilter = {
                                filter = it
                                // R72 - persist the choice so Settings shows the live value
                                session.setChatsListFilter(
                                    when (it) {
                                        MirrorFilter.Unread -> "unread"
                                        MirrorFilter.Groups -> "groups"
                                        else -> "all"
                                    },
                                )
                            },
                            activeFolderId = activeFolderId,
                            onFolder = { activeFolderId = it },
                            onStory = { card ->
                                val group = if (card.isYou) myGroup else otherGroups.firstOrNull { it.user?.name == card.label }
                                if (group != null) {
                                    viewingStory = group
                                } else if (card.isYou) {
                                    composerOpen = true
                                }
                            },
                            onOpenConversation = { row -> openById(row.id) },
                            onRowOptions = { row -> conversations.firstOrNull { it.id == row.id }?.let { rowOptions = it } },
                            typingIds = typers.map { it.conversationId }.toSet(),
                        )
                    }
                    MirrorTab.Hub -> {
                        MirrorHub(
                            repository = repository,
                            viewerId = viewerId.orEmpty(),
                            onOpenConversation = { openById(it) },
                        )
                    }
                    MirrorTab.Contacts -> {
                        MirrorContacts(
                            repository = repository,
                            viewerId = viewerId.orEmpty(),
                            onOpenConversation = { openById(it) },
                            onOpenProfile = { userPageFor = it },
                            onGoProfile = { tab = MirrorTab.Profile },
                            onNewGroup = { groupOpen = true },
                            onAdd = { newChatOpen = true },
                        )
                    }
                    MirrorTab.Profile -> {
                        androidx.compose.runtime.key(viewerId, repository.attachmentSessionKey(), profileRefresh) {
                            val profileRepository = remember(repository, viewerId, repository.attachmentSessionKey()) { repository.boundToCurrentAccount() }
                            MirrorProfile(
                                viewerId = viewerId.orEmpty(),
                                repository = profileRepository,
                                iAmOnline = viewerId != null && presence.contains(viewerId),
                                onSignOut = { session.forgetViewer() },
                                onOpenHub = { tab = MirrorTab.Hub },
                                onOpenSettings = { settingsOpen = true },
                                onOpenSaved = { profileSavedOpen = true },
                                onEditProfile = { profileEditOpen = true },
                            )
                        }
                    }
                }
                }

                // R66 - zinc-900 sub-pages over the scene (web calls/channels pages)
                if (callsOpen) {
                    MirrorCallsPage(
                        repository = repository,
                        onOpenConversation = {
                            callsOpen = false
                            openById(it)
                        },
                        onClose = { callsOpen = false },
                    )
                }
                if (channelsOpen) {
                    MirrorChannelsPage(
                        repository = repository,
                        onOpenConversation = {
                            channelsOpen = false
                            openById(it)
                        },
                        onClose = { channelsOpen = false },
                    )
                }

                MirrorDock(
                    activeTab = tab,
                    unread = activeConversations.sumOf { it.badgeUnreadCount },
                    // R90 - a dock tab press dismisses the calls/channels
                    // sub-pages: on web the dock floats above the sub-page and
                    // every slot navigates; before this round the sub-page
                    // swallowed the shell and trapped the user on the calls
                    // page (the report's fatal-mistake complaint). R92 - the archived
                    // and mentions sub-pages ride the same contract (web dock slots
                    // navigate away from every hash sub-page).
                    onTab = {
                        roomOpenRequest = null
                        tab = it
                        callsOpen = false
                        channelsOpen = false
                        archivedOpen = false
                        mentionsOpen = false
                    },
                    // web: the Calls slot opens the zinc-900 calls sub-page, tab stays
                    onCalls = { callsOpen = true },
                    onFab = { newChatOpen = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // R69 - the web mini chat window floats over everything
                pipFor?.let { pipConvo ->
                    MirrorPipChat(
                        convo = pipConvo,
                        repository = repository,
                        viewerId = viewerId.orEmpty(),
                        onOpen = {
                            pipFor = null
                            openById(pipConvo.id)
                        },
                        onClose = { pipFor = null },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 116.dp),
                    )
                }


                if (kebabOpen) {
                    MirrorKebabMenu(
                        archivedCount = archivedConversations.size,
                        selfOpen = conversations.any { it.isSelf },
                        mentionCount = mentionCount,
                        channelCount = activeConversations.count { it.isChannel },
                        archivedUnread = archivedConversations.sumOf { it.badgeUnreadCount },
                        onSearch = {
                            // R75 - kebab Search opens the SPOTLIGHT palette (the web
                            // shell overflow "Search" action opens Spotlight too); the
                            // in-tab search mode stays on the header search icon.
                            spotlightOpen = true
                        },
                        onNewChat = { newChatOpen = true },
                        onNewGroup = { groupOpen = true },
                        onJoinCode = { joinOpen = true },
                        // web kebab Contacts -> '#/contacts' (the contacts TAB)
                        onContacts = { tab = MirrorTab.Contacts },
                        onCalls = { callsOpen = true },
                        onArchived = { archivedOpen = true },
                        onNoteToSelf = { viewerId?.let { openPersonalChat(it) } },
                        onMentions = { mentionsOpen = true },
                        onChannels = { channelsOpen = true },
                        onFolders = { foldersOpen = true },
                        // web kebab Saved opens the saved-messages library
                        onSaved = { profileSavedOpen = true },
                        // web openMyStatus: open the viewer when the viewer has
                        // an active story, otherwise the composer (You card parity)
                        onStories = {
                            val mine = stories.firstOrNull { it.mine }
                            if (mine != null) viewingStory = mine else composerOpen = true
                        },
                        // R71 - Settings + Appearance open the REAL settings screen
                        onSettings = { settingsOpen = true },
                        onAppearance = { settingsOpen = true },
                        onDismiss = { kebabOpen = false },
                    )
                }
                if (newChatOpen) {
                    MirrorNewChatSheet(
                        repository = repository,
                        onDismiss = { newChatOpen = false },
                        onOpened = { convoId ->
                            newChatOpen = false
                            openById(convoId)
                        },
                    )
                }
                if (composerOpen) {
                    MirrorStoryComposer(
                        repository = repository,
                        onDismiss = { composerOpen = false },
                        onPosted = {
                            composerOpen = false
                            val ticket = storyReads.beginRead()
                            val account = repository.boundToCurrentAccount()
                            storyScope.launch {
                                val feed = account.stories()
                                currentCoroutineContext().ensureActive()
                                if (ticket != null && storyReads.isCurrent(ticket)) feed.getOrNull()?.let { stories = it }
                            }
                        },
                    )
                }
                if (groupOpen) {
                    MirrorGroupSheet(
                        repository = repository,
                        onOpened = {
                            groupOpen = false
                            CoroutineScope(Dispatchers.IO).launch { runCatching { repository.refreshConversations() } }
                            openById(it)
                        },
                        onDismiss = { groupOpen = false },
                    )
                }
                if (joinOpen) {
                    MirrorJoinSheet(
                        repository = repository,
                        onOpened = {
                            joinOpen = false
                            CoroutineScope(Dispatchers.IO).launch { runCatching { repository.refreshConversations() } }
                            openById(it)
                        },
                        onDismiss = { joinOpen = false },
                    )
                }
                if (archivedOpen) {
                    // R92 - the web kebab Archived row opens the FULL #/chats/archived
                    // sub-page (chats-tab.tsx:1614 -> ChatsArchivedPage:2083), never a
                    // bottom sheet.
                    MirrorArchivedPage(
                        archived = archivedConversations,
                        presence = presence,
                        onOpen = {
                            archivedOpen = false
                            openById(it.id)
                        },
                        onUnarchive = { convo ->
                            CoroutineScope(Dispatchers.IO).launch {
                                repository.archive(convo.id, false)
                                runCatching { repository.refreshConversations() }
                            }
                        },
                        onClose = { archivedOpen = false },
                    )
                }
                if (mentionsOpen) {
                    // R92 - the web kebab Mentions row opens the FULL #/mentions
                    // sub-page (chats-tab.tsx:1642 -> MentionsPage:2113) with the real
                    // GET /api/mentions feed.
                    MirrorMentionsPage(
                        repository = repository,
                        onOpenConv = { conversationId, messageId ->
                            mentionsOpen = false
                            openById(conversationId, messageId)
                        },
                        onClose = { mentionsOpen = false },
                    )
                }
                if (foldersOpen) {
                    MirrorFoldersSheet(
                        repository = repository,
                        conversations = conversations,
                        activeFolderId = activeFolderId,
                        onPick = {
                            activeFolderId = it
                            foldersOpen = false
                            tab = MirrorTab.Chats
                        },
                        onChanged = {
                            // the rail rides its 5s refresh; nudge the list once for snappiness
                            CoroutineScope(Dispatchers.IO).launch { runCatching { repository.refreshConversations() } }
                        },
                        onDismiss = { foldersOpen = false },
                    )
                }
                if (rowOptions != null) {
                    val convo = rowOptions!!
                    MirrorRowOptionsSheet(
                        conversationId = convo.id,
                        title = convo.title,
                        pinned = convo.isPinned,
                        archived = convo.isArchived,
                        manualUnread = convo.myManualUnread,
                        muted = convo.isMuted,
                        repository = repository,
                        onChanged = {
                            CoroutineScope(Dispatchers.IO).launch { runCatching { repository.refreshConversations() } }
                        },
                        onDismiss = { rowOptions = null },
                    )
                }
            }
        }

        // R73 - the room slides over the shell with the web's signature
        // full-screen entrance (chat-room.tsx:3895 slide-up spring 320/34,
        // exit slides back down). roomShown keeps the convo composed through
        // the exit so the slide-down is visible.
        LaunchedEffect(openRoom) { if (openRoom != null) roomShown = openRoom }
        AnimatedVisibility(
            visible = viewingStory == null && openRoom != null && openRoom?.id != closingRoomId,
            enter = slideInVertically(MirrorMotion.soft()) { it } + fadeIn(tween(90)),
            exit = slideOutVertically(tween(210, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(150)),
        ) {
            roomShown?.let { shown ->
                val convo = conversations.firstOrNull { it.id == shown.id } ?: shown
                val roomTypers = typers
                    .filter { it.conversationId == convo.id }
                    .map { it.userName }
                val roomJump = pendingJump?.forRoom(convo.id, openRoom?.id)
                androidx.compose.runtime.key(viewerId, convo.id, repository.attachmentSessionKey(), roomVisitSequence) {
                    MirrorRoomScaffold(
                        convo = convo,
                        readingEnabled = readingEnabled && openRoom?.id == convo.id && closingRoomId != convo.id &&
                            roomOpenRequest == null && viewingStory == null &&
                            roomInfoFor == null && managerFor == null && userPageFor == null &&
                            !settingsOpen && !profileEditOpen && !profileSavedOpen && !callsOpen && !spotlightOpen,
                        accountRouter = repository,
                        viewerId = viewerId.orEmpty(),
                        presence = presence,
                        typers = roomTypers,
                        pipActive = pipFor?.id == convo.id,
                        onTogglePip = { pipFor = if (pipFor?.id == convo.id) null else convo },
                        onClose = { pendingJump = null; openRoom = null },
                        onRoomInfo = { roomInfoFor = convo },
                        onManageGroup = { managerFor = convo },
                        onStartCall = onStartCall,
                        onOpenLiveRoom = onOpenLiveRoom,
                        reducedMotion = reducedMotion,
                        jumpMessageId = roomJump?.messageId,
                        jumpRequestId = roomJump?.deliveryId,
                        onJumpConsumed = {
                            if (pendingJump == roomJump && openRoom?.id == convo.id) pendingJump = null
                        },
                        onOpenReminder = { target ->
                            if (repository.viewerId == viewerId && openRoom?.id == convo.id) {
                                openById(target.conversationId, target.messageId)
                            }
                        },
                    )
                }
            }
        }

        // A confirmed departure closes only the matching room and pending open, not a newer destination.
        fun closeDepartedRoom(id: String) {
            if (roomInfoFor?.id == id) roomInfoFor = null
            if (managerFor?.id == id) managerFor = null
            if (pipFor?.id == id) pipFor = null
            if (roomOpenRequest?.let { !it.personal && it.id == id } == true) roomOpenRequest = null
            if (openRoom?.id == id) {
                // Do not cancel/re-dispatch a newer DM/open request just because
                // this older departure finished. Hide the departed room while
                // preserving that request's original navigation context.
                if (roomOpenRequest != null) closingRoomId = id else openRoom = null
                pendingJump = null
            }
        }

        if (roomInfoFor != null) {
            val convo = roomInfoFor!!
            androidx.compose.runtime.key(viewerId, convo.id, repository.attachmentSessionKey()) {
                val infoRepository = remember(repository, viewerId, convo.id, repository.attachmentSessionKey()) { repository.boundToCurrentAccount() }
                val infoScope = androidx.compose.runtime.rememberCoroutineScope()
                MirrorRoomInfoPage(
                    conversationId = convo.id,
                    viewerId = viewerId.orEmpty(),
                    viewerName = viewerName.orEmpty(),
                    repository = infoRepository,
                    presence = presence,
                    onChanged = {
                        infoScope.launch {
                            try {
                                withContext(Dispatchers.IO) { infoRepository.refreshConversations() }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // The confirmed mutation is independent of this refresh.
                            }
                        }
                    },
                    onDismiss = { roomInfoFor = null },
                    onLeft = { closeDepartedRoom(convo.id) },
                    onOpenManager = { managerFor = roomInfoFor; roomInfoFor = null },
                    onOpenUser = { roomInfoFor = null; userPageFor = it },
                )
            }
        }
        if (managerFor != null) {
            val convo = managerFor!!
            androidx.compose.runtime.key(viewerId, convo.id) {
                val managerRepository = remember(repository, viewerId, convo.id) { repository.boundToCurrentAccount() }
                val managerScope = androidx.compose.runtime.rememberCoroutineScope()
                MirrorGroupManagerSheet(
                    conversationId = convo.id,
                    viewerId = viewerId.orEmpty(),
                    viewerName = viewerName.orEmpty(),
                    repository = managerRepository,
                    presence = presence,
                    onChanged = {
                        managerScope.launch {
                            try {
                                withContext(Dispatchers.IO) { managerRepository.refreshConversations() }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Repository reconciliation remains independent.
                            }
                        }
                    },
                    onDismiss = { managerFor = null },
                    onLeft = { closeDepartedRoom(convo.id) },
                    onOpenUser = { managerFor = null; userPageFor = it },
                )
            }
        }
        // R72 - the web #/user/:id full-screen profile page (topmost overlay)
        if (userPageFor != null) {
            MirrorUserPage(
                userId = userPageFor!!,
                viewerId = viewerId.orEmpty(),
                repository = repository,
                conversations = conversations,
                presence = presence,
                onDismiss = { userPageFor = null },
                onOpenConversation = { conversationId ->
                    userPageFor = null
                    openById(conversationId)
                },
            )
        }
        // R71 - the web SettingsScreen overlay (root + 9 sections)
        if (settingsOpen) {
            MirrorSettingsScreen(
                repository = repository,
                session = session,
                viewerId = viewerId.orEmpty(),
                onEditProfile = {
                    settingsOpen = false
                    profileEditOpen = true
                },
                onOpenHub = {
                    settingsOpen = false
                    tab = MirrorTab.Hub
                },
                onClose = { settingsOpen = false },
            )
        }
        // R71 - the web Edit profile sub-page (real PATCH /api/users/:id)
        if (profileEditOpen) {
            androidx.compose.runtime.key(viewerId, repository.attachmentSessionKey()) {
                val profileRepository = remember(repository, viewerId, repository.attachmentSessionKey()) { repository.boundToCurrentAccount() }
                val profileSession = remember(profileRepository) { profileRepository.attachmentSessionKey() }
                MirrorEditProfilePage(
                    repository = profileRepository,
                    viewerId = viewerId.orEmpty(),
                    onProfileConfirmed = { confirmed, patch ->
                        session.adoptProfileMetadata(confirmed.id, profileSession,
                            confirmed.name.takeIf { patch.name != null }, confirmed.color.takeIf { patch.color != null })
                    },
                    onSaved = { profileRefresh++; profileEditOpen = false },
                    onDismiss = { profileRefresh++; profileEditOpen = false },
                )
            }
        }
        // R71 - the saved-messages library drawer (profile kebab + kebab row)
        if (profileSavedOpen) {
            MirrorSavedSheet(
                repository = repository,
                viewerId = viewerId.orEmpty(),
                onOpenConversation = { conversationId, _ ->
                    profileSavedOpen = false
                    openById(conversationId)
                },
                onDismiss = { profileSavedOpen = false },
            )
        }

        // R75 - the Spotlight search palette, TOPMOST (web main-shell z-90,
        // above everything incl. the dock); People rows open real DMs.
        if (spotlightOpen) {
            MirrorSpotlightOverlay(
                conversations = conversations,
                viewerId = viewerId.orEmpty(),
                repository = repository,
                onOpenConversation = { conversationId ->
                    spotlightOpen = false
                    openById(conversationId)
                },
                // R76 - web message rows anchor the room at the hit
                // (spotlight.tsx:396 onOpenConversation(convId, null, hit.id))
                onOpenMessage = { conversationId, messageId ->
                    spotlightOpen = false
                    openById(conversationId, messageId)
                },
                onOpenDm = { userId ->
                    spotlightOpen = false
                    openPersonalChat(userId)
                },
                onNewChat = {
                    spotlightOpen = false
                    newChatOpen = true
                },
                onCheckInToHub = { tab = MirrorTab.Hub },
                darkResolved = darkResolved,
                onToggleTheme = {
                    session.setDarkOverride(if (darkResolved) "light" else "dark")
                },
                onDismiss = { spotlightOpen = false },
            )
        }
        if (roomOpenRequest != null) {
            Text("Opening chat…", color = MirrorArt.TextSoft, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
                    .background(MirrorArt.BubbleIn, RoundedCornerShape(12.dp)).padding(12.dp))
        }
    }
}

/**
 * art-scene (globals.css .art-scene, verbatim): the linear wash + TWO
 * elliptical horizon glows + the ::before breathing glow. The R62 port drew
 * the glows through a pivot scale that flung their centers far off-screen,
 * so the phone showed a flat wash while the web bloomed - fixed here by
 * translating to the glow center first, then scaling the unit circle.
 */
private fun MirrorScene(scope: DrawScope, breatheAlpha: Float) {
    // linear-gradient(180deg, #2b1c10 0%, #241609 30%, #170e07 58%, #0d0906 92%)
    scope.drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF2B1C10),
            0.30f to Color(0xFF241609),
            0.58f to Color(0xFF170E07),
            0.92f to Color(0xFF0D0906),
            1f to Color(0xFF0D0906),
        ),
    )
    // radial-gradient(135% 44% at 50% 63%, glow1 0%, transparent 62%)
    scope.sceneGlow(
        core = Color(0x70BE6826),
        cx = scope.size.width / 2f,
        cy = scope.size.height * 0.63f,
        rx = scope.size.width * 1.35f,
        ry = scope.size.height * 0.44f,
        fadeStop = 0.62f,
    )
    // radial-gradient(170% 64% at 50% 70%, glow2 0%, transparent 72%)
    scope.sceneGlow(
        core = Color(0x458A481C),
        cx = scope.size.width / 2f,
        cy = scope.size.height * 0.70f,
        rx = scope.size.width * 1.70f,
        ry = scope.size.height * 0.64f,
        fadeStop = 0.72f,
    )
    // .art-scene::before: radial-gradient(100% 34% at 50% 63%, glow1 0%, transparent 58%)
    scope.sceneGlow(
        core = Color(0x70BE6826),
        cx = scope.size.width / 2f,
        cy = scope.size.height * 0.63f,
        rx = scope.size.width,
        ry = scope.size.height * 0.34f,
        fadeStop = 0.58f,
        alpha = breatheAlpha,
    )
}

/**
 * One elliptical radial glow with CSS-exact stops: translate to the glow
 * center, scale a unit radial circle to (rx, ry), fade to transparent at
 * [fadeStop] (the web gradient's percentage radius).
 */
internal fun DrawScope.sceneGlow(
    core: Color,
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    fadeStop: Float,
    alpha: Float = 1f,
) {
    if (rx <= 0f || ry <= 0f) return
    withTransform({
        translate(cx, cy)
        scale(scaleX = rx, scaleY = ry)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                *arrayOf(
                    0f to core.copy(alpha = core.alpha * alpha),
                    fadeStop to core.copy(alpha = 0f),
                    1f to Color.Transparent,
                ),
                center = Offset.Zero,
                radius = 1f,
            ),
            radius = 1f,
            center = Offset.Zero,
        )
    }
}

/** Room scaffold: title/avatar live from the conversation, messages from the cache. */
@Composable
private fun MirrorRoomScaffold(
    convo: Conversation,
    readingEnabled: Boolean,
    accountRouter: PulseRepository,
    viewerId: String,
    presence: Set<String>,
    typers: List<String>,
    pipActive: Boolean,
    onTogglePip: () -> Unit,
    onClose: () -> Unit,
    onRoomInfo: () -> Unit,
    onManageGroup: () -> Unit,
    onStartCall: ((Conversation, video: Boolean) -> Unit)?,
    onOpenLiveRoom: (String, app.pulse.feature.voice.VoiceRoomsEngine.RoomKind) -> Unit,
    /** R74 - web prefs.reducedMotion gate for the full-screen effects layer. */
    reducedMotion: Boolean = false,
    /** R76 - web jumpToMessage: open anchored at one message. */
    jumpMessageId: String? = null,
    jumpRequestId: String? = null,
    onOpenReminder: (MirrorReminderTarget) -> Unit,
    onJumpConsumed: () -> Unit = {},
) {
    val repository = remember(accountRouter, viewerId, convo.id, accountRouter.attachmentSessionKey()) { accountRouter.boundToCurrentAccount() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val visible by androidx.compose.runtime.rememberUpdatedState(readingEnabled)
    val messages by repository.observeRiverMessages(convo.id).collectAsStateWithLifecycle(initialValue = emptyList<Message>())
    var phrases by remember { mutableStateOf<List<QuickPhrase>>(emptyList()) }
    var muted by remember { mutableStateOf(convo.isMuted) }
    var ttlSeconds by remember { mutableStateOf<Int?>(null) }
    var settingsBusy by remember { mutableStateOf(false) }
    var settingsRevision by remember { mutableStateOf(0L) }
    var settingsReadTicket by remember { mutableStateOf(0L) }
    // Covering/reopening the room can include a privacy mutation in room info.
    // Do not drop protection until a fresh owned detail confirms both flags off.
    var screenPrivacy by remember(repository, convo.id, readingEnabled) { mutableStateOf<Boolean?>(null) }
    val personalPrivacy by androidx.compose.runtime.key(repository, convo.id, repository.attachmentSessionKey()) {
        remember { repository.observePersonalScreenPrivacy(convo.id) }.collectAsStateWithLifecycle(
            initialValue = app.pulse.domain.model.PersonalScreenPrivacy())
    }
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    var phraseBusy by remember { mutableStateOf(false) }
    var phraseRevision by remember { mutableStateOf(0L) }
    var typingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun notice(message: String) {
        if (visible) android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }

    suspend fun refreshSettings() {
        val revision = settingsRevision
        val readTicket = ++settingsReadTicket
        val epoch = repository.attachmentSessionKey() ?: return
        if (repository.viewerId != viewerId) return
        try {
            val meta = withContext(Dispatchers.IO) { repository.groupMeta(convo.id).getOrThrow() }
            currentCoroutineContext().ensureActive()
            if (visible && settingsRevision == revision && settingsReadTicket == readTicket && !settingsBusy &&
                repository.viewerId == viewerId && repository.attachmentSessionKey() == epoch) {
                ttlSeconds = meta.ttlSeconds
                screenPrivacy = meta.screenPrivacy
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Retain last known truth; unknown is not Off.
        }
    }

    fun <T> updateSetting(request: suspend () -> Result<T>, apply: (T) -> Unit) {
        if (!visible || settingsBusy) return
        settingsBusy = true
        settingsRevision++ // invalidate detail fetched before this write
        scope.launch {
            try {
                val applied = withContext(Dispatchers.IO) { request().getOrThrow() }
                currentCoroutineContext().ensureActive()
                settingsRevision++
                if (visible) apply(applied)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                settingsRevision++
                notice(error.message ?: "Couldn't confirm the change. Check room info before retrying.")
            } finally { settingsBusy = false }
            if (visible) refreshSettings()
        }
    }

    fun updatePhrase(text: String? = null, removeId: String? = null) {
        if (!visible || phraseBusy) return
        phraseBusy = true; phraseRevision++
        scope.launch {
            try {
                if (text != null) {
                    val added = withContext(Dispatchers.IO) { repository.addPhrase(text).getOrThrow() }
                    currentCoroutineContext().ensureActive()
                    phraseRevision++
                    if (visible) phrases = phrases.filterNot { it.id == added.id } + added
                } else if (removeId != null) {
                    withContext(Dispatchers.IO) { repository.deletePhrase(removeId).getOrThrow() }
                    currentCoroutineContext().ensureActive()
                    phraseRevision++
                    if (visible) phrases = phrases.filterNot { it.id == removeId }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(error.message ?: "Couldn't confirm the quick-phrase change")
            } finally { phraseBusy = false }
        }
    }

    LaunchedEffect(convo.isMuted) { muted = convo.isMuted }
    LaunchedEffect(repository, convo.id, readingEnabled) {
        if (!readingEnabled) return@LaunchedEffect
        val revision = phraseRevision
        try {
            val loaded = withContext(Dispatchers.IO) { repository.phrases().getOrThrow() }
            currentCoroutineContext().ensureActive()
            if (visible && phraseRevision == revision && !phraseBusy) phrases = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed refresh must not erase known phrases.
        }
    }

    LaunchedEffect(repository, convo.id, readingEnabled, windowFocused) {
        if (!readingEnabled || !windowFocused) return@LaunchedEffect
        while (true) {
            refreshSettings()
            kotlinx.coroutines.delay(20_000)
        }
    }

    val other = convo.members.firstOrNull { it.id != viewerId }
    // R54-c subtitle truth: groups roll the member names, DMs the presence line
    val subtitle = when {
        convo.isGroupish -> convo.memberNames.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Group" }
        other != null && presence.contains(other.id) -> "online"
        other != null -> "offline"
        else -> ""
    }

    // A different account/gateway session must not inherit a remembered
    // caption, picker callback, reply target or room-owned coroutine.
    androidx.compose.runtime.key(repository, convo.id, viewerId, repository.attachmentSessionKey()) {
        MirrorRoomSecurity(enabled = screenPrivacy != false || personalPrivacy.on != false) {
            MirrorRoom(
                onOpenLiveRoom = { kind -> onOpenLiveRoom(convo.id, kind) },
                readingEnabled = readingEnabled,
                title = convo.title,
                color = convo.accentColor ?: other?.color,
                isGroup = convo.isGroupish,
                groupId = convo.id,
                subtitle = subtitle,
                typers = typers,
                messages = messages,
                viewerId = viewerId,
                viewerName = convo.members.firstOrNull { it.id == viewerId }?.name.orEmpty(),
                repository = repository,
                viewerColor = convo.members.firstOrNull { it.id == viewerId }?.color,
                memberNames = convo.members.map { it.name },
                members = convo.members,
                phrases = phrases,
                muted = muted,
                ttlSeconds = ttlSeconds,
                // R76 - the jump anchor flows through the scaffold into the room
                jumpMessageId = jumpMessageId,
                jumpRequestId = jumpRequestId,
                onOpenReminder = onOpenReminder,
                onJumpConsumed = onJumpConsumed,
                // R72 - channel truth: broadcast rooms carry the Channel pill and the
                // locked composer for non-admin viewers (web broadcastLocked parity)
                isBroadcast = convo.kind == Conversation.Kind.CHANNEL,
                avatarPath = convo.avatar,
                onBack = onClose,
                onAddPhrase = { text -> updatePhrase(text = text) },
                onDeletePhrase = { phraseId -> updatePhrase(removeId = phraseId) },
                onTyping = { typing ->
                    typingJob?.cancel()
                    typingJob = scope.launch {
                        try {
                            repository.setTyping(convo.id, convo.members.firstOrNull { it.id == viewerId }?.name.orEmpty(), typing)
                        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* ephemeral typing expires on the relay */ }
                    }
                },
                onCall = { video ->
                    // R64 - native calls wired through the same engines the shell uses
                    onStartCall?.invoke(convo, video)
                },
                onRoomInfo = onRoomInfo,
                onManageGroup = onManageGroup,
                roomSettingsBusy = settingsBusy,
                onMuteChoice = { until ->
                    updateSetting(request = { repository.setMutedUntil(convo.id, until) }, apply = { muted = until != null })
                },
                onTtlChoice = { ttl ->
                    updateSetting(request = { repository.setDisappearingTtl(convo.id, ttl) }, apply = { ttlSeconds = it })
                },
                onUnpinMessage = { messageId -> repository.unpinMessage(messageId) },
                fetchPinned = { repository.pinnedMessages(convo.id) },
                pipActive = pipActive,
                onTogglePip = onTogglePip,
            )
        }
    }
}

/**
 * R69 - the web mini chat window (PiP): a floating compact room card that
 * lives over any tab while the kebab row keeps it open. Live messages from
 * the repository cache, send wired to the real send pipeline, expand opens
 * the room and the X closes the window (web closePipChat).
 */
@Composable
private fun MirrorPipChat(
    convo: Conversation,
    repository: PulseRepository,
    viewerId: String,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages by repository.observeRiverMessages(convo.id).collectAsStateWithLifecycle(initialValue = emptyList<Message>())
    val epoch = repository.attachmentSessionKey()
    val target = remember(repository,viewerId,epoch) { repository.boundToCurrentAccount() }
    val sendScope = androidx.compose.runtime.rememberCoroutineScope()
    var draft by remember(convo.id,viewerId,epoch) { mutableStateOf("") }
    var revision by remember(convo.id,viewerId,epoch) { mutableStateOf(0L) }
    var sending by remember(convo.id,viewerId,epoch) { mutableStateOf(false) }
    var sendError by remember(convo.id,viewerId,epoch) { mutableStateOf<String?>(null) }
    LaunchedEffect(convo.id,viewerId,epoch) {
        val captured = revision
        val local = runCatching { target.observeDraft(convo.id).first() }.getOrNull()
        if (revision == captured && local != null) draft = local
        val remote = target.refreshComposerDraft(convo.id).getOrNull()
        if (revision == captured && remote != null) draft = remote
    }
    // R72 - channel truth: the pip window cannot send into a broadcast room
    // either (the server would 403 - mirror the locked composer honestly)
    val pipLocked = convo.isGroupish &&
        convo.kind == Conversation.Kind.CHANNEL &&
        convo.members.none { it.id == viewerId && it.role == "admin" }
    Column(
        modifier
            .width(272.dp)
            .heightIn(max = 336.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF21C1610))
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(18.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MirrorArt.Panel)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                convo.title,
                color = MirrorArt.Text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Expand",
                color = MirrorArt.Accent2,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                "Close",
                color = MirrorArt.Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 240.dp),
        ) {
            items(messages.takeLast(10)) { message ->
                val line = when {
                    message.deletedAt != null -> "Message deleted"
                    message.imagePath != null -> "Photo"
                    message.durationMs != null -> "Voice message"
                    else -> message.body
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (convo.isGroupish && message.authorName.isNotBlank()) {
                        Text(
                            // Section-5 anonymity audit: glance cards obey the anon mask.
                            message.displayAuthorName,
                            color = MirrorArt.Accent2,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        line,
                        color = if (message.authorId == viewerId) MirrorArt.TextSoft else MirrorArt.Text,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        sendError?.let { Text(it,color=MirrorArt.Dim,fontSize=11.sp,modifier=Modifier.padding(horizontal=10.dp)) }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            if (pipLocked) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MirrorArt.White7)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    MirrorLucideIcon("LLock", tint = MirrorArt.Accent2, modifier = Modifier.size(14.dp))
                    Text("Only admins can post", color = MirrorArt.Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it; revision++; target.stageDraft(convo.id,it) },
                singleLine = true,
                textStyle = TextStyle(color = MirrorArt.Text, fontSize = 12.sp),
                cursorBrush = SolidColor(MirrorArt.Accent2),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MirrorArt.White7)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    if (draft.isBlank()) {
                        Text("Type here", color = MirrorArt.Faint, fontSize = 12.sp)
                    }
                    inner()
                },
            )
            Text(
                "Send",
                color = if (draft.isNotBlank()) MirrorArt.Accent2 else MirrorArt.Faint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = draft.isNotBlank() && !sending) {
                        val text = draft.trim()
                        val captured = revision
                        val token = target.captureDraftToken(convo.id)
                        sending = true
                        sendScope.launch {
                            try {
                                val result = target.sendMessage(convo.id,text,draftToken=token)
                                if (epoch != target.attachmentSessionKey()) return@launch
                                result.onSuccess { if (revision == captured) draft = ""; sendError = null }
                                    .onFailure { sendError = it.message ?: "Message was not confirmed. Your text is retained." }
                            } finally { sending = false }
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            }
        }
    }
}

private fun Conversation.toRow(presence: Set<String>, viewerId: String?): ConversationRow {
    val other = members.firstOrNull { it.id != viewerId }
    val prefix = when {
        lastMessageMine -> "You: "
        isGroupish && lastMessageAuthorName != null -> "$lastMessageAuthorName: "
        else -> ""
    }
    val basePreview = lastMessagePreview ?: "No messages yet"
    val preview = when {
        lastMessageDeleted -> "Message deleted"
        lastMessageIsImage -> "Photo"
        lastMessageIsAudio -> "Voice message"
        lastMessageIsFile -> lastMessageFileName ?: "File"
        else -> basePreview
    }
    return ConversationRow(
        id = id,
        title = title,
        color = accentColor ?: other?.color,
        isGroup = isGroupish,
        preview = preview,
        previewPrefix = prefix,
        previewDeleted = lastMessageDeleted,
        time = MirrorRowTime(lastActivityAt),
        unread = unreadCount,
        online = otherUserId != null && presence.contains(otherUserId),
        pinned = isPinned,
        muted = isMuted,
        streak = streakCount,
        // live typing rides MirrorHome's typingIds (row.copy below)
        typing = false,
        draft = myDraft?.takeIf { it.isNotBlank() },
        manualUnread = myManualUnread,
    )
}
