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
 * value strings, stored under the EXACT web key "pulse.navStyle.v2".
 * Anything unreadable (junk, wrong case, null) resolves to CAPSULE - the
 * web DEFAULT_NAV_STYLE.
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
        /** Web zustand persist key (nav-registry.ts:90) - byte-identical. */
        const val PREFS_KEY = "pulse.navStyle.v2"

        const val DEFAULT_ID = "capsule"

        private val BY_ID = entries.associateBy { it.id }

        /**
         * Web isNavStyleId + fallback parity (nav-registry.ts:94-96): only an
         * EXACT (case-sensitive) id returns its style; junk, wrong case and
         * null all fall back to the capsule default.
         */
        fun fromPersisted(raw: String?): PulseNavStyle = BY_ID[raw] ?: CAPSULE
    }
}
