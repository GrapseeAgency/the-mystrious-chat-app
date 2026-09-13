import XCTest
@testable import Pulse

/// Wave 6 — discovery LOGIC tests (F-SM/F-FD/F-CH + deep links). The pure
/// kernels the new surfaces run on: the pulse:// parser, the mentions
/// regex mirror, the 99+ badge cap, channel/folder validation, the 2600 ms
/// two-tap delete window, the handle verdict machine, the ≤64 snippet
/// window and the folder-membership payload planner. Deterministic, no
/// network, no AVAudio, no device.
@MainActor
final class Wave6LogicTests: XCTestCase {

    // ── deep links (pulse:// scheme, F-DL) ───────────────────

    func testDeepLinkInviteRoute() {
        XCTAssertEqual(PulseDeepLink.parse(URL(string: "pulse://invite/abc123")!), .invite(code: "abc123"))
        // The web's ?join= legacy alias maps onto the same family.
        XCTAssertEqual(PulseDeepLink.parse(URL(string: "pulse://join/abc123")!), .invite(code: "abc123"))
    }

    func testDeepLinkUserRoute() {
        XCTAssertEqual(PulseDeepLink.parse(URL(string: "pulse://user/u-42")!), .user(userId: "u-42"))
        XCTAssertEqual(PulseDeepLink.parse(URL(string: "pulse://u/u-42")!), .user(userId: "u-42"))
    }

    func testDeepLinkRoomRoute() {
        XCTAssertEqual(PulseDeepLink.parse(URL(string: "pulse://room/c9")!), .room(conversationId: "c9"))
        XCTAssertEqual(PulseDeepLink.parse(URL(string: "pulse://chat/c9")!), .room(conversationId: "c9"))
        XCTAssertEqual(PulseDeepLink.parse(URL(string: "pulse://conversation/c9")!), .room(conversationId: "c9"))
    }

    func testDeepLinkPercentEncodedKeysDecode() {
        XCTAssertEqual(
            PulseDeepLink.parse(URL(string: "pulse://invite/a%20b")!),
            .invite(code: "a b"),
        )
    }

    func testDeepLinkRejectsForeignSchemesAndJunk() {
        XCTAssertNil(PulseDeepLink.parse(URL(string: "https://pulse.app/invite/abc")!))
        XCTAssertNil(PulseDeepLink.parse(URL(string: "pulse://unknown/k")!))
        XCTAssertNil(PulseDeepLink.parse(URL(string: "pulse://user")!))
        XCTAssertNil(PulseDeepLink.parse(URL(string: "pulse://user/")!))
    }

    // ── badge cap (mentions header + pills + dock) ───────────

    func testBadgeCapRenders99PlusPastTheCap() {
        XCTAssertEqual(PulseBadgeCap.cap(0), "0")
        XCTAssertEqual(PulseBadgeCap.cap(1), "1")
        XCTAssertEqual(PulseBadgeCap.cap(99), "99")
        XCTAssertEqual(PulseBadgeCap.cap(100), "99+")
        XCTAssertEqual(PulseBadgeCap.cap(999), "99+")
    }

    // ── mentions: composer token + feed chip mirror (F-SM-03/04) ──

    func testComposerTokenAnchoredAtTheTail() {
        XCTAssertEqual(PulseMentions.activeToken(in: "@Ada")?.token, "Ada")
        XCTAssertEqual(PulseMentions.activeToken(in: "hey @Ada")?.token, "Ada")
        // "@Ada @Bob" — the ACTIVE token is the last one.
        XCTAssertEqual(PulseMentions.activeToken(in: "@Ada @Bob")?.token, "Bob")
        XCTAssertEqual(PulseMentions.activeToken(in: "@")?.token, "")
    }

    func testComposerTokenAbsentWithoutTailTrigger() {
        // Mid-text @ with text after it is not an active completion.
        XCTAssertNil(PulseMentions.activeToken(in: "@Ada is here"))
        // An email-style @ glued to letters is NOT a trigger (needs ^|space).
        XCTAssertNil(PulseMentions.activeToken(in: "mail me@Ada"))
        XCTAssertNil(PulseMentions.activeToken(in: "plain text"))
    }

