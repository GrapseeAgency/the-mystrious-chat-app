import Combine
import SwiftUI
import UIKit

// Wave 7 - the LIVE hub (F-HB-01…10). Replaces the static tile
// grid: real wallet + ledger, daily check-in (+25 PC base, +2/day
// streak bonus capped +20, UTC-gated server-side), @handle
// transfers, PC⇄GEM swap (100/80 rates + real stats), personal
// tasks, market with atomic buy, logs stream, the 100-app matrix
// from the bundled catalog JSON (browsable offline), install/
// connect, app communities (auto-provisioned real conversations)
// and My apps (fan-out per-app install state - web hub-data
// parity). Server error copy surfaces verbatim.

enum HubCatalogStore {
    static func load() -> HubCatalog {
        guard let url = Bundle.main.url(forResource: "hub_catalog", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let catalog = try? JSONDecoder().decode(HubCatalog.self, from: data) else {
            return HubCatalog(apps: [], categories: [], taglines: [:])
        }
        return catalog
    }
}

@MainActor
final class HubViewModel: ObservableObject {
    struct WalletUi {
        var loading = false
        var page: WireWalletPage?
        var stale = false
        var error: String?
    }

    @Published var wallet = WalletUi()
    @Published private(set) var walletMutationBusy = false
    private var walletRequest = UUID()
    private var marketRequest = UUID()
    private var logsRequest = UUID()
    private var appsRequest = UUID()
    @Published var tasks: [WireHubTask] = []
    @Published var listings: [WireMarketListing] = []
    @Published var logs: [WireHubLog] = []
    @Published var swap: WireSwapPage?
    @Published var installs: [String: WireAppInstallState] = [:]
    // R5-A Item 3 - install-state honesty: the fan-out marks which app ids
    // failed to load (the detail sheet shows a Retry state instead of
    // pretending "0 connected").
    @Published var installStateFailed: Set<String> = []
    // R5-A Item 3 - per-app community state (memberCount/joined/roster) for
    // the detail sheet's Community tab (GET /api/hub/apps/{id}/community).
    @Published var communities: [String: WireAppCommunity] = [:]
    @Published var communityFailed: Set<String> = []
    @Published var toast: String?
    @Published var toastIsError = false

    private let session: PulseSession

    init(session: PulseSession) {
        self.session = session
    }

    /// R5-A Item 3 - the viewer id for the connectors roster "(you)" marks.
    var viewerId: String? { session.viewer?.id }

    private var api: PulseAPIClient? { session.api }

    func toast(_ text: String, isError: Bool = false) {
        self.toast = text
        toastIsError = isError
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            if self.toast == text { self.toast = nil }
        }
    }

    private func describe(_ error: Error) -> String {
        (error as? PulseAPIClient.Failure)?.message ?? error.localizedDescription
    }

    // MARK: wallet + checkin

    func loadWallet() {
        let ticket = UUID()
        walletRequest = ticket
        let epoch = session.sessionGeneration
        let capturedAPI = api
        Task { @MainActor in
            guard walletRequest == ticket, session.sessionGeneration == epoch else { return }
            wallet.loading = true
            wallet.error = nil
            guard let api = capturedAPI else {
                wallet.loading = false
                wallet.error = "No gateway configured - set your server in Profile → Connection."
                return
            }
            do {
                let page = try await api.walletPage()
                guard walletRequest == ticket, session.sessionGeneration == epoch else { return }
                wallet = WalletUi(loading: false, page: page, stale: false, error: nil)
            } catch {
                guard walletRequest == ticket, session.sessionGeneration == epoch else { return }
                wallet.stale = wallet.page != nil
                wallet.loading = false
                if wallet.page == nil { wallet.error = describe(error) }
            }
        }
    }

    func checkin() {
        guard let api else { return }
        performWalletMutation(operation: {
            let result = try await api.checkinWallet()
            return "Checked in - +\(result.reward ?? 0) PC · \(result.streak ?? 0)-day streak"
        }, completion: { result in
            switch result {
            case .success(let message):
                self.session.particles.fire(kind: .confetti, count: 60)
                PulseHaptics.success()
                self.toast(message)
            case .failure(let error): self.toast(self.describe(error), isError: true)
            }
            self.loadWallet()
        })
    }

    // MARK: transfer

    /// Gate is taken before creating the task, not after its first suspension.
    @discardableResult
    func performWalletMutation(operation: @escaping () async throws -> String,
                               completion: @escaping (Result<String, Error>) -> Void) -> Task<Void, Never>? {
        guard !walletMutationBusy else { return nil }
        walletMutationBusy = true
        let epoch = session.sessionGeneration
        return Task { @MainActor in
            defer { walletMutationBusy = false }
            guard session.sessionGeneration == epoch else { return }
            do {
                try Task.checkCancellation()
                let message = try await operation()
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                completion(.success(message))
            } catch {
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                completion(.failure(error))
            }
        }
    }

    func transfer(handle: String, amount: Int, note: String?, completion: @escaping (Bool) -> Void) {
        guard let api else { completion(false); return }
        performWalletMutation(operation: {
            let result = try await api.transferCoins(toUsername: handle, amount: amount, note: note)
            return "Sent \(amount) PC to @\(result.to?.username ?? result.to?.name ?? "?")"
        }, completion: { result in
            switch result {
            case .success(let message): self.toast(message); self.loadWallet(); completion(true)
            case .failure(let error): self.toast(self.describe(error), isError: true); completion(false)
            }
        })
    }

    // MARK: swap

    func loadSwap() {
        Task { @MainActor in
            guard let api = api else { return }
            swap = try? await api.swapRates()
        }
    }

    func swap(direction: String, amount: Int, completion: @escaping (Bool) -> Void) {
        guard let api else { completion(false); return }
        performWalletMutation(operation: {
            let result = try await api.swap(direction: direction, amount: amount)
            return result.note ?? "Swap complete"
        }, completion: { result in
            switch result {
            case .success(let message): self.toast(message); self.loadWallet(); completion(true)
            case .failure(let error): self.toast(self.describe(error), isError: true); completion(false)
            }
        })
    }

    // MARK: tasks

    @Published private(set) var taskBusy = false
    private var taskRevision = 0

