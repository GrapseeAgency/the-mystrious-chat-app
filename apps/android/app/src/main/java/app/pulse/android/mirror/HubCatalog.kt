package app.pulse.android.mirror

import androidx.compose.ui.graphics.Color

/**
 * R66 - the 100-platform Apps Matrix, ported VERBATIM from the web's
 * src/lib/hub-catalog.ts (the static catalog the Hub > Apps panel renders).
 * Same 10 categories, same nav styles, same input/secret copy - the mirror
 * renders the exact data the browser renders.
 *
 * R75 - full category-page support: CATEGORY_META (slug, label, blurb,
 * accent pair), slug helpers, brand accent overrides and the per-app
 * editorial taglines are ported verbatim from hub-catalog.ts so the
 * native #/hub/c/<slug> full page renders the exact copy and gradients
 * the browser renders.
 */
internal data class MatrixApp(
    val n: Int,
    val name: String,
    val nav: String,
    val input: String,
    val category: String,
    val secret: String,
)

/** Web CategoryMeta (hub-catalog.ts): slug, label, blurb, accent [from, to]. */
internal data class CategoryMeta(
    val slug: String,
    val label: String,
    val blurb: String,
    val accent: List<String>,
)

internal object HubCatalog {

    val CATEGORY_CHIPS: List<Pair<String, String>> = listOf(
        "all" to "All",
        "Web3 FinTech / Hyper-Apps" to "Web3",
        "Dev-Ops / Community Boards" to "Dev-Ops",
        "Workplace Canvas / Dev-Ops" to "Workplace",
        "E-Commerce Showcase" to "E-Commerce",
        "Stark Privacy Minimalist" to "Privacy",
        "Cross-Server Bridges / Matrix" to "Bridges",
        "Spatial 3D Environments" to "Spatial 3D",
        "Spatial 2D/3D Art" to "Spatial 2D",
    )

    /** Extra chips for categories the static chip list misses (web EXTRA_CHIP_LABELS). */
    fun extraChipLabel(category: String): String? = when (category) {
        "E-Commerce / Global FinTech" -> "Global FinTech"
        "E-Commerce / Hyper-Apps" -> "Hyper-Apps"
        else -> null
    }

