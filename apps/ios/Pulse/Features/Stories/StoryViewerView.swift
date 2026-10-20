import SwiftUI

/// Full-screen story viewer (Wave 4) - a thin renderer over the pure
/// [StoryViewerMachine]: 5000ms stories, per-group progress bars,
/// left-32%-prev tap zones, ≥240ms hold-to-pause with a "Paused" pill,
/// drag-down dismiss (>110px or >550px/s), optimistic view marking with a
/// single retry (D6), owner viewers sheet polling every 5s (D7) and a delete
/// confirm strip. Vanish auto-advance (D3) is the machine's job.
struct StoryViewerView: View {
    @ObservedObject var session: PulseSession
    /// Shared, activity-scoped owner of the feed (publish/delete/seen land everywhere at once).
    @ObservedObject var stories: StoriesSessionModel
    let startUserId: String?
    let onClose: () -> Void

    @StateObject private var machine: StoryViewerMachine
    @Environment(\.scenePhase) private var scenePhase
    @State private var viewersStoryId: String?
    @State private var deleteStoryId: String?
    @State private var holdTask: Task<Void, Never>?

    init(session: PulseSession, stories: StoriesSessionModel, startUserId: String?, onClose: @escaping () -> Void) {
        self.session = session
        self.stories = stories
        self.startUserId = startUserId
        self.onClose = onClose
        _machine = StateObject(wrappedValue: StoryViewerMachine(startUserId: startUserId))
    }

