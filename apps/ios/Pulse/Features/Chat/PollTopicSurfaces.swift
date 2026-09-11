import SwiftUI

/// Wave 2 chat surfaces — polls, topics and link previews, plus the pure
/// decision helpers the room view model (and Wave2UITests) consume:
///   • UnfurlTrigger — spec §1 row 8 (sender-side fire-and-forget /unfurl).
///   • TopicHeal — spec §1 row 9 (a vanished active topic resets to General).
///   • PollCard — spec §1 rows 2/3/4 (votedBy-derived pick, no revote,
///     single-choice + manual close).
///   • PollBuilderSheet — spec §1 row 5-adjacent attach flow (question ≤140,
///     2–6 non-blank options).
///   • TopicBar — spec §1 row 9 (General = the WHOLE room, never a row).
///   • LinkPreviewCard — spec §1 row 8 (absent-then-arrives: until the
///     envelope lands the row stays plain text).
enum UnfurlTrigger {
    /// Simple contains-check (spec mandate): http(s):// or a bare www.
    static func matches(_ content: String) -> Bool {
        let lowered = content.lowercased()
        return lowered.contains("http://")
            || lowered.contains("https://")
            || lowered.contains("www.")
    }
}

enum TopicHeal {
    /// `activeTopicId` that no longer exists (deleted by another member)
    /// must reset to General (nil) — never ghost-filter the river.
    static func healed(_ activeTopicId: String?, topics: [WireTopic]) -> String? {
        guard let activeTopicId else { return nil }
        return topics.contains(where: { $0.id == activeTopicId }) ? activeTopicId : nil
    }
}

// ── poll card ─────────────────────────────────────────────

struct PollCard: View {
    let poll: WirePoll
    let mine: Bool
    let viewerId: String?
    let onVote: ((String) -> Void)?
    let onClose: (() -> Void)?

    private var closed: Bool { poll.closed == true }
    /// Spec §1 row 2 — the pick comes from votedBy ONLY; myOptionId is
    /// actor-relative on relayed rows and is never rendered.
    private var picked: String? { poll.pickFor(viewerId) }
    private var options: [WirePollOption] { poll.options ?? [] }
    private var totalVotes: Int {
        if let total = poll.totalVotes { return total }
        return options.reduce(0) { $0 + ($1.voteCount ?? 0) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: closed ? "checkmark.seal.fill" : "chart.bar.fill")
                    .font(.caption)
                Text(closed ? "Poll · Final results" : "Live poll")
                    .font(.caption.weight(.semibold))
                Spacer(minLength: 0)
            }
            .foregroundStyle(mine ? Color.white.opacity(0.9) : PulseTheme.emerald)

            Text(poll.question)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(mine ? Color.white : .primary)
                .fixedSize(horizontal: false, vertical: true)

            ForEach(options, id: \.id) { option in
                optionRow(option)
            }

            HStack(spacing: 8) {
                Text(footerText)
                    .font(.caption2)
                    .foregroundStyle(mine ? Color.white.opacity(0.75) : .secondary)
                Spacer(minLength: 0)
                if mine && !closed, let onClose {
                    Button {
                        onClose()
                    } label: {
                        Text("End poll")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(mine ? Color.white : PulseTheme.amber)
                            .underline()
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("End poll and freeze results")
                }
            }
        }
    }

    private var footerText: String {
        if closed { return "Voting closed" }
        if totalVotes == 0 { return "No votes yet" }
        return "\(totalVotes) vote\(totalVotes == 1 ? "" : "s") · tap an option to vote"
    }

    private func optionRow(_ option: WirePollOption) -> some View {
        let count = option.voteCount ?? 0
        let percent = totalVotes > 0 ? Double(count) / Double(totalVotes) : 0
        let isPicked = picked == option.id
        // Spec §1 row 3 — no revote once picked; closed polls are frozen.
        let canVote = !closed && picked == nil && viewerId != nil && onVote != nil
        let fillOpacity: Double = isPicked ? 0.4 : 0.18
        return Button {
            if canVote { onVote?(option.id) }
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(option.text)
                        .font(.subheadline)
                        .foregroundStyle(mine ? Color.white : .primary)
                        .lineLimit(2)
                    Spacer(minLength: 4)
                    if isPicked {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.caption)
                            .foregroundStyle(mine ? Color.white : PulseTheme.emerald)
                    }
                    Text("\(Int((percent * 100).rounded()))%")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(mine ? Color.white.opacity(0.85) : .secondary)
                }
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(mine ? Color.white.opacity(0.14) : Color.primary.opacity(0.06))
                        Capsule()
                            .fill(mine ? Color.white.opacity(fillOpacity) : PulseTheme.emerald.opacity(fillOpacity))
                            .frame(width: proxy.size.width * percent)
                    }
                }
                .frame(height: 5)
                Text("\(count) vote\(count == 1 ? "" : "s")")
                    .font(.caption2)
                    .foregroundStyle(mine ? Color.white.opacity(0.65) : .secondary)
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(mine ? Color.white.opacity(isPicked ? 0.14 : 0.08) : Color.primary.opacity(isPicked ? 0.05 : 0.03)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .strokeBorder(
                        isPicked ? (mine ? Color.white.opacity(0.6) : PulseTheme.emerald.opacity(0.5)) : Color.clear,
                        lineWidth: 1,
                    )
            )
        }
        .buttonStyle(PulseButtonStyle())
        .disabled(!canVote)
        .animation(.pulse(.pulseSoft, reduceMotion: false), value: totalVotes)
        .accessibilityHint(canVote ? "Vote for this option" : isPicked ? "Your vote" : "Voting unavailable")
    }
}