    val MATRIX: List<MatrixApp> = listOf(
        MatrixApp(1, "Discord", "Persistent Solid Split Rail", "Media slider, rich presence triggers", "Dev-Ops / Community Boards", "WebGL custom profile mesh themes"),
        MatrixApp(2, "Slack", "Persistent Solid Split Rail", "Lightning bolt workflow automation picker", "Workplace Canvas / Dev-Ops", "Slide-out right-side thread panels"),
        MatrixApp(3, "WhatsApp", "Floating Acrylic Bottom Dock", "Cubic-bezier + media sheet, voice seek", "E-Commerce / Global FinTech", "Real-time audio canvas waveforms"),
        MatrixApp(4, "Telegram", "Floating Acrylic Bottom Dock", "Paperclip sheet, mini-app launcher button", "Web3 FinTech / Hyper-Apps", "Real-time Gaussian blur overlay headers"),
        MatrixApp(5, "Signal", "Persistent Solid Split Rail", "Disappearing text timer wheel indicator", "Stark Privacy Minimalist", "Localized pin database security shields"),
        MatrixApp(6, "Element", "Floating Acrylic Bottom Dock", "Cross-signing cryptographic check toggle", "Cross-Server Bridges / Matrix", "Dynamic server origin labels on text blocks"),
        MatrixApp(7, "Revolt", "Minimalist Hidden Edge Rail", "Markdown auto-syntax triggers, raw code blocks", "Dev-Ops / Community Boards", "High-density text layout configuration panels"),
        MatrixApp(8, "Beeper", "Floating Acrylic Bottom Dock", "Unified network protocol adapter badge", "Cross-Server Bridges / Matrix", "Sub-icon branding indicators on contact avatars"),
        MatrixApp(9, "Threema", "Persistent Solid Split Rail", "Anonymous encrypted inline polling sheets", "Stark Privacy Minimalist", "Random alphanumeric user ID generator screens"),
        MatrixApp(10, "Viber", "Floating Acrylic Bottom Dock", "Interactive shopping / GIF carousels", "E-Commerce Showcase", "Hidden text vault secure PIN keypad sheets"),
        MatrixApp(11, "WeChat", "Persistent Solid Split Rail", "Invoice tracking / red packet cash triggers", "E-Commerce / Global FinTech", "Pull-down recently used mini-app drawer layer"),
        MatrixApp(12, "LINE", "Persistent Solid Split Rail", "Character sticker shop canvas engine", "E-Commerce Showcase", "Dynamic message expansions for large graphics"),
        MatrixApp(13, "KakaoTalk", "Persistent Solid Split Rail", "Shared group calendar reservation picker", "E-Commerce Showcase", "Rounded card grid gift marketplace views"),
        MatrixApp(14, "Session", "Minimalist Hidden Edge Rail", "Multi-hop onion routing telemetry indicator", "Stark Privacy Minimalist", "Alphanumeric account key security containers"),
        MatrixApp(15, "Wickr", "Persistent Solid Split Rail", "Hardware-level shredder timer count blocks", "Stark Privacy Minimalist", "Local device cache deletion admin panels"),
        MatrixApp(16, "Matrix", "Minimalist Hidden Edge Rail", "Raw JSON payload inspector triggers", "Cross-Server Bridges / Matrix", "Open-source multi-server directory indices"),
        MatrixApp(17, "Twitch", "Persistent Solid Split Rail", "Micro-transaction cheers, sub badges", "Dev-Ops / Community Boards", "Stream canvas panel fixed layout parameters"),
        MatrixApp(18, "Microsoft Teams", "Persistent Solid Split Rail", "Font hierarchy rail, message importance toggle", "Workplace Canvas / Dev-Ops", "Acrylic blur panel real-time live captions"),
        MatrixApp(19, "Google Chat", "Persistent Solid Split Rail", "Context-aware AI text chip trays", "Workplace Canvas / Dev-Ops", "Side-panel document collaboration viewers"),
        MatrixApp(20, "Zoom", "Persistent Solid Split Rail", "Drawing canvas whiteboard launching toggle", "Workplace Canvas / Dev-Ops", "Vector ink line whiteboard workspaces"),
        MatrixApp(21, "Webex", "Persistent Solid Split Rail", "AI background noise-tuning level meters", "Workplace Canvas / Dev-Ops", "Decibel suppression telemetry dashboards"),
        MatrixApp(22, "Chanty", "Floating Acrylic Bottom Dock", "Direct kanban task conversion actions", "Workplace Canvas / Dev-Ops", "Interactive drag-and-drop workflow sheets"),
        MatrixApp(23, "Flock", "Persistent Solid Split Rail", "Integrated collaborative checklist modules", "Workplace Canvas / Dev-Ops", "Three-pane desktop split layout structure"),
        MatrixApp(24, "Ryver", "Persistent Solid Split Rail", "Split-screen kanban task generators", "Workplace Canvas / Dev-Ops", "Bulletin forum board corporate layouts"),
        MatrixApp(25, "Mattermost", "Minimalist Hidden Edge Rail", "Operational deployment playbook connectors", "Dev-Ops / Community Boards", "Incident emergency response logging streams"),
        MatrixApp(26, "Rocket.Chat", "Minimalist Hidden Edge Rail", "Multi-channel outgoing language translators", "Workplace Canvas / Dev-Ops", "Multi-source ticket sorting inbox tables"),
        MatrixApp(27, "Zulip", "Minimalist Hidden Edge Rail", "Mandatory topic title composition fields", "Workplace Canvas / Dev-Ops", "Explicit conversation category top banners"),
        MatrixApp(28, "Fumble", "Persistent Solid Split Rail", "Live git code repository link pickers", "Dev-Ops / Community Boards", "Language color badge repository indicators"),
        MatrixApp(29, "Guilded", "Persistent Solid Split Rail", "Shared tournament bracket calendar loaders", "Dev-Ops / Community Boards", "Zoomable HTML5 gaming bracket layouts"),
        MatrixApp(30, "TeamSpeak", "Minimalist Hidden Edge Rail", "Push-to-talk key mapping matrices", "Dev-Ops / Community Boards", "Hierarchical server structural connection trees"),
        MatrixApp(31, "Mumble", "Minimalist Hidden Edge Rail", "Low-latency audio voice threshold sliders", "Dev-Ops / Community Boards", "Directional 3D acoustic layout tuners"),
        MatrixApp(32, "Steam Chat", "Floating Acrylic Bottom Dock", "PC hardware monitoring lookup readouts", "Dev-Ops / Community Boards", "Direct game invite pop-up sliding panels"),
        MatrixApp(33, "Skype", "Persistent Solid Split Rail", "Cellular landline destination dialers", "Workplace Canvas / Dev-Ops", "Translucent blurred background video filters"),
        MatrixApp(34, "VRChat", "Volumetric Radial Overlay", "3D avatar facial expression dials", "Spatial 3D Environments", "Floating virtual controller attachment panes"),
        MatrixApp(35, "Rec Room", "Volumetric Radial Overlay", "Visual programming logic node wire frames", "Spatial 3D Environments", "Volumetric wearable watch control screens"),
        MatrixApp(36, "Snapchat", "Volumetric Radial Overlay", "Fullscreen camera AR lens asset triggers", "Spatial 3D Environments", "Full-bleed vector cartographic friend maps"),
        MatrixApp(37, "Instagram DM", "Floating Acrylic Bottom Dock", "Inline video post looping components", "E-Commerce Showcase", "Gradient mesh chat background customization"),
        MatrixApp(38, "TikTok Inbox", "Persistent Solid Split Rail", "Trending audio clip clip samplers", "E-Commerce Showcase", "Swipe-to-reply video response capture frames"),
        MatrixApp(39, "X DMs", "Persistent Solid Split Rail", "Secure audio call network routers", "Stark Privacy Minimalist", "Audio space round profile indicator borders"),
        MatrixApp(40, "Threads", "Persistent Solid Split Rail", "Text-first thread branching lane links", "Workplace Canvas / Dev-Ops", "Branching timeline connection lines"),
        MatrixApp(41, "iMessage", "Persistent Solid Split Rail", "Physics-based text effect button engines", "Spatial 2D/3D Art", "Fullscreen screen-space particle loops"),
        MatrixApp(42, "Google Messages", "Persistent Solid Split Rail", "RCS connection status validation meters", "E-Commerce Showcase", "Dynamic system-matching theme palette layouts"),
        MatrixApp(43, "RCS Chat", "Persistent Solid Split Rail", "Verified commercial account badge queries", "E-Commerce Showcase", "Carrier-level video container components"),
        MatrixApp(44, "Voxer", "Volumetric Radial Overlay", "Oversized touch push-to-talk buttons", "Spatial 2D/3D Art", "Real-time scrolling voice note meters"),
        MatrixApp(45, "Zello", "Volumetric Radial Overlay", "Field dispatch channel isolation knobs", "Workplace Canvas / Dev-Ops", "Bright red crisis emergency broadcast screens"),
        MatrixApp(46, "Marco Polo", "Floating Acrylic Bottom Dock", "Asynchronous video note capture wheels", "Spatial 2D/3D Art", "Chronological friend video diary grids"),
        MatrixApp(47, "Clubhouse", "Floating Acrylic Bottom Dock", "Virtual panel hand-raise request icons", "Spatial 2D/3D Art", "Stage speaker card alignment grids"),
        MatrixApp(48, "Twitter Spaces", "Floating Acrylic Bottom Dock", "Audio transcription captioning toggles", "Spatial 2D/3D Art", "Dynamic talker border glow visual layers"),
        MatrixApp(49, "BeReal", "Volumetric Radial Overlay", "Dual-camera matrix sensor triggers", "Spatial 2D/3D Art", "Picture-in-picture selfie realmoji sheets"),
        MatrixApp(50, "Bere.al", "Volumetric Radial Overlay", "Timeline submission clock confirmations", "Spatial 2D/3D Art", "Flat high-contrast text timeline blocks"),
        MatrixApp(51, "Status", "Floating Acrylic Bottom Dock", "Ethereum contract transaction bars", "Web3 FinTech / Hyper-Apps", "Monospace asset ledger financial graphs"),
        MatrixApp(52, "Status IM", "Floating Acrylic Bottom Dock", "Decentralized ID verification blocks", "Web3 FinTech / Hyper-Apps", "Biometric fingerprint security splash panels"),
        MatrixApp(53, "Status Network", "Minimalist Hidden Edge Rail", "Cross-app data distribution permission chips", "Web3 FinTech / Hyper-Apps", "Public identity cryptographic key profiles"),
        MatrixApp(54, "Session Messaging", "Minimalist Hidden Edge Rail", "Onion-routing transmission hop log metrics", "Stark Privacy Minimalist", "Secure local database data storage panels"),
        MatrixApp(55, "SimpleX Chat", "Minimalist Hidden Edge Rail", "Unidirectional invitation key builders", "Stark Privacy Minimalist", "High-contrast temporary invite QR views"),
        MatrixApp(56, "Briar", "Minimalist Hidden Edge Rail", "Bluetooth mesh connection discovery lookups", "Stark Privacy Minimalist", "Local device proximity hardware charts"),
        MatrixApp(57, "Jami", "Minimalist Hidden Edge Rail", "Distributed Hash Table diagnostic maps", "Spatial 2D/3D Art", "Full-bleed direct peer-to-peer call lanes"),
        MatrixApp(58, "Tox", "Minimalist Hidden Edge Rail", "P2P un-throttled speed calculation panels", "Stark Privacy Minimalist", "Horizontal expanding chat box layouts"),
        MatrixApp(59, "Wire", "Persistent Solid Split Rail", "Sovereign enterprise key permission toggles", "Workplace Canvas / Dev-Ops", "High-end typography corporate cloud links"),
        MatrixApp(60, "Dust", "Persistent Solid Split Rail", "Automatic screenshot detection blocks", "Stark Privacy Minimalist", "Self-shredding floating pixel animations"),
        MatrixApp(61, "Keybase", "Minimalist Hidden Edge Rail", "Git repository cryptographic signing blocks", "Dev-Ops / Community Boards", "Monospace dev identity proof verification cards"),
        MatrixApp(62, "Spike", "Floating Acrylic Bottom Dock", "Expanded conversational email row managers", "Workplace Canvas / Dev-Ops", "Stripped signature header bubble streams"),
        MatrixApp(63, "Missive", "Floating Acrylic Bottom Dock", "Real-time concurrent text creation sheets", "Workplace Canvas / Dev-Ops", "Collaborative multi-user email text editors"),
        MatrixApp(64, "Front", "Persistent Solid Split Rail", "Multi-channel customer service selectors", "Workplace Canvas / Dev-Ops", "Customer interaction metadata sidebar rows"),
        MatrixApp(65, "Intercom", "Volumetric Radial Overlay", "Automated help center knowledge base indices", "E-Commerce Showcase", "Slide-in self-service web widget overlays"),
        MatrixApp(66, "Drift", "Volumetric Radial Overlay", "Inline automated calendar setting popups", "E-Commerce Showcase", "Oversized lead collection call-to-action cards"),
        MatrixApp(67, "Crisp", "Volumetric Radial Overlay", "Live agent cursor tracker controllers", "E-Commerce Showcase", "WebGL real-time visitor coordinate maps"),
        MatrixApp(68, "Tidio", "Volumetric Radial Overlay", "Shopify database cart lookup drawers", "E-Commerce Showcase", "Drag-and-drop response automation node loops"),
        MatrixApp(69, "Zendesk Chat", "Persistent Solid Split Rail", "Macro response script shortcut templates", "Workplace Canvas / Dev-Ops", "Browser-style agent multi-user window tabs"),
        MatrixApp(70, "HubSpot Chat", "Persistent Solid Split Rail", "CRM interaction history timeline lookups", "E-Commerce Showcase", "Integrated enterprise deal stage pipelines"),
        MatrixApp(71, "ManyChat", "Minimalist Hidden Edge Rail", "Social ad automated lead funnel markers", "E-Commerce Showcase", "Zoomable customer path logic canvases"),
        MatrixApp(72, "Chatfuel", "Minimalist Hidden Edge Rail", "Meta network chat configuration option items", "E-Commerce Showcase", "Horizontal reply carousel product menus"),
        MatrixApp(73, "MobileMonkey", "Minimalist Hidden Edge Rail", "Ad campaign source routing parameters", "E-Commerce Showcase", "Inbound marketing audience segment matrices"),
        MatrixApp(74, "Landbot", "Volumetric Radial Overlay", "Fullscreen media item choice chips", "Spatial 2D/3D Art", "Full-bleed interactive background canvases"),
        MatrixApp(75, "Tars", "Volumetric Radial Overlay", "Mobile thumb-optimized form data fields", "E-Commerce Showcase", "High-contrast single-track marketing tunnels"),
        MatrixApp(76, "Typeform Chat", "Volumetric Radial Overlay", "Question-by-question layout transitions", "Spatial 2D/3D Art", "Minimalist survey option selection paths"),
        MatrixApp(77, "Jostle", "Floating Acrylic Bottom Dock", "Internal update thread posting shortcuts", "Workplace Canvas / Dev-Ops", "Magazine-style milestone photo layout blocks"),
        MatrixApp(78, "Workplace", "Persistent Solid Split Rail", "Corporate live stream feedback reactions", "Workplace Canvas / Dev-Ops", "Policy document storage knowledge libraries"),
        MatrixApp(79, "Basecamp Chat", "Persistent Solid Split Rail", "Context-focused milestone mention checkers", "Workplace Canvas / Dev-Ops", "Campfire conversation text row containers"),
        MatrixApp(80, "ClickUp Chat", "Persistent Solid Split Rail", "Agile workspace sprint link managers", "Workplace Canvas / Dev-Ops", "Real-time wiki document editor lanes"),
        MatrixApp(81, "Asana Chat", "Persistent Solid Split Rail", "Direct task dependency status verifiers", "Workplace Canvas / Dev-Ops", "High-resolution project Gantt timeline charts"),
        MatrixApp(82, "Monday Chat", "Persistent Solid Split Rail", "Status column automated update hooks", "Workplace Canvas / Dev-Ops", "Slide-out database row update panels"),
        MatrixApp(83, "Notion Chat", "Minimalist Hidden Edge Rail", "Inline block-anchored discussion threads", "Workplace Canvas / Dev-Ops", "High-whitespace page modification ledgers"),
        MatrixApp(84, "Miro Chat", "Minimalist Hidden Edge Rail", "Whiteboard team canvas focus selectors", "Spatial 2D/3D Art", "Real-time cursor particle text labels"),
        MatrixApp(85, "Figma Chat", "Minimalist Hidden Edge Rail", "Cursor tracking vector feedback launchers", "Spatial 2D/3D Art", "Floating viewport design comment sidebars"),
        MatrixApp(86, "Gather.town", "Volumetric Radial Overlay", "Local proximity range layout rules", "Spatial 3D Environments", "16-bit pixel landscape private room maps"),
        MatrixApp(87, "Kumospace", "Volumetric Radial Overlay", "Spatial broadcast perimeter distance bars", "Spatial 3D Environments", "Floor plan WebGL presentation templates"),
        MatrixApp(88, "Topia", "Volumetric Radial Overlay", "Hand-sketched link insertion parameters", "Spatial 3D Environments", "Fading circular participant video bubbles"),
        MatrixApp(89, "Spatial", "Volumetric Radial Overlay", "Digital wallet gallery verification tools", "Spatial 3D Environments", "3D museum display room asset frameworks"),
        MatrixApp(90, "Roblox Chat", "Volumetric Radial Overlay", "Real-time text safety filter scanners", "Spatial 3D Environments", "Translucent in-game game canvas boxes"),
        MatrixApp(91, "Minecraft Chat", "Volumetric Radial Overlay", "Server console command execution sheets", "Spatial 3D Environments", "Monospace script auto-complete panels"),
        MatrixApp(92, "Among Us Chat", "Volumetric Radial Overlay", "Round-timer selection shortcut matrices", "Spatial 3D Environments", "Character target voting card layouts"),
        MatrixApp(93, "Plato", "Floating Acrylic Bottom Dock", "Casual turn-based board game match sheets", "Dev-Ops / Community Boards", "Split-pane chat / multiplayer arcade frames"),
        MatrixApp(94, "Hago", "Floating Acrylic Bottom Dock", "3D screen animation gift trigger boxes", "Dev-Ops / Community Boards", "Horizontal interactive virtual store rails"),
        MatrixApp(95, "Yubo", "Floating Acrylic Bottom Dock", "Swiping category preference filters", "Spatial 2D/3D Art", "Equal-tiled camera grid layout boxes"),
        MatrixApp(96, "Leket", "Persistent Solid Split Rail", "Food delivery drop-off tracking maps", "Workplace Canvas / Dev-Ops", "Local civic volunteer routing indicators"),
        MatrixApp(97, "Amino", "Persistent Solid Split Rail", "Fan wiki resource authoring toolsets", "Dev-Ops / Community Boards", "Custom fandom color theme asset builders"),
        MatrixApp(98, "Band", "Persistent Solid Split Rail", "Team attendance roster check-in blocks", "Workplace Canvas / Dev-Ops", "Roster item balance configuration matrices"),
        MatrixApp(99, "MeWe", "Persistent Solid Split Rail", "Non-tracking data privacy switch controls", "Stark Privacy Minimalist", "Chronological ad-free personal social feeds"),
        MatrixApp(100, "Nextdoor", "Persistent Solid Split Rail", "Verified address localized proximity grids", "Spatial 3D Environments", "Amber safety notice alert container plates"),
    )