    func loadTasks() {
        guard !taskBusy, let owner = session.viewer?.id, let client = api else { return }
        taskRevision += 1
        let ticket = taskRevision, epoch = session.sessionGeneration, store = session.store
        let key = "hub:tasks:v2:\(owner)"
        Task { @MainActor in
            guard session.sessionGeneration == epoch, taskRevision == ticket, !taskBusy else { return }
            if tasks.isEmpty, let blob = try? store?.loadWave7Cache(key: key), let data = blob.json.data(using: .utf8),
               let cached = try? JSONDecoder().decode(WireHubTasksPage.self, from: data), cached.userId == owner,
               cached.tasks.allSatisfy({ $0.ownerId == owner }) { tasks = cached.tasks }
            do {
                let page = try await client.hubTasks()
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch, taskRevision == ticket, !taskBusy else { return }
                tasks = page.tasks
                if let data = try? JSONEncoder().encode(page), let json = String(data: data, encoding: .utf8) {
                    try? store?.saveWave7Cache(key: key, json: json, updatedAt: Int64(Date().timeIntervalSince1970 * 1000))
                }
            } catch is CancellationError { }
            catch {
                if session.sessionGeneration == epoch, taskRevision == ticket { toast(describe(error), isError: true) }
            }
        }
    }

    private func taskAction(_ operation: @escaping (PulseAPIClient) async throws -> Void, completed: @escaping (Bool) -> Void = { _ in }) {
        guard !taskBusy, session.ready, let owner = session.viewer?.id, let store = session.store, let client = api else { completed(false); return }
        let epoch = session.sessionGeneration
        taskBusy = true; taskRevision += 1
        Task { @MainActor in
            var accepted = false
            do {
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch else { return }
                try store.deleteWave7Cache(key: "hub:tasks:v2:\(owner)")
                try await operation(client)
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch else { return }
                accepted = true
            } catch is CancellationError { }
            catch { if session.sessionGeneration == epoch { toast(describe(error), isError: true) } }
            guard session.sessionGeneration == epoch else { return }
            taskBusy = false; taskRevision += 1
            completed(accepted)
            loadTasks()
        }
    }

    func createTask(_ title: String, completed: @escaping (Bool) -> Void) {
        taskAction({ api in _ = try await api.createHubTask(title: title, status: "todo") }, completed: { accepted in
            if accepted { self.toast("Task added") }
            completed(accepted)
        })
    }
    func moveTask(_ task: WireHubTask, to status: String) {
        taskAction({ api in _ = try await api.updateHubTask(task.id, title: nil, status: status) })
    }
    func deleteTask(_ task: WireHubTask) {
        taskAction({ api in try await api.deleteHubTask(task.id) })
    }

    // MARK: market

    @Published private(set) var marketBusy = false
    @Published private(set) var marketLoaded = false
    @Published private(set) var marketLoading = false
    @Published private(set) var marketError: String?
    @Published private(set) var marketActionError: String?
    private var marketOwner: String?

    func loadMarket() {
        if marketOwner != viewerId {
            listings = []; marketLoaded = false; marketOwner = viewerId
            marketError = "Account changed. Refresh the market."; marketActionError = nil; marketRequest = UUID()
        }
        guard !marketBusy else { return }
        guard let client = api else { marketError = "No gateway configured."; return }
        let ticket = UUID()
        marketRequest = ticket
        marketLoading = true; marketError = nil
        let epoch = session.sessionGeneration
        Task { @MainActor in
            guard marketRequest == ticket, session.sessionGeneration == epoch else { return }
            defer { if marketRequest == ticket, session.sessionGeneration == epoch { marketLoading = false } }
            do {
                let fresh = try await client.market().listings
                guard !Task.isCancelled, marketRequest == ticket, session.sessionGeneration == epoch else { return }
                listings = fresh; marketLoaded = true
            } catch {
                guard !Task.isCancelled, marketRequest == ticket, session.sessionGeneration == epoch else { return }
                marketError = describe(error)
            }
        }
    }

    private func marketAction<T: Sendable>(spendsMoney: Bool = false, operation: @escaping (PulseAPIClient) async throws -> T,
                                 accepted: @escaping (T) -> Void, completion: @escaping (Bool) -> Void = { _ in }) {
        guard !marketBusy, !walletMutationBusy, let client = api else { completion(false); return }
        let epoch = session.sessionGeneration
        marketBusy = true; marketRequest = UUID(); marketLoading = false; marketActionError = nil
        if spendsMoney { walletMutationBusy = true; walletRequest = UUID() }
        Task { @MainActor in
            var confirmed = false
            do {
                defer { marketBusy = false; marketRequest = UUID(); if spendsMoney { walletMutationBusy = false } }
                guard session.sessionGeneration == epoch else { return }
                try Task.checkCancellation()
                let value = try await operation(client)
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                accepted(value); confirmed = true
            } catch {
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                marketActionError = describe(error)
                toast(describe(error), isError: true)
            }
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            completion(confirmed); loadMarket(); if spendsMoney { loadWallet() }
        }
    }

    func createListing(_ title: String, price: Int, completion: @escaping (Bool) -> Void) {
        marketAction(operation: { client in
            try await client.createListing(title: title, description: nil, price: price)
        }, accepted: { row in
            self.listings = [row] + self.listings.filter { $0.id != row.id }; self.marketLoaded = true
            self.toast("Listed \"\(row.title ?? title)\" for \(price) PC")
        }, completion: completion)
    }

    func buy(_ listing: WireMarketListing) {
        marketAction(spendsMoney: true, operation: { client in try await client.buyListing(listing.id) }, accepted: { _ in
            self.listings.removeAll { $0.id == listing.id }
            self.toast("Bought \"\(listing.title ?? "")\"")
        })
    }

    // MARK: logs

    func loadLogs() {
        let ticket = UUID()
        logsRequest = ticket
        let epoch = session.sessionGeneration, client = api
        let owner = session.viewer?.id
        logs = owner.map { id in logs.filter { $0.user?.id == id } } ?? []
        Task { @MainActor in
            guard logsRequest == ticket, session.sessionGeneration == epoch, let api = client else { return }
            do {
                let fresh = try await api.hubLogs(limit: 80, kind: nil).logs
                guard !Task.isCancelled, logsRequest == ticket, session.sessionGeneration == epoch else { return }
                logs = fresh
            } catch {
                guard !Task.isCancelled, logsRequest == ticket, session.sessionGeneration == epoch else { return }
                toast(describe(error), isError: true) // keep the last real list
            }
        }
    }

    // MARK: apps

