package app.pulse.feature.hub

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pulse.core.fx.PulseFx
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.HubLogDto
import app.pulse.protocol.HubTaskDto
import app.pulse.protocol.HubTasksPageDto
import app.pulse.protocol.LedgerEntryDto
import app.pulse.protocol.MarketListingDto
import app.pulse.protocol.PulseJson
import app.pulse.protocol.SwapPageDto
import app.pulse.protocol.WalletPageDto
import app.pulse.ui.PulsePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wave 7 F-HB-01…10 — the LIVE hub. Replaces the Wave-0 static tile grid:
 * real wallet + ledger, daily check-in (+25 PC, +2/day streak bonus capped
 * +20), @handle transfers, PC⇄GEM swap (100/80 rates), personal tasks,
 * market with atomic buy, logs stream, the 100-app matrix (bundled catalog
 * JSON — browsable offline) with install/connect, app communities
 * (auto-provisioned real conversations) and My apps (fan-out install state
 * — web hub-data.tsx parity).
 */

// Bundled catalog (generated from src/lib/hub-catalog.ts — tools/gen-hub-catalog.mjs).
@kotlinx.serialization.Serializable
data class HubCatalogApp(
    val n: Int = 0,
    val name: String = "",
    val nav: String = "",
    val input: String = "",
    val category: String = "",
    val secret: String = "",
)

@kotlinx.serialization.Serializable
data class HubCatalog(
    val apps: List<HubCatalogApp> = emptyList(),
    val categories: List<CategoryMeta> = emptyList(),
    val taglines: Map<String, String> = emptyMap(),
) {
    @kotlinx.serialization.Serializable
    data class CategoryMeta(
        val slug: String = "",
        val label: String = "",
        val blurb: String = "",
        val from: String = "",
        val to: String = "",
    )
}

object HubCatalogLoader {
    @Volatile private var cached: HubCatalog? = null

    fun load(context: android.content.Context): HubCatalog {
        cached?.let { return it }
        val parsed = runCatching {
            val json = context.assets.open("hub_catalog.json").bufferedReader().use { it.readText() }
            PulseJson.decodeFromString(HubCatalog.serializer(), json)
        }.getOrDefault(HubCatalog())
        cached = parsed
        return parsed
    }
}

