import SwiftUI

/// Wave 6 — the invite deep-link join sheet (F-DL, join-sheet.tsx parity):
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
        .task { await loadPreview() }
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
                ? "You are already in this group — jump back in?"
                : "You were invited to join this group on Pulse.")
                .font(.system(size: 13))
                .foregroundStyle(PulseTheme.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 280)
            Button {
                if invite.alreadyMember == true {
                    finish(alreadyMember: true)
                } else {
                    Task { await join() }
                }
            } label: {
                HStack(spacing: 8) {
                    if joining {
                        ProgressView().tint(.white)
                        Text("Joining…")
                    } else {
                        Image(systemName: "arrow.right.to.line")
                        Text(invite.alreadyMember == true ? "Open chat" : "Join group")
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

    private func loadPreview() async {
        defer { loading = false }
        do {
            preview = try await session.api.invitePreview(code: code)
        } catch {
            invalid = true
        }
    }

    private func join() async {
        guard !joining else { return }
        joining = true
        defer { joining = false }
        do {
            let result = try await session.api.joinInvite(code: code)
            let name = preview?.name ?? "the group"
            PulseHaptics.success()
            session.toasts.show(result.alreadyMember == true ? "You are already in this group" : "Welcome to \(name)!")
            session.noteInboxChanged()
            if let conv = try? await session.api.conversationDetail(id: result.conversationId, userId: session.api.userId) {
                dismiss()
                onJoined(conv)
            } else {
                session.toasts.show("Joined — find it at the top of your chats")
            }
        } catch let failure as PulseAPIClient.Failure {
            session.toasts.show(failure.message ?? "Could not join the group")
        } catch {
            session.toasts.show("Could not join the group")
        }
    }

    private func finish(alreadyMember: Bool) {
        guard let conversationId = preview?.conversationId else { return }
        Task {
            if let conv = try? await session.api.conversationDetail(id: conversationId, userId: session.api.userId) {
                dismiss()
                onJoined(conv)
            } else {
                session.toasts.show("That chat isn't available right now")
            }
        }
    }
}