// ── poll builder ──────────────────────────────────────────

/// Attach-menu "New Poll" sheet — question ≤140 chars, 2–6 option rows with
/// add/remove; Post enabled once the question is non-empty AND 2 options are
/// non-blank (server-gated contract, spec §0).
struct PollBuilderSheet: View {
    @ObservedObject var viewModel: RoomViewModel
    @ObservedObject var session: PulseSession

    @Environment(\.dismiss) private var dismiss
    @State private var question = ""
    @State private var options: [String] = ["", ""]

    static let questionLimit = 140
    static let minOptions = 2
    static let maxOptions = 6

    private var trimmedOptions: [String] {
        options.map { $0.trimmingCharacters(in: .whitespaces) }
    }

    private var canPost: Bool {
        !question.trimmingCharacters(in: .whitespaces).isEmpty
            && trimmedOptions.filter { !$0.isEmpty }.count >= Self.minOptions
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Ask something…", text: $question, axis: .vertical)
                        .lineLimit(1...3)
                        .onChange(of: question) { _, value in
                            if value.count > Self.questionLimit {
                                question = String(value.prefix(Self.questionLimit))
                            }
                        }
                    Text("\(question.count)/\(Self.questionLimit)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("Question")
                }
                Section {
                    ForEach(options.indices, id: \.self) { index in
                        HStack(spacing: 8) {
                            Text("\(index + 1).")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            TextField("Option \(index + 1)", text: $options[index])
                            if options.count > Self.minOptions {
                                Button {
                                    options.remove(at: index)
                                } label: {
                                    Image(systemName: "minus.circle.fill")
                                        .foregroundStyle(.red)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Remove option")
                            }
                        }
                    }
                    if options.count < Self.maxOptions {
                        Button {
                            options.append("")
                        } label: {
                            Label("Add option", systemImage: "plus.circle")
                        }
                    }
                } header: {
                    Text("Options")
                } footer: {
                    Text("Single choice — voting closes when you end the poll.")
                }
            }
            .navigationTitle("New poll")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Post") {
                        viewModel.createPoll(
                            question: question,
                            options: trimmedOptions.filter { !$0.isEmpty },
                            session: session,
                        )
                        dismiss()
                    }
                    .disabled(!canPost)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// ── topic rail ────────────────────────────────────────────

/// Zulip-style topic chips (groups only) — "💬 General" (activeTopicId nil =
/// WHOLE room, spec §1 row 9), one chip per topic with a 99+-capped count
/// badge, and a dashed "+" that opens the inline create panel
/// (name ≤32 chars + emoji row).
struct TopicBar: View {
    let topics: [WireTopic]
    let activeTopicId: String?
    let onSelect: (String?) -> Void
    let onCreate: (String, String) -> Void

    @State private var creating = false
    @State private var draftName = ""
    @State private var draftEmoji = "💬"

