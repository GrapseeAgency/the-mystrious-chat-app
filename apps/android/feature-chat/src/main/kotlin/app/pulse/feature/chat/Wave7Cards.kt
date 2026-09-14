package app.pulse.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.protocol.GameDetailDto
import app.pulse.protocol.GameMatchDto
import app.pulse.protocol.PulseWave7Logic
import app.pulse.protocol.RedPacketDetailDto
import app.pulse.protocol.TournamentSummaryDto
import app.pulse.ui.PulsePalette
import kotlinx.coroutines.delay

/**
 * Wave 7 in-bubble rich-object cards (F-RO-02 red packet, F-RO-07 tic-tac-toe,
 * F-RO-08 tournament). Poll transports mirror the web exactly: game card
 * 1500 ms while status == active (game-tictactoe-card.tsx:33), red packet
 * bubble 20 s (redpacket-bubble.tsx:79); tournament never self-polls (web
 * opens fetches standings on expand).
 */

// ── Red packet (F-RO-02) ─────────────────────────────────────────────────

@Composable
fun RedPacketCard(
    packetId: String,
    total: Long,
    count: Int,
    note: String?,
    isMine: Boolean,
    viewerId: String,
    load: suspend () -> RedPacketDetailDto?,
    onGrab: () -> Unit,
    onOpenDetail: (RedPacketDetailDto) -> Unit,
) {
    var detail by remember(packetId) { mutableStateOf<RedPacketDetailDto?>(null) }
    var grabbed by remember(packetId) { mutableStateOf(0) }

    // 20 s poll — web redpacket-bubble.tsx:79.
    LaunchedEffect(packetId) {
        while (true) {
            detail?.let { d -> grabbed = d.packet?.grabbed ?: 0 }
            load()?.let { detail = it }
            delay(20_000)
        }
    }

    val status = detail?.packet?.status
    val myGrab = detail?.myGrab
    val grabbedNow: Int = detail?.packet?.grabbed ?: 0
    val exhausted = status == "exhausted" || status == "expired"
    val canGrab = !isMine && !exhausted && myGrab == null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFB4453A), Color(0xFF8C2F28))))
            .clickable(enabled = detail != null) { detail?.let(onOpenDetail) }
            .padding(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x33FFFFFF)),
        ) { Text("🧧", fontSize = 22.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                note?.ifBlank { null } ?: "Red packet",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = when {
                myGrab != null && myGrab > 0 -> "You grabbed ${myGrab} PC"
                isMine -> "$grabbedNow/$count claimed · $total PC"
                exhausted -> when (status) {
                    "expired" -> "Expired — unclaimed PC refunded"
                    else -> "Fully grabbed"
                }
                else -> "$total PC · $count grabs"
            }
            Text(meta, color = Color(0xCCFFFFFF), fontSize = 12.sp)
        }
        if (canGrab) {
            Button(
                onClick = onGrab,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF8C2F28)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp),
            ) { Text("Open", fontSize = 13.sp) }
        }
    }
}