    @Published private(set) var appBusy: Set<String> = []
    @Published private(set) var appErrors: [String: String] = [:]
    private var appTickets: [String: UUID] = [:]
    private var appsOwner: UUID?
    private func prepareApps() {
        if appsOwner != session.sessionGeneration {
            installs = [:]; communities = [:]; installStateFailed = []; communityFailed = []
            appErrors = [:]; appTickets = [:]; appsOwner = session.sessionGeneration
        }
    }
    private func fetchInstall(_ appId: String, client: PulseAPIClient, epoch: UUID) async {
        let key = "install:" + appId
        guard session.sessionGeneration == epoch, !appBusy.contains(key) else { return }
        let ticket = UUID(); appTickets[key] = ticket
        do {
            let row = try await client.appInstallState(appId: appId)
            guard !Task.isCancelled, session.sessionGeneration == epoch, appTickets[key] == ticket else { return }
            installs[appId] = row; installStateFailed.remove(appId)
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch, appTickets[key] == ticket else { return }
            installStateFailed.insert(appId)
        }
    }
    func loadInstallState(appId: String) {
        prepareApps()
        guard let client = api else { installStateFailed.insert(appId); return }
        let epoch = session.sessionGeneration
        Task { @MainActor in await fetchInstall(appId, client: client, epoch: epoch) }
    }
    func loadAppCommunity(appId: String) {
        prepareApps()
        let key = "community:" + appId
        guard !appBusy.contains(key), let client = api else { return }
        let epoch = session.sessionGeneration, ticket = UUID(); appTickets[key] = ticket
        Task { @MainActor in
            guard session.sessionGeneration == epoch, appTickets[key] == ticket else { return }
            do {
                let row = try await client.appCommunity(appId: appId)
                guard !Task.isCancelled, session.sessionGeneration == epoch, appTickets[key] == ticket else { return }
                communities[appId] = row; communityFailed.remove(appId)
            } catch {
                guard !Task.isCancelled, session.sessionGeneration == epoch, appTickets[key] == ticket else { return }
                communityFailed.insert(appId)
            }
        }
    }
    private func appAction<T: Sendable>(_ key: String, operation: @escaping (PulseAPIClient) async throws -> T,
                                        accepted: @escaping (T) -> Void, refresh: @escaping () -> Void) {
        prepareApps()
        guard !appBusy.contains(key), let client = api else { return }
        let epoch = session.sessionGeneration
        appBusy.insert(key); appTickets[key] = UUID(); appErrors[key] = nil
        Task { @MainActor in
            do {
                defer { appBusy.remove(key) }
                guard session.sessionGeneration == epoch else { return }
                try Task.checkCancellation()
                let row = try await operation(client)
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                accepted(row)
            } catch {
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                appErrors[key] = describe(error)
            }
            if !Task.isCancelled, session.sessionGeneration == epoch { refresh() }
        }
    }
    func toggleInstall(_ app: HubCatalogApp) {
        prepareApps()
        let id = String(app.n)
        guard let state = installs[id], !installStateFailed.contains(id) else { loadInstallState(appId: id); return }
        let disconnect = state.installed == true
        appAction("install:" + id, operation: { client in
            if disconnect { return try await client.uninstallApp(appId: id) }
            return try await client.installApp(appId: id)
        }, accepted: { row in
            self.installs[id] = WireAppInstallState(appVersion: row.appVersion, appId: row.appId, userId: row.userId,
                installed: row.installed, status: row.status, installedAt: row.installedAt, installs: row.installs, installers: row.installers)
            self.toast(disconnect ? "Disconnected from \(app.name)" : "Connected to \(app.name)")
        }, refresh: { self.loadInstallState(appId: id) })
    }
    func joinCommunity(_ app: HubCatalogApp, completion: @escaping (WireConversationSummary) -> Void) {
        let id = String(app.n)
        appAction("community:" + id, operation: { client in try await client.joinAppCommunity(appId: id) }, accepted: { row in
            guard row.joined == true, let conversation = row.conversation else { return }
            self.communities[id] = row; self.communityFailed.remove(id)
            completion(conversation)
        }, refresh: { self.loadAppCommunity(appId: id) })
    }
    func loadMyApps(_ catalog: HubCatalog) {
        prepareApps()
        guard let client = api else { return }
        let ticket = UUID(), epoch = session.sessionGeneration; appsRequest = ticket
        Task { @MainActor in
            for app in catalog.apps {
                guard !Task.isCancelled, appsRequest == ticket, session.sessionGeneration == epoch else { return }
                await fetchInstall(String(app.n), client: client, epoch: epoch)
            }
        }
    }

}

private enum HubSurface: String, Identifiable {
    case wallet, transfer, swap, tasks, market, logs, apps, myApps
    var id: String { rawValue }
}

struct HubView: View {
    @ObservedObject var session: PulseSession
    var onOpenRoom: ((WireConversationSummary) -> Void)? = nil

    @StateObject private var vm: HubViewModel
    @State private var catalog = HubCatalog(apps: [], categories: [], taglines: [:])
    @State private var surface: HubSurface?
    @State private var expandedApp: HubCatalogApp?
    // R1-W2G D40 - the 12 s logs ticker (house Timer.publish pattern, see
    // VoiceRoomSessionModel) + the scene gate that pauses it while the app
    // is inactive. The subscription only exists while the logs surface is
    // mounted, so nothing leaks after the sheet closes.
    @Environment(\.scenePhase) private var scenePhase
    @State private var logsTicker = Timer.publish(every: 12, on: .main, in: .common).autoconnect()

