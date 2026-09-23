package app.pulse.protocol

/**
 * R4-B item 3 — navigation-style registry (web src/lib/nav-registry.ts port,
 * phone-feasible subset). The web ships 13 architectures; Android ports the
 * 8 structural styles that make sense on a phone:
 *
 *   capsule · floating-top · pill · bottom-bar · tab-bar ·
 *   floating-tab-bar · rail · island
 *
 * EXCLUDED on this platform (desktop/keyboard/exotic idioms — the web keeps
 * them, Android parses them to the capsule fallback):
 *   floating-dock  · desktop-style dock with magnifying (hover) icons
 *   command-bar    · compact text command strip (⌘K-style search chrome)
 *   radial         · FAB fanning destinations in an arc
 *   gesture        · edge swipes + gesture-pill quick switcher
 *   contextual-dock· dock that morphs per tab (layout-thrash on phones)
 *
 * Persistence is byte-parity with the web: the raw id strings ARE the web
 * value strings, stored under the EXACT web key "pulse.navStyle.v2".
 * Anything unreadable (junk, wrong case, the 5 excluded ids, null) resolves
 * to CAPSULE — the web DEFAULT_NAV_STYLE.
 */
enum class PulseNavStyle(val id: String) {
    CAPSULE("capsule"),
    FLOATING_TOP("floating-top"),
    PILL("pill"),
    BOTTOM_BAR("bottom-bar"),
    TAB_BAR("tab-bar"),
    FLOATING_TAB_BAR("floating-tab-bar"),
    RAIL("rail"),
    ISLAND("island");

    companion object {
        /** Web zustand persist key (nav-registry.ts:90) — byte-identical. */
        const val PREFS_KEY = "pulse.navStyle.v2"

        const val DEFAULT_ID = "capsule"

        /** The 5 web styles Android does not ship — every one parses to CAPSULE. */
        val EXCLUDED_ON_PHONE = setOf("floating-dock", "command-bar", "radial", "gesture", "contextual-dock")

        private val BY_ID = entries.associateBy { it.id }

        /**
         * Web isNavStyleId + fallback parity (nav-registry.ts:94-96): only an
         * EXACT (case-sensitive) included id returns its style; the 5 excluded
         * ids, junk, wrong case and null all fall back to the capsule default.
         */
        fun fromPersisted(raw: String?): PulseNavStyle = BY_ID[raw] ?: CAPSULE
    }
}
