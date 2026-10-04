package app.pulse.android.mirror

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * R66 - the Contacts tab (contacts-tab.tsx verbatim conversion): the real
 * A-Z indexed people directory the home kebab opens on the web. Zinc-900
 * page, glass header (Contacts + "N people on this Pulse · M online"), the
 * You card, sticky glass letter rails, PersonRow anatomy (presence halo,
 * @handle, Chat chip, status line, hairline divider) and the Add flow that
 * creates REAL DMs. Rows open the person's conversation (create-if-needed),
 * the same real action the web's user page "Message" button performs.
 */

@Composable
internal fun MirrorContacts(
    repository: PulseRepository,
    viewerId: String,
    onOpenConversation: (String) -> Unit,
    onGoProfile: () -> Unit,
    onNewGroup: () -> Unit,
    onAdd: () -> Unit,
) {
    var people by remember { mutableStateOf<List<User>>(emptyList()) }
    var viewerName by remember { mutableStateOf("") }
    var viewerAbout by remember { mutableStateOf("") }
    var viewerColor by remember { mutableStateOf<String?>(null) }

    // live presence + conversation flows (same repository streams the shell uses)
    val onlineIds by repository.observePresence().collectAsState(initial = emptySet())
    val conversations by repository.observeConversations().collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        people = repository.users("").getOrDefault(emptyList()).filter { it.id != viewerId }
        val me = repository.me()
        viewerName = me?.name.orEmpty()
        viewerAbout = me?.bio.orEmpty()
        viewerColor = me?.color
    }

    val others = people
    val onlineCount = others.count { it.id in onlineIds }

    // DM-membership marks: the DM whose other member is the person (web dmByUserId)
    val dmByUserId = remember(conversations) {
        val map = mutableMapOf<String, String>()
        for (c in conversations) {
            if (!c.isGroupish) {
                val other = c.members.firstOrNull { it.id != viewerId }
                if (other != null) map[other.id] = c.id
            }
        }
        map
    }

    // A-Z index sections ('#' bucket last) - web indexLetterOf
    val sections = others
        .groupBy { indexLetterOf(it.name) }
        .toSortedMap(compareBy({ it == "#" }, { it }))

    Column(
        Modifier
            .fillMaxSize()
            .background(SubPageInk.Page)
            .statusBarsPadding(),
    ) {
        // header: border-b zinc-800/80, "Contacts" xl bold + count subtitle
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
                Column(Modifier.weight(1f)) {
                    Text("Contacts", color = SubPageInk.Zinc50, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        others.size.toString() + (if (others.size == 1) " person" else " people") +
                            " on this Pulse · $onlineCount online",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                // glass-pill 40dp UsersRound (new group)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SubPageInk.GlassPill)
                        .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                        .clickable(onClick = onNewGroup),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LUsersRound", tint = SubPageInk.Zinc300, modifier = Modifier.size(18.dp))
                }
                // glass-pill h-10 Add (amber, UserPlus)
                Row(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clip(CircleShape)
                        .background(SubPageInk.GlassPill)
                        .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                        .clickable(onClick = onAdd)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MirrorLucideIcon("LUserPlus", tint = SubPageInk.Amber400, modifier = Modifier.size(16.dp))
                    Text("Add", color = SubPageInk.Amber400, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x1AFFFFFF)),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (others.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(24.dp))
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0x1AF59E0B)),
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon("LUsersRound", tint = SubPageInk.Amber400, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("It's quiet in here", color = SubPageInk.Zinc100, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "No other humans yet. Add someone with the button above and they will appear here.",
                            color = SubPageInk.Zinc500,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else {
                // You card - glass-deep rounded-3xl p-3, opens the profile
                item(key = "you") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(SubPageInk.Panel)
                            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp))
                            .clickable(onClick = onGoProfile)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(44.dp)) {
                            MirrorAvatar(
                                name = viewerName,
                                color = viewerColor,
                                isGroup = false,
                                groupId = "",
                                online = true,
                                showPresence = true,
                                sizeDp = 44,
                                cornerDp = 22,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                viewerName.ifBlank { "You" },
                                color = SubPageInk.Zinc100,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                viewerAbout,
                                color = SubPageInk.Zinc500,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MirrorLucideIcon("LChevronRight", tint = SubPageInk.Zinc500, modifier = Modifier.size(16.dp))
                    }
                }

                for ((letter, letterPeople) in sections) {
                    item(key = "sec-$letter") {
                        Column {
                            // sticky glass letter header: h-7 rounded-full mx-3 px-3
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .heightIn(min = 28.dp)
                                    .clip(CircleShape)
                                    .background(SubPageInk.Panel)
                                    .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    letter,
                                    color = SubPageInk.Zinc400,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp,
                                )
                                Text(
                                    letterPeople.size.toString(),
                                    color = SubPageInk.Zinc600,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            for (person in letterPeople) {
                                MirrorPersonRow(
                                    person = person,
                                    online = person.id in onlineIds,
                                    sharesDm = dmByUserId.containsKey(person.id),
                                    onPress = {
                                        val existing = dmByUserId[person.id]
                                        if (existing != null) {
                                            onOpenConversation(existing)
                                        } else {
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val convo = repository.createDm(person.id).getOrNull()
                                                if (convo != null) onOpenConversation(convo.id)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Uppercase index bucket ("A"…"Z", anything else -> "#") - web indexLetterOf. */
private fun indexLetterOf(name: String): String {
    val first = name.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}

/** PersonRow: 44dp avatar + presence halo, name/@handle, Chat chip, divider. */
@Composable
private fun MirrorPersonRow(person: User, online: Boolean, sharesDm: Boolean, onPress: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onPress)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp)) {
                if (online) {
                    // pulsing amber halo ring behind online avatars (web PresenceGlow:
                    // -inset-[3px] ring-2 ring-amber-400/60 breathing alpha)
                    val pulse by rememberInfiniteTransition(label = "presenceHalo").animateFloat(
                        initialValue = 0.65f,
                        targetValue = 0.18f,
                        animationSpec = infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(900),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "haloAlpha",
                    )
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(50.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color(0x99F59E0B).copy(alpha = pulse)),
                    )
                }
                MirrorAvatar(
                    name = person.name,
                    color = person.color,
                    isGroup = false,
                    groupId = "",
                    online = online,
                    showPresence = true,
                    sizeDp = 44,
                    cornerDp = 22,
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        person.name,
                        color = SubPageInk.Zinc100,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (person.handle.isNotBlank()) {
                        Text(
                            "@" + person.handle,
                            color = SubPageInk.Amber400,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (sharesDm) {
                        Row(
                            Modifier
                                .heightIn(min = 16.dp)
                                .clip(CircleShape)
                                .background(Color(0x1AF59E0B))
                                .border(1.dp, Color(0x33F59E0B), CircleShape)
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            MirrorLucideIcon("LMessageCircle", tint = SubPageInk.Amber400, modifier = Modifier.size(10.dp))
                            Text("Chat", color = SubPageInk.Amber400, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    listOfNotNull(person.statusText).joinToString(" ").ifBlank { person.bio.orEmpty() },
                    color = SubPageInk.Zinc500,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MirrorLucideIcon("LChevronRight", tint = SubPageInk.Zinc600, modifier = Modifier.size(16.dp))
        }
        // hairline divider indented under the text column (ml-[60px])
        Box(
            Modifier
                .padding(start = 60.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF27272A)),
        )
    }
}