    init(session: PulseSession, onOpenRoom: ((WireConversationSummary) -> Void)? = nil) {
        self.session = session
        self.onOpenRoom = onOpenRoom
        _vm = StateObject(wrappedValue: HubViewModel(session: session))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Hub")
                        .font(.largeTitle.weight(.bold))
                    Text("XP · streaks · mini apps")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    walletHero
                    tiles

                    Color.clear.frame(height: 90) // dock clearance
                }
                .padding(.horizontal, 18)
            }
            .background(
                // PULSE EMBER (EMB-I): sunset ground, subtree pinned dark.
                Rectangle().fill(PulseTheme.emberBackdrop).ignoresSafeArea()
            )
            .environment(\.colorScheme, .dark)
            .tint(.white)
        }
        .overlay(alignment: .bottom) {
            if let toast = vm.toast {
                Text(toast)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background {
                        Capsule().fill(vm.toastIsError ? Color.red.opacity(0.92) : Color.black.opacity(0.85))
                    }
                    .padding(.bottom, 110)
                    .transition(.opacity)
            }
        }
        .onAppear {
            if catalog.apps.isEmpty { catalog = HubCatalogStore.load() }
            vm.loadWallet()
            vm.loadMyApps(catalog)
        }
        .sheet(item: $surface) { s in
            NavigationStack {
                surfaceBody(s)
            }
        }
        .sheet(item: Binding(
            get: { expandedApp.map { HubAppTarget(app: $0) } },
            set: { expandedApp = $0?.app },
        )) { target in
            HubAppDetailSheet(
                app: target.app,
                vm: vm,
                catalog: catalog,
                onOpenCommunity: { app in
                    vm.joinCommunity(app) { conversation in
                        expandedApp = nil
                        onOpenRoom?(conversation)
                    }
                },
            )
        }
    }

    // F-HB-01 + F-HB-02 - wallet hero
    private var walletHero: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Pulse Wallet")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                Spacer()
                if vm.wallet.stale {
                    Text("offline - cached").font(.caption2).foregroundStyle(.white.opacity(0.6))
                }
                if vm.wallet.loading {
                    ProgressView().tint(.white)
                }
            }
            HStack {
                hubStat("PC", vm.wallet.page?.wallet?.coins)
                hubStat("GEM", vm.wallet.page?.wallet?.gems)
                hubStat("Streak", vm.wallet.page?.wallet?.streak)
            }
            let checkedIn = vm.wallet.page?.wallet?.checkedInToday == true
            Button {
                vm.checkin()
            } label: {
                Text(checkedIn ? "Checked in today" : "Daily check-in · +25 PC")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(checkedIn || vm.wallet.loading || vm.walletMutationBusy)
            if let w = vm.wallet.page?.wallet, !checkedIn, let streak = w.streak, streak > 0 {
                Text("Next check-in continues your \(streak)-day streak (+\(min(streak * 2, 20)) bonus)")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.6))
            }
            if let error = vm.wallet.error {
                Text(error).font(.caption).foregroundStyle(.red.opacity(0.9))
            }
        }
        .padding(16)
        .background {
            let g = LinearGradient(
                colors: [Color(red: 0.07, green: 0.23, blue: 0.17), Color(red: 0.05, green: 0.17, blue: 0.13)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing,
            )
            RoundedRectangle(cornerRadius: 20).fill(g)
        }
    }

    private var tiles: some View {
        let all: [(String, String, HubSurface)] = [
            ("Wallet", "PC · GEM · ledger", .wallet),
            ("Transfer", "@handle → PC", .transfer),
            ("Swap", "PC ⇄ GEM", .swap),
            ("Tasks", "personal kanban", .tasks),
            ("Market", "buy & sell", .market),
            ("Logs", "activity stream", .logs),
            ("Mini apps", "100-app matrix", .apps),
            ("My apps", "installed", .myApps),
        ]
        return LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
            ForEach(all, id: \.0) { label, sub, s in
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.subheadline.weight(.semibold))
                    Text(sub).font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background {
                    RoundedRectangle(cornerRadius: 16).fill(Color(uiColor: .secondarySystemBackground))
                }
                .onTapGesture { surface = s }
            }
        }
    }

    @ViewBuilder
    private func surfaceBody(_ s: HubSurface) -> some View {
        switch s {
        case .wallet:
            HubLedgerList(ledger: vm.wallet.page?.ledger ?? [])
                .task { vm.loadWallet() }
        case .transfer:
            HubTransferSheet(vm: vm)
        case .swap:
            HubSwapSheet(vm: vm)
        case .tasks:
            HubTasksSheet(vm: vm)
        case .market:
            HubMarketSheet(vm: vm)
        case .logs:
            HubLogsList(logs: vm.logs)
                .task { vm.loadLogs() }
                // R1-W2G D40 - live logs: a 12 s repeating refresh while the
                // logs surface is open (web hub-tab.tsx:603-611
                // refetchInterval 12_000 parity). Scene-gated - no hub
                // fetches while the app is inactive; the onReceive
                // subscription dies with the surface.
                .onReceive(logsTicker) { _ in
                    guard scenePhase == .active else { return }
                    vm.loadLogs()
                }
        case .apps:
            HubAppsList(catalog: catalog, installs: vm.installs, myAppsOnly: false) { app in
                expandedApp = app
            }
        case .myApps:
            HubAppsList(catalog: catalog, installs: vm.installs, myAppsOnly: true) { app in
                expandedApp = app
            }
        }
    }
}

private struct HubAppTarget: Identifiable {
    let app: HubCatalogApp
    var id: Int { app.n }
}

@ViewBuilder
private func hubStat(_ label: String, _ value: Int?) -> some View {
    VStack(alignment: .leading, spacing: 2) {
        Text(value == nil ? "…" : "\(value!)")
            .font(.title2.weight(.bold))
            .foregroundStyle(.white)
        Text(label).font(.caption).foregroundStyle(.white.opacity(0.6))
    }
    .frame(maxWidth: .infinity, alignment: .leading)
}

// MARK: - Hub sub-sheets

private struct HubLedgerList: View {
    let ledger: [WireLedgerEntry]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            ForEach(Array((ledger).enumerated()), id: \.offset) { _, entry in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.note ?? entry.kind ?? "")
                            .font(.subheadline)
                            .lineLimit(1)
                        Text(String((entry.createdAt ?? "").prefix(10)))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text("\((entry.amount ?? 0) >= 0 ? "+" : "")\(entry.amount ?? 0) \(entry.asset ?? "PC")")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle((entry.amount ?? 0) >= 0 ? PulseTheme.emberGlowBottom : Color.red)
                }
            }
        }
        .navigationTitle("Ledger")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
    }
}

private struct HubTransferSheet: View {
    @ObservedObject var vm: HubViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var handle = ""
    @State private var amount = ""
    @State private var note = ""