    private var playbackBlocked: Bool { scenePhase != .active || viewersOpen || confirmDelete }
    private var playbackNow: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    @State private var viewersOpen = false
    @State private var confirmDelete = false
    /// Vertical drag offset (visual only; the machine owns dismissal truth).
    @State private var dragY: CGFloat = 0
    @State private var dragStart = Date.distantPast
    @State private var dragging = false
    @State private var holdStarted = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let current = machine.state.current {
                stage(for: current)
                    .offset(y: dragY)
                    .opacity(1 - Double(min(0.5, max(0, dragY / 600))))

                VStack(spacing: 0) {
                    header(for: current)
                        .padding(.horizontal, 10)
                        .padding(.top, 8)
                    Spacer()
                }
                .offset(y: dragY)

                if machine.state.paused && !dragging {
                    Text("Paused")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Capsule().fill(Color.black.opacity(0.55)))
                }
            } else {
                ProgressView().tint(.white.opacity(0.6))
            }
        }
        .gesture(viewerGesture)
        .task(id: playbackBlocked) {
            machine.setPlaybackBlocked(playbackBlocked, nowMs: playbackNow)
            guard !playbackBlocked else { return }
            var lastExpiryCheck: Int64 = 0
            while !Task.isCancelled {
                let now = playbackNow
                if now - lastExpiryCheck >= 1000 {
                    if stories.flags.loadedOnce || !stories.groups.isEmpty {
                        machine.groupsUpdated(stories.groups, nowMs: Int64(Date().timeIntervalSince1970 * 1000))
                    }
                    lastExpiryCheck = now
                }
                machine.tick(nowMs: now)
                do { try await Task.sleep(nanoseconds: 33_000_000) } catch { break }
            }
        }
        .onChange(of: machine.state.current?.id) { _, id in
            if deleteStoryId != id { confirmDelete = false; deleteStoryId = nil }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active {
                holdTask?.cancel()
                holdStarted = false
                dragging = false
                dragStart = .distantPast
                dragY = 0
                machine.holdEnd(nowMs: playbackNow)
            }
            machine.setPlaybackBlocked(playbackBlocked, nowMs: playbackNow)
        }
        .onChange(of: machine.state.pendingMark) { _, mark in
            guard let mark else { return }
            Task { await markViewed(mark) }
        }
        .onChange(of: machine.state.dismissed) { _, dismissed in
            if dismissed { onClose() }
        }
        .onChange(of: stories.groups) { _, groups in
            machine.groupsUpdated(groups, nowMs: Int64(Date().timeIntervalSince1970 * 1000))
        }
        .task {
            // First feed lands through the same door as refreshes.
            if stories.groups.isEmpty { await stories.refresh(quiet: false) }
            machine.groupsUpdated(stories.groups, nowMs: Int64(Date().timeIntervalSince1970 * 1000))
            stories.startPolling()
        }
        .onDisappear {
            holdTask?.cancel()
            machine.setPlaybackBlocked(true, nowMs: playbackNow)
            stories.stopPolling()
        }
        .sheet(isPresented: $viewersOpen, onDismiss: { stories.resetViewers() }) {
            viewersSheet
        }
    }

    // stage

    @ViewBuilder
    private func stage(for current: StoryViewerMachine.FlatStory) -> some View {
        if current.isImage, let path = current.story.imagePath {
            ZStack(alignment: .bottom) {
                AsyncImage(url: PulseEndpoints.mediaURL(path)) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFit()
                    } else {
                        ZStack {
                            Color.black
                            if phase.error != nil {
                                Text("Couldn't load this photo")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.6))
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel(caption.isEmpty ? "Story photo" : caption)

                if !caption.isEmpty {
                    Text(caption)
                        .font(.system(size: 15, weight: .regular))
                        .lineSpacing(5)
                        .foregroundStyle(.white)
                        .lineLimit(3)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Color.black.opacity(0.55)))
                        .padding(.bottom, 42)
                        .padding(.horizontal, 24)
                }
            }
        } else {
            ZStack {
                StoryPalette.gradient(for: current.story.background)
                    .ignoresSafeArea()
                ScrollView(showsIndicators: false) {
                    Text(caption)
                        .font(.system(size: 24, weight: .bold))
                        .lineSpacing(8)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 28)
                        .padding(.vertical, 90)
                    Spacer(minLength: 0)
                }
            }
        }
    }

    private var caption: String { machine.state.current?.story.caption ?? "" }

    // header

    private func header(for current: StoryViewerMachine.FlatStory) -> some View {
        VStack(spacing: 10) {
            bars(for: current)
            HStack(spacing: 10) {
                RowAvatar(name: current.userName, colorName: current.userColor, size: 34)
                VStack(alignment: .leading, spacing: 1) {
                    Text(current.mine ? "My status" : current.userName)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    let stamp = storyRelativeTime(
                        storyEpochMs(current.story.createdAt),
                        now: Int64(Date().timeIntervalSince1970 * 1000),
                    )
                    if !stamp.isEmpty {
                        Text(stamp)
                            .font(.system(size: 12, weight: .regular))
                            .foregroundStyle(.white.opacity(0.72))
                    }
                }
                Spacer()
                if confirmDelete {
                    Text("Delete this status?")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.white)
                    Button {
                        confirmDelete = false
                        guard let id = deleteStoryId else { return }
                        deleteStoryId = nil
                        Task { _ = await stories.deleteStory(id: id) }
                    } label: {
                        Image(systemName: "trash.fill")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(StoryPalette.pair(for: "rose").1)
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(Color.white.opacity(0.10)))
                    }
                    .accessibilityLabel("Confirm delete")
                    Button {
                        confirmDelete = false
                    } label: {
                        Text("Keep")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                } else {
                    if current.mine {
                        Button {
                            PulseHaptics.tap()
                            confirmDelete = false
                            viewersStoryId = current.id
                            stories.resetViewers()
                            viewersOpen = true
                        } label: {
                            ZStack(alignment: .topTrailing) {
                                Image(systemName: "eye")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .frame(width: 34, height: 34)
                                    .background(Circle().fill(Color.white.opacity(0.10)))
                                let count = current.story.viewCount ?? 0
                                if count > 0 {
                                    Text("\(count)")
                                        .font(.system(size: 10, weight: .bold))
                                        .foregroundStyle(.white)
                                        .padding(.horizontal, 4)
                                        .background(Capsule().fill(PulseTheme.emberChrome))
                                        .offset(x: 8, y: -4)
                                }
                            }
                        }
                        .accessibilityLabel("Viewers, \(current.story.viewCount ?? 0)")
                        Button {
                            PulseHaptics.tap()
                            deleteStoryId = current.id
                            confirmDelete = true
                        } label: {
                            Image(systemName: "trash")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 34, height: 34)
                                .background(Circle().fill(Color.white.opacity(0.10)))
                        }
                        .accessibilityLabel("Delete status")
                    }
                    Button {
                        onClose()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(Color.white.opacity(0.10)))
                    }
                    .accessibilityLabel("Close stories")
                }
            }
        }
    }

    /// Progress bars - CURRENT GROUP only (done / animated / future).
    private func bars(for current: StoryViewerMachine.FlatStory) -> some View {
        let groupStories = machine.state.flat.filter { $0.groupIndex == current.groupIndex }
        return HStack(spacing: 4) {
            ForEach(groupStories) { item in
                let fraction: CGFloat = item.storyIndexInGroup < current.storyIndexInGroup
                    ? 1
                    : (item.id == current.id ? CGFloat(machine.state.progress) : 0)
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.32))
                        Capsule().fill(Color.white)
                            .frame(width: max(0, min(geo.size.width, geo.size.width * fraction)))
                    }
                }
                .frame(height: 3)
            }
        }
    }

    // gestures

    /// Tap zones (left 32% = prev, else next) + hold ≥240ms pause + vertical
    /// drag-down dismiss. A drag release is never a tap; a hold pause RESUMES
    /// from elapsed (the machine preserves it).
    private var viewerGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { value in
                // First frame of the press = hold-detection clock start.
                guard !playbackBlocked else { return }
                if dragStart == .distantPast {
                    dragStart = Date()
                    holdTask?.cancel()
                    holdTask = Task { @MainActor in
                        do { try await Task.sleep(nanoseconds: 240_000_000) } catch { return }
                        guard !Task.isCancelled, !playbackBlocked else { return }
                        holdStarted = true
                        machine.holdStart(nowMs: playbackNow)
                    }
                }
                let heldMs = Int64(Date().timeIntervalSince(dragStart) * 1000)
                let t = value.translation

                if !dragging {
                    // Mostly-vertical pull beyond a small dead-zone = drag.
                    if t.height > 12, abs(t.height) > abs(t.width) * 1.4 {
                        dragging = true
                        if !holdStarted {
                            holdStarted = true
                            machine.holdStart(nowMs: playbackNow)
                        }
                    } else if heldMs >= StoryViewerMachine.holdThresholdMs {
                        // press-and-hold ≥240ms = pause (web hold timer).
                        if !holdStarted {
                            holdStarted = true
                            machine.holdStart(nowMs: playbackNow)
                        }
                    }
                }
                if dragging {
                    dragY = max(0, t.height)
                }
            }
            .onEnded { value in
                holdTask?.cancel()
                guard dragStart != .distantPast, !playbackBlocked else {
                    machine.holdEnd(nowMs: playbackNow)
                    dragStart = .distantPast
                    return
                }
                let heldMs = Int64(Date().timeIntervalSince(dragStart) * 1000)
                defer {
                    dragging = false
                    holdStarted = false
                    dragStart = .distantPast
                    dragY = 0
                }
                if dragging {
                    machine.holdEnd(nowMs: playbackNow)
                    let seconds = max(0.001, Double(heldMs) / 1000.0)
                    let velocity = CGFloat(Double(value.translation.height) / seconds)
                    if dragY > StoryViewerMachine.dismissDistancePx || velocity > StoryViewerMachine.dismissVelocityPxPerS {
                        machine.dragDismiss()
                    }
                    return // a drag release is never a zone tap
                }
                machine.holdEnd(nowMs: playbackNow)
                if heldMs >= StoryViewerMachine.holdThresholdMs {
                    return // pure pause - release resumes, never a zone tap
                }
                if value.startLocation.x < UIScreen.main.bounds.width * 0.32 {
                    machine.tapPrev()
                } else {
                    machine.tapNext()
                }
            }
    }

    // view marking (D6)

    private func markViewed(_ mark: StoryViewerMachine.PendingMark) async {
        // Optimistic local flip already applied by the feed model; one retry
        // max - the second failure gives up and the next fetch reconciles.
        do {
            let count = try await session.api.markStoryViewed(id: mark.storyId)
            stories.markViewedLocally(storyId: mark.storyId, viewCount: count)
            machine.viewMarkOk(storyId: mark.storyId, viewCount: count)
        } catch {
            stories.markViewedLocally(storyId: mark.storyId, viewCount: nil)
            machine.viewMarkFailed(storyId: mark.storyId)
        }
    }

    // viewers sheet (D7)

    private var viewersSheet: some View {
        NavigationStack {
            ZStack {
                // PULSE EMBER (EMB-I): sunset ground under the viewers sheet.
                Rectangle().fill(PulseTheme.emberBackdrop).ignoresSafeArea()
                Group {
                    if stories.viewers.isEmpty {
                        VStack(spacing: 8) {
                            if stories.viewersLoading {
                                ProgressView()
                            } else {
                                Text("No views yet")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                ForEach(stories.viewers, id: \.userId) { viewer in
                                    HStack(spacing: 12) {
                                        RowAvatar(name: viewer.name ?? "Pulse user", colorName: viewer.color, size: 34)
                                        VStack(alignment: .leading, spacing: 1) {
                                            Text(viewer.name ?? "Pulse user")
                                                .font(.system(size: 14, weight: .medium))
                                                .foregroundStyle(PulseTheme.titleOnWash)
                                            if let username = viewer.username, !username.isEmpty {
                                                Text("@\(username)")
                                                    .font(.system(size: 12))
                                                    .foregroundStyle(.secondary)
                                            }
                                        }
                                        Spacer()
                                        Text(storyRelativeTime(
                                            storyEpochMs(viewer.viewedAt),
                                            now: Int64(Date().timeIntervalSince1970 * 1000),
                                        ))
                                        .font(.system(size: 12))
                                        .foregroundStyle(.secondary)
                                    }
                                    .padding(.horizontal, 20)
                                    .padding(.vertical, 8)
                                }
                            }
                        }
                    }
                }
            }
            // PULSE EMBER (EMB-I): the sheet subtree pins dark on the ground.
            .environment(\.colorScheme, .dark)
            .tint(.white)
            .navigationTitle("Viewers · \(stories.viewers.count)")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task(id: viewersOpen) {
            // D7: poll every ≤5s while the sheet is open - the count badge
            // refreshes from each tick.
            while viewersOpen && !Task.isCancelled {
                if let id = viewersStoryId {
                    await stories.loadViewers(storyId: id)
                }
                do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { break }
            }
        }
    }
}
