package app.pulse.protocol

/**
 * R4-B item 3 / R14 - navigation-style registry (web src/lib/nav-registry.ts
 * port, now 13/13). The web ships 13 architectures; Android ports ALL of
 * them as honest mobile adaptations of the desktop/keyboard idioms:
 *
 *   capsule · floating-top · floating-dock · pill · bottom-bar · tab-bar ·
 *   floating-tab-bar · command-bar · rail · island · radial · gesture ·
 *   contextual-dock
 *
 * Mobile adaptations (MainActivity renderers):
 *   floating-dock  · icon-only dock, the ACTIVE tile magnifies (web hover)
 *   command-bar    · top glass strip with tabs + search/settings buttons
 *   radial         · center FAB fanning the destinations in an arc overlay
 *   gesture        · minimal bottom pill; drag = tab switch, tap = switcher
 *   contextual-dock· dock whose trailing action chip morphs per active tab
 *
 * Persistence is byte-parity with the web: the raw id strings ARE the web
 * value strings. R24 NATIVE: the storage key bumps to v3 so every install
 * (including devices that still carry the old default) lands on the native
 * BOTTOM_BAR default; the id strings themselves stay web-verbatim.
 * Anything unreadable (junk, wrong case, null) resolves to BOTTOM_BAR -
 * the native default navigation.
 */
enum class PulseNavStyle(val id: String) {
    CAPSULE("capsule"),
    FLOATING_TOP("floating-top"),
    FLOATING_DOCK("floating-dock"),
    PILL("pill"),
    BOTTOM_BAR("bottom-bar"),
    TAB_BAR("tab-bar"),
    FLOATING_TAB_BAR("floating-tab-bar"),
    COMMAND_BAR("command-bar"),
    RAIL("rail"),
    ISLAND("island"),
    RADIAL("radial"),
    GESTURE("gesture"),
    CONTEXTUAL_DOCK("contextual-dock");

    companion object {
        /**
         * R24 NATIVE - the Android storage key bumps to v3 (the web keeps
         * its own zustand key; prefs are per-platform local so there is no
         * cross-device contract to preserve). The bump retires every
         * persisted v2 choice so the native default applies app-wide.
         */
        const val PREFS_KEY = "pulse.navStyle.v3"

        /** The native navigation: a standard Material bottom bar. */
        const val DEFAULT_ID = "bottom-bar"

        private val BY_ID = entries.associateBy { it.id }

        /**
         * Web isNavStyleId + fallback parity (nav-registry.ts:94-96): only an
         * EXACT (case-sensitive) id returns its style; junk, wrong case and
         * null all fall back to the native bottom-bar default.
         */
        fun fromPersisted(raw: String?): PulseNavStyle = BY_ID[raw] ?: BOTTOM_BAR
    }
}