    var body: some View {
        Form {
            Section {
                TextField("@handle", text: $handle)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Amount PC", text: $amount)
                    .keyboardType(.numberPad)
                TextField("Note (optional)", text: $note)
            } footer: {
                Text("Whole PC only - max 100,000 per transfer.")
            }
            Button("Send") {
                let amt = Int(amount) ?? 0
                guard !handle.trimmingCharacters(in: .whitespaces).isEmpty else { vm.toast("Recipient @handle is required.", isError: true); return }
                guard amt >= 1, amt <= 100_000 else { vm.toast("Amount must be a positive whole number (max 100,000 PC).", isError: true); return }
                let trimmed = note.trimmingCharacters(in: .whitespaces)
                vm.transfer(handle: handle, amount: amt, note: trimmed.isEmpty ? nil : trimmed) { ok in
                    if ok { dismiss() }
                }
            }
        }
        .disabled(vm.walletMutationBusy)
        .navigationTitle("Send PC")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct HubSwapSheet: View {
    @ObservedObject var vm: HubViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var amount = ""

    var body: some View {
        Form {
            Section("Rates") {
                if let rates = vm.swap?.rates {
                    Text("Buy 1 GEM = \(rates.pcPerGemBuy ?? 100) PC · Sell 1 GEM = \(rates.pcPerGemSell ?? 80) PC")
                } else {
                    Text("Loading rates…").foregroundStyle(.secondary)
                }
                if let stats = vm.swap?.stats {
                    Text("\(stats.swaps ?? 0) swaps · \(stats.circulatingCoins ?? 0) PC · \(stats.circulatingGems ?? 0) GEM circulating")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Section {
                TextField("Amount PC", text: $amount)
                    .keyboardType(.numberPad)
                Button("PC → GEM") {
                    let amt = Int(amount) ?? 0
                    if amt < 100 {
                        vm.toast("Minimum exchange is 100 PC for 1 GEM.", isError: true)
                    } else if amt % 100 != 0 {
                        vm.toast("Amount must be a multiple of 100 PC.", isError: true)
                    } else {
                        vm.swap(direction: "pc2gem", amount: amt) { ok in if ok { dismiss() } }
                    }
                }
                Button("GEM → PC") {
                    let amt = Int(amount) ?? 0
                    if amt < 1 {
                        vm.toast("Amount must be a positive whole number.", isError: true)
                    } else {
                        vm.swap(direction: "gem2pc", amount: amt) { ok in if ok { dismiss() } }
                    }
                }
            }
        }
        .disabled(vm.walletMutationBusy)
        .navigationTitle("PC ⇄ GEM")
        .navigationBarTitleDisplayMode(.inline)
        .task { vm.loadSwap() }
    }
}

private struct HubTasksSheet: View {
    @ObservedObject var vm: HubViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""

    var body: some View {
        List {
            Section("New") {
                TextField("Task (1–120)", text: $title)
                Button("Add task") {
                    let trimmed = title.trimmingCharacters(in: .whitespaces)
                    if !trimmed.isEmpty {
                        let submitted = title
                        vm.createTask(trimmed) { accepted in if accepted && title == submitted { title = "" } }
                    }
                }
            }
            ForEach(["doing", "todo", "done"], id: \.self) { col in
                Section(headerLabel(col) + " · \(vm.tasks.filter { $0.status == col }.count)") {
                    let colTasks = vm.tasks.filter { $0.status == col }
                    if colTasks.isEmpty {
                        Text("Nothing here.").foregroundStyle(.secondary)
                    }
                    ForEach(colTasks, id: \.id) { task in
                        HStack {
                            Text(task.title ?? "")
                                .font(.subheadline)
                                .lineLimit(1)
                            Spacer()
                            if col != "todo" {
                                Button { vm.moveTask(task, to: col == "doing" ? "todo" : "doing") } label: { Text("‹") }
                                    .buttonStyle(.borderless)
                            }
                            if col != "done" {
                                Button { vm.moveTask(task, to: col == "todo" ? "doing" : "done") } label: { Text("›") }
                                    .buttonStyle(.borderless)
                            }
                            Button { vm.deleteTask(task) } label: { Image(systemName: "xmark").foregroundStyle(.red) }
                                .buttonStyle(.borderless)
                        }
                    }
                }
            }
        }
        .disabled(vm.taskBusy)
        .navigationTitle("Tasks")
        .navigationBarTitleDisplayMode(.inline)
        .task { vm.loadTasks() }
    }

    private func headerLabel(_ col: String) -> String {
        switch col {
        case "todo": return "To do"
        case "doing": return "Doing"
        default: return "Done"
        }
    }
}

private struct HubMarketSheet: View {
    @ObservedObject var vm: HubViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var price = ""
    @State private var creating = false
    @State private var confirmBuy: String?
    @State private var validationError: String?

    var body: some View {
        List {
            Section {
                Button(creating ? "Close" : "+ List") { creating.toggle() }
                if creating {
                    if let error = validationError { Text(error).foregroundStyle(.red) }
                    TextField("Title (1–80)", text: $title)
                    TextField("Price PC", text: $price)
                        .keyboardType(.numberPad)
                    Button("Post listing") {
                        let t = title.trimmingCharacters(in: .whitespaces)
                        let p = Int(price) ?? 0
                        guard !t.isEmpty, t.utf16.count <= 80 else { validationError = "Title must be 1–80 characters."; return }
                        guard p >= 1, p <= 100_000 else { validationError = "Price must be a positive whole number (max 100,000 PC)."; return }
                        validationError = nil
                        let sentTitle = title, sentPrice = price
                        vm.createListing(t, price: p) { accepted in
                            if accepted, title == sentTitle, price == sentPrice { title = ""; price = ""; creating = false }
                        }
                    }
                }
            }
            Section("Listings") {
                if let error = vm.marketActionError { Text(error).foregroundStyle(.red) }
                if vm.marketLoading { Text("Refreshing market…").foregroundStyle(.secondary) }
                if let error = vm.marketError {
                    Text(error).foregroundStyle(.secondary)
                    Button("Retry") { vm.loadMarket() }.disabled(vm.marketLoading)
                }
                if vm.marketLoaded && vm.listings.isEmpty {
                    Text("Nothing on the market yet.").foregroundStyle(.secondary)
                }
                ForEach(vm.listings, id: \.id) { listing in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(listing.title ?? "")
                                .font(.subheadline)
                                .lineLimit(1)
                            Text("\(listing.price ?? 0) PC · \(listing.seller?.name ?? "")" + (listing.mine == true ? " · yours" : ""))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if listing.status == "sold" {
                            Text("SOLD").font(.caption).foregroundStyle(.secondary)
                        } else if listing.mine == true {
                            Text("Yours").font(.caption).foregroundStyle(PulseTheme.emberGlowBottom)
                        } else if confirmBuy == listing.id {
                            Button("No") { confirmBuy = nil }.buttonStyle(.borderless)
                            Button("Buy") { vm.buy(listing); confirmBuy = nil }
                                .disabled(vm.walletMutationBusy)
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                        } else {
                            Button("Buy") { confirmBuy = listing.id }
                                .disabled(vm.walletMutationBusy)
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                        }
                    }
                }
            }
        }
        .disabled(vm.marketBusy || vm.walletMutationBusy)
        .interactiveDismissDisabled(vm.marketBusy)
        .navigationTitle("Market")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: vm.viewerId) { vm.loadMarket() }
    }
}

private struct HubLogsList: View {
    let logs: [WireHubLog]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            if logs.isEmpty {
                Text("No activity yet.").foregroundStyle(.secondary)
            }
            ForEach(Array(logs.enumerated()), id: \.offset) { _, log in
                VStack(alignment: .leading, spacing: 2) {
                    Text(log.message ?? "")
                        .font(.subheadline)
                        .lineLimit(2)
                    Text("\(log.kind ?? "") · \(String((log.createdAt ?? "").prefix(16)).replacingOccurrences(of: "T", with: " "))")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Logs")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
    }
}

private struct HubAppsList: View {
    let catalog: HubCatalog
    let installs: [String: WireAppInstallState]
    let myAppsOnly: Bool
    let onExpand: (HubCatalogApp) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var category: String?

    private var visible: [HubCatalogApp] {
        catalog.apps
            .filter { !myAppsOnly || (installs[String($0.n)]?.installed == true) }
            .filter { category == nil || $0.category == category }
            .filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        List {
            if !myAppsOnly {
                Section {
                    TextField("Search apps", text: $query)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            chip("All", slug: nil)
                            ForEach(catalog.categories, id: \.slug) { c in
                                chip(c.label, slug: c.label)
                            }
                        }
                    }
                }
            }
            Section(myAppsOnly ? "Installed & connected apps" : "\(catalog.apps.count) apps · \(catalog.categories.count) categories - browsable offline") {
                if visible.isEmpty {
                    Text(myAppsOnly ? "No apps connected yet - open Mini apps to connect." : "No apps match.")
                        .foregroundStyle(.secondary)
                }
                ForEach(visible, id: \.n) { app in
                    HStack {
                        Text(String(app.name.prefix(1)))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(PulseTheme.emberGlowBottom, in: Circle())
                        VStack(alignment: .leading, spacing: 1) {
                            Text(app.name).font(.subheadline)
                            Text("\(installs[String(app.n)]?.installs ?? 0) connected")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if installs[String(app.n)]?.installed == true {
                            Text("connected")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(PulseTheme.emberGlowBottom)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { onExpand(app) }
                }
            }
        }
        .navigationTitle(myAppsOnly ? "My apps" : "Mini apps")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
    }

    private func chip(_ label: String, slug: String?) -> some View {
        Text(label)
            .font(.caption)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background {
                Capsule().fill(category == slug ? PulseTheme.emberGlowBottom.opacity(0.25) : Color(uiColor: .secondarySystemBackground))
            }
            .onTapGesture { category = slug }
    }
}

// R5-A Item 3 - app detail at web depth (web app-detail-sheet.tsx
// parity): Overview | Community | Connectors tab bar with count
// badges, the installer stack (≤6 most-recent real users + "+N"
// overflow, hidden when empty like the web InstallerStack), the
// related-apps rail (same-category apps from the bundled
// hub_catalog.json, tap swaps the sheet's detail in place), the
// CountUp-style installs figure and the web field labels. The wire
// DTO (WireAppInstallState) already decodes installed/status/
// installedAt/installs/installers - unknown keys stay tolerated.
private struct HubAppDetailSheet: View {
    @ObservedObject var vm: HubViewModel
    let catalog: HubCatalog
    let onOpenCommunity: (HubCatalogApp) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var currentApp: HubCatalogApp
    @State private var tab: DetailTab = .overview

    private enum DetailTab: String, CaseIterable {
        case overview, community, connectors
    }

    init(app: HubCatalogApp, vm: HubViewModel, catalog: HubCatalog, onOpenCommunity: @escaping (HubCatalogApp) -> Void) {
        _currentApp = State(initialValue: app)
        self.vm = vm
        self.catalog = catalog
        self.onOpenCommunity = onOpenCommunity
    }

    private var appId: String { String(currentApp.n) }
    private var tagline: String? { catalog.taglines[appId] }
    private var installState: WireAppInstallState? { vm.installs[appId] }
    private var installed: Bool { installState?.installed == true }
    private var installs: Int { installState?.installs ?? 0 }
    private var community: WireAppCommunity? { vm.communities[appId] }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                tabBar
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if let error = vm.appErrors["install:" + appId] ?? vm.appErrors["community:" + appId] {
                            Text(error).foregroundStyle(.red)
                        }
                        switch tab {
                        case .overview: overviewPanel
                        case .community: communityPanel
                        case .connectors: connectorsPanel
                        }
                    }
                    .padding(16)
                    .padding(.bottom, 28)
                }
            }
            .disabled(vm.appBusy.contains("install:" + appId) || vm.appBusy.contains("community:" + appId))
            .background(Color(.systemBackground))
            .navigationTitle(currentApp.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            // Load (and re-load on related-app swaps) the install state +
            // community state for the current app (web useAppInstallStatus /
            // useAppCommunity parity).
            .task(id: "\(vm.viewerId ?? ""):\(currentApp.n)") {
                vm.loadInstallState(appId: appId)
                vm.loadAppCommunity(appId: appId)
            }
        }
    }

