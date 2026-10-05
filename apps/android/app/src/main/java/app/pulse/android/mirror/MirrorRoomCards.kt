package app.pulse.android.mirror

// Pulse - in-chat red packet + tic-tac-toe cards, native ports of
// redpacket-sheet.tsx (R23-a), redpacket-bubble.tsx (R23-a) and
// game-tictactoe-card.tsx (R23-b). Real data only - every render reads the
// live REST endpoints through PulseRepository, same as the web cards.
//
//   Red packet sheet : amount (PC) + grab stepper (1..50) + 60-char note,
//                      live split preview + balance affordability check,
//                      POSTs createRedPacket (server debits the wallet).
//   Red packet bubble: self-fetching detail, 20s refresh while open,
//                      tap-to-grab with burst particles, reveal flip,
//                      grabs ledger with the Crown lucky badge.
//   Tic-tac-toe card : self-fetch + 1.5s poll while active, optimistic
//                      cell fill, win-line ring, Join-as-O, rematch,
//                      confetti exactly once on an observed own win.

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import app.pulse.core.fx.PulseFx
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.GameDetailDto
import app.pulse.protocol.RedPacketDetailDto
import app.pulse.protocol.RedPacketPayloadDto
import app.pulse.protocol.GamePayloadDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.floor

// bounds mirror the server-side validation (web L56-60)
private const val RP_TOTAL_MIN = 1L
private const val RP_TOTAL_MAX = 10_000L
private const val RP_COUNT_MIN = 1
private const val RP_COUNT_MAX = 50
private const val RP_NOTE_MAX = 60

private val RP_GRADIENT = listOf(Color(0xFFF59E0B), Color(0xFFF43F5E))
private val RP_VIOLET = Color(0xFF8B5CF6)
private val RP_EMERALD = Color(0xFF10B981)
private val RP_ROSE = Color(0xFFF43F5E)
private val RP_AMBER = Color(0xFFF59E0B)

// red packet composer sheet (web RedPacketSheet L64) ==========================

/**
 * Dark bottom sheet that sends a red packet into the room: amount, grab
 * count stepper, festive note, live split preview + real wallet balance.
 * POSTs through the repository (server debits the sender's wallet now).
 */
