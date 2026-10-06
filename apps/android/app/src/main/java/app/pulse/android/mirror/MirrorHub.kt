package app.pulse.android.mirror

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.HubLogDto
import app.pulse.protocol.HubTaskDto
import app.pulse.protocol.MarketListingDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * R66 - the Hub tab (hub-tab.tsx verbatim conversion): the web's economy and
 * control deck the artboard dock's "Updates" slot actually opens. Zinc-900
 * page, glass-deep header with the ember Flame tile + live wallet chip, the
 * glass pill panel rail (Wallet / Tasks / Market / Swap / Logs / Apps), and
 * every panel talking to the real /api/hub routes the repository exposes -
 * zero mocks, zero dead controls.
 */

@Composable
internal fun MirrorHub(
    repository: PulseRepository,
    viewerId: String,
    onOpenConversation: (String) -> Unit,
) {
    var walletCoins by remember { mutableStateOf<Long?>(null) }
    var walletGems by remember { mutableStateOf<Long?>(null) }
    var panel by remember { mutableStateOf("wallet") }
    // R75 - the full-page category surface (web #/hub/c/<slug>, MINE_SLUG = "mine")
    var categoryPage by remember { mutableStateOf<String?>(null) }

    suspend fun loadWallet() {
        repository.wallet().onSuccess { page ->
            walletCoins = page.wallet.coins
            walletGems = page.wallet.gems
        }
    }
    LaunchedEffect(Unit) { loadWallet() }

    Box(
        Modifier
            .fillMaxSize()
            .background(SubPageInk.Page),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(SubPageInk.Page)
                .statusBarsPadding(),
        ) {
        // header: glass-deep px-4 pb-3 pt-3 - Flame tile + Hub + wallet chip
        Column(
            Modifier
                .fillMaxWidth()
                .background(SubPageInk.Panel)
                .border(1.dp, SubPageInk.PanelBorder),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(listOf(Color(0xFFC9762B), Color(0xFFE08A3C))),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LFlame", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Hub", color = SubPageInk.Zinc50, fontSize = 16.5.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Economy · tasks · market · the 100-app matrix",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // real wallet chip
                Row(
                    Modifier
                        .heightIn(min = 36.dp)
                        .clip(CircleShape)
                        .background(SubPageInk.GlassPill)
                        .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                        .clickable { panel = "wallet" }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MirrorLucideIcon("LCoins", tint = SubPageInk.Amber400, modifier = Modifier.size(14.dp))
                    Text(
                        (walletCoins ?: 0).toString() + " PC",
                        color = SubPageInk.Amber400,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    MirrorLucideIcon("LGem", tint = SubPageInk.Violet400, modifier = Modifier.size(12.dp))
                    Text(
                        (walletGems ?: 0).toString(),
                        color = SubPageInk.Violet400,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x1AFFFFFF)),
            )
        }

        // panel rail: glass-pill p-1 gap-1, active = amber-600 pill
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(CircleShape)
                .background(SubPageInk.GlassPill)
                .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((id, label, icon) in HUB_PANELS) {
                val active = panel == id
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (active) SubPageInk.Amber600 else Color.Transparent)
                        .clickable { panel = id }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MirrorLucideIcon(icon, tint = if (active) Color.White else SubPageInk.Zinc300, modifier = Modifier.size(14.dp))
                    Text(
                        label,
                        color = if (active) Color.White else SubPageInk.Zinc300,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            when (panel) {
                "tasks" -> HubTasksPanel(repository)
                "market" -> HubMarketPanel(repository, viewerId, onCoinsChanged = { CoroutineScope(Dispatchers.IO).launch { loadWallet() } })
                "swap" -> HubSwapPanel(repository, onCoinsChanged = { CoroutineScope(Dispatchers.IO).launch { loadWallet() } })
                "logs" -> HubLogsPanel(repository)
                "apps" -> HubAppsPanel(repository, viewerId, onOpenCategory = { categoryPage = it })
                else -> HubWalletPanel(repository, onCoinsChanged = { walletCoins = it.first; walletGems = it.second })
            }
        }
        }

        // R75 - web #/hub/c/<slug>: the category full page slides over the whole
        // tab (hub-tab.tsx AnimatePresence; push slides in from the right).
        AnimatedVisibility(
            visible = categoryPage != null,
            enter = slideInHorizontally(MirrorMotion.snappy()) { it } + fadeIn(tween(160)),
            exit = slideOutHorizontally(tween(190, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(140)),
        ) {
            // keep the last non-null slug mounted so the exit animation keeps its content
            val shown = remember { mutableStateOf(categoryPage) }
            if (categoryPage != null) shown.value = categoryPage
            shown.value?.let { slug ->
                MirrorHubCategoryPage(
                    slug = slug,
                    repository = repository,
                    onBack = { categoryPage = null },
                )
            }
        }
    }
}

private val HUB_PANELS = listOf(
    Triple("wallet", "Wallet", "LCoins"),
    Triple("tasks", "Tasks", "LListTodo"),
    Triple("market", "Market", "LShoppingBag"),
    Triple("swap", "Swap", "LRepeat"),
    Triple("logs", "Logs", "LTerminal"),
    Triple("apps", "Apps", "LBadgeCheck"),
)