    // CATEGORY_META - hub-catalog.ts lines 183-245, verbatim.

    val CATEGORY_META: Map<String, CategoryMeta> = mapOf(
        "Dev-Ops / Community Boards" to CategoryMeta(
            slug = "dev-ops",
            label = "Dev-Ops",
            blurb = "Community engines, guild halls and live-ops command centers.",
            accent = listOf("#6366f1", "#2563eb"),
        ),
        "Workplace Canvas / Dev-Ops" to CategoryMeta(
            slug = "workplace",
            label = "Workplace",
            blurb = "Structured work chat - boards, threads and operational flows.",
            accent = listOf("#0ea5e9", "#0891b2"),
        ),
        "E-Commerce Showcase" to CategoryMeta(
            slug = "e-commerce",
            label = "E-Commerce",
            blurb = "Storefront chat, social selling and conversational commerce.",
            accent = listOf("#10b981", "#0d9488"),
        ),
        "E-Commerce / Global FinTech" to CategoryMeta(
            slug = "global-fintech",
            label = "Global FinTech",
            blurb = "Chat platforms that doubled as national payment rails.",
            accent = listOf("#f59e0b", "#ea580c"),
        ),
        "E-Commerce / Hyper-Apps" to CategoryMeta(
            slug = "hyper-apps",
            label = "Hyper-Apps",
            blurb = "Everything-apps - messaging fused with services and mini-programs.",
            accent = listOf("#84cc16", "#16a34a"),
        ),
        "Web3 FinTech / Hyper-Apps" to CategoryMeta(
            slug = "web3",
            label = "Web3",
            blurb = "Wallet-native messengers with on-chain rails in every thread.",
            accent = listOf("#8b5cf6", "#7e22ce"),
        ),
        "Stark Privacy Minimalist" to CategoryMeta(
            slug = "privacy",
            label = "Privacy",
            blurb = "Zero-knowledge, ephemeral and audit-first communication.",
            accent = listOf("#64748b", "#374151"),
        ),
        "Cross-Server Bridges / Matrix" to CategoryMeta(
            slug = "bridges",
            label = "Bridges",
            blurb = "Federation fabrics that stitch many networks into one.",
            accent = listOf("#06b6d4", "#0284c7"),
        ),
        "Spatial 3D Environments" to CategoryMeta(
            slug = "spatial-3d",
            label = "Spatial 3D",
            blurb = "Presence as a place - avatars, rooms and proximity audio.",
            accent = listOf("#d946ef", "#9333ea"),
        ),
        "Spatial 2D/3D Art" to CategoryMeta(
            slug = "spatial-art",
            label = "Spatial Art",
            blurb = "Expressive canvases - voice stages, visual threads, motion mail.",
            accent = listOf("#f43f5e", "#db2777"),
        ),
    )