@HiltViewModel
class HubViewModel @Inject constructor(
    private val repo: PulseRepository,
) : ViewModel() {

    data class WalletUi(
        val loading: Boolean = false,
        val page: WalletPageDto? = null,
        val stale: Boolean = false,
        val error: String? = null,
    )

    private val _wallet = MutableStateFlow(WalletUi())
    val wallet: StateFlow<WalletUi> = _wallet.asStateFlow()

    data class AppInstallRow(val appId: String, val installed: Boolean, val installs: Int)

    private val _installs = MutableStateFlow<Map<String, AppInstallRow>>(emptyMap())
    val installs: StateFlow<Map<String, AppInstallRow>> = _installs.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * R1-W2H D38 — one-shot check-in success event (carries the reward). The
     * screen turns it into the F-HB-02 celebration; never replayed to a
     * re-entering surface (extraBufferCapacity=1, DROP_OLDEST, no replay).
     */
    private val _checkinSuccess = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val checkinSuccess: SharedFlow<Long> = _checkinSuccess.asSharedFlow()

    private fun notify(text: String, isError: Boolean = false) {
        _notice.value = (if (isError) "⚠ " else "") + text
    }

    fun consumeNotice() { _notice.value = null }

    // ── F-HB-01 wallet ──
    fun loadWallet() {
        viewModelScope.launch {
            _wallet.value = _wallet.value.copy(loading = true, error = null)
            if (_wallet.value.page == null) {
                repo.cachedWallet()?.let { cached ->
                    _wallet.value = WalletUi(loading = true, page = cached, stale = true)
                }
            }
            repo.wallet().fold(
                onSuccess = { page -> _wallet.value = WalletUi(loading = false, page = page, stale = false) },
                onFailure = { t ->
                    val msg = t.message ?: "No gateway configured — set your server in Profile → Connection."
                    val hasCache = _wallet.value.page != null
                    _wallet.value = _wallet.value.copy(loading = false, stale = hasCache, error = if (hasCache) null else msg)
                },
            )
        }
    }

    // ── F-HB-02 check-in (409 body carries {error, wallet}) ──
    fun checkin() {
        viewModelScope.launch {
            repo.checkinWallet().fold(
                onSuccess = { v ->
                    notify("Checked in — +${v.reward} PC${if (v.streak > 1) " · ${v.streak}-day streak" else ""}")
                    _checkinSuccess.tryEmit(v.reward) // R1-W2H D38 — once per success
                    loadWallet()
                },
                onFailure = { t ->
                    loadWallet()
                    notify(t.message ?: "Check-in failed", isError = true)
                },
            )
        }
    }

    // ── F-HB-03 transfer ──
    fun transfer(handle: String, amount: Long, note: String?, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            repo.transferCoins(handle, amount, note).fold(
                onSuccess = { v ->
                    notify("Sent $amount PC to @${v.to.username ?: v.to.name}")
                    loadWallet()
                    onDone(true, "")
                },
                onFailure = { t -> onDone(false, t.message ?: "Transfer failed") },
            )
        }
    }

    // ── F-HB-04 swap ──
    private val _swap = MutableStateFlow<SwapPageDto?>(null)
    val swap: StateFlow<SwapPageDto?> = _swap.asStateFlow()

    fun loadSwap() {
        viewModelScope.launch {
            repo.swapRates().fold(
                onSuccess = { v -> _swap.value = v },
                onFailure = { t -> notify(t.message ?: "Could not load swap rates", isError = true) },
            )
        }
    }

    fun swap(direction: String, amount: Long, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            repo.swap(direction, amount).fold(
                onSuccess = { v ->
                    notify(v.note ?: "Swap complete")
                    loadWallet()
                    onDone(true, "")
                },
                onFailure = { t -> onDone(false, t.message ?: "Swap failed") },
            )
        }
    }

    // ── F-HB-05 tasks ──
    private val _tasks = MutableStateFlow<HubTasksPageDto?>(null)
    val tasks: StateFlow<HubTasksPageDto?> = _tasks.asStateFlow()

    fun loadTasks() {
        viewModelScope.launch {
            if (_tasks.value == null) repo.cachedHubTasks()?.let { cached -> _tasks.value = cached }
            repo.hubTasks().fold(
                onSuccess = { v -> _tasks.value = v },
                onFailure = { t -> if (_tasks.value == null) notify(t.message ?: "Could not load tasks", isError = true) },
            )
        }
    }

    fun createTask(title: String) {
        viewModelScope.launch {
            repo.createHubTask(title, "todo").fold(
                onSuccess = { _ -> loadTasks(); notify("Task added") },
                onFailure = { t -> notify(t.message ?: "Could not add the task", isError = true) },
            )
        }
    }

    fun moveTask(task: HubTaskDto, status: String) {
        viewModelScope.launch {
            repo.updateHubTask(task.id, null, status).fold(
                onSuccess = { _ -> loadTasks() },
                onFailure = { t -> notify(t.message ?: "Could not move the task", isError = true) },
            )
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            repo.deleteHubTask(taskId).fold(
                onSuccess = { _ -> loadTasks() },
                onFailure = { t -> notify(t.message ?: "Could not delete the task", isError = true) },
            )
        }
    }

    // ── F-HB-06 market ──
    private val _market = MutableStateFlow<List<MarketListingDto>>(emptyList())
    val market: StateFlow<List<MarketListingDto>> = _market.asStateFlow()

    fun loadMarket() {
        viewModelScope.launch {
            repo.market().fold(
                onSuccess = { v -> _market.value = v.listings },
                onFailure = { t -> notify(t.message ?: "Could not load the market", isError = true) },
            )
        }
    }

    fun createListing(title: String, price: Long, description: String?) {
        viewModelScope.launch {
            repo.createListing(title, description, price).fold(
                onSuccess = { v -> loadMarket(); notify("Listed \"${v.title}\" for $price PC") },
                onFailure = { t -> notify(t.message ?: "Could not list", isError = true) },
            )
        }
    }

    fun buy(listing: MarketListingDto) {
        viewModelScope.launch {
            repo.buyListing(listing.id).fold(
                onSuccess = { _ -> notify("Bought \"${listing.title}\""); loadWallet(); loadMarket() },
                onFailure = { t -> notify(t.message ?: "Purchase failed", isError = true) },
            )
        }
    }

    // ── F-HB-07 logs ──
    private val _logs = MutableStateFlow<List<HubLogDto>>(emptyList())
    val logs: StateFlow<List<HubLogDto>> = _logs.asStateFlow()

    fun loadLogs() {
        viewModelScope.launch {
            repo.hubLogs(limit = 80, kind = null).fold(
                onSuccess = { v -> _logs.value = v.logs },
                onFailure = { t -> notify(t.message ?: "Could not load logs", isError = true) },
            )
        }
    }

    // ── F-HB-08/09/10 apps ──
    fun loadInstallState(appId: String) {
        viewModelScope.launch {
            repo.appInstallState(appId).onSuccess { v ->
                _installs.value = _installs.value + (appId to AppInstallRow(appId, v.installed, v.installs))
            }
        }
    }

    fun installApp(appId: String, name: String) {
        viewModelScope.launch {
            repo.installApp(appId).fold(
                onSuccess = { v ->
                    notify(if (v.installed) "Connected to $name" else "Disconnected from $name")
                    loadInstallState(appId)
                },
                onFailure = { t -> notify(t.message ?: "Install failed", isError = true) },
            )
        }
    }

    fun uninstallApp(appId: String, name: String) {
        viewModelScope.launch {
            repo.uninstallApp(appId).fold(
                onSuccess = { _ -> notify("Disconnected from $name"); loadInstallState(appId) },
                onFailure = { t -> notify(t.message ?: "Disconnect failed", isError = true) },
            )
        }
    }

    /** F-HB-09 — join (or fetch) the app community → real conversation id. */
    fun joinCommunity(appId: String, name: String, onJoined: (String) -> Unit) {
        viewModelScope.launch {
            repo.joinAppCommunity(appId).fold(
                onSuccess = { v ->
                    v.conversation?.id?.let { id ->
                        notify("Welcome to the $name community")
                        onJoined(id)
                    } ?: notify("Community unavailable", isError = true)
                },
                onFailure = { t -> notify(t.message ?: "Could not join the community", isError = true) },
            )
        }
    }

    /** F-HB-10 — fan-out My apps state (web hub-data.tsx parity). */
    fun loadMyApps(catalog: HubCatalog) {
        viewModelScope.launch {
            val ids = catalog.apps.take(24).map { it.n.toString() }
            val rows = ids.map { id -> async { id to runCatching { repo.appInstallState(id).getOrNull() }.getOrNull() } }.awaitAll()
            val map = rows.mapNotNull { (id, state) ->
                state?.let { id to AppInstallRow(id, it.installed, it.installs) }
            }.toMap()
            _installs.value = _installs.value + map
        }
    }
}

