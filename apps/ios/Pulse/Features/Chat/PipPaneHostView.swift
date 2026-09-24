import SwiftUI

// ─────────────────────────────────────────────────────────────
// R1-W2I — PiP pane host (F-PI-01..03). iOS mirror of the web
// PipChat + pip-stack and of the Android PipPaneOverlay:
//   · ONE expanded draggable glass card (the focused pane)
//   · every other live pane collapses into a 48pt pill stack on
//     the right edge (avatar + unread badge + close)
//   · drag repositions with the web pip-store.ts frame reserves
//     (top 64 / bottom 92 / margin 10), positions persist via the
//     store (UserDefaults `pulse.pip.v2`)
//   · header tap → opens the conversation in the main shell
//   · composer sends through the REAL api path with the same
//     outbox fallback the room composer uses (no second socket)
//   · data = the shared GRDB cache (cachedConversations) — the web
//     "no extra polling" rule
// Mounted once at the RootView level, below the call/rooms overlays.
// ─────────────────────────────────────────────────────────────

struct PipPaneHostView: View {
    @ObservedObject var session: PulseSession
    /// Direct observation — PulseSession does not forward nested store
    /// changes, so pane additions/removals must be observed here.
    @ObservedObject var pip: PulsePiPStore
    /// Bridge into the main shell (RootView): open the conversation full-screen.
    let onOpenRoom: (String) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var dragOffset: CGSize = .zero
    @State private var paneDraft = ""

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topTrailing) {
                if let focused = focusedPane(in: geo) {
                    expandedCard(focused, in: geo)
                        .transition(reduceMotion ? .opacity : .scale(scale: 0.92).combined(with: .opacity))
                }
                pillStack(in: geo)
            }
        }
        .allowsHitTesting(pip.isOpen)
        .animation(reduceMotion ? nil : .pulse(.pulseSnappy, reduceMotion: reduceMotion), value: pip.panes)
    }

    // ── focused pane ──────────────────────────────────────────

    private func focusedPane(in geo: GeometryProxy) -> PulsePiPStore.PipPane? {
        guard let id = pip.conversationId else { return nil }
        return pip.panes.first { $0.conversationId == id && !$0.minimized }
    }

    private func expandedCard(_ pane: PulsePiPStore.PipPane, in geo: GeometryProxy) -> some View {
        let conversation = cachedConversation(pane.conversationId)
        let width = min(max(geo.size.width - 2 * PipGeometry.marginX, PipGeometry.paneMinW), PipGeometry.paneMaxW)
        let height = min(max(geo.size.height * 0.62, PipGeometry.paneMinH), PipGeometry.paneMaxH)
        let rangeX = max(geo.size.width - width - 2 * PipGeometry.marginX, 0)
        let rangeY = max(
            geo.size.height - height - PipGeometry.topReserve - PipGeometry.bottomReserve,
            0
        )
        let baseX = PipGeometry.marginX + CGFloat(pane.nx) * rangeX
        let baseY = PipGeometry.topReserve + CGFloat(pane.ny) * rangeY

        return VStack(spacing: 0) {
            PipPaneHeader(
                title: conversation?.title ?? "Chat",
                onClose: {
                    PulseHaptics.tap()
                    pip.close()
                },
                onMinimize: {
                    PulseHaptics.tap()
                    pip.minimize()
                },
            )
            .contentShape(Rectangle())
            .onTapGesture {
                PulseHaptics.tap()
                onOpenRoom(pane.conversationId)
            }
            Divider().overlay(PulseTheme.zinc(200).opacity(0.5))
            VStack(alignment: .leading, spacing: 8) {
                if let preview = conversation?.lastMessagePreview, !preview.isEmpty {
                    Text(preview)
                        .font(.system(size: 13))
                        .foregroundStyle(PulseTheme.textPrimary)
                        .lineLimit(4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    Text("No messages yet — say hi from here.")
                        .font(.system(size: 13))
                        .foregroundStyle(PulseTheme.textSecondary)
                }
                Spacer(minLength: 0)
                PipPaneComposer(
                    draft: $paneDraft,
                    onSend: { sendFromPane(pane.conversationId) },
                )
            }
            .padding(12)
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .frame(width: width, height: height)
        .background(.ultraThinMaterial)
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Color.white.opacity(0.14), lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .shadow(color: .black.opacity(0.28), radius: 18, y: 10)
        .onAppear {
            // R7 — the expanded window is the reading surface: mark the pane
            // seen LOCALLY (web pip-chat.tsx:255-259 onSeen parity — no server
            // /read call; the store's 1s guard absorbs repeat appearances and
            // the pill badges read this watermark).
            pip.markSeen(pane.conversationId, at: Date().timeIntervalSince1970 * 1000)
        }
        .offset(x: baseX + dragOffset.width, y: baseY + dragOffset.height)
        .gesture(
            DragGesture(minimumDistance: 4)
                .onChanged { value in dragOffset = value.translation }
                .onEnded { value in
                    let nx = Double(baseX + value.translation.width - PipGeometry.marginX) / Double(max(rangeX, 1))
                    let ny = Double(baseY + value.translation.height - PipGeometry.topReserve) / Double(max(rangeY, 1))
                    pip.setPanePosition(pane.conversationId, nx: nx, ny: ny)
                    dragOffset = .zero
                }
        )
    }

    // ── collapsed pill stack ──────────────────────────────────

    private func pillStack(in geo: GeometryProxy) -> some View {
        let pills = pip.panes.filter(\.minimized)
        return VStack(spacing: PipGeometry.stackGap) {
            if pip.conversationId == nil, pip.minimized, !pills.isEmpty {
                Button {
                    PulseHaptics.tap()
                    pip.restore()
                } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: PipGeometry.stackPill, height: PipGeometry.stackPill)
                        .background(Circle().fill(PulseTheme.emerald))
                }
            }
            ForEach(pills, id: \.conversationId) { pane in
                PipPanePill(
                    conversation: cachedConversation(pane.conversationId),
                    lastSeenAt: pane.lastSeenAt,
                    onClose: { pip.closePane(pane.conversationId) },
                    onTap: {
                        PulseHaptics.tap()
                        pip.focusPane(pane.conversationId)
                    },
                )
            }
        }
        .frame(maxHeight: .infinity, alignment: .bottom)
        .padding(.trailing, PipGeometry.marginX)
        .padding(.bottom, PipGeometry.bottomReserve)
    }

    private func cachedConversation(_ id: String) -> PulseConversation? {
        (try? session.store?.cachedConversations())?.first { $0.id == id } ?? nil
    }

    // ── send (real path + outbox fallback) ────────────────────

    private func sendFromPane(_ conversationId: String) {
        let body = paneDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        paneDraft = ""
        PulseHaptics.tap()
        Task {
            do {
                _ = try await session.api.sendMessage(
                    conversationId: conversationId,
                    content: body,
                    replyToId: nil,
                    topicId: nil,
                    anon: false,
                )
            } catch {
                // Network-class failure → the outbox queue (same contract as
                // the room composer); hard failures restore the draft.
                if PulseOutboxEngine.isDroppable(error) {
                    session.toasts.show("Couldn't send from the mini chat — kept as draft")
                    paneDraft = body
                } else {
                    session.enqueueOutbox(conversationId: conversationId, clientId: UUID().uuidString, content: body)
                    session.toasts.show("Message queued — sends when you're back online")
                }
            }
        }
    }
}

