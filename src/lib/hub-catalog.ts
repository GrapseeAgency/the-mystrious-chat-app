// ─────────────────────────────────────────────────────────────
// Pulse Hub — the 100-platform Apps Matrix (from the user's master
// blueprint). Every app maps: mobile nav style · input toolkit ·
// 400+ sub-page category · secret UI architecture feature.
// Rendered by the Hub → Apps panel; data is static catalog (the
// matrix itself), while live Pulse features attach via routes.
// ─────────────────────────────────────────────────────────────

export type NavStyleId = 'acrylic' | 'rail' | 'edge' | 'radial'

export type MatrixNavStyle =
  | 'Floating Acrylic Bottom Dock'
  | 'Persistent Solid Split Rail'
  | 'Minimalist Hidden Edge Rail'
  | 'Volumetric Radial Overlay'

export type MatrixCategory =
  | 'Dev-Ops / Community Boards'
  | 'Workplace Canvas / Dev-Ops'
  | 'E-Commerce Showcase'
  | 'E-Commerce / Global FinTech'
  | 'E-Commerce / Hyper-Apps'
  | 'Web3 FinTech / Hyper-Apps'
  | 'Stark Privacy Minimalist'
  | 'Cross-Server Bridges / Matrix'
  | 'Spatial 3D Environments'
  | 'Spatial 2D/3D Art'

/** Maps a matrix nav style onto Pulse's own 4-style NavRouter. */
export const MATRIX_TO_NAV: Record<MatrixNavStyle, NavStyleId> = {
  'Floating Acrylic Bottom Dock': 'acrylic',
  'Persistent Solid Split Rail': 'rail',
  'Minimalist Hidden Edge Rail': 'edge',
  'Volumetric Radial Overlay': 'radial',
}

export interface MatrixApp {
  n: number
  name: string
  nav: MatrixNavStyle
  input: string
  category: MatrixCategory
  secret: string
}

export const CATEGORY_CHIPS: Array<{ id: MatrixCategory | 'all'; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'Web3 FinTech / Hyper-Apps', label: 'Web3' },
  { id: 'Dev-Ops / Community Boards', label: 'Dev-Ops' },
  { id: 'Workplace Canvas / Dev-Ops', label: 'Workplace' },
  { id: 'E-Commerce Showcase', label: 'E-Commerce' },
  { id: 'Stark Privacy Minimalist', label: 'Privacy' },
  { id: 'Cross-Server Bridges / Matrix', label: 'Bridges' },
  { id: 'Spatial 3D Environments', label: 'Spatial 3D' },
  { id: 'Spatial 2D/3D Art', label: 'Spatial 2D' },
]