private val HUB_FIELD_BG = Color(0x12FFFFFF) // white/[0.07] field fill
private val HUB_CARD_BG = SubPageInk.Panel // glass-deep dark

@Composable
private fun HubField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, numeric: Boolean = false) {
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(HUB_FIELD_BG)
            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (value.isBlank()) Text(placeholder, color = SubPageInk.Zinc500, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = { next ->
                onValueChange(if (numeric) next.filter { it.isDigit() } else next)
            },
            singleLine = true,
            textStyle = TextStyle(color = SubPageInk.Zinc50, fontSize = 13.sp),
            cursorBrush = SolidColor(SubPageInk.Amber500),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HubPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) SubPageInk.Amber600 else Color(0x1AF59E0B))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Wallet panel ─────────────────────────────────────────────────────────────

@Composable
private fun HubWalletPanel(repository: PulseRepository, onCoinsChanged: (Pair<Long, Long>) -> Unit) {
    var coins by remember { mutableStateOf<Long?>(null) }
    var gems by remember { mutableStateOf<Long?>(null) }
    var streak by remember { mutableStateOf(0) }
    var checkedIn by remember { mutableStateOf<Boolean?>(null) }
    var ledger by remember { mutableStateOf<List<app.pulse.protocol.LedgerEntryDto>>(emptyList()) }
    var toHandle by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        repository.wallet().onSuccess { page ->
            coins = page.wallet.coins
            gems = page.wallet.gems
            streak = page.wallet.streak
            checkedIn = page.wallet.checkedInToday
            ledger = page.ledger
            onCoinsChanged(page.wallet.coins to page.wallet.gems)
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // balance hero - ember gradient 135deg C9762B -> E08A3C 55% -> A85423
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        0f to Color(0xFFC9762B),
                        0.55f to Color(0xFFE08A3C),
                        1f to Color(0xFFA85423),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                )
                .padding(20.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(Color(0x1AFFFFFF)),
            )
            Column {
                Text("PULSE BALANCE", color = Color(0xB3FFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column {
                        Text((coins ?: 0).toString(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                        Text("PC · Pulse Coins", color = Color(0xCCFFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(16.dp))
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(38.dp)
                            .background(Color(0x33FFFFFF)),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            MirrorLucideIcon("LGem", tint = Color.White, modifier = Modifier.size(16.dp))
                            Text((gems ?: 0).toString(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                        }
                        Text("Gems", color = Color(0xCCFFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(16.dp))
                // check-in button: white bg amber-700 text, full width
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .clickable(enabled = !busy && checkedIn != true) {
                            busy = true
                            CoroutineScope(Dispatchers.IO).launch {
                                repository.checkinWallet().fold(
                                    onSuccess = { r ->
                                        notice = "Checked in - +${r.reward} PC · ${r.streak}-day streak"
                                    },
                                    onFailure = { notice = it.message },
                                )
                                load()
                                busy = false
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (checkedIn == true) {
                            Text(
                                "Checked in today · streak $streak",
                                color = Color(0xFFB45309),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else {
                            MirrorLucideIcon("LGift", tint = Color(0xFFB45309), modifier = Modifier.size(16.dp))
                            Text(
                                "Daily check-in · +25 PC",
                                color = Color(0xFFB45309),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        if (notice != null) {
            Text(notice.orEmpty(), color = SubPageInk.Amber400, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        // transfer card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(HUB_CARD_BG)
                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MirrorLucideIcon("LSend", tint = SubPageInk.Amber400, modifier = Modifier.size(16.dp))
                Text("Send coins by @handle", color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            HubField(toHandle, { toHandle = it.removePrefix("@").lowercase() }, "@handle")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubField(amount, { amount = it }, "Amount (PC)", modifier = Modifier.weight(1f), numeric = true)
                HubField(note, { note = it }, "Note (optional)", modifier = Modifier.weight(1f))
            }
            HubPrimaryButton(
                label = if (busy) "Sending…" else "Transfer",
                enabled = !busy && toHandle.isNotBlank() && amount.isNotBlank(),
                onClick = {
                    busy = true
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.transferCoins(toHandle, amount.toLongOrNull() ?: 0, note.takeIf { it.isNotBlank() }).fold(
                            onSuccess = {
                                notice = "Sent $amount PC to @$toHandle"
                                toHandle = ""
                                amount = ""
                                note = ""
                            },
                            onFailure = { notice = it.message },
                        )
                        load()
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ledger card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(HUB_CARD_BG)
                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp)),
        ) {
            Text(
                "Ledger · last ${ledger.size}",
                color = SubPageInk.Zinc50,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x0FFFFFFF))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Column(Modifier.heightIn(max = 288.dp)) {
                if (ledger.isEmpty()) {
                    Text(
                        "No movements yet - check in above to mint your first coins.",
                        color = SubPageInk.Zinc500,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                for (row in ledger) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MirrorLucideIcon(
                            if (row.amount >= 0) "LArrowDownToLine" else "LArrowUpFromLine",
                            tint = if (row.amount >= 0) SubPageInk.Amber400 else SubPageInk.Rose400,
                            modifier = Modifier.size(16.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.note ?: row.kind,
                                color = SubPageInk.Zinc50,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(hubTimestamp(row.createdAt, withDate = true), color = SubPageInk.Zinc500, fontSize = 11.sp)
                        }
                        Text(
                            (if (row.amount >= 0) "+" else "") + row.amount + " " + row.asset,
                            color = if (row.amount >= 0) SubPageInk.Amber400 else SubPageInk.Rose400,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x0DFFFFFF)),
                    )
                }
            }
        }
    }
}

private val HUB_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val HUB_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm")

private fun hubTimestamp(iso: String?, withDate: Boolean): String {
    val parsed = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val zoned = parsed.atZone(ZoneId.systemDefault())
    return if (withDate) HUB_DATETIME.format(zoned) else HUB_TIME.format(zoned)
}

// ── Tasks panel (3-column kanban) ────────────────────────────────────────────

@Composable
private fun HubTasksPanel(repository: PulseRepository) {
    var tasks by remember { mutableStateOf<List<HubTaskDto>>(emptyList()) }
    var title by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        tasks = repository.hubTasks().getOrNull()?.tasks ?: emptyList()
    }

    fun move(task: HubTaskDto) {
        val next = when (task.status) {
            "todo" -> "doing"
            "doing" -> "done"
            else -> "todo"
        }
        tasks = tasks.map { if (it.id == task.id) it.copy(status = next) else it }
        CoroutineScope(Dispatchers.IO).launch {
            repository.updateHubTask(task.id, null, next).onSuccess { fresh ->
                tasks = tasks.map { if (it.id == fresh.id) fresh else it }
            }
        }
    }

    fun remove(task: HubTaskDto) {
        tasks = tasks.filterNot { it.id == task.id }
        CoroutineScope(Dispatchers.IO).launch {
            repository.deleteHubTask(task.id)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HubField(title, { title = it }, "New task…", modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (title.isNotBlank()) SubPageInk.Amber600 else Color(0x1AF59E0B))
                    .clickable(enabled = title.isNotBlank()) {
                        val t = title.trim()
                        title = ""
                        CoroutineScope(Dispatchers.IO).launch {
                            repository.createHubTask(t, null).onSuccess { fresh ->
                                tasks = tasks + fresh
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LCheck", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((status, label) in listOf("todo" to "To do", "doing" to "Doing", "done" to "Done")) {
                val col = tasks.filter { it.status == status }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x0DFFFFFF))
                        .padding(8.dp),
                ) {
                    Text(
                        "${label.uppercase()} · ${col.size}",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    )
                    for (task in col) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xCC18181B))
                                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(8.dp))
                                .padding(8.dp),
                        ) {
                            Text(
                                task.title,
                                color = if (task.status == "done") SubPageInk.Zinc400 else SubPageInk.Zinc50,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                textDecoration = if (task.status == "done") androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    if (task.status == "done") "↺ reopen" else "→ next",
                                    color = SubPageInk.Amber400,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { move(task) },
                                )
                                MirrorLucideIcon(
                                    "LX",
                                    tint = SubPageInk.Zinc400,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { remove(task) },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (col.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("empty", color = SubPageInk.Zinc500, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Market panel ─────────────────────────────────────────────────────────────

@Composable
private fun HubMarketPanel(repository: PulseRepository, viewerId: String, onCoinsChanged: () -> Unit) {
    var listings by remember { mutableStateOf<List<MarketListingDto>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var coins by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        listings = repository.market().getOrNull()?.listings ?: emptyList()
        coins = repository.wallet().getOrNull()?.wallet?.coins
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(HUB_CARD_BG)
                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MirrorLucideIcon("LCircleDollarSign", tint = SubPageInk.Amber400, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sell something", color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                coins?.let { Text("you: $it PC", color = SubPageInk.Zinc500, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            }
            HubField(title, { title = it }, "Title (what are you selling?)")
            HubField(description, { description = it }, "Description (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubField(price, { price = it }, "Price PC", modifier = Modifier.weight(1f), numeric = true)
                HubPrimaryButton(
                    label = "List",
                    enabled = !busy && title.isNotBlank() && price.isNotBlank(),
                    onClick = {
                        busy = true
                        CoroutineScope(Dispatchers.IO).launch {
                            repository.createListing(title.trim(), description.takeIf { it.isNotBlank() }, price.toLongOrNull() ?: 0).fold(
                                onSuccess = { fresh ->
                                    listings = listOf(fresh) + listings
                                    notice = "Listing posted to the market"
                                    title = ""
                                    description = ""
                                    price = ""
                                },
                                onFailure = { notice = it.message },
                            )
                            coins = repository.wallet().getOrNull()?.wallet?.coins
                            busy = false
                        }
                    },
                )
            }
        }

        if (notice != null) Text(notice.orEmpty(), color = SubPageInk.Amber400, fontSize = 12.sp, fontWeight = FontWeight.Medium)

        Text("Open board", color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

        for (listing in listings) {
            val sold = listing.status == "sold"
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sold) Color(0x08FFFFFF) else HUB_CARD_BG)
                    .border(1.dp, if (sold) SubPageInk.PanelBorder else Color.Transparent, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            listing.title,
                            color = if (sold) SubPageInk.Zinc400 else SubPageInk.Zinc50,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        listing.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = SubPageInk.Zinc500, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(4.dp))
                        val who = listing.seller.username?.let { "@$it" } ?: listing.seller.name
                        val buyer = listing.buyer?.username?.let { "@$it" } ?: listing.buyer?.name ?: "-"
                        Text(
                            (if (sold) "sold to $buyer" else "by $who") + " · " + hubTimestamp(listing.createdAt, withDate = true),
                            color = SubPageInk.Zinc500,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${listing.price} PC", color = SubPageInk.Amber400, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (listing.status == "open" && !listing.mine) {
                            Box(
                                Modifier
                                    .heightIn(min = 28.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                                    .clickable(enabled = !busy) {
                                        busy = true
                                        CoroutineScope(Dispatchers.IO).launch {
                                            repository.buyListing(listing.id).fold(
                                                onSuccess = { onCoinsChanged() },
                                                onFailure = { notice = it.message },
                                            )
                                            listings = repository.market().getOrNull()?.listings ?: emptyList()
                                            coins = repository.wallet().getOrNull()?.wallet?.coins
                                            busy = false
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Buy", color = SubPageInk.Zinc50, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (listing.mine && listing.status == "open") {
                            Text("yours", color = SubPageInk.Zinc500, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        if (listings.isEmpty()) {
            Text(
                "The board is empty - be the first seller.",
                color = SubPageInk.Zinc500,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ── Swap panel ───────────────────────────────────────────────────────────────

@Composable
private fun HubSwapPanel(repository: PulseRepository, onCoinsChanged: () -> Unit) {
    var rates by remember { mutableStateOf<app.pulse.protocol.SwapRatesDto?>(null) }
    var stats by remember { mutableStateOf<app.pulse.protocol.SwapStatsDto?>(null) }
    var direction by remember { mutableStateOf("pc2gem") }
    var amount by remember { mutableStateOf("") }
    var coins by remember { mutableStateOf<Long?>(null) }
    var gems by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        repository.swapRates().onSuccess { page ->
            rates = page.rates
            stats = page.stats
        }
        repository.wallet().onSuccess { page ->
            coins = page.wallet.coins
            gems = page.wallet.gems
        }
    }

    val hint = when {
        amount.toLongOrNull() ?: 0L <= 0L -> null
        direction == "pc2gem" && rates != null ->
            "${((amount.toLongOrNull() ?: 0L)) / (rates!!.pcPerGemBuy.coerceAtLeast(1))} GEM for $amount PC"
        direction == "gem2pc" && rates != null ->
            "${(amount.toLongOrNull() ?: 0L) * rates!!.pcPerGemSell} PC for $amount GEM"
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, value) in listOf(
                "BUY RATE" to (rates?.let { "${it.pcPerGemBuy} PC → 1 GEM" } ?: "-"),
                "SELL RATE" to (rates?.let { "1 GEM → ${it.pcPerGemSell} PC" } ?: "-"),
            )) {
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(HUB_CARD_BG)
                        .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                ) {
                    Text(label, color = SubPageInk.Zinc500, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(value, color = SubPageInk.Zinc50, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        stats?.let { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value) in listOf(
                    "swaps" to s.swaps,
                    "PC bought" to s.pcBought,
                    "coins live" to s.circulatingCoins,
                    "gems live" to s.circulatingGems,
                )) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x0DFFFFFF))
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(value.toString(), color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(label, color = SubPageInk.Zinc500, fontSize = 10.sp)
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(HUB_CARD_BG)
                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x0DFFFFFF))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((id, label) in listOf("pc2gem" to "PC → GEM", "gem2pc" to "GEM → PC")) {
                    val active = direction == id
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) SubPageInk.Amber600 else Color.Transparent)
                            .clickable { direction = id }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (active) Color.White else SubPageInk.Zinc300, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(HUB_FIELD_BG)
                    .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (amount.isBlank()) {
                    Text(
                        if (direction == "pc2gem") "PC amount (multiples of 100)" else "GEM amount",
                        color = SubPageInk.Zinc500,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = SubPageInk.Zinc50,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(SubPageInk.Amber500),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (hint != null) {
                Text(hint, color = SubPageInk.Amber400, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            HubPrimaryButton(
                label = if (busy) "Exchanging…" else "Exchange",
                enabled = !busy && (amount.toLongOrNull() ?: 0L) > 0L,
                onClick = {
                    busy = true
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.swap(direction, amount.toLongOrNull() ?: 0).fold(
                            onSuccess = { notice = it.note ?: "Exchange complete" },
                            onFailure = { notice = it.message },
                        )
                        repository.wallet().onSuccess { page ->
                            coins = page.wallet.coins
                            gems = page.wallet.gems
                        }
                        onCoinsChanged()
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (coins != null && gems != null) {
                Text(
                    "balance: $coins PC · $gems GEM",
                    color = SubPageInk.Zinc500,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        if (notice != null) Text(notice.orEmpty(), color = SubPageInk.Amber400, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Logs panel (terminal) ────────────────────────────────────────────────────

@Composable
private fun HubLogsPanel(repository: PulseRepository) {
    var logs by remember { mutableStateOf<List<HubLogDto>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        logs = repository.hubLogs(80, null).getOrNull()?.logs ?: emptyList()
    }
    // web refetchInterval 12s
    LaunchedEffect(tick) {
        delay(12_000)
        logs = repository.hubLogs(80, null).getOrNull()?.logs ?: emptyList()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC18181B))
            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x0FFFFFFF))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(SubPageInk.Rose500))
            Box(Modifier.size(10.dp).clip(CircleShape).background(SubPageInk.Amber400))
            Box(Modifier.size(10.dp).clip(CircleShape).background(SubPageInk.Amber500))
            Spacer(Modifier.width(4.dp))
            Text(
                "pulse://logs - live event stream",
                color = SubPageInk.Zinc400,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(Modifier.heightIn(max = 416.dp)) {
            if (logs.isEmpty()) {
                Text(
                    "no events yet - use the economy panels to generate some",
                    color = SubPageInk.Zinc600,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            for (l in logs) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        hubTimestamp(l.createdAt, withDate = false),
                        color = SubPageInk.Zinc600,
                        fontSize = 11.sp,
                    )
                    Text(
                        "[${l.kind}]",
                        color = when (l.kind) {
                            "checkin", "market" -> SubPageInk.Amber400
                            "transfer" -> Color(0xFFFB923C) // orange-400
                            "swap" -> SubPageInk.Violet400
                            else -> SubPageInk.Zinc400
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        (l.user?.let { "@" + (it.username ?: it.name) + " " } ?: "") + l.message,
                        color = SubPageInk.Zinc300,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0x0DFFFFFF)),
                )
            }
        }
    }
}

// ── Apps panel (100 matrix) ──────────────────────────────────────────────────

@Composable
private fun HubAppsPanel(
    repository: PulseRepository,
    viewerId: String,
    onOpenCategory: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) } // matrix app.n ids
    var busyId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<MatrixApp?>(null) }

    suspend fun loadInstalled() {
        // fan the real install states across the catalog (web hydrateInstalledSet:
        // Promise.all over the matrix) - batched to keep the phone's pool sane
        val ids = mutableSetOf<String>()
        for (batch in HubCatalog.MATRIX.chunked(8)) {
            val results = batch.map { app ->
                CoroutineScope(Dispatchers.IO).async {
                    val state = runCatching {
                        repository.appInstallState(app.n.toString()).getOrNull()
                    }.getOrNull()
                    app to (state?.installed == true)
                }
            }.map { it.await() }
            for ((app, on) in results) {
                if (on) ids.add(app.n.toString())
            }
        }
        installed = ids
    }

    fun toggleApp(app: MatrixApp) {
        busyId = app.n.toString()
        CoroutineScope(Dispatchers.IO).launch {
            if (app.n.toString() in installed) {
                repository.uninstallApp(app.n.toString())
            } else {
                repository.installApp(app.n.toString())
            }
            loadInstalled()
            busyId = null
        }
    }
    LaunchedEffect(Unit) { CoroutineScope(Dispatchers.IO).launch { loadInstalled() } }

    val chips = buildList {
        // static order first (minus 'all'), then live categories the static list misses
        for ((id, label) in HubCatalog.CATEGORY_CHIPS) {
            if (id != "all" && HubCatalog.MATRIX.any { it.category == id }) add(id to label)
        }
        for (cat in HubCatalog.MATRIX.map { it.category }.distinct()) {
            if (HubCatalog.CATEGORY_CHIPS.none { it.first == cat }) {
                add(cat to (HubCatalog.extraChipLabel(cat) ?: cat))
            }
        }
    }
    // web AppsPanel counts map (hub-tab.tsx:792-794): category -> platform count
    val counts = HubCatalog.MATRIX.groupingBy { it.category }.eachCount()

    // web root: tiles are search-filtered ONLY - the chips NAVIGATE to the
    // full category page instead of filtering in place (hub-tab.tsx:849-883)
    val q = search.trim().lowercase()
    val filtered = HubCatalog.MATRIX.filter { app ->
        q.isEmpty() || app.name.lowercase().contains(q) ||
            app.input.lowercase().contains(q) || app.secret.lowercase().contains(q)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // glass search pill h-11
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(CircleShape)
                .background(HUB_CARD_BG)
                .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            MirrorLucideIcon("LSearch", tint = SubPageInk.Zinc500, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.padding(start = 26.dp)) {
                if (search.isBlank()) Text("Search 100 platforms…", color = SubPageInk.Zinc500, fontSize = 14.sp)
                BasicTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    textStyle = TextStyle(color = SubPageInk.Zinc50, fontSize = 14.sp),
                    cursorBrush = SolidColor(SubPageInk.Amber500),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // chips: My apps (amber) + category chips (horizontal scroll) - each
        // OPENS the full category page (web navigateHash to #/hub/c/<slug>)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val haptics = LocalHapticFeedback.current
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(Color(0x1AF59E0B))
                    .border(1.dp, Color(0x66F59E0B), CircleShape)
                    .mirrorPressClick(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenCategory(HubCatalog.MINE_SLUG)
                    })
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "My apps" + if (installed.isNotEmpty()) " · ${installed.size}" else "",
                    color = SubPageInk.Amber400,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            for ((id, label) in chips) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0x0DFFFFFF))
                        .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                        .mirrorPressClick(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenCategory(HubCatalog.slugForCategory(id) ?: id)
                        })
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "$label · ${counts[id] ?: 0}",
                        color = SubPageInk.Zinc300,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // tiles 2-up grid
        val rows = filtered.chunked(2)
        for (pair in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (app in pair) {
                    HubAppTile(
                        app = app,
                        installed = app.n.toString() in installed,
                        busy = busyId == app.n.toString(),
                        onToggle = { toggleApp(app) },
                        onOpen = { detail = app },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (filtered.isEmpty()) {
            Text(
                "Nothing matches \"$search\".",
                color = SubPageInk.Zinc500,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    detail?.let { app ->
        HubAppDetailSheet(
            app = app,
            installed = app.n.toString() in installed,
            busy = busyId == app.n.toString(),
            onToggle = {
                toggleApp(app)
                if (app.n.toString() in installed) detail = null
            },
            onDismiss = { detail = null },
        )
    }
}

/** "mine" chip filter needs the installed set - filtered above honors it. */
@Composable
private fun HubAppTile(
    app: MatrixApp,
    installed: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xB318181B))
            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                app.name,
                color = SubPageInk.Zinc50,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MirrorLucideIcon("LChevronRight", tint = SubPageInk.Zinc500, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "#" + app.n.toString().padStart(3, '0') + " · " + app.category.substringBefore(" /"),
            color = SubPageInk.Zinc500,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(10.dp))
        if (installed) {
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color(0x66F59E0B), CircleShape)
                    .background(Color(0x1AF59E0B))
                    .clickable(enabled = !busy, onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(SubPageInk.Amber500))
                Text("Connected", color = SubPageInk.Amber400, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                    .clickable(enabled = !busy, onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MirrorLucideIcon("LPlus", tint = SubPageInk.Zinc300, modifier = Modifier.size(12.dp))
                Text("Connect", color = SubPageInk.Zinc300, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** App detail: the tile's full catalog fields + the real install toggle. */
@Composable
private fun HubAppDetailSheet(app: MatrixApp, installed: Boolean, busy: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1610))
                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(20.dp))
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.name,
                    color = SubPageInk.Zinc50,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0x12FFFFFF))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LX", tint = SubPageInk.Zinc300, modifier = Modifier.size(14.dp))
                }
            }
            Text(
                "#" + app.n.toString().padStart(3, '0') + " · " + app.category,
                color = SubPageInk.Amber400,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            HubDetailRow("Nav style", app.nav)
            HubDetailRow("Input toolkit", app.input)
            HubDetailRow("Secret feature", app.secret)
            Spacer(Modifier.height(14.dp))
            HubPrimaryButton(
                label = when {
                    busy -> "Working…"
                    installed -> "Connected - tap to disconnect"
                    else -> "Connect this app"
                },
                enabled = !busy,
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HubDetailRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label.uppercase(), color = SubPageInk.Zinc500, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(value, color = SubPageInk.Zinc100, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

// ── R75 - the category FULL PAGE (web hub-category-page.tsx) ────────────────

/** Static category -> lucide glyph map (web CATEGORY_ICONS, hub-primitives.tsx:42). */
private val CATEGORY_GLYPHS: Map<String, String> = mapOf(
    "Dev-Ops / Community Boards" to "LMessagesSquare",
    "Workplace Canvas / Dev-Ops" to "LBriefcase",
    "E-Commerce Showcase" to "LShoppingBag",
    "E-Commerce / Global FinTech" to "LLandmark",
    "E-Commerce / Hyper-Apps" to "LRocket",
    "Web3 FinTech / Hyper-Apps" to "LHexagon",
    "Stark Privacy Minimalist" to "LShieldCheck",
    "Cross-Server Bridges / Matrix" to "LNetwork",
    "Spatial 3D Environments" to "LOrbit",
    "Spatial 2D/3D Art" to "LPalette",
)

/**
 * The #/hub/c/<slug> surface as a FULL page over the Hub tab: glass
 * sub-header, accent identity band behind a glass-deep card, then the
 * category's platforms (or the "mine" = My apps install truth). Zero
 * mocks - install states fan the real /api/hub/apps/<id>/install routes
 * exactly like the root panel does.
 */
@Composable
internal fun MirrorHubCategoryPage(
    slug: String,
    repository: PulseRepository,
    onBack: () -> Unit,
) {
    val isMine = slug == HubCatalog.MINE_SLUG
    val category = HubCatalog.categoryBySlug(slug)
    val meta = category?.let { HubCatalog.CATEGORY_META[it] }
    val haptics = LocalHapticFeedback.current
    var detail by remember { mutableStateOf<MatrixApp?>(null) }

    // real install set - badges on rows + the My apps filter (web hydrateInstalledSet)
    var installed by remember { mutableStateOf<Set<String>?>(null) }
    var installFailed by remember { mutableStateOf(false) }

    fun loadInstalled() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ids = mutableSetOf<String>()
                for (batch in HubCatalog.MATRIX.chunked(8)) {
                    val results = batch.map { app ->
                        CoroutineScope(Dispatchers.IO).async {
                            val state = runCatching {
                                repository.appInstallState(app.n.toString()).getOrNull()
                            }.getOrNull()
                            app to (state?.installed == true)
                        }
                    }.map { it.await() }
                    for ((app, on) in results) if (on) ids.add(app.n.toString())
                }
                installed = ids
                installFailed = false
            } catch (_: Exception) {
                installFailed = true
            }
        }
    }
    LaunchedEffect(slug) {
        installed = null
        installFailed = false
        loadInstalled()
    }

    val apps = when {
        isMine -> (installed ?: emptySet()).let { set -> HubCatalog.MATRIX.filter { it.n.toString() in set } }
        category != null -> HubCatalog.MATRIX.filter { it.category == category }
        else -> emptyList()
    }

    // header copy (web lines 74-81)
    val title = when {
        isMine -> "My apps"
        meta != null -> meta.label
        else -> "Unknown category"
    }
    val blurb = when {
        isMine -> "Everything you have connected, live from your install history."
        meta != null -> meta.blurb
        else -> "This corner of the matrix does not exist."
    }
    val accent = meta?.accent ?: HubCatalog.MINE_ACCENT
    val accentColors = HubCatalog.accentColors(accent)

    Column(
        Modifier
            .fillMaxSize()
            .background(SubPageInk.Page)
            .statusBarsPadding(),
    ) {
        // HubSubHeader (hub-primitives.tsx:129): glass-deep h-14, 40dp back pill
        Row(
            Modifier
                .fillMaxWidth()
                .background(SubPageInk.Panel)
                .border(1.dp, SubPageInk.PanelBorder)
                .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x14FFFFFF))
                    .mirrorPressClick(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBack()
                    }),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LChevronLeft", tint = SubPageInk.Zinc300, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = SubPageInk.Zinc50,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (category != null) {
                    Text(
                        "${apps.size} platform${if (apps.size == 1) "" else "s"} in the matrix",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (isMine) {
                    Text(
                        "${apps.size} connected",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // accent identity band - the category wash sits BEHIND the glass card
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to accentColors[0].copy(alpha = 0x26 / 255f),
                            0.55f to accentColors[1].copy(alpha = 0x14 / 255f),
                            1f to Color.Transparent,
                        ),
                    ),
                )
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(SubPageInk.Panel)
                    .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HubAccentTile(
                    gradient = accentColors,
                    glyph = if (isMine) "LLayers" else CATEGORY_GLYPHS[category ?: ""],
                    initials = null,
                    sizeDp = 44,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        blurb,
                        color = SubPageInk.Zinc50,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (meta != null) "Category · ${meta.slug}" else "Live from your Pulse installs",
                        color = SubPageInk.Zinc500,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // body
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        ) {
            when {
                // unknown slug (web lines 140-159)
                category == null && !isMine -> HubCategoryStateCard(
                    glyph = "LCompass",
                    title = "Category not found",
                    body = "That slug does not map to any corner of the matrix.",
                    actionLabel = "Back to the Hub",
                    onAction = onBack,
                )
                // hydrating the install set for My apps (web lines 160-164)
                isMine && installed == null && !installFailed -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(SubPageInk.Panel)
                            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp))
                            .padding(vertical = 44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HubSkeletonDots()
                        Text(
                            "Checking your connections…",
                            color = SubPageInk.Zinc500,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                // failed install verification (web lines 165-169)
                isMine && installFailed -> HubCategoryStateCard(
                    glyph = "LSearchX",
                    title = "Something went wrong",
                    body = "Could not verify your connected apps.",
                    actionLabel = "Retry",
                    onAction = { loadInstalled() },
                )
                // empty (web lines 170-190)
                apps.isEmpty() -> HubCategoryStateCard(
                    glyph = "LSearchX",
                    title = if (isMine) "No connections yet" else "Nothing here yet",
                    body = if (isMine) {
                        "You haven\u2019t connected any apps yet - open one in the matrix and tap Connect."
                    } else {
                        "Nothing lives in this category yet."
                    },
                    actionLabel = null,
                    onAction = {},
                )
                // the app list (web lines 192-236) with staggered entrances
                else -> {
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(slug) { shown = true }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(SubPageInk.Panel)
                            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp)),
                    ) {
                        apps.forEachIndexed { index, app ->
                            if (index > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(Color(0x17FFFFFF)),
                                )
                            }
                            val appear by animateFloatAsState(
                                targetValue = if (shown) 1f else 0f,
                                animationSpec = tween(320, delayMillis = index * 45, easing = FastOutSlowInEasing),
                                label = "hubCatStagger",
                            )
                            HubCategoryAppRow(
                                app = app,
                                installed = installed?.contains(app.n.toString()) == true,
                                appear = appear,
                                onOpen = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    detail = app
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // row tap opens the real app detail (the R66 install-toggle sheet)
    detail?.let { app ->
        HubAppDetailSheet(
            app = app,
            installed = installed?.contains(app.n.toString()) == true,
            busy = false,
            onToggle = {
                CoroutineScope(Dispatchers.IO).launch {
                    if (app.n.toString() in (installed ?: emptySet())) {
                        repository.uninstallApp(app.n.toString())
                    } else {
                        repository.installApp(app.n.toString())
                    }
                    loadInstalled()
                }
            },
            onDismiss = { detail = null },
        )
    }
}

/** One app row of the category list (web motion.li rows, hub-category-page.tsx:199). */
@Composable
private fun HubCategoryAppRow(
    app: MatrixApp,
    installed: Boolean,
    appear: Float,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 10.dp.toPx()
            }
            .mirrorPressClick(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val tileColors = HubCatalog.accentColors(HubCatalog.appAccent(app))
        HubAccentTile(
            gradient = tileColors,
            glyph = null,
            initials = MirrorArt.initials(app.name),
            sizeDp = 44,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    app.name,
                    color = SubPageInk.Zinc50,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "#" + app.n.toString().padStart(3, '0'),
                    color = SubPageInk.Zinc400,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                HubCatalog.appTagline(app),
                color = SubPageInk.Zinc500,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (installed) HubConnectedBadge()
        MirrorLucideIcon("LChevronRight", tint = SubPageInk.Zinc400, modifier = Modifier.size(16.dp))
    }
}

/**
 * Accent gradient icon tile (web AppIconTile / CategoryIconTile,
 * hub-primitives.tsx:61-125): brand gradient, specular top highlight,
 * initials or a lucide glyph.
 */
@Composable
private fun HubAccentTile(gradient: List<Color>, glyph: String?, initials: String?, sizeDp: Int) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(((sizeDp * 0.3f).toInt()).coerceAtLeast(10).dp))
            .background(Brush.linearGradient(gradient))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(((sizeDp * 0.3f).toInt()).coerceAtLeast(10).dp)),
        contentAlignment = Alignment.Center,
    ) {
        // specular top highlight (white 55% -> 0 across the top half, 40% opacity)
        Box(
            Modifier
                .fillMaxWidth()
                .height((sizeDp / 2).dp)
                .align(Alignment.TopStart)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x8CFFFFFF), Color(0x00FFFFFF)),
                    ),
                )
                .alpha(0.4f),
        )
        when {
            glyph != null -> MirrorLucideIcon(
                glyph,
                tint = Color.White,
                modifier = Modifier.size((sizeDp * 0.46f).dp),
            )
            initials != null -> Text(
                initials,
                color = Color.White,
                fontSize = ((sizeDp * 0.34f).coerceAtLeast(11)).sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

/** Connected pill with the live ping dot (web ConnectedBadge, hub-primitives.tsx:171). */
@Composable
private fun HubConnectedBadge() {
    val ping = rememberInfiniteTransition(label = "hubBadgePing")
    val pingAlpha by ping.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "hubBadgePingAlpha",
    )
    val pingScale by ping.animateFloat(
        initialValue = 1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "hubBadgePingScale",
    )
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .border(1.dp, Color(0x66F59E0B), CircleShape)
            .background(Color(0x1AF59E0B))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        scaleX = pingScale
                        scaleY = pingScale
                        this.alpha = pingAlpha
                    }
                    .clip(CircleShape)
                    .background(SubPageInk.Amber500),
            )
            Box(Modifier.size(6.dp).clip(CircleShape).background(SubPageInk.Amber500))
        }
        Text("Connected", color = SubPageInk.Amber400, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Three pulsing skeleton dots (web SkeletonDots, hub-data.tsx). */
@Composable
private fun HubSkeletonDots() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 3) {
            val alpha by rememberInfiniteTransition(label = "hubSkeleton$i").animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(650, delayMillis = i * 160),
                    RepeatMode.Reverse,
                ),
                label = "hubSkeletonAlpha$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(SubPageInk.Amber500),
            )
        }
    }
}

/** Full-width glass state card with an optional outline pill action. */
@Composable
private fun HubCategoryStateCard(
    glyph: String,
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SubPageInk.Panel)
            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x1AF59E0B)),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon(glyph, tint = SubPageInk.Amber500, modifier = Modifier.size(28.dp))
        }
        Text(
            title,
            color = SubPageInk.Zinc100,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            body,
            color = SubPageInk.Zinc500,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null) {
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(CircleShape)
                    .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                    .mirrorPressClick(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onAction()
                    })
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(actionLabel, color = SubPageInk.Zinc300, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