    func testComposerTokenCarriesTheAtSignIndex() {
        let draft = "ping @Ada"
        let token = PulseMentions.activeToken(in: draft)
        XCTAssertNotNil(token)
        // The character AT the reported index is the "@" itself.
        let index = draft.index(draft.startIndex, offsetBy: token!.atStartIndex)
        XCTAssertEqual(draft[index], "@")
        // And the token runs from the @ to the tail.
        XCTAssertEqual(draft[index...], "@Ada")
    }

    func testRosterMatchesArePrefixCaseInsensitiveAndCappedAtFive() {
        let roster = ["Ada Lovelace", "ada Wu", "ADAM", "Bob", "Adaissent", "Ada2", "Ada3", "Ada4", "Ada5", "Ada6"]
        let matches = PulseMentions.matches(for: "ada", in: roster)
        XCTAssertEqual(matches.count, 5) // limit 5
        XCTAssertTrue(matches.allSatisfy { $0.lowercased().hasPrefix("ada") })
    }

    func testMentionInsertionHasTheTrailingSpace() {
        XCTAssertEqual(PulseMentions.insertion(for: "Ada Lovelace"), "@Ada Lovelace ")
    }

    /// The feed-chip mirror of the API boundary rule (?=\s|$|[^A-Za-z0-9]).
    func testFeedChipBoundaryRules() {
        // End of string boundary.
        XCTAssertNotNil(PulseMentions.firstMatch(of: "Ada", in: "ping @Ada"))
        // Whitespace boundary.
        XCTAssertNotNil(PulseMentions.firstMatch(of: "Ada", in: "@Ada hello"))
        // Punctuation boundary.
        XCTAssertNotNil(PulseMentions.firstMatch(of: "Ada", in: "hey @Ada!"))
        // Case-insensitive.
        XCTAssertNotNil(PulseMentions.firstMatch(of: "Ada", in: "yo @ada"))
        // NOT a hit inside a longer name (no boundary after "Ada").
        XCTAssertNil(PulseMentions.firstMatch(of: "Ada", in: "ping @Adalbert"))
        // No @, no match.
        XCTAssertNil(PulseMentions.firstMatch(of: "Ada", in: "ping Ada"))
    }

    func testFeedChipRangeSplitsTheSnippet() {
        let snippet = "ping @Ada!"
        let range = PulseMentions.firstMatch(of: "Ada", in: snippet)!
        XCTAssertEqual(String(snippet[range]), "@Ada")
        XCTAssertEqual(String(snippet[snippet.startIndex..<range.lowerBound]), "ping ")
        XCTAssertEqual(String(snippet[range.upperBound...]), "!")
    }

    // ── channel create validation (verbatim copy) ────────────

    func testChannelNameValidationCopy() {
        XCTAssertEqual(PulseChannelDraft.nameError("a"), "Name needs at least 2 characters.")
        XCTAssertEqual(PulseChannelDraft.nameError(" a "), "Name needs at least 2 characters.")
        XCTAssertEqual(PulseChannelDraft.nameError("ab"), nil)
        XCTAssertEqual(PulseChannelDraft.nameError(String(repeating: "x", count: 40)), nil)
        XCTAssertEqual(PulseChannelDraft.nameError(String(repeating: "x", count: 41)), "Keep the name under 41 characters.")
    }

    func testChannelNameValidationTrimsBeforeMeasuring() {
        // "  ab  " trims to 2 → valid.
        XCTAssertEqual(PulseChannelDraft.nameError("  ab  "), nil)
        // 41 chars hidden inside padding still fails.
        let long = " " + String(repeating: "x", count: 41) + " "
        XCTAssertEqual(PulseChannelDraft.nameError(long), "Keep the name under 41 characters.")
    }

    func testChannelDescriptionIsTrimmedAndCapped() {
        XCTAssertEqual(PulseChannelDraft.trimmedDescription("  hello  "), "hello")
        let over = String(repeating: "y", count: 260)
        XCTAssertEqual(PulseChannelDraft.trimmedDescription(over).count, 200)
    }

