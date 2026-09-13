import SwiftUI
import PhotosUI

// ─────────────────────────────────────────────────────────────
// Wave 6 — the channel directory (F-CH-01…03, channels-page.tsx parity):
// "Subscribed · N" + "Discover · N" over the REAL GET /api/channels feed,
// rows with the unread dot, subscriber counts, description || preview ||
// "No description yet", the OPTIMISTIC subscribe (+memberCount, rollback
// on error) and the honest leave flow (the server 403s the last admin —
// its verbatim copy is surfaced, never paraphrased). Create flow = name
// 2-40 / description ≤200 / optional photo through the real upload
// pipeline; the creator lands as admin with the server's first post.
// ─────────────────────────────────────────────────────────────

struct ChannelsView: View {
    @ObservedObject var session: PulseSession
    /// Open a subscribed channel's conversation (chat-list navigation).
    var onOpenConversation: (WireConversationSummary) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var channels: [WireChannelSummary] = []
    @State private var loading = true
    @State private var optimistic: [String: WireChannelSummary] = [:] // id → mutated copy
    @State private var busyId: String?
    @State private var mode: Mode = .directory

    // create form
    @State private var name = ""
    @State private var descriptionText = ""
    @State private var nameError: String?
    @State private var photoItem: PhotosPickerItem?
    @State private var photoPath: String?
    @State private var photoBusy = false