    // tab bar (web DetailTabBar: label + count badge + underline)

    private var tabBar: some View {
        HStack(spacing: 0) {
            tabButton(.overview, label: "Overview", badge: 0)
            tabButton(.community, label: "Community", badge: max(0, community?.memberCount ?? 0))
            tabButton(.connectors, label: "Connectors", badge: max(0, installs))
        }
        .frame(height: 44)
        .overlay(alignment: .bottom) { Divider() }
    }

    private func tabButton(_ id: DetailTab, label: String, badge: Int) -> some View {
        Button {
            PulseHaptics.tap()
            tab = id
        } label: {
            HStack(spacing: 5) {
                Text(label)
                    .font(.system(size: 12.5, weight: .semibold))
                if badge > 0 {
                    Text("\(badge)")
                        .font(.system(size: 10, weight: .bold))
                        .monospacedDigit()
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(Capsule().fill(Color(uiColor: .secondarySystemBackground)))
                }
            }
            .foregroundStyle(tab == id ? PulseTheme.emberGlowBottom : Color.secondary)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) {
            if tab == id {
                Capsule()
                    .fill(PulseTheme.emberGlowBottom)
                    .frame(width: 44, height: 2.5)
                    .offset(y: 1)
            }
        }
        .accessibilityLabel("\(label) tab\(badge > 0 ? ", \(badge)" : "")")
        .accessibilityAddTraits(tab == id ? [.isSelected] : [])
    }

    // shared bits

    /// The app icon tile (first letter over emerald - existing hub style).
    private var iconTile: some View {
        Text(String(currentApp.name.prefix(1)))
            .font(.title3.weight(.bold))
            .foregroundStyle(.white)
            .frame(width: 48, height: 48)
            .background(PulseTheme.emberGlowBottom, in: Circle())
    }