// ── Surface routing (hub tab → sub-surface sheets) ───────────────────────

private enum class HubSurface { WALLET, TRANSFER, SWAP, TASKS, MARKET, LOGS, APPS, MY_APPS }

private data class HubTileSpec(val label: String, val sub: String, val surface: HubSurface)

private val HUB_TILES = listOf(
    HubTileSpec("Wallet", "PC · GEM · ledger", HubSurface.WALLET),
    HubTileSpec("Transfer", "@handle → PC", HubSurface.TRANSFER),
    HubTileSpec("Swap", "PC ⇄ GEM", HubSurface.SWAP),
    HubTileSpec("Tasks", "personal kanban", HubSurface.TASKS),
    HubTileSpec("Market", "buy & sell", HubSurface.MARKET),
    HubTileSpec("Logs", "activity stream", HubSurface.LOGS),
    HubTileSpec("Mini apps", "100-app matrix", HubSurface.APPS),
    HubTileSpec("My apps", "installed", HubSurface.MY_APPS),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    viewerName: String,
    onOpenConversation: (String) -> Unit,
    vm: HubViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val catalog = remember { HubCatalogLoader.load(context) }
    val wallet by vm.wallet.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val market by vm.market.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val swap by vm.swap.collectAsStateWithLifecycle()
    val installs by vm.installs.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var surface by remember { mutableStateOf<HubSurface?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var communityRoom by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.loadWallet()
        vm.loadMyApps(catalog)
    }
    LaunchedEffect(notice) { notice?.let { toast = it; vm.consumeNotice() } }
    LaunchedEffect(toast) { toast?.let { delay(2_600); toast = null } }

    // R1-W2H D38 — check-in celebration (spec F-HB-02 "Success haptic +
    // particles"; the web's toast-only ground truth is exceeded, sanctioned).
    // ONE confetti burst per successful check-in via the same API onboarding
    // uses — the shell's global ParticleBurstHost renders it and ticks its own
    // subtle haptic. The screen adds the house screen-haptic (LocalHapticFeedback,
    // the idiom every other surface uses) so the success feedback survives
    // Reduce Motion, where the host gates everything.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        vm.checkinSuccess.collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            PulseFx.fire(PulseFx.BurstKind.CONFETTI, count = 90)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            Text("Hub", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                if (viewerName.isBlank()) "XP · streaks · mini apps" else "Hey $viewerName — XP · streaks · mini apps",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))

            // ── Wallet hero (F-HB-01 + F-HB-02) ──
            val page = wallet.page
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF123A2C), Color(0xFF0C2B21))))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pulse Wallet", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (wallet.stale) Text("offline — cached", color = Color(0x99FFFFFF), fontSize = 11.sp)
                    if (wallet.loading) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    HeroStat("PC", page?.wallet?.coins, Modifier.weight(1f))
                    HeroStat("GEM", page?.wallet?.gems, Modifier.weight(1f))
                    HeroStat("Streak", page?.wallet?.streak?.toLong(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                val checkedIn = page?.wallet?.checkedInToday == true
                Button(
                    onClick = { vm.checkin() },
                    enabled = !checkedIn && !wallet.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (checkedIn) "Checked in today ✓" else "Daily check-in · +25 PC") }
                page?.wallet?.let { w ->
                    if (!checkedIn && w.streak > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Next check-in continues your ${w.streak}-day streak (+${minOf(w.streak * 2, 20)} bonus)",
                            color = Color(0x99FFFFFF), fontSize = 11.sp,
                        )
                    }
                }
                wallet.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HUB_TILES.chunked(2).forEach { rowTiles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowTiles.forEach { tile ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .clickable { surface = tile.surface }
                                    .padding(14.dp),
                            ) {
                                Text(tile.label, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(tile.sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(120.dp)) // dock clearance
        }

        toast?.let {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp)) {
                Text(
                    it,
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xEE1F2937))
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    color = Color.White, fontSize = 13.sp,
                )
            }
        }
    }

    // ── Sub-surfaces ──
    when (surface) {
        HubSurface.WALLET -> SheetHost(onDismiss = { surface = null }) {
            LedgerBody(wallet.page?.ledger ?: emptyList())
        }
        HubSurface.TRANSFER -> TransferSheet(vm = vm, onDismiss = { surface = null })
        HubSurface.SWAP -> SwapSheet(vm = vm, onDismiss = { surface = null })
        HubSurface.TASKS -> TasksSheet(vm = vm, onDismiss = { surface = null })
        HubSurface.MARKET -> MarketSheet(vm = vm, onDismiss = { surface = null })
        HubSurface.LOGS -> {
            LogsLivePoll(vm) // R1-W2H D40 — 12s live poll while this sheet is open
            SheetHost(onDismiss = { surface = null }) {
                LogsBody(logs = logs)
            }
        }
        HubSurface.APPS -> AppsSheet(
            catalog = catalog, installs = installs, vm = vm, myAppsOnly = false,
            onDismiss = { surface = null },
            onOpenCommunity = { appId, name ->
                vm.joinCommunity(appId, name) { id -> communityRoom = id; surface = null }
            },
        )
        HubSurface.MY_APPS -> AppsSheet(
            catalog = catalog, installs = installs, vm = vm, myAppsOnly = true,
            onDismiss = { surface = null },
            onOpenCommunity = { appId, name ->
                vm.joinCommunity(appId, name) { id -> communityRoom = id; surface = null }
            },
        )
        null -> Unit
    }

    communityRoom?.let { id ->
        LaunchedEffect(id) { onOpenConversation(id); communityRoom = null }
    }
}

