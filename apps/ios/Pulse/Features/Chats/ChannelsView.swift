import SwiftUI
import PhotosUI

// Wave 6 - the channel directory (F-CH-01…03, channels-page.tsx parity):
// "Subscribed · N" + "Discover · N" over the REAL GET /api/channels feed,
// rows with the unread dot, subscriber counts, description || preview ||
// "No description yet", the OPTIMISTIC subscribe (+memberCount, rollback
// on error) and the honest leave flow (the server 403s the last admin -
// its verbatim copy is surfaced, never paraphrased). Create flow = name
// 2-40 / description ≤200 / optional photo through the real upload
// pipeline; the creator lands as admin with the server's first post.

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
    @State private var mounted = false
    @State private var ownerEpoch: UUID?
    @State private var initialLoad: Task<Void, Never>?
    @State private var loadError: String?
    @State private var loadTicket = UUID()
    @State private var actionTicket = UUID()
    @State private var actionTask: Task<Void, Never>?
    @State private var photoTicket = UUID()
    @State private var photoTask: Task<Void, Never>?

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
                            leaveCreateMode()
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if mode == .create {
                        Button("Cancel") {
                            PulseHaptics.tap()
                            leaveCreateMode()
                        }
                    } else {
                        Button {
                            PulseHaptics.tap()
                            withAnimation(.pulse(.pulseSoft, reduceMotion: PulseMotion.reduceMotion)) { mode = .create }
                        } label: {
                            Image(systemName: "plus")
                        }
                        .accessibilityLabel("New channel")
                        .disabled(actionTask != nil)
                    }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .onAppear {
            mounted = true
            if ownerEpoch != session.sessionGeneration {
                channels = []; optimistic = [:]; name = ""; descriptionText = ""; photoPath = nil; mode = .directory
            }
            ownerEpoch = session.sessionGeneration
            initialLoad = Task { await reload() }
        }
        .onDisappear { mounted = false; cancelWork() }
        .onChange(of: session.sessionGeneration) { _, _ in mounted = false; cancelWork(); dismiss() }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            photoItem = nil
            guard mode == .create, busyId != "creating" else { return }
            photoTask?.cancel()
            let ticket = UUID()
            photoTicket = ticket
            photoBusy = true
            photoTask = Task { await uploadPhoto(item, ticket: ticket) }
        }
    }

    // directory

    private var resolved: [WireChannelSummary] {
        channels.map { optimistic[$0.id] ?? $0 }
    }

    // R1-W2G - the directory partition lives in PulseChannelDirectory
    // (Core/Support), pinned by PulseStoryChannelTests.
    private var subscribed: [WireChannelSummary] { PulseChannelDirectory.subscribed(resolved) }
    private var discover: [WireChannelSummary] { PulseChannelDirectory.discover(resolved) }

    @ViewBuilder
    private var directoryContent: some View {
        Text("Broadcast spaces - only admins post")
            .font(.system(size: 12))
            .foregroundStyle(PulseTheme.textSecondary)
            .padding(.bottom, 2)

        if let loadError {
            Text(loadError).font(.footnote).foregroundStyle(PulseTheme.textSecondary)
            Button("Retry loading channels") { beginAction { await reload() } }
                .disabled(actionTask != nil)
        }
        if loading && channels.isEmpty {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(PulseTheme.glassFill)
                .frame(height: 120)
                .frame(maxWidth: .infinity)
        } else if resolved.isEmpty && loadError == nil {
            VStack(spacing: 8) {
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.system(size: 26, weight: .light))
                    .foregroundStyle(PulseTheme.textTertiary)
                Text("No channels yet")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Text("Start one from the + button - pick a name, drop a description, and your broadcast space is live.")
                    .font(.system(size: 12))
                    .foregroundStyle(PulseTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 280)
            }
            .padding(.vertical, 26)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial))
        } else if !resolved.isEmpty {
            if !subscribed.isEmpty {
                sectionHeader("Subscribed", count: subscribed.count)
                ForEach(subscribed, id: \.id) { channel in
                    channelRow(channel)
                }
            }
            sectionHeader("Discover", count: discover.count)
            if discover.isEmpty {
                Text("You are in every channel on this Pulse - nice.")
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
        // R1-W2G - blurb fallback chain pinned in PulseChannelDirectory.
        let blurb = PulseChannelDirectory.blurb(channel)
        return HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                if let photo = channel.photo, !photo.isEmpty,
                   let url = PulseTheme.photoURL(photo) {
                    // R45 re-audit: directory photos stay HEADERLESS on
                    // purpose. The Discover section renders channels the
                    // viewer has NOT joined (the web directory serves the
                    // same photos to anonymous sessions), so an
                    // authenticated fetch would 403 non-member tiles. Joined
                    // channels re-prove on every other authenticated surface.
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
                Text(blurb)
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
                    beginAction { await subscribe(channel) }
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
                    beginAction { await leave(channel) }
                } label: {
                    Label("Leave channel", systemImage: "person.badge.minus")
                }
            } else {
                Button {
                    beginAction { await subscribe(channel) }
                } label: {
                    Label("Subscribe", systemImage: "dot.radiowaves.left.and.right")
                }
            }
        }
        .disabled(actionTask != nil)
    }

    // R1-W2G - copy pinned in PulseChannelDirectory.subscriberCount.
    private func subscriberCount(_ channel: WireChannelSummary) -> String {
        PulseChannelDirectory.subscriberCount(channel.memberCount)
    }

    // create mode

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
                    Text("Square works best - cropped to 512.")
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
                beginAction { await create() }
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
            .disabled(actionTask != nil || photoBusy)
            Text("You become the channel's admin - only admins post, everyone else reads along.")
                .font(.system(size: 11.5))
                .foregroundStyle(PulseTheme.textSecondary)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
        .disabled(busyId == "creating")
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

    // actions

    private func owns(_ epoch: UUID) -> Bool {
        mounted && ownerEpoch == epoch && !Task.isCancelled && session.sessionGeneration == epoch
    }

    private func beginAction(_ action: @escaping @MainActor () async -> Void) {
        let epoch = session.sessionGeneration
        guard owns(epoch), actionTask == nil else { return }
        let ticket = UUID()
        actionTicket = ticket
        loadTicket = UUID() // an older list response cannot undo this action
        loading = false
        actionTask = Task { @MainActor in
            defer {
                if actionTicket == ticket { actionTask = nil; busyId = nil }
            }
            guard owns(epoch) else { return }
            await action()
        }
    }

    private func cancelWork() {
        actionTicket = UUID(); loadTicket = UUID(); photoTicket = UUID()
        initialLoad?.cancel(); initialLoad = nil
        actionTask?.cancel(); actionTask = nil
        photoTask?.cancel(); photoTask = nil
        busyId = nil; photoBusy = false
    }

    private func leaveCreateMode() {
        let uncertain = busyId == "creating"
        cancelWork()
        withAnimation(.pulse(.pulseSoft, reduceMotion: PulseMotion.reduceMotion)) { mode = .directory }
        if uncertain { session.toasts.show("Creation may still finish. Check Channels before creating again.") }
        beginAction { await reload() }
    }

    private func reload() async {
        guard owns(session.sessionGeneration) else { return }
        let api = session.api, epoch = session.sessionGeneration, ticket = UUID()
        loadTicket = ticket
        loading = true
        loadError = nil
        defer { if owns(epoch), loadTicket == ticket { loading = false } }
        do {
            let fresh = try await api.channels(mineOnly: false)
            guard owns(epoch), loadTicket == ticket else { return }
            channels = fresh
            optimistic = [:]
        } catch {
            guard owns(epoch), loadTicket == ticket else { return }
            loadError = "Couldn't refresh channels. Any displayed channels are the last loaded list."
        }
    }

    private func subscribe(_ channel: WireChannelSummary) async {
        guard busyId == nil, owns(session.sessionGeneration) else { return }
        let api = session.api, epoch = session.sessionGeneration
        busyId = channel.id
        let snapshot = optimistic[channel.id] ?? channel
        let optimisticCopy = snapshot.with(subscribed: true, memberCount: (snapshot.memberCount ?? 0) + 1)
        optimistic[channel.id] = optimisticCopy
        do {
            let result = try await api.subscribeChannel(channel.id)
            guard owns(epoch) else { return }
            optimistic[channel.id] = optimisticCopy.with(memberCount: result.memberCount)
            PulseHaptics.success()
            session.toasts.show(result.already == true ? "Already subscribed" : "Subscribed")
            session.noteInboxChanged()
            await reload()
        } catch {
            guard owns(epoch) else { return }
            optimistic[channel.id] = snapshot
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }

    private func leave(_ channel: WireChannelSummary) async {
        guard busyId == nil, owns(session.sessionGeneration) else { return }
        let api = session.api, epoch = session.sessionGeneration
        busyId = channel.id
        do {
            let result = try await api.unsubscribeChannel(channel.id)
            guard owns(epoch) else { return }
            guard result.ok == true else { throw PulseAPIClient.Failure(kind: .validation, message: "The channel did not confirm leaving.") }
            optimistic[channel.id] = channel.with(subscribed: false, memberCount: result.memberCount)
            PulseHaptics.success()
            session.toasts.show("You left \(channel.name ?? "the channel")")
            session.noteInboxChanged()
            await reload()
        } catch {
            guard owns(epoch) else { return }
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }

    private func open(_ channel: WireChannelSummary) {
        guard let conversationId = PulseChannelDirectory.roomId(channel) else {
            session.toasts.show("That channel isn't available right now")
            return
        }
        beginAction {
            session.noteRoomNavigationIntent()
            let api = session.api, epoch = session.sessionGeneration, navigation = session.roomNavigationGeneration
            busyId = channel.id
            do {
                let conv = try await PulseConversationNavigation.conversation(id: conversationId, cached: []) {
                    try await api.conversationDetail(id: conversationId, userId: api.userId)
                }
                guard owns(epoch), session.roomNavigationGeneration == navigation else { return }
                onOpenConversation(conv)
                dismiss()
            } catch {
                guard owns(epoch), session.roomNavigationGeneration == navigation else { return }
                session.toasts.show("Couldn't open this channel. The directory is still here so you can retry.")
            }
        }
    }

    private func create() async {
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        if let error = PulseChannelDraft.nameError(trimmedName) { nameError = error; return }
        guard busyId == nil, !photoBusy, owns(session.sessionGeneration) else { return }
        nameError = nil
        session.noteRoomNavigationIntent()
        let api = session.api, epoch = session.sessionGeneration, navigation = session.roomNavigationGeneration
        let description = PulseChannelDraft.trimmedDescription(descriptionText), photo = photoPath
        busyId = "creating"
        var acknowledged = false
        defer { if session.sessionGeneration == epoch { session.noteInboxChanged() } }
        do {
            let channel = try await api.createChannel(name: trimmedName, description: description, photoPath: photo)
            guard owns(epoch) else { return }
            guard let conversationId = PulseChannelDirectory.roomId(channel) else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Channel response didn't identify its conversation.")
            }
            guard let room = channel.creationConversation, let store = session.store else {
                throw PulseAPIClient.Failure(kind: .server, message: "The channel couldn't be saved locally. Retry the same details.")
            }
            try store.upsert(conversations: [room.toDomain()])
            try await api.acknowledgeChannelCreation(name: trimmedName, description: description, photoPath: photo, receipt: channel)
            guard owns(epoch) else { return }
            acknowledged = true
            channels.removeAll { $0.id == channel.id }
            channels.insert(channel, at: 0)
            optimistic.removeValue(forKey: channel.id)
            name = ""; descriptionText = ""; photoPath = nil
            photoTicket = UUID(); photoTask?.cancel(); photoTask = nil; photoBusy = false
            withAnimation(.pulse(.pulseSoft, reduceMotion: PulseMotion.reduceMotion)) { mode = .directory }
            PulseHaptics.success()
            session.toasts.show("Channel “\(channel.name ?? trimmedName)” created")
            guard session.roomNavigationGeneration == navigation else { return }
            let conv = try await PulseConversationNavigation.conversation(id: conversationId, cached: [room]) {
                try await api.conversationDetail(id: conversationId, userId: api.userId)
            }
            guard owns(epoch), session.roomNavigationGeneration == navigation else { return }
            onOpenConversation(conv)
            dismiss()
        } catch {
            guard owns(epoch), session.roomNavigationGeneration == navigation else { return }
            session.toasts.show(acknowledged ? "Channel created, but opening failed. Use Open in the directory; don't create it again."
                : "Couldn't confirm channel creation. Retry the same details to recover the same operation.")
        }
    }

    private func uploadPhoto(_ item: PhotosPickerItem, ticket: UUID) async {
        let api = session.api, epoch = session.sessionGeneration
        defer { if photoTicket == ticket { photoBusy = false; photoTask = nil } }
        guard owns(epoch), photoTicket == ticket, mode == .create else { return }
        do {
            let photo = try await item.loadTransferable(type: PulsePickedPhoto.self)
            guard owns(epoch), photoTicket == ticket, mode == .create else { return }
            guard let photo, let jpeg = PulseAvatarImage.jpegData(from: photo.jpeg) else {
                session.toasts.show("Couldn't read that image - try another one")
                return
            }
            let path = try await api.uploadMedia(dataUrl: PulseAvatarImage.dataUrl(jpeg))
            guard owns(epoch), photoTicket == ticket, mode == .create else { return }
            photoPath = path
        } catch {
            guard owns(epoch), photoTicket == ticket else { return }
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }

}

/// Memberwise re-clone - the wire struct keeps `let` storage (Codable+Sendable
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