    /** Web MINE_SLUG (hub-category-page.tsx:41). */
    const val MINE_SLUG: String = "mine"

    /** URL slug for a matrix category (web slugForCategory). */
    fun slugForCategory(category: String): String? = CATEGORY_META[category]?.slug

    /** Reverse lookup: #/hub/c/<slug> -> category, or null for an unknown slug (web categoryBySlug). */
    fun categoryBySlug(slug: String): String? =
        CATEGORY_META.entries.firstOrNull { it.value.slug == slug }?.key

    /** Brand-true accent overrides, keyed by matrix id (hub-catalog.ts BRAND_ACCENTS). */
    private val BRAND_ACCENTS: Map<Int, List<String>> = mapOf(
        1 to listOf("#5865f2", "#404eed"), // Discord
        3 to listOf("#25d366", "#128c7e"), // WhatsApp
        4 to listOf("#2aabee", "#229ed9"), // Telegram
        5 to listOf("#3a76f0", "#1c5cd6"), // Signal
        11 to listOf("#07c160", "#059a4c"), // WeChat
        12 to listOf("#06c755", "#04a044"), // LINE
        17 to listOf("#9146ff", "#6441a5"), // Twitch
        32 to listOf("#1b2838", "#2a475e"), // Steam Chat
        51 to listOf("#4360df", "#2c3ea5"), // Status
        41 to listOf("#34c759", "#0a9e4a"), // iMessage
    )