    private enum Mode { case directory, create }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        switch mode {
                        case .directory: directoryContent
                        case .create: createForm
                        }
                        Color.clear.frame(height: 16)
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                }
            }
            .navigationTitle(mode == .create ? "New channel" : "Channels")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if mode == .create {
                        Button("Back") {
                            PulseHaptics.tap()
                            withAnimation(.pulse(.pulseSoft, reduceMotion: false)) { mode = .directory }
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if mode == .create {
                        Button("Cancel") {
                            PulseHaptics.tap()
                            withAnimation(.pulse(.pulseSoft, reduceMotion: false)) { mode = .directory }
                        }
                    } else {
                        Button {
                            PulseHaptics.tap()
                            withAnimation(.pulse(.pulseSoft, reduceMotion: false)) { mode = .create }
                        } label: {
                            Image(systemName: "plus")
                        }
                        .accessibilityLabel("New channel")
                    }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task { await reload() }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            photoItem = nil
            Task { await uploadPhoto(item) }
        }
    }

    // ── directory ────────────────────────────────────────────

    private var resolved: [WireChannelSummary] {
        channels.map { optimistic[$0.id] ?? $0 }
    }

    private var subscribed: [WireChannelSummary] { resolved.filter { $0.isSubscribed ?? false } }
    private var discover: [WireChannelSummary] { resolved.filter { !($0.isSubscribed ?? false) } }

    @ViewBuilder
    private var directoryContent: some View {
        Text("Broadcast spaces — only admins post")
            .font(.system(size: 12))
            .foregroundStyle(PulseTheme.textSecondary)
            .padding(.bottom, 2)

        if loading && channels.isEmpty {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(PulseTheme.glassFill)
                .frame(height: 120)
                .frame(maxWidth: .infinity)
        } else if resolved.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.system(size: 26, weight: .light))
                    .foregroundStyle(PulseTheme.textTertiary)
                Text("No channels yet")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Text("Start one from the + button — pick a name, drop a description, and your broadcast space is live.")
                    .font(.system(size: 12))
                    .foregroundStyle(PulseTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 280)
            }
            .padding(.vertical, 26)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial))
        } else {
            if !subscribed.isEmpty {
                sectionHeader("Subscribed", count: subscribed.count)
                ForEach(subscribed, id: \.id) { channel in
                    channelRow(channel)
                }
            }
            sectionHeader("Discover", count: discover.count)
            if discover.isEmpty {
                Text("You are in every channel on this Pulse — nice.")
                    .font(.system(size: 12))
                    .foregroundStyle(PulseTheme.textTertiary)
                    .padding(.vertical, 6)
            } else {
                ForEach(discover, id: \.id) { channel in
                    channelRow(channel)
                }
            }
        }
    }

    private func sectionHeader(_ title: String, count: Int) -> some View {
        Text(count > 0 ? "\(title) · \(count)" : title)
            .font(.system(size: 11, weight: .semibold))
            .textCase(.uppercase)
            .foregroundStyle(PulseTheme.textSecondary)
            .padding(.top, 6)
    }

    private func channelRow(_ channel: WireChannelSummary) -> some View {
        let isSubscribed = channel.isSubscribed ?? false
        let busy = busyId == channel.id
        let blurb = channel.description?.trimmingCharacters(in: .whitespaces).isEmpty == false
            ? channel.description
            : (channel.preview ?? "No description yet")
        return HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                if let photo = channel.photo, !photo.isEmpty,
                   let url = PulseTheme.photoURL(photo) {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Rectangle().fill(PulseTheme.chipFill)
                    }
                    .frame(width: 46, height: 46)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                } else {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(PulseTheme.groupGradient(for: channel.id))
                        Image(systemName: "dot.radiowaves.left.and.right")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                    .frame(width: 46, height: 46)
                }
                if channel.unread == true {
                    Circle()
                        .fill(PulseTheme.emerald500)
                        .frame(width: 10, height: 10)
                        .overlay(Circle().strokeBorder(PulseTheme.badgeRing, lineWidth: 2))
                        .offset(x: 3, y: 3)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(channel.name ?? "Channel")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                    .lineLimit(1)
                Text(blurb ?? "No description yet")
                    .font(.system(size: 12))
                    .foregroundStyle(PulseTheme.textSecondary)
                    .lineLimit(1)
                Text(subscriberCount(channel))
                    .font(.system(size: 11))
                    .foregroundStyle(PulseTheme.textTertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if isSubscribed {
                Button {
                    PulseHaptics.tap()
                    open(channel)
                } label: {
                    Text("Open")
                        .font(.system(size: 12, weight: .bold))
                        .frame(width: 64).frame(minHeight: 32)
                        .background(Capsule().fill(PulseTheme.glassFill))
                        .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
                        .foregroundStyle(PulseTheme.accent)
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Open \(channel.name ?? "channel")")
            } else {
                Button {
                    Task { await subscribe(channel) }
                } label: {
                    if busy {
                        ProgressView().controlSize(.small)
                    } else {
                        Text("Subscribe")
                            .font(.system(size: 12, weight: .bold))
                    }
                }
                .buttonStyle(.bordered)
                .tint(PulseTheme.accent)
                .disabled(busy)
                .accessibilityLabel("Subscribe to \(channel.name ?? "channel")")
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.ultraThinMaterial))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
        .contextMenu {
            if isSubscribed {
                Button {
                    open(channel)
                } label: {
                    Label("Open", systemImage: "arrow.right.circle")
                }
                Button(role: .destructive) {
                    Task { await leave(channel) }
                } label: {
                    Label("Leave channel", systemImage: "person.badge.minus")
                }
            } else {
                Button {
                    Task { await subscribe(channel) }
                } label: {
                    Label("Subscribe", systemImage: "dot.radiowaves.left.and.right")
                }
            }
        }
    }

    private func subscriberCount(_ channel: WireChannelSummary) -> String {
        let count = channel.memberCount ?? 0
        return count == 1 ? "1 subscriber" : "\(count) subscribers"
    }

    // ── create mode ──────────────────────────────────────────

    private var createForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                photoTile
                VStack(alignment: .leading, spacing: 4) {
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        if photoBusy {
                            ProgressView()
                        } else {
                            Text(photoPath == nil ? "Add photo" : "Change photo")
                                .font(.system(size: 12.5, weight: .semibold))
                                .foregroundStyle(PulseTheme.accent)
                        }
                    }
                    Text("Square works best — cropped to 512.")
                        .font(.system(size: 10.5))
                        .foregroundStyle(PulseTheme.textTertiary)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                TextField("Channel name", text: $name)
                    .textFieldStyle(.plain)
                    .font(.system(size: 15, weight: .semibold))
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.rowFill))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(PulseTheme.hairlineSoft, lineWidth: 1))
                if let nameError {
                    Text(nameError)
                        .font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(PulseTheme.rose500)
                }
            }
            TextField("Description (optional)", text: $descriptionText, axis: .vertical)
                .lineLimit(2...4)
                .font(.system(size: 13.5))
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.rowFill))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(PulseTheme.hairlineSoft, lineWidth: 1))
            Text("\(descriptionText.count)/\(PulseChannelDraft.descriptionMax)")
                .font(.system(size: 10.5))
                .foregroundStyle(PulseTheme.textTertiary)
            Button {
                Task { await create() }
            } label: {
                Group {
                    if busyId == "creating" {
                        ProgressView().tint(.white)
                    } else {
                        Text("Create channel")
                            .font(.system(size: 14, weight: .bold))
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 46)
                .background(RoundedRectangle(cornerRadius: 14).fill(PulseTheme.brandGradient))
                .foregroundStyle(.white)
            }
            .buttonStyle(PulseButtonStyle())
            .disabled(busyId == "creating" || photoBusy)
            Text("You become the channel's admin — only admins post, everyone else reads along.")
                .font(.system(size: 11.5))
                .foregroundStyle(PulseTheme.textSecondary)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
    }

    private var photoTile: some View {
        ZStack {
            if let path = photoPath, let url = PulseTheme.photoURL(path) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Rectangle().fill(PulseTheme.chipFill)
                }
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(PulseTheme.groupGradient(for: "new-channel"))
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .frame(width: 56, height: 56)
            }
        }
    }

    // ── actions ──────────────────────────────────────────────

    private func reload() async {
        if let fresh = try? await session.api.channels(mineOnly: false) {
            channels = fresh
            optimistic = [:]
        }
        loading = false
    }

    /// Subscribe — OPTIMISTIC (isSubscribed flips, memberCount +1), settle
    /// confirms with the verbatim toasts, failure rolls the snapshot back.
    private func subscribe(_ channel: WireChannelSummary) async {
        guard busyId == nil else { return }
        busyId = channel.id
        let snapshot = optimistic[channel.id] ?? channel
        let optimisticCopy = snapshot.with(subscribed: true, memberCount: (snapshot.memberCount ?? 0) + 1)
        optimistic[channel.id] = optimisticCopy
        defer { busyId = nil }
        do {
            let result = try await session.api.subscribeChannel(channel.id)
            PulseHaptics.success()
            session.toasts.show(result.already == true ? "Already subscribed" : "Subscribed")
            if let count = result.memberCount {
                optimistic[channel.id] = optimisticCopy.with(memberCount: count)
            }
            await reload()
        } catch {
            optimistic[channel.id] = snapshot
            session.toasts.show("Could not subscribe — try again")
        }
    }

    /// Unsubscribe — the server owns the last-admin rule and answers 403
    /// with the verbatim succession copy; the toast carries it untouched.
    private func leave(_ channel: WireChannelSummary) async {
        guard busyId == nil else { return }
        busyId = channel.id
        defer { busyId = nil }
        do {
            _ = try await session.api.unsubscribeChannel(channel.id)
            PulseHaptics.success()
            session.toasts.show("You left \(channel.name ?? "the channel")")
            await reload()
        } catch let failure as PulseAPIClient.Failure {
            session.toasts.show(failure.message ?? "Could not leave the channel")
        } catch {
            session.toasts.show("Could not leave the channel")
        }
    }

    private func open(_ channel: WireChannelSummary) {
        guard let conversationId = channel.conversationId else {
            session.toasts.show("That channel isn't available right now")
            return
        }
        PulseHaptics.tap()
        dismiss()
        Task {
            if let conv = try? await session.api.conversationDetail(id: conversationId, userId: session.api.userId) {
                onOpenConversation(conv)
            } else {
                session.toasts.show("That channel isn't available right now")
            }
        }
    }

    private func create() async {
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        if let error = PulseChannelDraft.nameError(trimmedName) {
            nameError = error
            return
        }
        nameError = nil
        guard busyId == nil, !photoBusy else { return }
        busyId = "creating"
        defer { busyId = nil }
        do {
            let channel = try await session.api.createChannel(
                name: trimmedName,
                description: PulseChannelDraft.trimmedDescription(descriptionText),
                photoPath: photoPath,
            )
            PulseHaptics.success()
            session.toasts.show("Channel “\(channel.name ?? trimmedName)” created")
            name = ""
            descriptionText = ""
            photoPath = nil
            await reload()
            withAnimation(.pulse(.pulseSoft, reduceMotion: false)) { mode = .directory }
            if let conversationId = channel.conversationId,
               let conv = try? await session.api.conversationDetail(id: conversationId, userId: session.api.userId) {
                onOpenConversation(conv)
            }
        } catch {
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }

    /// Photo → square ≤512 JPEG q0.85 → /api/uploads (the shared Wave 6
    /// avatar pipeline, avatar-editor parity).
    private func uploadPhoto(_ item: PhotosPickerItem) async {
        guard let raw = try? await item.loadTransferable(type: Data.self),
              let jpeg = PulseAvatarImage.jpegData(from: raw) else {
            session.toasts.show("Couldn't read that image — try another one")
            return
        }
        photoBusy = true
        defer { photoBusy = false }
        do {
            photoPath = try await session.api.uploadMedia(dataUrl: PulseAvatarImage.dataUrl(jpeg))
        } catch {
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }
}

/// Memberwise re-clone — the wire struct keeps `let` storage (Codable+Sendable
/// house style), so the optimistic flip builds a copy instead of mutating.
private extension WireChannelSummary {
    func with(subscribed: Bool? = nil, memberCount: Int? = nil) -> WireChannelSummary {
        WireChannelSummary(
            id: id,
            conversationId: conversationId,
            name: name,
            description: description,
            createdAt: createdAt,
            memberCount: memberCount ?? self.memberCount,
            isSubscribed: subscribed ?? self.isSubscribed,
            unread: unread,
            preview: preview,
            photo: photo,
        )
    }
}