export const MATRIX: MatrixApp[] = [
  { n: 1, name: 'Discord', nav: 'Persistent Solid Split Rail', input: 'Media slider, rich presence triggers', category: 'Dev-Ops / Community Boards', secret: 'WebGL custom profile mesh themes' },
  { n: 2, name: 'Slack', nav: 'Persistent Solid Split Rail', input: 'Lightning bolt workflow automation picker', category: 'Workplace Canvas / Dev-Ops', secret: 'Slide-out right-side thread panels' },
  { n: 3, name: 'WhatsApp', nav: 'Floating Acrylic Bottom Dock', input: 'Cubic-bezier + media sheet, voice seek', category: 'E-Commerce / Global FinTech', secret: 'Real-time audio canvas waveforms' },
  { n: 4, name: 'Telegram', nav: 'Floating Acrylic Bottom Dock', input: 'Paperclip sheet, mini-app launcher button', category: 'Web3 FinTech / Hyper-Apps', secret: 'Real-time Gaussian blur overlay headers' },
  { n: 5, name: 'Signal', nav: 'Persistent Solid Split Rail', input: 'Disappearing text timer wheel indicator', category: 'Stark Privacy Minimalist', secret: 'Localized pin database security shields' },
  { n: 6, name: 'Element', nav: 'Floating Acrylic Bottom Dock', input: 'Cross-signing cryptographic check toggle', category: 'Cross-Server Bridges / Matrix', secret: 'Dynamic server origin labels on text blocks' },
  { n: 7, name: 'Revolt', nav: 'Minimalist Hidden Edge Rail', input: 'Markdown auto-syntax triggers, raw code blocks', category: 'Dev-Ops / Community Boards', secret: 'High-density text layout configuration panels' },
  { n: 8, name: 'Beeper', nav: 'Floating Acrylic Bottom Dock', input: 'Unified network protocol adapter badge', category: 'Cross-Server Bridges / Matrix', secret: 'Sub-icon branding indicators on contact avatars' },
  { n: 9, name: 'Threema', nav: 'Persistent Solid Split Rail', input: 'Anonymous encrypted inline polling sheets', category: 'Stark Privacy Minimalist', secret: 'Random alphanumeric user ID generator screens' },
  { n: 10, name: 'Viber', nav: 'Floating Acrylic Bottom Dock', input: 'Interactive shopping / GIF carousels', category: 'E-Commerce Showcase', secret: 'Hidden text vault secure PIN keypad sheets' },
  { n: 11, name: 'WeChat', nav: 'Persistent Solid Split Rail', input: 'Invoice tracking / red packet cash triggers', category: 'E-Commerce / Global FinTech', secret: 'Pull-down recently used mini-app drawer layer' },
  { n: 12, name: 'LINE', nav: 'Persistent Solid Split Rail', input: 'Character sticker shop canvas engine', category: 'E-Commerce Showcase', secret: 'Dynamic message expansions for large graphics' },
  { n: 13, name: 'KakaoTalk', nav: 'Persistent Solid Split Rail', input: 'Shared group calendar reservation picker', category: 'E-Commerce Showcase', secret: 'Rounded card grid gift marketplace views' },
  { n: 14, name: 'Session', nav: 'Minimalist Hidden Edge Rail', input: 'Multi-hop onion routing telemetry indicator', category: 'Stark Privacy Minimalist', secret: 'Alphanumeric account key security containers' },
  { n: 15, name: 'Wickr', nav: 'Persistent Solid Split Rail', input: 'Hardware-level shredder timer count blocks', category: 'Stark Privacy Minimalist', secret: 'Local device cache deletion admin panels' },
  { n: 16, name: 'Matrix', nav: 'Minimalist Hidden Edge Rail', input: 'Raw JSON payload inspector triggers', category: 'Cross-Server Bridges / Matrix', secret: 'Open-source multi-server directory indices' },
  { n: 17, name: 'Twitch', nav: 'Persistent Solid Split Rail', input: 'Micro-transaction cheers, sub badges', category: 'Dev-Ops / Community Boards', secret: 'Stream canvas panel fixed layout parameters' },
  { n: 18, name: 'Microsoft Teams', nav: 'Persistent Solid Split Rail', input: 'Font hierarchy rail, message importance toggle', category: 'Workplace Canvas / Dev-Ops', secret: 'Acrylic blur panel real-time live captions' },
  { n: 19, name: 'Google Chat', nav: 'Persistent Solid Split Rail', input: 'Context-aware AI text chip trays', category: 'Workplace Canvas / Dev-Ops', secret: 'Side-panel document collaboration viewers' },
  { n: 20, name: 'Zoom', nav: 'Persistent Solid Split Rail', input: 'Drawing canvas whiteboard launching toggle', category: 'Workplace Canvas / Dev-Ops', secret: 'Vector ink line whiteboard workspaces' },
  { n: 21, name: 'Webex', nav: 'Persistent Solid Split Rail', input: 'AI background noise-tuning level meters', category: 'Workplace Canvas / Dev-Ops', secret: 'Decibel suppression telemetry dashboards' },
  { n: 22, name: 'Chanty', nav: 'Floating Acrylic Bottom Dock', input: 'Direct kanban task conversion actions', category: 'Workplace Canvas / Dev-Ops', secret: 'Interactive drag-and-drop workflow sheets' },
  { n: 23, name: 'Flock', nav: 'Persistent Solid Split Rail', input: 'Integrated collaborative checklist modules', category: 'Workplace Canvas / Dev-Ops', secret: 'Three-pane desktop split layout structure' },
  { n: 24, name: 'Ryver', nav: 'Persistent Solid Split Rail', input: 'Split-screen kanban task generators', category: 'Workplace Canvas / Dev-Ops', secret: 'Bulletin forum board corporate layouts' },
  { n: 25, name: 'Mattermost', nav: 'Minimalist Hidden Edge Rail', input: 'Operational deployment playbook connectors', category: 'Dev-Ops / Community Boards', secret: 'Incident emergency response logging streams' },
  { n: 26, name: 'Rocket.Chat', nav: 'Minimalist Hidden Edge Rail', input: 'Multi-channel outgoing language translators', category: 'Workplace Canvas / Dev-Ops', secret: 'Multi-source ticket sorting inbox tables' },
  { n: 27, name: 'Zulip', nav: 'Minimalist Hidden Edge Rail', input: 'Mandatory topic title composition fields', category: 'Workplace Canvas / Dev-Ops', secret: 'Explicit conversation category top banners' },
  { n: 28, name: 'Fumble', nav: 'Persistent Solid Split Rail', input: 'Live git code repository link pickers', category: 'Dev-Ops / Community Boards', secret: 'Language color badge repository indicators' },
  { n: 29, name: 'Guilded', nav: 'Persistent Solid Split Rail', input: 'Shared tournament bracket calendar loaders', category: 'Dev-Ops / Community Boards', secret: 'Zoomable HTML5 gaming bracket layouts' },
  { n: 30, name: 'TeamSpeak', nav: 'Minimalist Hidden Edge Rail', input: 'Push-to-talk key mapping matrices', category: 'Dev-Ops / Community Boards', secret: 'Hierarchical server structural connection trees' },
  { n: 31, name: 'Mumble', nav: 'Minimalist Hidden Edge Rail', input: 'Low-latency audio voice threshold sliders', category: 'Dev-Ops / Community Boards', secret: 'Directional 3D acoustic layout tuners' },
  { n: 32, name: 'Steam Chat', nav: 'Floating Acrylic Bottom Dock', input: 'PC hardware monitoring lookup readouts', category: 'Dev-Ops / Community Boards', secret: 'Direct game invite pop-up sliding panels' },
  { n: 33, name: 'Skype', nav: 'Persistent Solid Split Rail', input: 'Cellular landline destination dialers', category: 'Workplace Canvas / Dev-Ops', secret: 'Translucent blurred background video filters' },
  { n: 34, name: 'VRChat', nav: 'Volumetric Radial Overlay', input: '3D avatar facial expression dials', category: 'Spatial 3D Environments', secret: 'Floating virtual controller attachment panes' },
  { n: 35, name: 'Rec Room', nav: 'Volumetric Radial Overlay', input: 'Visual programming logic node wire frames', category: 'Spatial 3D Environments', secret: 'Volumetric wearable watch control screens' },
  { n: 36, name: 'Snapchat', nav: 'Volumetric Radial Overlay', input: 'Fullscreen camera AR lens asset triggers', category: 'Spatial 3D Environments', secret: 'Full-bleed vector cartographic friend maps' },
  { n: 37, name: 'Instagram DM', nav: 'Floating Acrylic Bottom Dock', input: 'Inline video post looping components', category: 'E-Commerce Showcase', secret: 'Gradient mesh chat background customization' },
  { n: 38, name: 'TikTok Inbox', nav: 'Persistent Solid Split Rail', input: 'Trending audio clip clip samplers', category: 'E-Commerce Showcase', secret: 'Swipe-to-reply video response capture frames' },
  { n: 39, name: 'X DMs', nav: 'Persistent Solid Split Rail', input: 'Secure audio call network routers', category: 'Stark Privacy Minimalist', secret: 'Audio space round profile indicator borders' },
  { n: 40, name: 'Threads', nav: 'Persistent Solid Split Rail', input: 'Text-first thread branching lane links', category: 'Workplace Canvas / Dev-Ops', secret: 'Branching timeline connection lines' },
  { n: 41, name: 'iMessage', nav: 'Persistent Solid Split Rail', input: 'Physics-based text effect button engines', category: 'Spatial 2D/3D Art', secret: 'Fullscreen screen-space particle loops' },
  { n: 42, name: 'Google Messages', nav: 'Persistent Solid Split Rail', input: 'RCS connection status validation meters', category: 'E-Commerce Showcase', secret: 'Dynamic system-matching theme palette layouts' },
  { n: 43, name: 'RCS Chat', nav: 'Persistent Solid Split Rail', input: 'Verified commercial account badge queries', category: 'E-Commerce Showcase', secret: 'Carrier-level video container components' },
  { n: 44, name: 'Voxer', nav: 'Volumetric Radial Overlay', input: 'Oversized touch push-to-talk buttons', category: 'Spatial 2D/3D Art', secret: 'Real-time scrolling voice note meters' },
  { n: 45, name: 'Zello', nav: 'Volumetric Radial Overlay', input: 'Field dispatch channel isolation knobs', category: 'Workplace Canvas / Dev-Ops', secret: 'Bright red crisis emergency broadcast screens' },
  { n: 46, name: 'Marco Polo', nav: 'Floating Acrylic Bottom Dock', input: 'Asynchronous video note capture wheels', category: 'Spatial 2D/3D Art', secret: 'Chronological friend video diary grids' },
  { n: 47, name: 'Clubhouse', nav: 'Floating Acrylic Bottom Dock', input: 'Virtual panel hand-raise request icons', category: 'Spatial 2D/3D Art', secret: 'Stage speaker card alignment grids' },
  { n: 48, name: 'Twitter Spaces', nav: 'Floating Acrylic Bottom Dock', input: 'Audio transcription captioning toggles', category: 'Spatial 2D/3D Art', secret: 'Dynamic talker border glow visual layers' },
  { n: 49, name: 'BeReal', nav: 'Volumetric Radial Overlay', input: 'Dual-camera matrix sensor triggers', category: 'Spatial 2D/3D Art', secret: 'Picture-in-picture selfie realmoji sheets' },
  { n: 50, name: 'Bere.al', nav: 'Volumetric Radial Overlay', input: 'Timeline submission clock confirmations', category: 'Spatial 2D/3D Art', secret: 'Flat high-contrast text timeline blocks' },
  { n: 51, name: 'Status', nav: 'Floating Acrylic Bottom Dock', input: 'Ethereum contract transaction bars', category: 'Web3 FinTech / Hyper-Apps', secret: 'Monospace asset ledger financial graphs' },
  { n: 52, name: 'Status IM', nav: 'Floating Acrylic Bottom Dock', input: 'Decentralized ID verification blocks', category: 'Web3 FinTech / Hyper-Apps', secret: 'Biometric fingerprint security splash panels' },
  { n: 53, name: 'Status Network', nav: 'Minimalist Hidden Edge Rail', input: 'Cross-app data distribution permission chips', category: 'Web3 FinTech / Hyper-Apps', secret: 'Public identity cryptographic key profiles' },
  { n: 54, name: 'Session Messaging', nav: 'Minimalist Hidden Edge Rail', input: 'Onion-routing transmission hop log metrics', category: 'Stark Privacy Minimalist', secret: 'Secure local database data storage panels' },
  { n: 55, name: 'SimpleX Chat', nav: 'Minimalist Hidden Edge Rail', input: 'Unidirectional invitation key builders', category: 'Stark Privacy Minimalist', secret: 'High-contrast temporary invite QR views' },
  { n: 56, name: 'Briar', nav: 'Minimalist Hidden Edge Rail', input: 'Bluetooth mesh connection discovery lookups', category: 'Stark Privacy Minimalist', secret: 'Local device proximity hardware charts' },
  { n: 57, name: 'Jami', nav: 'Minimalist Hidden Edge Rail', input: 'Distributed Hash Table diagnostic maps', category: 'Spatial 2D/3D Art', secret: 'Full-bleed direct peer-to-peer call lanes' },
  { n: 58, name: 'Tox', nav: 'Minimalist Hidden Edge Rail', input: 'P2P un-throttled speed calculation panels', category: 'Stark Privacy Minimalist', secret: 'Horizontal expanding chat box layouts' },
  { n: 59, name: 'Wire', nav: 'Persistent Solid Split Rail', input: 'Sovereign enterprise key permission toggles', category: 'Workplace Canvas / Dev-Ops', secret: 'High-end typography corporate cloud links' },
  { n: 60, name: 'Dust', nav: 'Persistent Solid Split Rail', input: 'Automatic screenshot detection blocks', category: 'Stark Privacy Minimalist', secret: 'Self-shredding floating pixel animations' },
  { n: 61, name: 'Keybase', nav: 'Minimalist Hidden Edge Rail', input: 'Git repository cryptographic signing blocks', category: 'Dev-Ops / Community Boards', secret: 'Monospace dev identity proof verification cards' },
  { n: 62, name: 'Spike', nav: 'Floating Acrylic Bottom Dock', input: 'Expanded conversational email row managers', category: 'Workplace Canvas / Dev-Ops', secret: 'Stripped signature header bubble streams' },
  { n: 63, name: 'Missive', nav: 'Floating Acrylic Bottom Dock', input: 'Real-time concurrent text creation sheets', category: 'Workplace Canvas / Dev-Ops', secret: 'Collaborative multi-user email text editors' },
  { n: 64, name: 'Front', nav: 'Persistent Solid Split Rail', input: 'Multi-channel customer service selectors', category: 'Workplace Canvas / Dev-Ops', secret: 'Customer interaction metadata sidebar rows' },
  { n: 65, name: 'Intercom', nav: 'Volumetric Radial Overlay', input: 'Automated help center knowledge base indices', category: 'E-Commerce Showcase', secret: 'Slide-in self-service web widget overlays' },
  { n: 66, name: 'Drift', nav: 'Volumetric Radial Overlay', input: 'Inline automated calendar setting popups', category: 'E-Commerce Showcase', secret: 'Oversized lead collection call-to-action cards' },
  { n: 67, name: 'Crisp', nav: 'Volumetric Radial Overlay', input: 'Live agent cursor tracker controllers', category: 'E-Commerce Showcase', secret: 'WebGL real-time visitor coordinate maps' },
  { n: 68, name: 'Tidio', nav: 'Volumetric Radial Overlay', input: 'Shopify database cart lookup drawers', category: 'E-Commerce Showcase', secret: 'Drag-and-drop response automation node loops' },
  { n: 69, name: 'Zendesk Chat', nav: 'Persistent Solid Split Rail', input: 'Macro response script shortcut templates', category: 'Workplace Canvas / Dev-Ops', secret: 'Browser-style agent multi-user window tabs' },
  { n: 70, name: 'HubSpot Chat', nav: 'Persistent Solid Split Rail', input: 'CRM interaction history timeline lookups', category: 'E-Commerce Showcase', secret: 'Integrated enterprise deal stage pipelines' },
  { n: 71, name: 'ManyChat', nav: 'Minimalist Hidden Edge Rail', input: 'Social ad automated lead funnel markers', category: 'E-Commerce Showcase', secret: 'Zoomable customer path logic canvases' },
  { n: 72, name: 'Chatfuel', nav: 'Minimalist Hidden Edge Rail', input: 'Meta network chat configuration option items', category: 'E-Commerce Showcase', secret: 'Horizontal reply carousel product menus' },
  { n: 73, name: 'MobileMonkey', nav: 'Minimalist Hidden Edge Rail', input: 'Ad campaign source routing parameters', category: 'E-Commerce Showcase', secret: 'Inbound marketing audience segment matrices' },
  { n: 74, name: 'Landbot', nav: 'Volumetric Radial Overlay', input: 'Fullscreen media item choice chips', category: 'Spatial 2D/3D Art', secret: 'Full-bleed interactive background canvases' },
  { n: 75, name: 'Tars', nav: 'Volumetric Radial Overlay', input: 'Mobile thumb-optimized form data fields', category: 'E-Commerce Showcase', secret: 'High-contrast single-track marketing tunnels' },
  { n: 76, name: 'Typeform Chat', nav: 'Volumetric Radial Overlay', input: 'Question-by-question layout transitions', category: 'Spatial 2D/3D Art', secret: 'Minimalist survey option selection paths' },
  { n: 77, name: 'Jostle', nav: 'Floating Acrylic Bottom Dock', input: 'Internal update thread posting shortcuts', category: 'Workplace Canvas / Dev-Ops', secret: 'Magazine-style milestone photo layout blocks' },
  { n: 78, name: 'Workplace', nav: 'Persistent Solid Split Rail', input: 'Corporate live stream feedback reactions', category: 'Workplace Canvas / Dev-Ops', secret: 'Policy document storage knowledge libraries' },
  { n: 79, name: 'Basecamp Chat', nav: 'Persistent Solid Split Rail', input: 'Context-focused milestone mention checkers', category: 'Workplace Canvas / Dev-Ops', secret: 'Campfire conversation text row containers' },
  { n: 80, name: 'ClickUp Chat', nav: 'Persistent Solid Split Rail', input: 'Agile workspace sprint link managers', category: 'Workplace Canvas / Dev-Ops', secret: 'Real-time wiki document editor lanes' },
  { n: 81, name: 'Asana Chat', nav: 'Persistent Solid Split Rail', input: 'Direct task dependency status verifiers', category: 'Workplace Canvas / Dev-Ops', secret: 'High-resolution project Gantt timeline charts' },
  { n: 82, name: 'Monday Chat', nav: 'Persistent Solid Split Rail', input: 'Status column automated update hooks', category: 'Workplace Canvas / Dev-Ops', secret: 'Slide-out database row update panels' },
  { n: 83, name: 'Notion Chat', nav: 'Minimalist Hidden Edge Rail', input: 'Inline block-anchored discussion threads', category: 'Workplace Canvas / Dev-Ops', secret: 'High-whitespace page modification ledgers' },
  { n: 84, name: 'Miro Chat', nav: 'Minimalist Hidden Edge Rail', input: 'Whiteboard team canvas focus selectors', category: 'Spatial 2D/3D Art', secret: 'Real-time cursor particle text labels' },
  { n: 85, name: 'Figma Chat', nav: 'Minimalist Hidden Edge Rail', input: 'Cursor tracking vector feedback launchers', category: 'Spatial 2D/3D Art', secret: 'Floating viewport design comment sidebars' },
  { n: 86, name: 'Gather.town', nav: 'Volumetric Radial Overlay', input: 'Local proximity range layout rules', category: 'Spatial 3D Environments', secret: '16-bit pixel landscape private room maps' },
  { n: 87, name: 'Kumospace', nav: 'Volumetric Radial Overlay', input: 'Spatial broadcast perimeter distance bars', category: 'Spatial 3D Environments', secret: 'Floor plan WebGL presentation templates' },
  { n: 88, name: 'Topia', nav: 'Volumetric Radial Overlay', input: 'Hand-sketched link insertion parameters', category: 'Spatial 3D Environments', secret: 'Fading circular participant video bubbles' },
  { n: 89, name: 'Spatial', nav: 'Volumetric Radial Overlay', input: 'Digital wallet gallery verification tools', category: 'Spatial 3D Environments', secret: '3D museum display room asset frameworks' },
  { n: 90, name: 'Roblox Chat', nav: 'Volumetric Radial Overlay', input: 'Real-time text safety filter scanners', category: 'Spatial 3D Environments', secret: 'Translucent in-game game canvas boxes' },
  { n: 91, name: 'Minecraft Chat', nav: 'Volumetric Radial Overlay', input: 'Server console command execution sheets', category: 'Spatial 3D Environments', secret: 'Monospace script auto-complete panels' },
  { n: 92, name: 'Among Us Chat', nav: 'Volumetric Radial Overlay', input: 'Round-timer selection shortcut matrices', category: 'Spatial 3D Environments', secret: 'Character target voting card layouts' },
  { n: 93, name: 'Plato', nav: 'Floating Acrylic Bottom Dock', input: 'Casual turn-based board game match sheets', category: 'Dev-Ops / Community Boards', secret: 'Split-pane chat / multiplayer arcade frames' },
  { n: 94, name: 'Hago', nav: 'Floating Acrylic Bottom Dock', input: '3D screen animation gift trigger boxes', category: 'Dev-Ops / Community Boards', secret: 'Horizontal interactive virtual store rails' },
  { n: 95, name: 'Yubo', nav: 'Floating Acrylic Bottom Dock', input: 'Swiping category preference filters', category: 'Spatial 2D/3D Art', secret: 'Equal-tiled camera grid layout boxes' },
  { n: 96, name: 'Leket', nav: 'Persistent Solid Split Rail', input: 'Food delivery drop-off tracking maps', category: 'Workplace Canvas / Dev-Ops', secret: 'Local civic volunteer routing indicators' },
  { n: 97, name: 'Amino', nav: 'Persistent Solid Split Rail', input: 'Fan wiki resource authoring toolsets', category: 'Dev-Ops / Community Boards', secret: 'Custom fandom color theme asset builders' },
  { n: 98, name: 'Band', nav: 'Persistent Solid Split Rail', input: 'Team attendance roster check-in blocks', category: 'Workplace Canvas / Dev-Ops', secret: 'Roster item balance configuration matrices' },
  { n: 99, name: 'MeWe', nav: 'Persistent Solid Split Rail', input: 'Non-tracking data privacy switch controls', category: 'Stark Privacy Minimalist', secret: 'Chronological ad-free personal social feeds' },
  { n: 100, name: 'Nextdoor', nav: 'Persistent Solid Split Rail', input: 'Verified address localized proximity grids', category: 'Spatial 3D Environments', secret: 'Amber safety notice alert container plates' },
]

/** Category → count helper for the chips row. */
export function categoryCounts(): Record<string, number> {
  const counts: Record<string, number> = {}
  for (const app of MATRIX) {
    counts[app.category] = (counts[app.category] ?? 0) + 1
  }
  return counts
}