    /** Per-app accent gradient - brand override, else the category accent (web appAccent). */
    fun appAccent(app: MatrixApp): List<String> =
        BRAND_ACCENTS[app.n] ?: (CATEGORY_META[app.category]?.accent ?: listOf("#c9762b", "#0d9488"))

    /** Web CATEGORY_META fallback for the My apps page accent (hub-category-page.tsx:80). */
    val MINE_ACCENT: List<String> = listOf("#c9762b", "#0d9488")

    // APP_TAGLINES - hub-catalog.ts lines 296-396, verbatim (static catalog copy).

    private val APP_TAGLINES: Map<Int, String> = mapOf(
        1 to "Communities that never sleep, in voice and text",
        2 to "Where work conversations get organized in channels",
        3 to "The world in one simple, secure messenger",
        4 to "Speed-first chats with mini-app superpowers",
        5 to "Encrypted by default, metadata-free by design",
        6 to "Open federation for decentralized rooms",
        7 to "The open-source community hangout",
        8 to "Every messenger, one unified inbox",
        9 to "Anonymous IDs instead of phone numbers",
        10 to "Communities and calls, with hidden text vaults",
        11 to "Payments, mini-programs and chats in one",
        12 to "Stickers, communities and everything cute",
        13 to "Korea\u2019s everything platform for friends",
        14 to "Untraceable sessions over onion routing",
        15 to "Enterprise secrecy with shredder timers",
        16 to "The protocol that became a network",
        17 to "Live streams with chat as the main stage",
        18 to "Meetings that turn into message threads",
        19 to "Google-grade chat inside the workspace",
        20 to "Video calls that grow a whiteboard",
        21 to "Webex rooms with live captions and AI tuning",
        22 to "Small-team chat that ships tasks fast",
        23 to "Checklists and chats in one dock",
        24 to "Kanban-built conversations for teams",
        25 to "Self-hosted DevOps command channels",
        26 to "Tickets, translators and team inboxes",
        27 to "Threads with mandatory topic rigor",
        28 to "Code links land live in the room",
        29 to "Guilds, brackets and tournament boards",
        30 to "Voice servers with push-to-talk DNA",
        31 to "Low-latency voice with directional audio",
        32 to "Friends, invites and hardware readouts",
        33 to "Calls to anywhere, blurred backgrounds on",
        34 to "Be anyone in a world of user rooms",
        35 to "Rooms, gadgets and creative code",
        36 to "The camera is the conversation",
        37 to "Stories-first messaging with loop video",
        38 to "Trends, sounds and reply-by-video",
        39 to "Spaces and DMs on the town square",
        40 to "Text-first threads, branching lanes",
        41 to "Bubbles with physics and particle joy",
        42 to "RCS messaging with carrier smarts",
        43 to "Verified business chat over carrier rails",
        44 to "Push-to-talk with scrolling waveforms",
        45 to "Dispatch channels for field crews",
        46 to "Video diaries your friends answer",
        47 to "Drop into live audio rooms",
        48 to "Stage-grade audio with glowing talkers",
        49 to "Dual-camera posts with realmoji sheets",
        50 to "Timeline moments on a flat grid",
        51 to "Web3 chat with on-chain ledgers",
        52 to "Decentralized IDs meet secure chat",
        53 to "Cross-app permissions, cryptographic keys",
        54 to "Onion hops you can actually read",
        55 to "One-way invite keys, zero metadata",
        56 to "Mesh networking when the internet is gone",
        57 to "Peer-to-peer calls without servers",
        58 to "Uncensorable P2P messaging lanes",
        59 to "Sovereign encryption for enterprises",
        60 to "Screenshots detected, messages shredded",
        61 to "Signed git commits from your chat",
        62 to "Email reimagined as chat bubbles",
        63 to "Co-write emails in real time",
        64 to "Customer conversations with full context",
        65 to "Help desks that slide into any site",
        66 to "Calendar popups close the lead",
        67 to "Watch agents browse with the visitor",
        68 to "Shopify carts answered in chat",
        69 to "Macros and tabs for support agents",
        70 to "CRM timelines inside the thread",
        71 to "Lead funnels on autopilot",
        72 to "Carousels and flows for Meta apps",
        73 to "Ad campaigns that route themselves",
        74 to "Fullscreen media choice chips",
        75 to "Thumb-optimized form tunnels",
        76 to "One question at a time, beautifully",
        77 to "Internal updates with magazine flair",
        78 to "The corporate social workplace",
        79 to "Campfires for project check-ins",
        80 to "Sprints, docs and chat in one lane",
        81 to "Task dependencies, verified live",
        82 to "Status columns that update the room",
        83 to "Discussions anchored to the page",
        84 to "Cursor particles on a shared canvas",
        85 to "Vector feedback in the margin",
        86 to "Pixel offices with proximity chat",
        87 to "Floor plans that host presentations",
        88 to "Video bubbles that fade behind you",
        89 to "Museums you walk with your wallet",
        90 to "Play-safe chat for blocky worlds",
        91 to "Server console in your pocket",
        92 to "Emergency meetings, voting cards",
        93 to "Board games with built-in chat",
        94 to "Gift animations over game rails",
        95 to "Swipe rooms with equal camera tiles",
        96 to "Delivery maps meet volunteer routes",
        97 to "Fandoms with wiki-grade toolsets",
        98 to "Rosters, check-ins and team balance",
        99 to "Ad-free feeds, chronological and calm",
        100 to "Neighbors verified by the block map",
    )