@Composable
internal fun MirrorRedPacketSheet(
    conversationId: String,
    repository: PulseRepository,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    var amountRaw by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(1) }
    var note by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<Long?>(null) }
    var errorNote by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current

    // fresh compose every open + real balance for the affordability hint
    LaunchedEffect(Unit) {
        runCatching { repository.wallet().getOrThrow() }
            .onSuccess { balance = it.wallet.coins }
    }

    val total = amountRaw.toLongOrNull()
    val totalValid = total != null && total >= RP_TOTAL_MIN && total <= RP_TOTAL_MAX
    val countValid = count in RP_COUNT_MIN..RP_COUNT_MAX
    val countFitsTotal = totalValid && count <= (total ?: 0L)
    val affordable = balance == null || (totalValid && (total ?: 0L) <= balance)
    val valid = totalValid && countValid && countFitsTotal && affordable
    val avg = if (totalValid && countFitsTotal) floor(total.toDouble() / count).toLong() else 0L

    MirrorSheet(title = "Send a red packet", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // header: gradient gift chip + subtitle (web L161-185)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(RP_GRADIENT[1].copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LGift", tint = RP_ROSE, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text("Red packet", color = MirrorArt.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Random split - every grab wins at least 1 PC",
                        color = MirrorArt.Dim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // amount
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TOTAL (PC)", color = MirrorArt.Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MirrorArt.White7)
                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = amountRaw,
                        onValueChange = { amountRaw = it.take(6).filter { ch -> ch.isDigit() } },
                        singleLine = true,
                        textStyle = TextStyle(color = MirrorArt.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        cursorBrush = SolidColor(MirrorArt.Accent),
                        decorationBox = { inner ->
                            Box {
                                if (amountRaw.isEmpty()) Text("30", color = MirrorArt.Faint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                inner()
                                Text(
                                    "PC",
                                    color = MirrorArt.Dim,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (amountRaw.isNotEmpty() && !totalValid) {
                    SheetError("Enter a whole number between $RP_TOTAL_MIN and $RP_TOTAL_MAX PC.")
                } else if (!countFitsTotal && totalValid) {
                    SheetError("Total must be at least $count PC so every grab wins 1 PC.")
                } else if (!affordable) {
                    SheetError("Insufficient PC - your balance is $balance.")
                }
            }

            // count stepper (web L232-275)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("GRABS", color = MirrorArt.Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MirrorArt.White7)
                            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
                            .clickable(enabled = count > RP_COUNT_MIN) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                count = (count - 1).coerceAtLeast(RP_COUNT_MIN)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LMinus", tint = MirrorArt.Text, modifier = Modifier.size(16.dp))
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MirrorArt.White7)
                            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$count", color = MirrorArt.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MirrorArt.White7)
                            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
                            .clickable(enabled = count < RP_COUNT_MAX) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                count = (count + 1).coerceAtMost(RP_COUNT_MAX)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LPlus", tint = MirrorArt.Text, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // note with the live counter (web L278-297)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text("NOTE", color = MirrorArt.Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${note.length}/$RP_NOTE_MAX", color = MirrorArt.Faint, fontSize = 12.sp)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MirrorArt.White7)
                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = note,
                        onValueChange = { note = it.take(RP_NOTE_MAX) },
                        singleLine = true,
                        textStyle = TextStyle(color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(MirrorArt.Accent),
                        decorationBox = { inner ->
                            Box {
                                if (note.isEmpty()) Text("Lucky money, on me!", color = MirrorArt.Faint, fontSize = 14.sp)
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // live preview + balance (web L300-320)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    buildString {
                        append(if (totalValid) "$total PC" else "- PC")
                        append(" - $count grab" + if (count == 1) "" else "s")
                        if (totalValid && countFitsTotal) append(" - about $avg PC each")
                    },
                    color = MirrorArt.TextSoft,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (balance != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MirrorLucideIcon(
                            "LWallet",
                            tint = if (affordable) MirrorArt.Dim else RP_ROSE,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "Balance $balance PC",
                            color = if (affordable) MirrorArt.Dim else RP_ROSE,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // submit (web L323-338: amber-rose gradient, disabled = zinc)
            val sheetError = errorNote
            if (sheetError != null) SheetError(sheetError)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (valid && !pending) {
                            androidx.compose.foundation.Brush.linearGradient(RP_GRADIENT)
                        } else {
                            androidx.compose.foundation.Brush.linearGradient(
                                listOf(MirrorArt.Chip, MirrorArt.Chip),
                            )
                        },
                    )
                    .clickable(enabled = valid && !pending) {
                        pending = true
                        errorNote = null
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val outcome = runCatching {
                                repository.createRedPacket(
                                    conversationId = conversationId,
                                    total = total!!,
                                    count = count,
                                    note = note.trim().take(RP_NOTE_MAX).ifBlank { null },
                                ).getOrThrow()
                            }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                pending = false
                                outcome.onSuccess {
                                    onSent()
                                }.onFailure { failure ->
                                    errorNote = failure.message ?: "Could not send the red packet."
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (pending) {
                    MirrorLucideIcon("LLoaderCircle", tint = MirrorArt.Text, modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        if (totalValid) "Send $total PC" else "Send red packet",
                        color = if (valid) MirrorArt.Text else MirrorArt.Faint,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetError(text: String) {
    Text(text, color = RP_ROSE, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
}

// red packet bubble (web RedPacketBubble L52) =================================

/**
 * Self-fetching kind:"redpacket" bubble. Tap opens the packet (POST grab)
 * with a spring flip + burst particles, or expands the grabs ledger with
 * the Crown lucky badge on the max grab. Every render reads the detail API.
 */
@Composable
internal fun MirrorRedPacketBubble(
    packetId: String,
    viewerId: String,
    mine: Boolean,
    senderName: String,
    repository: PulseRepository,
) {
    var detail by remember(packetId) { mutableStateOf<RedPacketDetailDto?>(null) }
    var failed by remember(packetId) { mutableStateOf(false) }
    var grabbing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var myGrabLive by remember(packetId) { mutableStateOf<Long?>(null) }
    var grabbedLive by remember(packetId) { mutableStateOf<Int?>(null) }
    val haptics = LocalHapticFeedback.current
    // reveal flip - spring rotationY from 90 to 0 once a grab lands (web L180-191)
    val flip = remember { Animatable(if (myGrabLive != null) 0f else -90f) }

    // self-fetch + 20s refresh while the packet is still open (web refetchInterval)
    LaunchedEffect(packetId) {
        while (true) {
            runCatching { repository.redPacket(packetId).getOrThrow() }
                .onSuccess {
                    detail = it
                    failed = false
                    if (myGrabLive == null) myGrabLive = it.myGrab
                }
                .onFailure { failed = true }
            val stillOpen = detail?.packet?.status == "open"
            if (!stillOpen) break
            delay(20_000L)
        }
    }
    LaunchedEffect(myGrabLive) {
        if (myGrabLive != null) flip.animateTo(
            0f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        )
    }

    val packet = detail?.packet
    val myGrab = myGrabLive ?: detail?.myGrab
    val isMine = mine || (detail?.isMine ?: false)
    val grabbed = grabbedLive ?: packet?.grabbed ?: 0
    val openable = packet?.status == "open" && !isMine && myGrab == null

    // status line exactly per the web QA contract (L89-96)
    val statusLine = when {
        isMine && packet != null -> "$grabbed of ${packet.count} grabbed - tap for details"
        myGrab != null -> "+$myGrab PC"
        packet == null -> ""
        packet.status == "open" -> "Tap to open"
        packet.status == "exhausted" -> "All grabbed"
        else -> "Expired"
    }
    val maxAmount = detail?.grabs?.maxOfOrNull { it.amount } ?: 0L
    val noteText = packet?.note?.trim()?.takeIf { it.isNotEmpty() } ?: "A little luck for you"

    if (failed && packet == null) {
        Row(
            Modifier
                .widthIn(min = 200.dp, max = 224.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MirrorArt.Chip)
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MirrorLucideIcon("LGift", tint = MirrorArt.Faint, modifier = Modifier.size(22.dp))
            Text("Red packet unavailable", color = MirrorArt.Dim, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
        return
    }

    Column(Modifier.widthIn(min = 200.dp, max = 240.dp)) {
        // envelope face (web L158-232): amber-rose gradient, gift icon,
        // note + status + sender lines
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(androidx.compose.foundation.Brush.linearGradient(RP_GRADIENT))
                .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(24.dp))
                .clickable {
                    when {
                        openable && !grabbing -> {
                            grabbing = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                val outcome = runCatching { repository.grabRedPacket(packetId).getOrThrow() }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    grabbing = false
                                    outcome.onSuccess { res ->
                                        myGrabLive = res.amount
                                        grabbedLive = res.grabbed
                                        flip.snapTo(90f)
                                        // web fireParticles({kind:'burst',count:90}) + haptic 30
                                        PulseFx.fire(PulseFx.BurstKind.BURST, 90)
                                    }.onFailure { failure ->
                                        expanded = true
                                    }
                                }
                            }
                        }
                        else -> {
                            expanded = !expanded
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        rotationY = if (myGrab != null) flip.value else 0f
                        alpha = if (abs_kt(flip.value) >= 89f && myGrab != null) 0f else 1f
                    }
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                if (grabbing) {
                    MirrorLucideIcon("LLoaderCircle", tint = Color(0xE6FFFFFF), modifier = Modifier.size(22.dp))
                } else if (myGrab != null) {
                    Text("+$myGrab", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                } else {
                    MirrorLucideIcon("LGift", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(noteText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusLine.ifBlank { "..." }, color = Color(0xE6FFFFFF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (isMine) "From you" else "From $senderName",
                    color = Color(0xB3FFFFFF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // grabs ledger (web L234-296): owner detail + claim history
        if (expanded && detail != null) {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MirrorArt.Chip)
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                    .padding(vertical = 8.dp),
            ) {
                val grabs = detail!!.grabs
                if (grabs.isEmpty()) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MirrorLucideIcon("LUsers", tint = MirrorArt.Dim, modifier = Modifier.size(14.dp))
                        Text("No grabs yet", color = MirrorArt.Dim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp),
                    ) {
                        for (grab in grabs) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(MirrorArt.White10),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        grab.name.take(1).uppercase(),
                                        color = MirrorArt.Dim,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        grab.name + if (grab.userId == viewerId) " - you" else "",
                                        color = MirrorArt.TextSoft,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (grab.amount == maxAmount && grabs.size > 1) {
                                    Row(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(RP_AMBER.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        MirrorLucideIcon("LCrown", tint = RP_AMBER, modifier = Modifier.size(11.dp))
                                        Text("Lucky", color = RP_AMBER, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text("+" + grab.amount, color = RP_AMBER, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Text(
                    "Total ${detail!!.packet!!.total} PC - $grabbed/${detail!!.packet!!.count} grabbed",
                    color = MirrorArt.Dim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private fun abs_kt(v: Float): Float = if (v < 0f) -v else v

// tic-tac-toe card (web GameTicTacToeCard L115) ===============================

private val NAME_COLORS = mapOf(
    "emerald" to Color(0xFF34D399),
    "rose" to Color(0xFFFB7185),
    "amber" to Color(0xFFFBBF24),
    "violet" to Color(0xFFA78BFA),
    "teal" to Color(0xFF2DD4BF),
    "orange" to Color(0xFFFB923C),
    "pink" to Color(0xFFF472B6),
    "cyan" to Color(0xFF22D3EE),
)

private fun nameColor(color: String?): Color = NAME_COLORS[color] ?: RP_EMERALD

/**
 * A match rides the chat as a real kind:"game" message. Self-fetches the
 * detail, polls every 1.5s while active, plays moves with optimistic cell
 * fill and celebrates an own win with the app-wide confetti layer ONCE.
 */
@Composable
internal fun MirrorTicTacToeCard(
    matchId: String,
    viewerId: String,
    repository: PulseRepository,
    onRematch: () -> Unit,
) {
    var data by remember(matchId) { mutableStateOf<GameDetailDto?>(null) }
    var failed by remember(matchId) { mutableStateOf(false) }
    var pendingCell by remember { mutableIntStateOf(-1) }
    var joining by remember { mutableStateOf(false) }
    var rematching by remember { mutableStateOf(false) }
    var celebrated by remember(matchId) { mutableStateOf(false) }
    var errorNote by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current

    // self-fetch + live sync: 1.5s poll ONLY while the match is active (web L129)
    suspend fun load() {
        runCatching { repository.game(matchId).getOrThrow() }
            .onSuccess {
                data = it
                failed = false
            }
            .onFailure { failed = true }
    }
    LaunchedEffect(matchId) {
        load()
        while (data?.match?.status == "active") {
            delay(1_500L)
            load()
        }
    }
    // confetti ONCE when an own win is observed (web L163)
    LaunchedEffect(data?.match?.status) {
        val match = data?.match ?: return@LaunchedEffect
        val won = (match.status == "x_won" || match.status == "o_won") && match.winnerId == viewerId
        if (won && !celebrated) {
            celebrated = true
            PulseFx.fire(PulseFx.BurstKind.CONFETTI, 120)
        }
    }

    val match = data?.match
    if (match == null) {
        // loading skeleton / failure card (web L241-273)
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MirrorArt.Chip)
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MirrorLucideIcon(if (failed) "LSwords" else "LLoaderCircle", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                Text(
                    if (failed) "This game could not be loaded." else "Tic-tac-toe",
                    color = if (failed) MirrorArt.Dim else MirrorArt.Faint,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (failed) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(RP_EMERALD.copy(alpha = 0.12f))
                        .clickable {
                            failed = false
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { load() }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("Retry", color = RP_EMERALD, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // 3x3 pulse skeleton
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MirrorArt.White7),
                        )
                    }
                }
            }
        }
        return
    }

    val playerX = data!!.playerX
    val playerO = data!!.playerO
    val mySide: Char? = when {
        match.playerXId == viewerId -> 'X'
        match.playerOId == viewerId -> 'O'
        else -> null
    }
    val isPlayer = mySide != null
    val isMyTurn = match.status == "active" && mySide != null && match.turn == mySide.toString()
    val isOpenChallenge = match.status == "active" && match.playerOId == null
    val canJoin = isOpenChallenge && viewerId != match.playerXId
    val finished = match.status != "active"
    val winnerName = when (match.status) {
        "x_won" -> playerX.name
        "o_won" -> playerO?.name
        else -> null
    }
    val winSet = match.winLine?.toSet() ?: emptySet()

    // board cells - server truth overlaid with the optimistic pending move
    val glyphs = match.board.padEnd(9, ' ').toCharArray()
    if (pendingCell >= 0 && glyphs[pendingCell] == ' ' && mySide != null) {
        glyphs[pendingCell] = mySide
    }

    val statusText = when {
        finished && winnerName != null -> "$winnerName wins!"
        finished && match.status == "draw" -> "Draw - board is full."
        finished -> "Match abandoned."
        isOpenChallenge && mySide == 'X' -> "Open challenge - waiting for an opponent..."
        isOpenChallenge -> "Open challenge"
        isMyTurn -> "Your move - tap a cell"
        mySide == 'X' && match.playerOId == null -> "Waiting for an opponent..."
        else -> "Waiting for " + (if (mySide == 'X') playerO?.name ?: "opponent" else playerX.name) + "..."
    }

    // shimmering status (web StatusLine L100) - 1.6s alpha pulse loop
    val shimmer = rememberInfiniteTransition(label = "statusShimmer").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "statusShimmerAlpha",
    )

    Column(
        Modifier
            .widthIn(max = 300.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MirrorArt.Chip)
            .border(1.dp, RP_EMERALD.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // header row (web L312-333)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MirrorLucideIcon("LSwords", tint = RP_EMERALD, modifier = Modifier.size(16.dp))
                Text("Tic-tac-toe", color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            val badge = when {
                finished && winnerName != null -> "Finished"
                finished && match.status == "draw" -> "Draw"
                finished -> "Finished"
                else -> "Live"
            }
            Text(
                badge,
                color = if (finished) MirrorArt.Dim else RP_EMERALD,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (finished) MirrorArt.Chip else RP_EMERALD.copy(alpha = 0.15f),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        // players line (web L336-346)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("X", color = MirrorArt.Dim, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(playerX.name, color = nameColor(playerX.color), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("vs", color = MirrorArt.Dim, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text("O", color = MirrorArt.Dim, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            if (playerO != null) {
                Text(playerO.name, color = nameColor(playerO.color), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("open seat", color = MirrorArt.Dim, fontSize = 12.5.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
        }

        // board (web L349-401)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (rowIdx in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (colIdx in 0 until 3) {
                        val i = rowIdx * 3 + colIdx
                        val glyph = glyphs[i]
                        val cellFilled = glyph != ' '
                        val isWinCell = i in winSet
                        val dimmed = finished && winnerName != null && !isWinCell
                        val canTap = isMyTurn && !cellFilled && pendingCell < 0
                        val markColor = when {
                            glyph == 'X' -> nameColor(playerX.color)
                            glyph == 'O' && playerO != null -> nameColor(playerO.color)
                            else -> MirrorArt.Dim
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isWinCell) RP_EMERALD.copy(alpha = 0.15f) else MirrorArt.White7)
                                .border(
                                    1.dp,
                                    when {
                                        isWinCell -> RP_EMERALD.copy(alpha = 0.8f)
                                        else -> MirrorArt.Hairline
                                    },
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable(enabled = canTap) {
                                    // optimistic fill + real move (web playMove L169)
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    pendingCell = i
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        val outcome = runCatching { repository.gameMove(matchId, i).getOrThrow() }
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            pendingCell = -1
                                            outcome.onSuccess { data = it }
                                                .onFailure { errorNote = "Move failed - try again." }
                                        }
                                    }
                                }
                                .alpha(if (dimmed) 0.45f else if (!canTap && !cellFilled) 0.7f else 1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (cellFilled) {
                                Text(
                                    glyph.toString(),
                                    color = markColor,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.alpha(if (pendingCell == i) 0.6f else 1f),
                                )
                            }
                        }
                    }
                }
            }
        }

        // status strip / join / rematch (web L404-473)
        val err = errorNote
        if (err != null) {
            Text(err, color = RP_ROSE, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        when {
            finished && winnerName != null -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    MirrorLucideIcon("LTrophy", tint = RP_EMERALD, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(statusText, color = RP_EMERALD, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            finished -> {
                Text(
                    statusText,
                    color = MirrorArt.Dim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            canJoin -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(CircleShape)
                        .background(RP_EMERALD.copy(alpha = 0.85f))
                        .clickable(enabled = !joining) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            joining = true
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                val outcome = runCatching { repository.joinGame(matchId).getOrThrow() }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    joining = false
                                    outcome.onSuccess { data = it }
                                        .onFailure { errorNote = "Could not join this match." }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (joining) {
                        MirrorLucideIcon("LLoaderCircle", tint = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MirrorLucideIcon("LSwords", tint = Color.White, modifier = Modifier.size(16.dp))
                            Text("Join as O", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            else -> {
                Text(
                    statusText,
                    color = MirrorArt.Dim.copy(alpha = shimmer.value),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // rematch - finished matches with me as a player (web L450-473)
        if (finished && isPlayer && match.playerOId != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .background(RP_EMERALD.copy(alpha = 0.10f))
                    .border(1.dp, RP_EMERALD.copy(alpha = 0.30f), CircleShape)
                    .clickable(enabled = !rematching) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        rematching = true
                        val opponentId = if (mySide == 'X') match.playerOId else match.playerXId
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val outcome = opponentId?.let {
                                runCatching { repository.createGame(match.conversationId, it).getOrThrow() }
                            }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                rematching = false
                                outcome?.onSuccess { onRematch() }
                                if (outcome == null) errorNote = "Rematch failed - try again."
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (rematching) {
                    MirrorLucideIcon("LLoaderCircle", tint = RP_EMERALD, modifier = Modifier.size(16.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MirrorLucideIcon("LRefreshCw", tint = RP_EMERALD, modifier = Modifier.size(16.dp))
                        Text("Rematch", color = RP_EMERALD, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Parses a kind:"redpacket" payload blob - never throws (web parseRedPacketPayload). */
internal fun mirrorParseRedPacketPayload(payload: String?): RedPacketPayloadDto? {
    if (payload.isNullOrBlank()) return null
    return runCatching { Json.decodeFromString(RedPacketPayloadDto.serializer(), payload) }.getOrNull()
        ?.takeIf { it.packetId.isNotBlank() }
}

/** Parses a kind:"game" payload blob - never throws (web parseGamePayload). */
internal fun mirrorParseGamePayload(payload: String?): GamePayloadDto? {
    if (payload.isNullOrBlank()) return null
    return runCatching { Json.decodeFromString(GamePayloadDto.serializer(), payload) }.getOrNull()
        ?.takeIf { it.matchId.isNotBlank() }
}
