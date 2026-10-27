import SwiftUI

/// Wave 6 - the invite deep-link join sheet (F-DL, join-sheet.tsx parity):
/// previews the group behind a pulse://invite/{code} link, then joins (or
/// jumps in when the viewer is already a member). Every state + string is
/// the verbatim web copy: invalid-link pair, member-count line, the two
/// invite lines, "Open chat"/"Join group"/"Joining…", "Not now", and the
/// welcome toasts.
struct JoinInviteSheet: View {
    @ObservedObject var session: PulseSession
    let code: String
    /// Handed the joined/already-member conversation to open.
    var onJoined: (WireConversationSummary) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var preview: WireInvitePreview?
    @State private var loading = true
    @State private var invalid = false
    @State private var joining = false
    @State private var loadError: String?
    @State private var mounted = false
    @State private var ownerEpoch: UUID?
    @State private var loadTicket = UUID()
    @State private var actionTicket = UUID()
    @State private var loadTask: Task<Void, Never>?
    @State private var actionTask: Task<Void, Never>?
    @State private var joinedRoom: String?

    var body: some View {
        VStack(spacing: 14) {
            if loading {
                VStack(spacing: 14) {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(PulseTheme.chipFill)
                        .frame(width: 64, height: 64)
                    RoundedRectangle(cornerRadius: 8).fill(PulseTheme.chipFill).frame(width: 160, height: 20)
                    RoundedRectangle(cornerRadius: 8).fill(PulseTheme.chipFill).frame(width: 96, height: 16)
                }
                .padding(.vertical, 26)
            } else if let loadError {
                VStack(spacing: 10) {
                    Text(loadError).font(.footnote).foregroundStyle(PulseTheme.textSecondary)
                    Button("Retry") { startPreview() }
                    Button("Not now") { dismiss() }
                }.padding(.vertical, 18)
            } else if invalid || preview == nil {
                VStack(spacing: 10) {
                    Image(systemName: "link")
                        .font(.system(size: 22))
                        .foregroundStyle(PulseTheme.textTertiary)
                        .frame(width: 56, height: 56)
                        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(PulseTheme.chipFill))
                    Text("Link not valid")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                    Text("This invite was reset or never existed. Ask the group admin for a fresh one.")
                        .font(.system(size: 12))
                        .foregroundStyle(PulseTheme.textSecondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 260)
                    Button {
                        dismiss()
                    } label: {
                        Text("Close")
                            .font(.system(size: 13.5, weight: .medium))
                            .frame(maxWidth: .infinity, minHeight: 40)
                            .background(RoundedRectangle(cornerRadius: 12).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
                    }
                    .buttonStyle(PulseButtonStyle())
                    .padding(.top, 4)
                }
                .padding(.vertical, 18)
            } else {
                previewBody
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 6)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .onAppear {
            mounted = true
            if ownerEpoch != session.sessionGeneration { joinedRoom = nil }
            ownerEpoch = session.sessionGeneration
            startPreview()
        }
        .onDisappear { retire() }
        .onChange(of: session.sessionGeneration) { _, _ in retire(); dismiss() }
        .onChange(of: code) { _, _ in
            actionTicket = UUID(); actionTask?.cancel(); actionTask = nil
            joining = false; joinedRoom = nil
            startPreview()
        }
    }

    @ViewBuilder
    private var previewBody: some View {
        let invite = preview!
        VStack(spacing: 10) {
            RowAvatar(name: invite.name ?? "Group", colorName: nil, photoPath: nil, size: 64, groupID: invite.conversationId ?? code)
            Text(invite.name ?? "Group")
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(PulseTheme.titleOnPanel)
                .lineLimit(1)
            HStack(spacing: 5) {
                Image(systemName: "person.2")
                    .font(.system(size: 11))
                Text(memberLine(invite.memberCount))
                    .font(.system(size: 12, weight: .medium))
            }
            .foregroundStyle(PulseTheme.textSecondary)
            Text(invite.alreadyMember == true
                ? "You are already in this group - jump back in?"
                : "You were invited to join this group on Pulse.")
                .font(.system(size: 13))
                .foregroundStyle(PulseTheme.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 280)
            Button {
                startJoin()
            } label: {
                HStack(spacing: 8) {
                    if joining {
                        ProgressView().tint(.white)
                        Text("Joining…")
                    } else {
                        Image(systemName: "arrow.right.to.line")
                        Text(invite.alreadyMember == true || joinedRoom != nil ? "Open chat" : "Join group")
                    }
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.emerald600))
            }
            .buttonStyle(PulseButtonStyle())
            .disabled(joining)
            Button {
                dismiss()
            } label: {
                Text("Not now")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(PulseTheme.textTertiary)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 8)
    }

    private func memberLine(_ count: Int?) -> String {
        let value = count ?? 0
        return "\(value) member\(value == 1 ? "" : "s")"
    }

    private func owns(_ epoch: UUID) -> Bool {
        mounted && ownerEpoch == epoch && session.sessionGeneration == epoch && !Task.isCancelled
    }

    private func retire() {
        mounted = false
        loadTicket = UUID(); actionTicket = UUID()
        loadTask?.cancel(); actionTask?.cancel()
        loadTask = nil; actionTask = nil
        joining = false
    }

    private func startPreview() {
        let epoch = session.sessionGeneration
        guard owns(epoch), !joining else { return }
        let api = session.api, requestedCode = code, ticket = UUID()
        loadTask?.cancel(); loadTicket = ticket
        loading = true; invalid = false; loadError = nil; preview = nil
        loadTask = Task { @MainActor in
            defer { if owns(epoch), loadTicket == ticket { loading = false; loadTask = nil } }
            do {
                guard owns(epoch), loadTicket == ticket else { return }
                let result = try await api.invitePreview(code: requestedCode)
                guard owns(epoch), loadTicket == ticket, code == requestedCode else { return }
                guard let room = result.conversationId, !room.isEmpty else {
                    loadError = "The server didn't identify this invite's group. Retry loading it."
                    return
                }
                preview = result
            } catch {
                guard owns(epoch), loadTicket == ticket else { return }
                if PulseInviteRules.isInvalidPreview(error) { invalid = true }
                else { loadError = "Couldn't load this invite. Check your connection and retry; the link has not been confirmed invalid." }
            }
        }
    }

    private func startJoin() {
        let epoch = session.sessionGeneration
        guard owns(epoch), !joining, let preview, let expectedRoom = preview.conversationId else { return }
        let api = session.api, requestedCode = code, ticket = UUID()
        session.noteRoomNavigationIntent()
        let navigation = session.roomNavigationGeneration
        actionTicket = ticket
        joining = true
        actionTask = Task { @MainActor in
            defer { if actionTicket == ticket { joining = false; actionTask = nil } }
            do {
                guard owns(epoch), actionTicket == ticket else { return }
                var room = joinedRoom ?? expectedRoom
                if preview.alreadyMember != true && joinedRoom == nil {
                    let result = try await api.joinInvite(code: requestedCode)
                    guard owns(epoch), actionTicket == ticket, code == requestedCode else { return }
                    guard result.conversationId == expectedRoom else {
                        session.noteInboxChanged()
                        session.toasts.show("The joined chat didn't match the preview. Check Chats before trying again.")
                        return
                    }
                    room = result.conversationId
                    joinedRoom = room // an opening failure must not repeat the membership mutation
                    PulseHaptics.success()
                    session.toasts.show(result.alreadyMember == true ? "You are already in this group" : "Welcome to \(preview.name ?? "the group")!")
                    session.noteInboxChanged()
                }
                let conv = try await PulseConversationNavigation.conversation(id: room, cached: []) {
                    try await api.conversationDetail(id: room, userId: api.userId)
                }
                guard owns(epoch), actionTicket == ticket, session.roomNavigationGeneration == navigation else { return }
                onJoined(conv)
                dismiss()
            } catch {
                guard owns(epoch), actionTicket == ticket, session.roomNavigationGeneration == navigation else { return }
                session.toasts.show(joinedRoom != nil ? "Joined, but opening failed. Tap Open chat or find it in Chats."
                    : ChatsViewModel.describe(error))
            }
        }
    }
}