    /// Web InstallerStack (:102-125) - up to 6 most-recent installer
    /// avatars + "+N" overflow chip; hidden entirely when the list is
    /// empty (web returns null for zero installers).
    @ViewBuilder
    private var installerStack: some View {
        let people = installState?.installers ?? []
        if !people.isEmpty {
            HStack(spacing: -8) {
                ForEach(Array(people.prefix(6).enumerated()), id: \.offset) { _, person in
                    PulseAvatar(
                        name: person.name ?? "?",
                        color: PulseTheme.color(named: person.color),
                        size: 26,
                    )
                    .overlay(Circle().strokeBorder(Color(.systemBackground), lineWidth: 2))
                }
                let extra = max(0, installs - min(people.count, 6))
                if extra > 0 {
                    Text("+\(extra)")
                        .font(.system(size: 9, weight: .bold))
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                        .frame(width: 26, height: 26)
                        .background(Circle().fill(Color(uiColor: .secondarySystemBackground)))
                        .overlay(Circle().strokeBorder(Color(.systemBackground), lineWidth: 2))
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Recently connected: \(people.prefix(6).compactMap(\.name).joined(separator: ", "))\(installs > min(people.count, 6) ? " and \(installs - min(people.count, 6)) more" : "")")
        }
    }

    /// Live install stats: CountUp-style figure + "member(s) connected"
    /// (web hero stats block), stack at the trailing edge, honest states.
    private var installStatsBlock: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 2) {
                if vm.installStateFailed.contains(appId) {
                    Text("Connection stats unavailable")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(PulseTheme.rose)
                } else if installState == nil {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.small)
                        Text("Loading connection stats")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Text("\(installs)")
                        .font(.system(size: 24, weight: .black, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(PulseTheme.emberGlowBottom)
                        .contentTransition(.numericText())
                        .animation(.default, value: installs)
                    Text("member\(installs == 1 ? "" : "s") connected")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            installerStack
        }
    }

    private var connectButton: some View {
        Button {
            vm.toggleInstall(currentApp)
        } label: {
            HStack(spacing: 6) {
                if installed {
                    Image(systemName: "checkmark")
                    Text("Connected")
                } else {
                    Image(systemName: "plus")
                    Text("Connect")
                }
            }
            .font(.system(size: 13.5, weight: .bold))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 11)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(installed ? PulseTheme.emberGlowBottom.opacity(0.12) : PulseTheme.emberGlowBottom),
            )
            .foregroundStyle(installed ? PulseTheme.emberGlowBottom : Color.white)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(installed ? "Disconnect from \(currentApp.name)" : "Connect to \(currentApp.name)")
        .accessibilityAddTraits(installed ? [.isSelected] : [])
    }

    private var communityButton: some View {
        Button {
            onOpenCommunity(currentApp)
        } label: {
            Text("Open community chat")
                .font(.system(size: 13.5, weight: .bold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(Color(uiColor: .secondarySystemBackground)),
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open the \(currentApp.name) community chat")
    }

    // Overview tab

    private var overviewPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 12) {
                    iconTile
                    VStack(alignment: .leading, spacing: 2) {
                        Text(currentApp.name).font(.headline)
                        if let tagline {
                            Text(tagline)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        HStack(spacing: 6) {
                            if let category = currentApp.category {
                                Text(category)
                                    .font(.system(size: 10, weight: .bold))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(Capsule().fill(Color(uiColor: .secondarySystemBackground)))
                            }
                            Text(String(format: "#%03d", currentApp.n))
                                .font(.system(size: 10, weight: .semibold))
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                installStatsBlock
                if installed, let stamp = installState?.installedAt {
                    // Web: "Connected on {formatDay}" (per-viewer truth).
                    Text("Connected on \(PulseFormat.hubDayStamp(stamp))")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(PulseTheme.emberGlowBottom)
                }
                HStack(spacing: 8) {
                    connectButton
                    communityButton
                }
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
            )

            // Real matrix fields (web OverviewPanel labels).
            if let nav = currentApp.nav { fieldCard(label: "Mobile nav style", value: nav) }
            if let input = currentApp.input { fieldCard(label: "Input toolkit", value: input) }
            if let secret = currentApp.secret {
                fieldCard(label: "Secret UI architecture feature", value: secret, accent: true)
            }

            relatedRail
        }
    }

    private func fieldCard(label: String, value: String, accent: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .textCase(.uppercase)
                .foregroundStyle(accent ? PulseTheme.emberGlowBottom : Color.secondary)
            Text(value)
                .font(.system(size: 13.5, weight: .medium))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
        )
    }

    // related-apps rail (same category, bundled catalog)

    @ViewBuilder
    private var relatedRail: some View {
        let related = catalog.apps
            .filter { $0.category == currentApp.category && $0.n != currentApp.n }
            .prefix(10)
        if !related.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                // Web header: "More in {category first ' /' segment}".
                Text("More in \(currentApp.category?.components(separatedBy: "/").first?.trimmingCharacters(in: .whitespaces) ?? "")")
                    .font(.system(size: 11, weight: .bold))
                    .textCase(.uppercase)
                    .tracking(0.8)
                    .foregroundStyle(.secondary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(related), id: \.n) { relatedApp in
                            Button {
                                PulseHaptics.tap()
                                // Web navigates to the app page - the sheet
                                // swaps its detail in place (same catalog,
                                // fresh state per id via .task(id:)).
                                currentApp = relatedApp
                                tab = .overview
                            } label: {
                                VStack(spacing: 5) {
                                    Text(String(relatedApp.name.prefix(1)))
                                        .font(.system(size: 17, weight: .bold))
                                        .foregroundStyle(.white)
                                        .frame(width: 40, height: 40)
                                        .background(PulseTheme.emberGlowBottom, in: Circle())
                                    Text(relatedApp.name)
                                        .font(.system(size: 11.5, weight: .bold))
                                        .foregroundStyle(PulseTheme.titleOnPanel)
                                        .lineLimit(1)
                                    Text(String(format: "#%03d", relatedApp.n))
                                        .font(.system(size: 9.5, weight: .semibold))
                                        .monospacedDigit()
                                        .foregroundStyle(.secondary)
                                }
                                .frame(width: 104)
                                .padding(.vertical, 12)
                                .background(
                                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                                        .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Open \(relatedApp.name) page")
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }

    // Community tab

    private var communityPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            if community == nil && vm.communityFailed.contains(appId) {
                honestRetryCard(message: "Could not load the community.") {
                    vm.loadAppCommunity(appId: appId)
                }
            } else if community == nil {
                loadingCard("Loading community")
            } else if let state = community, state.conversation == nil {
                // Web founder moment - nobody has provisioned the group yet.
                VStack(spacing: 8) {
                    Image(systemName: "person.3")
                        .font(.system(size: 20))
                        .foregroundStyle(.secondary)
                    Text("Be the first to start the community")
                        .font(.system(size: 13, weight: .bold))
                    Text("No members yet - the room gets created on first join. You'll be the founding admin.")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button {
                        onOpenCommunity(currentApp)
                    } label: {
                        Label("Found the community", systemImage: "sparkles")
                            .font(.system(size: 13, weight: .bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 11)
                            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(PulseTheme.emberGlowBottom))
                            .foregroundStyle(.white)
                    }
                    .buttonStyle(.plain)
                }
                .frame(maxWidth: .infinity)
                .padding(16)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
                )
            } else if let state = community, let conversation = state.conversation {
                let memberCount = state.memberCount ?? conversation.members.count
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 10) {
                        iconTile
                        VStack(alignment: .leading, spacing: 3) {
                            Text(conversation.name ?? currentApp.name)
                                .font(.system(size: 13.5, weight: .bold))
                                .lineLimit(1)
                            Text("\(memberCount) member\(memberCount == 1 ? "" : "s")")
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(state.joined == true ? "Member" : "Not joined")
                            .font(.system(size: 10, weight: .bold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(state.joined == true ? PulseTheme.emberGlowBottom.opacity(0.12) : Color(uiColor: .secondarySystemBackground)))
                            .foregroundStyle(state.joined == true ? PulseTheme.emberGlowBottom : Color.secondary)
                    }
                    communityButton
                }
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
                )

                // Real participant roster, ≤8 rows (web CommunityPanel).
                VStack(alignment: .leading, spacing: 0) {
                    Text("Members")
                        .font(.system(size: 11, weight: .bold))
                        .textCase(.uppercase)
                        .tracking(0.8)
                        .foregroundStyle(.secondary)
                        .padding(12)
                    Divider()
                    ForEach(Array(conversation.members.prefix(8).enumerated()), id: \.offset) { _, member in
                        HStack(spacing: 10) {
                            PulseAvatar(name: member.name, color: PulseTheme.color(named: member.color), size: 30)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(member.name + (member.id == vm.viewerId ? " (you)" : ""))
                                    .font(.system(size: 12.5, weight: .semibold))
                                    .lineLimit(1)
                                Text(member.username.map { "@\($0)" } ?? "Pulse member")
                                    .font(.system(size: 10.5))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer()
                            if member.role == "admin" {
                                Label("Admin", systemImage: "crown.fill")
                                    .font(.system(size: 9.5, weight: .bold))
                                    .foregroundStyle(PulseTheme.emberGlowBottom)
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        if member.id != conversation.members.prefix(8).last?.id {
                            Divider()
                        }
                    }
                }
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
                )
            }
        }
    }

    // Connectors tab

    private var connectorsPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Viewer's own connect-state card (web ConnectorsPanel).
            HStack(spacing: 10) {
                Text(String(currentApp.name.prefix(1)))
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(PulseTheme.emberGlowBottom, in: Circle())
                VStack(alignment: .leading, spacing: 1) {
                    Text("Your connection")
                        .font(.system(size: 13, weight: .bold))
                    Text(connectionStatusLine)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    vm.toggleInstall(currentApp)
                } label: {
                    Text(installed ? "Connected" : "Connect")
                        .font(.system(size: 12.5, weight: .bold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 9)
                        .background(
                            Capsule().fill(installed ? PulseTheme.emberGlowBottom.opacity(0.12) : PulseTheme.emberGlowBottom),
                        )
                        .foregroundStyle(installed ? PulseTheme.emberGlowBottom : Color.white)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(installed ? "Disconnect from \(currentApp.name)" : "Connect to \(currentApp.name)")
                .accessibilityAddTraits(installed ? [.isSelected] : [])
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
            )

            if vm.installStateFailed.contains(appId) {
                honestRetryCard(message: "Could not load connectors.") {
                    vm.loadInstallState(appId: appId)
                }
            } else if installState == nil {
                loadingCard("Loading connectors")
            } else if (installState?.installers ?? []).isEmpty {
                // Web empty state, verbatim copy.
                VStack(spacing: 6) {
                    Image(systemName: "cable.connector")
                        .font(.system(size: 18))
                        .foregroundStyle(.secondary)
                    Text("No connectors yet")
                        .font(.system(size: 13, weight: .bold))
                    Text("Be the first to connect \(currentApp.name).")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(16)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
                )
            } else {
                let people = installState?.installers ?? []
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Text("Connected members")
                            .font(.system(size: 11, weight: .bold))
                            .textCase(.uppercase)
                            .tracking(0.8)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text("\(installs)")
                            .font(.system(size: 12, weight: .black, design: .rounded))
                            .monospacedDigit()
                            .foregroundStyle(PulseTheme.emberGlowBottom)
                            .contentTransition(.numericText())
                            .animation(.default, value: installs)
                    }
                    .padding(12)
                    Divider()
                    ForEach(Array(people.enumerated()), id: \.offset) { index, person in
                        let isViewer = person.id != nil && person.id == vm.viewerId
                        HStack(spacing: 10) {
                            PulseAvatar(name: person.name ?? "?", color: PulseTheme.color(named: person.color), size: 30)
                            VStack(alignment: .leading, spacing: 1) {
                                Text((person.name ?? "?") + (isViewer ? " (you)" : ""))
                                    .font(.system(size: 12.5, weight: .semibold))
                                    .lineLimit(1)
                                Text(person.username.map { "@\($0)" } ?? "Pulse member")
                                    .font(.system(size: 10.5))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 1) {
                                Text("connected")
                                    .font(.system(size: 10))
                                    .foregroundStyle(.secondary)
                                // installedAt is per-viewer truth - others
                                // get no invented date (web rule verbatim).
                                if isViewer, installed, let stamp = installState?.installedAt {
                                    Text(PulseFormat.hubRelativeStamp(stamp))
                                        .font(.system(size: 9.5))
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        if index != people.count - 1 {
                            Divider()
                        }
                    }
                    // Web "+N more connected" footer for the ≤6 window.
                    let extra = max(0, installs - people.count)
                    if extra > 0 {
                        Divider()
                        Text("+\(extra) more connected")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                    }
                }
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
                )
            }
        }
    }

    private var connectionStatusLine: String {
        if installState == nil { return "Checking…" }
        if installed, let stamp = installState?.installedAt {
            return "Connected on \(PulseFormat.hubDayStamp(stamp))"
        }
        return "Not connected yet"
    }

    private func loadingCard(_ label: String) -> some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            Text(label)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
        )
        .accessibilityLabel(label)
    }

    private func honestRetryCard(message: String, retry: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Text(message)
                .font(.system(size: 12.5, weight: .medium))
                .foregroundStyle(.secondary)
            Button {
                retry()
            } label: {
                Label("Try again", systemImage: "arrow.clockwise")
                    .font(.system(size: 12, weight: .semibold))
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
        }
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color(uiColor: .secondarySystemBackground).opacity(0.5)),
        )
        .accessibilityElement(children: .contain)
    }
}