    // ── folder validation + membership planner (F-FD) ────────

    func testFolderNameValidation() {
        XCTAssertFalse(PulseFolderDraft.isValidName(""))
        XCTAssertFalse(PulseFolderDraft.isValidName("   "))
        XCTAssertTrue(PulseFolderDraft.isValidName("W"))
        XCTAssertTrue(PulseFolderDraft.isValidName(String(repeating: "n", count: 24)))
        XCTAssertFalse(PulseFolderDraft.isValidName(String(repeating: "n", count: 25)))
    }

    func testFolderMembershipPlannerKeepsChatListOrder() {
        let order = ["c5", "c1", "c3", "c9"]
        XCTAssertEqual(
            FolderMembership.orderedSelection(checked: ["c3", "c5"], listOrder: order),
            ["c5", "c3"],
        )
        // Nothing checked → empty array (PUT clears the folder).
        XCTAssertEqual(FolderMembership.orderedSelection(checked: [], listOrder: order), [])
        // Checked ids absent from the list never leak into the payload.
        XCTAssertEqual(FolderMembership.orderedSelection(checked: ["ghost"], listOrder: order), [])
    }

    // ── two-tap destructive confirm (2600 ms window) ─────────

    func testTwoTapWindowArmsAndExecutes() {
        // Never armed → first tap does NOT execute.
        XCTAssertFalse(PulseTwoTap.shouldExecute(nowMs: 1000, armedAtMs: nil))
        // Second tap inside 2600 ms executes.
        XCTAssertTrue(PulseTwoTap.shouldExecute(nowMs: 3600, armedAtMs: 1000))
        // Exactly at the window edge is still IN (inclusive).
        XCTAssertTrue(PulseTwoTap.shouldExecute(nowMs: 1000 + PulseTwoTap.windowMs, armedAtMs: 1000))
        // One ms past the window re-arms instead.
        XCTAssertFalse(PulseTwoTap.shouldExecute(nowMs: 1000 + PulseTwoTap.windowMs + 1, armedAtMs: 1000))
        // Clock sanity: a negative delta never executes.
        XCTAssertFalse(PulseTwoTap.shouldExecute(nowMs: 500, armedAtMs: 1000))
    }

    // ── handle verdict machine (profile edit, verbatim copy) ──

    func testHandleVerdictOrder() {
        var state = HandleCheckState()
        XCTAssertEqual(state.verdict(currentHandle: "ada"), .empty)

        state.value = "Bad Handle!"
        XCTAssertEqual(state.verdict(currentHandle: "ada"), .invalid)

        state.value = "ada"
        state.checking = true
        XCTAssertEqual(state.verdict(currentHandle: "ada"), .checking)

        state.checking = false
        state.checkedValue = "ada"
        state.available = true
        XCTAssertEqual(state.verdict(currentHandle: "ada"), .current)

        state.value = "ada2"
        state.checkedValue = "ada2"
        XCTAssertEqual(state.verdict(currentHandle: "ada"), .free("ada2", suggestion: nil))

        state.available = false
        XCTAssertEqual(state.verdict(currentHandle: "ada"), .taken("ada2", suggestion: nil))

        state.value = "ada2"
        state.checkedValue = "ada2"
        state.clashSuggestion = "ada_3"
        XCTAssertEqual(state.verdict(currentHandle: "ada"), .clash("ada2", suggestion: "ada_3"))

        // Staleness: a result for an older value reports checking.
        state.clashSuggestion = nil
        state.available = true
        state.checkedValue = "old"
        XCTAssertEqual(state.verdict(currentHandle: "ada"), .checking)
    }

    func testHandleVerdictCopyIsVerbatim() {
        XCTAssertEqual(HandleCheckState.message(for: .empty), "Type a handle, or leave empty.")
        XCTAssertEqual(HandleCheckState.message(for: .invalid), "3–20 characters: a-z, 0-9, underscore.")
        XCTAssertEqual(HandleCheckState.message(for: .free("ada", suggestion: nil)), "@ada is free")
        XCTAssertEqual(HandleCheckState.message(for: .current), "That's your current handle")
        XCTAssertEqual(HandleCheckState.message(for: .taken("ada", suggestion: nil)), "@ada is taken")
        // The 409-clash line the task pins word-for-word.
        XCTAssertEqual(HandleCheckState.message(for: .clash("ada", suggestion: "ada_2")), "@ada was just taken")
    }