// ── pieces ───────────────────────────────────────────────────

private struct PipPaneHeader: View {
    let title: String
    let onClose: () -> Void
    let onMinimize: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(PulseTheme.brandGradient)
                .frame(width: 22, height: 22)
                .overlay(
                    Text(String(title.prefix(1)).uppercased())
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                )
            Text(title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(PulseTheme.titleOnPanel)
                .lineLimit(1)
            Spacer(minLength: 4)
            Button(action: onMinimize) {
                Image(systemName: "minus")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(PulseTheme.textSecondary)
                    .frame(width: 26, height: 26)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(PulseTheme.textSecondary)
                    .frame(width: 26, height: 26)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
    }
}

private struct PipPaneComposer: View {
    @Binding var draft: String
    let onSend: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            TextField("Message…", text: $draft, axis: .vertical)
                .font(.system(size: 13))
                .lineLimit(1...3)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(
                    Capsule().fill(PulseTheme.zinc(200).opacity(0.35))
                )
                .onSubmit(onSend)
            Button(action: onSend) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(PulseTheme.emerald))
                    .opacity(draft.trimmingCharacters(in: .whitespaces).isEmpty ? 0.45 : 1)
            }
            .buttonStyle(.plain)
            .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }
}

private struct PipPanePill: View {
    let conversation: PulseConversation?
    /// R7 — the pane's local seen watermark (ms epoch): the badge counts only
    /// rows newer than it (web usePaneUnread parity) — the expanded card's
    /// markSeen onAppear clears the pill's stale badge.
    let lastSeenAt: Double
    let onClose: () -> Void
    let onTap: () -> Void

    /// unreadCount > 0 AND the cached row's last activity is newer than the
    /// seen watermark (a missing activity stamp keeps the legacy badge).
    private var showsUnreadBadge: Bool {
        guard let unread = conversation?.unreadCount, unread > 0 else { return false }
        guard let iso = conversation?.lastActivityAt,
              let activityMs = PulseFormat.date(iso).map({ $0.timeIntervalSince1970 * 1000 })
        else { return true }
        return activityMs > lastSeenAt
    }

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .topTrailing) {
                Circle()
                    .fill(.ultraThinMaterial)
                    .frame(width: 48, height: 48)
                    .overlay(
                        Circle().strokeBorder(Color.white.opacity(0.14), lineWidth: 1)
                    )
                    .overlay(
                        Text(String((conversation?.title ?? "C").prefix(1)).uppercased())
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(PulseTheme.emerald)
                    )
                if showsUnreadBadge, let unread = conversation?.unreadCount {
                    Text(unread > 9 ? "9+" : "\(unread)")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 4)
                        .frame(minWidth: 15, minHeight: 15)
                        .background(Capsule().fill(PulseTheme.brandGradient))
                        .offset(x: 5, y: -3)
                }
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 7, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 13, height: 13)
                        .background(Circle().fill(PulseTheme.zinc(900).opacity(0.85)))
                }
                .buttonStyle(.plain)
                .offset(x: 3, y: -2)
            }
        }
        .buttonStyle(.plain)
    }
}