@Composable
private fun HeroStat(label: String, value: Long?, modifier: Modifier = Modifier) {
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = (value ?: 0).toFloat(),
        animationSpec = tween(700),
        label = "hero-$label",
    )
    Column(modifier) {
        Text(
            if (value == null) "…" else animated.toLong().toString(),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(label, color = Color(0x99FFFFFF), fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetHost(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * R1-W2H D40 — hub logs live poll (web ground truth hub-tab.tsx:603-611
 * refetchInterval 12_000). Runs only while the Logs sheet is composed (the
 * call site lives inside the LOGS branch, so closing the sheet disposes it)
 * and pauses whenever the app drops below STARTED — the D25 house pattern
 * (DisposableEffect + LifecycleEventObserver) copied verbatim. First tick is
 * immediate so the sheet never opens empty; failure toasts stay rare (the VM
 * notifies only on error).
 */
@Composable
private fun LogsLivePoll(vm: HubViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        var job: Job? = null
        fun startLoop() {
            job?.cancel()
            job = scope.launch {
                while (true) {
                    vm.loadLogs()
                    delay(12_000)
                }
            }
        }
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startLoop()
                Lifecycle.Event.ON_STOP -> job?.cancel()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        // The sheet can open while the app is already STARTED — the observer
        // only fires on transitions, so kick the loop for the current state.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startLoop()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            job?.cancel()
        }
    }
}

/** F-HB-01 ledger (≤30 rows, desc). */
@Composable
private fun LedgerBody(ledger: List<LedgerEntryDto>) {
    Text("Ledger", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Spacer(Modifier.height(8.dp))
    if (ledger.isEmpty()) Text("No transactions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    ledger.forEach { e ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.note ?: e.kind, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text(e.createdAt?.take(10) ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${if (e.amount >= 0) "+" else ""}${e.amount} ${e.asset}",
                color = if (e.amount >= 0) PulsePalette.Emerald else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
    }
}

/** F-HB-03 @handle transfer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(vm: HubViewModel, onDismiss: () -> Unit) {
    var handle by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Text("Send PC", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Whole PC only — max 100,000 per transfer.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = handle, onValueChange = { handle = it.take(24) }, label = { Text("@handle") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit).take(6) }, label = { Text("Amount PC") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = note, onValueChange = { note = it.take(140) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val amt = amount.toLongOrNull() ?: 0
                    when {
                        handle.isBlank() -> error = "Recipient @handle is required."
                        amt !in 1..100_000 -> error = "Amount must be a positive whole number (max 100,000 PC)."
                        else -> vm.transfer(handle, amt, note.trim().ifBlank { null }) { ok, msg ->
                            if (ok) onDismiss() else error = msg
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** F-HB-04 swap (rates 100/80 + real stats). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwapSheet(vm: HubViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadSwap() }
    val swap by vm.swap.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Text("PC ⇄ GEM", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            val s = swap
            Text(
                if (s == null) "Loading rates…" else "Buy 1 GEM = ${s.rates.pcPerGemBuy} PC · Sell 1 GEM = ${s.rates.pcPerGemSell} PC",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            if (s != null) {
                Text(
                    "${s.stats.swaps} swaps · ${s.stats.circulatingCoins} PC · ${s.stats.circulatingGems} GEM circulating",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit).take(6) }, label = { Text("Amount PC") }, modifier = Modifier.fillMaxWidth())
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val amt = amount.toLongOrNull() ?: 0
                        when {
                            amt < 100 -> error = "Minimum exchange is 100 PC for 1 GEM."
                            amt % 100 != 0L -> error = "Amount must be a multiple of 100 PC."
                            else -> vm.swap("pc2gem", amt) { ok, msg -> if (ok) onDismiss() else error = msg }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("PC → GEM") }
                OutlinedButton(
                    onClick = {
                        val amt = amount.toLongOrNull() ?: 0
                        if (amt < 1) error = "Amount must be a positive whole number."
                        else vm.swap("gem2pc", amt) { ok, msg -> if (ok) onDismiss() else error = msg }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("GEM → PC") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** F-HB-05 personal tasks (todo/doing/done). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksSheet(vm: HubViewModel, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadTasks() }
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Text("Tasks", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = title, onValueChange = { title = it.take(120) },
                label = { Text("New task (1–120)") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { if (title.isNotBlank()) { vm.createTask(title.trim()); title = "" } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add task") }
            Spacer(Modifier.height(10.dp))
            listOf("doing", "todo", "done").forEach { col ->
                val colTasks = tasks?.tasks?.filter { it.status == col } ?: emptyList()
                Text(
                    (when (col) { "doing" -> "Doing"; "todo" -> "To do"; else -> "Done" }) + " · ${colTasks.size}",
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                if (colTasks.isEmpty()) Text("Nothing here.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                colTasks.forEach { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t.title, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (col != "todo") Text("‹", Modifier.clickable { vm.moveTask(t, prevStatus(col)) }.padding(6.dp))
                        if (col != "done") Text("›", Modifier.clickable { vm.moveTask(t, nextStatus(col)) }.padding(6.dp))
                        Text("✕", Modifier.clickable { vm.deleteTask(t.id) }.padding(6.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun prevStatus(s: String) = when (s) { "doing" -> "todo"; else -> "doing" }
private fun nextStatus(s: String) = when (s) { "todo" -> "doing"; else -> "done" }

/** F-HB-06 market (list + buy). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketSheet(vm: HubViewModel, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmBuy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadMarket() }
    val listings by vm.market.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Market", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = !creating }) { Text(if (creating) "Close" else "+ List") }
            }
            if (creating) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("Title (1–80)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = price, onValueChange = { price = it.filter(Char::isDigit).take(6) }, label = { Text("Price PC") }, modifier = Modifier.fillMaxWidth())
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val p = price.toLongOrNull() ?: 0
                        when {
                            title.isBlank() -> error = "Title must be 1–80 characters."
                            p !in 1..100_000 -> error = "Price must be a positive whole number (max 100,000 PC)."
                            else -> { vm.createListing(title.trim(), p, null); title = ""; price = ""; creating = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Post listing") }
                Spacer(Modifier.height(10.dp))
            }
            if (listings.isEmpty()) Text("Nothing on the market yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            listings.forEach { l ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(l.title, fontWeight = if (l.status == "open") FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${l.price} PC · ${l.seller.name}" + if (l.mine) " · yours" else "",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        l.status == "sold" -> Text("SOLD", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        l.mine -> Text("Yours", color = PulsePalette.Emerald, fontSize = 12.sp)
                        confirmBuy == l.id -> Row {
                            TextButton(onClick = { confirmBuy = null }) { Text("No") }
                            Button(onClick = { vm.buy(l); confirmBuy = null }) { Text("Buy") }
                        }
                        else -> OutlinedButton(onClick = { confirmBuy = l.id }) { Text("Buy") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** F-HB-07 logs stream (≤80). */
@Composable
private fun LogsBody(logs: List<HubLogDto>) {
    Text("Logs", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Spacer(Modifier.height(8.dp))
    if (logs.isEmpty()) Text("No activity yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    logs.forEach { l ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(PulsePalette.Emerald).align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(l.message, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${l.kind} · ${l.createdAt?.take(16)?.replace('T', ' ') ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** F-HB-08/10 — apps matrix (bundled catalog, offline-browsable) + My apps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsSheet(
    catalog: HubCatalog,
    installs: Map<String, HubViewModel.AppInstallRow>,
    vm: HubViewModel,
    myAppsOnly: Boolean,
    onDismiss: () -> Unit,
    onOpenCommunity: (appId: String, name: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<HubCatalogApp?>(null) }

    val visible = catalog.apps
        .filter { !myAppsOnly || (installs[it.n.toString()]?.installed == true) }
        .filter { category == null || it.category == category }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                if (myAppsOnly) "My apps" else "Mini apps",
                fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                if (myAppsOnly) "Installed & connected apps"
                else "${catalog.apps.size} apps · ${catalog.categories.size} categories — browsable offline",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (!myAppsOnly) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(30) },
                    label = { Text("Search apps") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryChip(label = "All", selected = category == null) { category = null }
                    catalog.categories.forEach { c ->
                        CategoryChip(label = c.label, selected = category == c.label) { category = c.label }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Text(
                    if (myAppsOnly) "No apps connected yet — open Mini apps to connect."
                    else "No apps match.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(88.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(((visible.size / 3 + 1) * 108).coerceAtMost(360).dp),
            ) {
                items(visible, key = { it.n }) { app ->
                    val installed = installs[app.n.toString()]?.installed == true
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .clickable { expanded = app }
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(PulsePalette.Emerald, Color(0xFF0B3B2C)))),
                            contentAlignment = Alignment.Center,
                        ) { Text(app.name.take(1), color = Color.White, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(4.dp))
                        Text(app.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (installed) {
                            Text("connected", fontSize = 10.sp, color = PulsePalette.Emerald)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    expanded?.let { app ->
        val appId = app.n.toString()
        LaunchedEffect(appId) { vm.loadInstallState(appId) }
        val installed = installs[appId]?.installed == true
        ModalBottomSheet(onDismissRequest = { expanded = null }, sheetState = rememberModalBottomSheetState()) {
            Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(PulsePalette.Emerald, Color(0xFF0B3B2C)))),
                        contentAlignment = Alignment.Center,
                    ) { Text(app.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(app.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${app.category} · ${installs[appId]?.installs ?: 0} connected", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                catalog.taglines[appId]?.let { Text(it, fontSize = 13.sp) }
                Spacer(Modifier.height(6.dp))
                Text("Nav: ${app.nav}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Input: ${app.input}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Secret: ${app.secret}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (installed) vm.uninstallApp(appId, app.name) else vm.installApp(appId, app.name) },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (installed) "Disconnect" else "Install / Connect") }
                    OutlinedButton(
                        onClick = { onOpenCommunity(appId, app.name) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Community") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) PulsePalette.Emerald else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
        fontSize = 12.sp,
    )
}