    /** Tagline for an app (catalog copy - falls back to the input toolkit line, web appTagline). */
    fun appTagline(app: MatrixApp): String = APP_TAGLINES[app.n] ?: app.input

    /** web MATRIX_TO_NAV (hub-catalog.ts:28) - the four nav styles map 1:1. */
    private val MATRIX_TO_NAV: Map<String, String> = mapOf(
        "Floating Acrylic Bottom Dock" to "acrylic",
        "Persistent Solid Split Rail" to "rail",
        "Minimalist Hidden Edge Rail" to "edge",
        "Volumetric Radial Overlay" to "radial",
    )

    /**
     * Feature list for the app sub-page (web appFeatures, hub-catalog.ts:399):
     * derived from REAL matrix fields - input toolkit, secret UI feature, the
     * Pulse nav mapping. No invented stats.
     */
    fun appFeatures(app: MatrixApp): List<String> = listOf(
        app.input,
        app.secret,
        "Ships with Pulse's " + (MATRIX_TO_NAV[app.nav] ?: app.nav) + " nav pattern - switchable live in Profile → Navigation",
    )

    /** Hex accent pairs -> Compose colors (tiles render the exact web gradients). */
    fun accentColors(accent: List<String>): List<Color> = accent.map { it.toColor() }

    /** Web #RRGGBB -> Compose Color (the catalog stores hex strings). */
    private fun String.toColor(): Color =
        Color(android.graphics.Color.parseColor(this))
}