    func testHandleValidationRules() {
        XCTAssertTrue(OnboardingViewModel.isValidHandle("ada_2"))
        XCTAssertFalse(OnboardingViewModel.isValidHandle("ab")) // < 3
        XCTAssertFalse(OnboardingViewModel.isValidHandle(String(repeating: "a", count: 21))) // > 20
        XCTAssertFalse(OnboardingViewModel.isValidHandle("Ada")) // uppercase
        XCTAssertFalse(OnboardingViewModel.isValidHandle("a da")) // space
        // sanitize drops (never substitutes) the disallowed characters.
        XCTAssertEqual(OnboardingViewModel.sanitizeHandle("  Ada WU! "), "adawu")
    }

    // ── the ≤64 snippet window (chats search + room search) ──

    func testSnippetWindowShortContentStaysWhole() {
        let window = HighlightedSnippet.window(content: "hello world", query: "world")
        XCTAssertEqual(String(window.body), "hello world")
        XCTAssertFalse(window.clippedHead)
        XCTAssertFalse(window.clippedTail)
        XCTAssertEqual(window.matchRange.map { String(window.body[$0]) }, "world")
    }

    func testSnippetWindowClipsHeadWhenMatchIsDeep() {
        // 40 chars of lead + match → idx 40 > 28 → head clip at idx-24.
        let lead = String(repeating: "a", count: 40)
        let content = lead + "needle" + String(repeating: "b", count: 5)
        let window = HighlightedSnippet.window(content: content, query: "needle")
        XCTAssertTrue(window.clippedHead)
        // The ellipsis glyphs render OUTSIDE the body — the body itself is
        // the 24-char lead + match + the tail the ≤64 budget still allows.
        XCTAssertEqual(window.body.count, 34)
        XCTAssertTrue(window.clippedTail)
        XCTAssertEqual(window.matchRange.map { String(window.body[$0]) }, "needle")
    }

    func testSnippetWindowClipsTailAndCapsTheWindow() {
        let content = "needle" + String(repeating: "b", count: 80)
        let window = HighlightedSnippet.window(content: content, query: "needle")
        XCTAssertFalse(window.clippedHead)
        XCTAssertTrue(window.clippedTail)
        // 6 (match) + 28 (tail) = 34 total — inside the ≤64 budget.
        XCTAssertEqual(window.body.count, 34)
    }

    func testSnippetWindowWithoutAMatchDegradesToHeadClip() {
        let short = String(repeating: "z", count: 30)
        let window = HighlightedSnippet.window(content: short, query: "needle")
        XCTAssertEqual(String(window.body), short)
        XCTAssertFalse(window.clippedTail)

        let long = String(repeating: "z", count: 90)
        let clipped = HighlightedSnippet.window(content: long, query: "needle")
        XCTAssertEqual(clipped.body.count, 64)
        XCTAssertTrue(clipped.clippedTail)
        XCTAssertNil(clipped.matchRange)
    }

    // ── status glyph display (F-CP-09) ───────────────────────

    func testVacationTokenMapsToAPlaneGlyphForDisplayOnly() {
        XCTAssertEqual(UserPageView.statusGlyphDisplay("vacation"), "✈️")
        XCTAssertEqual(UserPageView.statusGlyphDisplay("🔥"), "🔥")
    }

    func testProfileEditGlyphSetMatchesTheElevenStoredValues() {
        XCTAssertEqual(ProfileEditView.statusGlyphs, ["🔥", "✨", "🎯", "☕", "🎧", "🌙", "💡", "🚀", "😴", "🍽️", "vacation"])
        XCTAssertEqual(ProfileEditView.colors.count, 8)
        XCTAssertEqual(ProfileEditView.nameMax, 32)
        XCTAssertEqual(ProfileEditView.aboutMax, 140)
        XCTAssertEqual(ProfileEditView.statusMax, 48)
    }
}