    static let emojiChoices = ["💬", "🎨", "🚀", "🧠", "🎉", "🛠️", "📌", "☕"]
    static let nameLimit = 32

    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip(active: activeTopicId == nil) {
                        HStack(spacing: 5) {
                            Text("💬").font(.caption)
                            Text("General").font(.caption.weight(.semibold))
                        }
                    } action: {
                        onSelect(nil)
                    }
                    ForEach(topics) { topic in
                        chip(active: activeTopicId == topic.id) {
                            HStack(spacing: 5) {
                                Text(topic.emoji ?? "💬").font(.caption)
                                Text(topic.name)
                                    .font(.caption.weight(.semibold))
                                    .lineLimit(1)
                                if let count = topic.messageCount, count > 0 {
                                    Text(count > 99 ? "99+" : "\(count)")
                                        .font(.system(size: 9, weight: .bold))
                                        .foregroundStyle(activeTopicId == topic.id ? Color.white.opacity(0.9) : PulseTheme.textTertiary)
                                }
                            }
                        } action: {
                            onSelect(topic.id)
                        }
                    }
                    Button {
                        withAnimation(.pulse(.pulseSnappy, reduceMotion: false)) { creating.toggle() }
                    } label: {
                        Image(systemName: "plus")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(PulseTheme.emerald)
                            .padding(6)
                            .background(
                                Capsule().strokeBorder(PulseTheme.emerald.opacity(0.7), style: StrokeStyle(lineWidth: 1.2, dash: [3, 2]))
                            )
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("New topic")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
            }
            if creating {
                createPanel
            }
            Divider()
        }
        .background(.thinMaterial)
    }

    private func chip<Content: View>(active: Bool, @ViewBuilder content: () -> Content, action: @escaping () -> Void) -> some View {
        Button {
            action()
        } label: {
            content()
                .foregroundStyle(active ? Color.white : PulseTheme.textSecondary)
                .padding(.horizontal, 11)
                .padding(.vertical, 6)
                .background(Capsule().fill(active ? AnyShapeStyle(PulseTheme.emerald) : AnyShapeStyle(PulseTheme.chipFill)))
        }
        .buttonStyle(PulseButtonStyle())
    }

    private var createPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                TextField("Topic name", text: $draftName)
                    .textFieldStyle(.roundedBorder)
                    .font(.subheadline)
                    .onChange(of: draftName) { _, value in
                        if value.count > Self.nameLimit {
                            draftName = String(value.prefix(Self.nameLimit))
                        }
                    }
                Button {
                    let name = draftName.trimmingCharacters(in: .whitespaces)
                    guard !name.isEmpty else { return }
                    onCreate(name, draftEmoji)
                    draftName = ""
                    creating = false
                } label: {
                    Text("Create")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(PulseTheme.emerald)
                }
                .buttonStyle(PulseButtonStyle())
                .disabled(draftName.trimmingCharacters(in: .whitespaces).isEmpty)
                Button {
                    withAnimation(.pulse(.pulseSnappy, reduceMotion: false)) { creating = false }
                    draftName = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Cancel topic creation")
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Self.emojiChoices, id: \.self) { choice in
                        Button {
                            draftEmoji = choice
                        } label: {
                            Text(choice)
                                .font(.body)
                                .padding(5)
                                .background(
                                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                                        .fill(draftEmoji == choice ? PulseTheme.emerald.opacity(0.18) : Color.clear)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                                        .strokeBorder(draftEmoji == choice ? PulseTheme.emerald : Color.clear, lineWidth: 1.2)
                                )
                        }
                        .buttonStyle(PulseButtonStyle())
                        .accessibilityLabel("Emoji \(choice)")
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .transition(.move(edge: .top).combined(with: .opacity))
    }
}

// ── link preview card ─────────────────────────────────────

/// Open-Graph card under the bubble text (suppressed on poll rows). Until
/// the unfurl envelope lands the row stays plain text — loading/failure
/// render nothing here by design.
struct LinkPreviewCard: View {
    let preview: WireLinkPreview

    private var urlString: String? {
        guard let url = preview.url, !url.isEmpty else { return nil }
        return url
    }

    private var displayTitle: String {
        if let title = preview.title, !title.isEmpty { return title }
        return urlString ?? "Link"
    }

    private var host: String {
        URL(string: urlString ?? "")?.host ?? ""
    }

    private var footer: String {
        if let siteName = preview.siteName, !siteName.isEmpty { return siteName }
        return host.isEmpty ? "Link" : host
    }

    private var imageURL: URL? {
        PulseEndpoints.mediaURL(preview.imageUrl)
    }

    var body: some View {
        Button {
            if let urlString, let url = URL(string: urlString) {
                UIApplication.shared.open(url)
            }
        } label: {
            VStack(alignment: .leading, spacing: 5) {
                if let imageURL {
                    AsyncImage(url: imageURL) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        } else {
                            Rectangle().fill(.quaternary)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 120)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                Text(displayTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                if let description = preview.description, !description.isEmpty {
                    Text(description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                Label(footer, systemImage: "link")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(PulseTheme.emerald)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(9)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.primary.opacity(0.05)))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Color.primary.opacity(0.08), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
