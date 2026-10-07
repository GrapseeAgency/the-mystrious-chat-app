package app.pulse.android.mirror

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
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
    onStartCall: ((Conversation, video: Boolean) -> Unit)? = null,
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
    /** R78 - system back exit hook: double-tap back at the shell root
     *  finishes the activity (WhatsApp/Telegram exit contract). */
    onExit: () -> Unit = {},
) {
    val viewerId by session.viewerId.collectAsState()
    val viewerName by session.viewerName.collectAsState()
    // R78 - the LIVE navigation style (web useNavStyle) - hoisted: both the
    // shell content and the room overlay need the rail zone exception.
    val navStyle by session.navStyle.collectAsState()
    val navRail = navStyle == app.pulse.protocol.PulseNavStyle.RAIL
    val conversations by repository.observeConversations().collectAsState(initial = emptyList())
    val presence by repository.observePresence().collectAsState(initial = emptySet())
    var tab by remember { mutableStateOf(MirrorTab.Chats) }
    var openRoom by remember { mutableStateOf<Conversation?>(null) }
    // R73 - keeps the room composed while the close slide-out plays
    // (openRoom flips null immediately; the exit animation needs the convo).
    var roomShown by remember { mutableStateOf<Conversation?>(null) }
    // R76 - jump-to-message anchor: Spotlight message hits, home search hits
    // and pulse://room/<id>?jump=<mid> deep links all open the room ANCHORED
    // at one message (web jumpToMessage parity).
    var pendingJumpId by remember { mutableStateOf<String?>(null) }

    // R76 - launcher tab shortcuts switch tabs cold or warm, then self-consume.
    LaunchedEffect(tabRequest) {
        val request = tabRequest ?: return@LaunchedEffect
        tab = request
        onConsumeTabRequest()
    }
    // R76 - pulse://room deep links open the conversation once the live cache
    // knows it (a cold link races the first REST load - the effect re-runs on
    // every cache emission until the id resolves), then self-consume. A jump
    // payload rides along and anchors the room at the message.
    LaunchedEffect(pendingRoomId, conversations) {
        val id = pendingRoomId ?: return@LaunchedEffect
        val convo = conversations.firstOrNull { it.id == id } ?: return@LaunchedEffect
        openRoom = convo
        if (pendingJumpMessageId != null) {
            pendingJumpId = pendingJumpMessageId
            onConsumePendingJumpMessage()
        }
        onConsumePendingRoom()
    }
    var stories by remember { mutableStateOf<List<StoryGroup>>(emptyList()) }
    var folders by remember { mutableStateOf<List<MirrorFolderChip>>(emptyList()) }

    // R72 - the settings "Default list filter" row now DRIVES this filter
    // (web chats-tab.tsx:795 applies the persisted listFilter on load).
    var filter by remember { mutableStateOf(MirrorFilter.All) }
    var filterSeeded by remember { mutableStateOf(false) }
    val savedListFilter by session.chatsListFilter.collectAsState()
    // R74 - web prefs.reducedMotion: gates the full-screen message effects
    val reducedMotion by session.reducedMotion.collectAsState()
    // R75 - Spotlight theme action needs the RESOLVED dark state (web resolvedTheme)
    val darkOverride by session.darkOverride.collectAsState()
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
    var viewingStory by remember { mutableStateOf<StoryGroup?>(null) }

    // R64 - kebab destinations + row options (all REAL repository-backed)
    var groupOpen by remember { mutableStateOf(false) }
    var joinOpen by remember { mutableStateOf(false) }
    var archivedOpen by remember { mutableStateOf(false) }
    var mentionsOpen by remember { mutableStateOf(false) }
    var foldersOpen by remember { mutableStateOf(false) }
    var mentionCount by remember { mutableStateOf(0) }
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
    var profileSavedOpen by remember { mutableStateOf(false) }

    // R78 - THE SYSTEM BACK CONTRACT (user report: "the app doesn't listen to
    // the phone's back button - it just exits. Back should go to where the
    // user came from; double-tap back on the home page exits").
    // Compose resolves the LAST enabled BackHandler in composition order, so
    // this chain is composed lowest-priority-first: the root double-tap-exit
    // fires only when every surface above it is closed; each surface's own
    // handler consumes back while it is visible - exactly WhatsApp/Telegram.
    val backContext = LocalContext.current
    var lastBackAt by remember { mutableStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2_000L) {
            onExit()
        } else {
            lastBackAt = now
            Toast.makeText(backContext, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }
    // shell-level surfaces (composing order = back priority order)
    BackHandler(enabled = searching) {
        searching = false
        searchQuery = ""
    }
    BackHandler(enabled = viewingStory != null) { viewingStory = null }
    BackHandler(enabled = callsOpen) { callsOpen = false }
    BackHandler(enabled = channelsOpen) { channelsOpen = false }
    BackHandler(enabled = kebabOpen) { kebabOpen = false }
    BackHandler(enabled = composerOpen) { composerOpen = false }
    BackHandler(enabled = newChatOpen) { newChatOpen = false }
    BackHandler(enabled = groupOpen) { groupOpen = false }
    BackHandler(enabled = joinOpen) { joinOpen = false }
    BackHandler(enabled = archivedOpen) { archivedOpen = false }
    BackHandler(enabled = mentionsOpen) { mentionsOpen = false }
    BackHandler(enabled = foldersOpen) { foldersOpen = false }
    BackHandler(enabled = rowOptions != null) { rowOptions = null }
    // the room covers the shell + the dock; its internal overlays (kebab,
    // tray, action panels) register their own handlers inside MirrorRoom,
    // which compose after this one and therefore win first
    BackHandler(enabled = openRoom != null) { openRoom = null }
    // full pages layered above the room
    BackHandler(enabled = roomInfoFor != null) { roomInfoFor = null }
    BackHandler(enabled = managerFor != null) { managerFor = null }
    BackHandler(enabled = userPageFor != null) { userPageFor = null }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = profileEditOpen) { profileEditOpen = false }
    BackHandler(enabled = profileSavedOpen) { profileSavedOpen = false }
    // Spotlight is the topmost shell surface (web z-90)
    BackHandler(enabled = spotlightOpen) { spotlightOpen = false }

    // R64 - typing state: relay events - per-conversation typer list (4s TTL).
    var typers by remember { mutableStateOf<List<MirrorTyper>>(emptyList()) }
    LaunchedEffect(viewerId) {
        if (viewerId == null) return@LaunchedEffect
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
                } else if (event is PulseEvent.MessageReceived) {
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

    // Refresh rhythm: conversations + stories + folders + mentions on mount and every 5s.
    LaunchedEffect(viewerId) {
        while (true) {
            if (viewerId != null) {
                runCatching { repository.refreshConversations() }
                stories = repository.stories().getOrDefault(emptyList())
                folders = repository.folders().getOrDefault(emptyList()).map { f ->
                    MirrorFolderChip(
                        id = f.id,
                        name = f.name,
                        emoji = f.emoji,
                        count = f.conversationIds.count { cid -> conversations.any { it.id == cid } },
                        conversationIds = f.conversationIds,
                    )
                }
                mentionCount = repository.mentions().getOrDefault(emptyList()).size
            }
            delay(5_000)
        }
    }

    // Web parity: the main list EXCLUDES archived chats (they live in the menu)
    // and the Note to Self chat (web R24-a - isSelf rows never render at home).
    val activeConversations = conversations.filter { !it.isArchived && !it.isSelf }
    val archivedConversations = conversations.filter { it.isArchived }

    fun openById(conversationId: String) {
        conversations.firstOrNull { it.id == conversationId }?.let { openRoom = it }
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
                viewingStory?.let { group ->
                    MirrorStoryViewer(group = group, repository = repository, onDismiss = { viewingStory = null })
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
                // R78 - the rail insets the content column 68dp (web w-[68px]).
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = if (navRail) 68.dp else 0.dp),
                ) {
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
                                if (jumpId != null) pendingJumpId = jumpId
                                openById(conversationId)
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
                        MirrorProfile(
                            viewerId = viewerId.orEmpty(),
                            repository = repository,
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
                } // end rail-inset content Box

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
                if (archivedOpen) {
                    // R78 - the kebab Archived destination is a FULL PAGE
                    // (web chats-archived-page.tsx), composed before the dock
                    // so the dock stays reachable like the calls/channels pages.
                    MirrorArchivedPage(
                        archived = archivedConversations,
                        presence = presence,
                        viewerId = viewerId.orEmpty(),
                        onOpen = {
                            archivedOpen = false
                            openRoom = it
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
                    // R78 - the kebab Mentions destination is a FULL PAGE
                    // (web mentions-page.tsx), composed before the dock.
                    MirrorMentionsPage(
                        repository = repository,
                        onOpenConv = {
                            mentionsOpen = false
                            openById(it)
                        },
                        onClose = { mentionsOpen = false },
                    )
                }

                // R78 - the web unmounts the nav while a surface owns the screen
                // (main-shell.tsx:515-527: openConversationId === null &&
                // navZone !== 'side' && !settingsVisible && !sheetMounted).
                // The rail is the side-zone exception: it STAYS visible while a
                // room is open (web renders it beside the room). The capsule and
                // the 12 other architectures render through MirrorNavRouter -
                // the LIVE style the settings picker persists.
                AnimatedVisibility(
                    visible = navRail || (openRoom == null && !settingsOpen && !profileEditOpen &&
                        !newChatOpen && !groupOpen && !joinOpen && !composerOpen),
                    enter = slideInVertically(MirrorMotion.soft()) { it } + fadeIn(tween(160)),
                    exit = slideOutVertically(tween(190, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(140)),
                ) {
                    // BoxScope host: the style renderers align themselves to
                    // their own zone (top/bottom/side/overlay) inside this Box.
                    Box(Modifier.fillMaxSize()) {
                        MirrorNavRouter(
                            navStyle = navStyle,
                            activeTab = tab,
                            unread = activeConversations.sumOf { it.unreadCount },
                            onTab = { tab = it },
                            // web: the Calls slot opens the zinc-900 calls sub-page, tab stays
                            onCalls = { callsOpen = true },
                            onFab = { newChatOpen = true },
                            // web NavOverflowButton / command-bar trailing dot → the kebab
                            onKebab = { kebabOpen = true },
                            // web command-bar search → the Spotlight palette
                            onSearch = { spotlightOpen = true },
                        )
                    }
                }

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
                        archivedUnread = archivedConversations.sumOf { it.unreadCount },
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
                        onNoteToSelf = {
                            // web handleSelfPress: open the existing self chat or create it
                            val self = conversations.firstOrNull { it.isSelf }
                            if (self != null) {
                                openRoom = self
                            } else {
                                val id = viewerId.orEmpty()
                                if (id.isNotBlank()) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        repository.createDm(id).onSuccess { created ->
                                            runCatching { repository.refreshConversations() }
                                            kotlinx.coroutines.withContext(Dispatchers.Main) { openRoom = created }
                                        }
                                    }
                                }
                            }
                        },
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
                            CoroutineScope(Dispatchers.IO).launch {
                                stories = repository.stories().getOrDefault(emptyList())
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
        // R78 - rail exception: the room overlays the CONTENT column, not
        // the rail (web main-shell renders the rail beside the room).
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = if (navRail && openRoom != null) 68.dp else 0.dp),
        ) {
        AnimatedVisibility(
            visible = viewingStory == null && openRoom != null,
            enter = slideInVertically(MirrorMotion.soft()) { it } + fadeIn(tween(90)),
            exit = slideOutVertically(tween(210, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(150)),
        ) {
            roomShown?.let { convo ->
                val roomTypers = typers
                    .filter { it.conversationId == convo.id }
                    .map { it.userName }
                MirrorRoomScaffold(
                    convo = convo,
                    repository = repository,
                    viewerId = viewerId.orEmpty(),
                    presence = presence,
                    typers = roomTypers,
                    pipActive = pipFor?.id == convo.id,
                    onTogglePip = {
                        pipFor = if (pipFor?.id == convo.id) null else convo
                    },
                    onClose = { openRoom = null },
                    onRoomInfo = { roomInfoFor = convo },
                    onManageGroup = { managerFor = convo },
                    onStartCall = onStartCall,
                    reducedMotion = reducedMotion,
                    jumpMessageId = pendingJumpId,
                    onJumpConsumed = { pendingJumpId = null },
                )
            }
        }
        } // end rail-inset room Box

        if (roomInfoFor != null) {
            val convo = roomInfoFor!!
            // R70 - the web RoomInfoPage parity (hero, stats, actions,
            // automations, encryption, members) over the real repository.
            MirrorRoomInfoPage(
                conversationId = convo.id,
                viewerId = viewerId.orEmpty(),
                viewerName = viewerName.orEmpty(),
                repository = repository,
                presence = presence,
                onChanged = {
                    CoroutineScope(Dispatchers.IO).launch { runCatching { repository.refreshConversations() } }
                },
                onDismiss = { roomInfoFor = null },
                onOpenManager = {
                    managerFor = roomInfoFor
                    roomInfoFor = null
                },
                onOpenUser = {
                    roomInfoFor = null
                    userPageFor = it
                },
            )
        }
        if (managerFor != null) {
            val convo = managerFor!!
            // R70 - the web GroupInfoSheet parity (webhooks, leaderboard,
            // tournaments, role actions, rename/leave).
            MirrorGroupManagerSheet(
                conversationId = convo.id,
                viewerId = viewerId.orEmpty(),
                viewerName = viewerName.orEmpty(),
                repository = repository,
                presence = presence,
                onChanged = {
                    CoroutineScope(Dispatchers.IO).launch { runCatching { repository.refreshConversations() } }
                },
                onDismiss = { managerFor = null },
                onOpenUser = {
                    managerFor = null
                    userPageFor = it
                },
            )
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
            MirrorEditProfilePage(
                repository = repository,
                viewerId = viewerId.orEmpty(),
                onSaved = { profileEditOpen = false },
                onDismiss = { profileEditOpen = false },
            )
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
                    pendingJumpId = messageId
                    openById(conversationId)
                },
                onOpenDm = { userId ->
                    // web onOpenDm (main-shell.tsx:377): open or lazily create the 1:1
                    spotlightOpen = false
                    val id = viewerId.orEmpty()
                    if (id.isNotBlank()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            repository.createDm(userId).onSuccess { created ->
                                runCatching { repository.refreshConversations() }
                                kotlinx.coroutines.withContext(Dispatchers.Main) { openRoom = created }
                            }
                        }
                    }
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
    }
}

/**
 * art-scene (globals.css .art-scene, verbatim): the linear wash + TWO
 * elliptical horizon glows + the ::before breathing glow. The R62 port drew
 * the glows through a pivot scale that flung their centers far off-screen,
 * so the phone showed a flat wash while the web bloomed - fixed here by
 * translating to the glow center first, then scaling the unit circle.
 */
internal fun MirrorScene(scope: DrawScope, breatheAlpha: Float) {
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
    repository: PulseRepository,
    viewerId: String,
    presence: Set<String>,
    typers: List<String>,
    pipActive: Boolean,
    onTogglePip: () -> Unit,
    onClose: () -> Unit,
    onRoomInfo: () -> Unit,
    onManageGroup: () -> Unit,
    onStartCall: ((Conversation, video: Boolean) -> Unit)?,
    /** R74 - web prefs.reducedMotion gate for the full-screen effects layer. */
    reducedMotion: Boolean = false,
    /** R76 - web jumpToMessage: open anchored at one message. */
    jumpMessageId: String? = null,
    onJumpConsumed: () -> Unit = {},
) {
    val messages by repository.observeMessages(convo.id).collectAsState(initial = emptyList<Message>())
    var phrases by remember { mutableStateOf<List<QuickPhrase>>(emptyList()) }
    var muted by remember { mutableStateOf(convo.isMuted) }
    var ttlSeconds by remember { mutableStateOf(0) }

    LaunchedEffect(convo.id) {
        runCatching { repository.refreshMessages(convo.id) }
        phrases = repository.phrases().getOrDefault(emptyList())
        // entering the room marks it read (web POST /read on open)
        runCatching { repository.markRead(convo.id) }
        // live room truth for the kebab controls (mute + disappearing TTL)
        runCatching { repository.conversationDetail(convo.id) }
    }

    val other = convo.members.firstOrNull { it.id != viewerId }
    // R54-c subtitle truth: groups roll the member names, DMs the presence line
    val subtitle = when {
        convo.isGroupish -> convo.memberNames.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Group" }
        other != null && presence.contains(other.id) -> "online"
        other != null -> "offline"
        else -> ""
    }

    MirrorRoom(
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
        onJumpConsumed = onJumpConsumed,
        // R72 - channel truth: broadcast rooms carry the Channel pill and the
        // locked composer for non-admin viewers (web broadcastLocked parity)
        isBroadcast = convo.kind == Conversation.Kind.CHANNEL,
        avatarPath = convo.avatar,
        onBack = onClose,
        onToggleReaction = { messageId, emoji ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.react(messageId, emoji) }
            }
        },
        onAddPhrase = { text ->
            CoroutineScope(Dispatchers.IO).launch {
                repository.addPhrase(text).onSuccess { phrases = repository.phrases().getOrDefault(emptyList()) }
            }
        },
        onDeletePhrase = { phraseId ->
            CoroutineScope(Dispatchers.IO).launch {
                repository.deletePhrase(phraseId).onSuccess { phrases = repository.phrases().getOrDefault(emptyList()) }
            }
        },
        onTyping = { typing ->
            // web signalTyping/cancelTyping - the relay fans out to members
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.setTyping(convo.id, convo.members.firstOrNull { it.id == viewerId }?.name.orEmpty(), typing) }
            }
        },
        onCall = { video ->
            // R64 - native calls wired through the same engines the shell uses
            onStartCall?.invoke(convo, video)
        },
        onRoomInfo = onRoomInfo,
        onManageGroup = onManageGroup,
        onMuteChoice = { until ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    // R70 fix: the wire /mute route ONLY accepts the preset
                    // strings ("8h" | "1w" | "always" | null) - the old ISO
                    // stamp body was rejected 400 and mute silently failed.
                    repository.setMutedUntil(convo.id, until)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        muted = until != null
                    }
                }
            }
        },
        onTtlChoice = { ttl ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val applied = repository.setDisappearingTtl(convo.id, ttl).getOrDefault(ttl)
                    kotlinx.coroutines.withContext(Dispatchers.Main) { ttlSeconds = applied }
                }
            }
        },
        onUnpinMessage = { messageId ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.toggleMessagePin(messageId) }
            }
        },
        fetchPinned = {
            runCatching { repository.pinnedMessages(convo.id) }.getOrDefault(emptyList())
        },
        pipActive = pipActive,
        onTogglePip = onTogglePip,
    )
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
    val messages by repository.observeMessages(convo.id).collectAsState(initial = emptyList<Message>())
    var draft by remember { mutableStateOf("") }
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
                            message.authorName,
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
                onValueChange = { draft = it },
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
                    .clickable(enabled = draft.isNotBlank()) {
                        val text = draft.trim()
                        draft = ""
                        if (viewerId.isNotBlank() && text.isNotEmpty()) {
                            CoroutineScope(Dispatchers.IO).launch {
                                runCatching { repository.sendMessage(convo.id, text) }
                            }
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