@Composable
fun RedPacketDetailSheetBody(
    detail: RedPacketDetailDto,
) {
    val p = detail.packet
    val myGrab = detail.myGrab ?: 0
    Column(Modifier.padding(horizontal = 18.dp)) {
        Text(
            when (p?.status) {
                "expired" -> "Expired — unclaimed PC refunded to the sender"
                "exhausted" -> "Fully grabbed"
                else -> "${p?.grabbed ?: 0}/${p?.count ?: 0} claimed"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text("From ${detail.senderName} · ${p?.total ?: 0} PC in ${p?.count ?: 0} grabs", fontWeight = FontWeight.SemiBold)
        if (myGrab > 0) {
            Spacer(Modifier.height(4.dp))
            Text("You grabbed $myGrab PC 🎉", color = PulsePalette.Emerald, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        if (detail.grabs.isEmpty()) {
            Text("No grabs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            detail.grabs.forEach { g ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(PulsePalette.Emerald.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(g.name.take(1).uppercase(), fontSize = 12.sp, color = PulsePalette.Emerald) }
                    Spacer(Modifier.width(8.dp))
                    Text(g.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${g.amount} PC", fontWeight = FontWeight.SemiBold, color = PulsePalette.Emerald)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

// ── Tic-tac-toe (F-RO-07) ────────────────────────────────────────────────

@Composable
fun TicTacToeCard(
    matchId: String,
    viewerId: String,
    initial: GameDetailDto?,
    load: suspend () -> GameDetailDto?,
    onMove: (Int) -> Unit,
    onJoin: () -> Unit,
) {
    var detail by remember(matchId) { mutableStateOf(initial) }

    // 1500 ms poll while active — web game-tictactoe-card.tsx:33,134.
    LaunchedEffect(matchId, detail?.match?.status) {
        while (detail?.match?.status == "active") {
            delay(1_500)
            load()?.let { detail = it }
        }
    }

    val m = detail?.match ?: GameMatchDto(id = matchId)
    val mySide = PulseWave7Logic.sideOf(m, viewerId)
    val myTurn = PulseWave7Logic.isMyTurn(m, viewerId)
    val openSeat = m.status == "active" && m.playerOId == null && mySide == null

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
    ) {
        Text(
            when {
                m.status == "x_won" -> "X wins — ${detail?.playerX?.name ?: "X"}"
                m.status == "o_won" -> "O wins — ${detail?.playerO?.name ?: "O"}"
                m.status == "draw" -> "Draw — both +10 XP"
                m.playerOId == null -> "⚔️ Open challenge — tap a seat to join"
                myTurn -> "Your turn ($mySide)"
                else -> "Turn: ${m.turn}"
            },
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(168.dp),
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(168.dp)) {
                val cell = size.width / 3f
                val line = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                val grid = listOf(1, 2)
                grid.forEach { i ->
                    drawLine(Color.Gray.copy(alpha = 0.4f), androidx.compose.ui.geometry.Offset(cell * i, 8f), androidx.compose.ui.geometry.Offset(cell * i, size.height - 8f), line.width)
                    drawLine(Color.Gray.copy(alpha = 0.4f), androidx.compose.ui.geometry.Offset(8f, cell * i), androidx.compose.ui.geometry.Offset(size.width - 8f, cell * i), line.width)
                }
                // winning strike
                m.winLine?.takeIf { it.size == 3 }?.let { l ->
                    val (a, c) = l.first() to l.last()
                    val ax = (a % 3) * cell + cell / 2; val ay = (a / 3) * cell + cell / 2
                    val cx = (c % 3) * cell + cell / 2; val cy = (c / 3) * cell + cell / 2
                    drawLine(
                        PulsePalette.Emerald,
                        androidx.compose.ui.geometry.Offset(ax, ay),
                        androidx.compose.ui.geometry.Offset(cx, cy),
                        Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round).width,
                    )
                }
            }
            Column(Modifier.size(168.dp)) {
                repeat(3) { r ->
                    Row(Modifier.weight(1f)) {
                        repeat(3) { c ->
                            val idx = r * 3 + c
                            val mark = PulseWave7Logic.cellAt(m.board, idx)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clickable(enabled = myTurn && mark == ' ' && m.status == "active") { onMove(idx) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    when (mark) {
                                        'X' -> "✕"
                                        'O' -> "◯"
                                        else -> ""
                                    },
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (mark == 'X') PulsePalette.Emerald else Color(0xFFF59E0B),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "✕ ${detail?.playerX?.name ?: "Player"}",
                Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = PulsePalette.Emerald,
                fontSize = 13.sp,
            )
            Text("vs", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(
                detail?.playerO?.name ?: "waiting…",
                Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Color(0xFFF59E0B),
                fontSize = 13.sp,
            )
        }
        if (openSeat) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onJoin, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Take the O seat")
            }
        }
    }
}

// ── Tournament (F-RO-08) ─────────────────────────────────────────────────

@Composable
fun TournamentCard(
    tournamentId: String,
    name: String,
    viewerId: String,
    isAdmin: Boolean,
    load: suspend () -> TournamentSummaryDto?,
    onJoin: () -> Unit,
    onFinish: () -> Unit,
) {
    var t by remember(tournamentId) { mutableStateOf<TournamentSummaryDto?>(null) }
    LaunchedEffect(tournamentId) { load()?.let { t = it } }

    val joined = t?.entries?.any { it.userId == viewerId } == true
    val running = t?.status == "running"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2A2118), Color(0xFF1F1A12))))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🏆", fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text(name.ifBlank { "Tournament" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (running) "${t?.playerCount ?: t?.entries?.size ?: 0} players" else "finished",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        t?.entries?.take(3)?.forEachIndexed { i, e ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", color = PulsePalette.Emerald, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(e.name, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${e.points} pts · ${e.wins}W ${e.losses}L ${e.draws}D", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            if (running && !joined) {
                Button(onClick = onJoin, modifier = Modifier.weight(1f)) { Text("Join") }
                Spacer(Modifier.width(8.dp))
            }
            if (running && isAdmin) {
                OutlinedButton(onClick = onFinish, modifier = Modifier.weight(1f)) { Text("Finish season") }
            }
        }
    }
}
