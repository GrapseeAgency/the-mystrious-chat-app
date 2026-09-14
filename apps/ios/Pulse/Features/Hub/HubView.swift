import SwiftUI

// ─────────────────────────────────────────────────────────────
// Wave 7 — the LIVE hub (F-HB-01…10). Replaces the static tile
// grid: real wallet + ledger, daily check-in (+25 PC base, +2/day
// streak bonus capped +20, UTC-gated server-side), @handle
// transfers, PC⇄GEM swap (100/80 rates + real stats), personal
// tasks, market with atomic buy, logs stream, the 100-app matrix
// from the bundled catalog JSON (browsable offline), install/
// connect, app communities (auto-provisioned real conversations)
// and My apps (fan-out per-app install state — web hub-data
// parity). Server error copy surfaces verbatim.
// ─────────────────────────────────────────────────────────────

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
    @Published var tasks: [WireHubTask] = []
    @Published var listings: [WireMarketListing] = []
    @Published var logs: [WireHubLog] = []
    @Published var swap: WireSwapPage?
    @Published var installs: [String: WireAppInstallState] = [:]
    @Published var toast: String?
    @Published var toastIsError = false

    private let session: PulseSession

    init(session: PulseSession) {
        self.session = session
    }

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
        Task { @MainActor in
            wallet.loading = true
            wallet.error = nil
            guard let api = api else {
                wallet.loading = false
                wallet.error = "No gateway configured — set your server in Profile → Connection."
                return
            }
            do {
                let page = try await api.walletPage()
                wallet = WalletUi(loading: false, page: page, stale: false, error: nil)
            } catch {
                wallet.stale = wallet.page != nil
                wallet.loading = false
                if wallet.page == nil { wallet.error = describe(error) }
            }
        }
    }

    func checkin() {
        Task { @MainActor in
            guard let api = api else { return }
            do {
                let result = try await api.checkinWallet()
                let streak = result.streak ?? 1
                toast("Checked in — +\(result.reward ?? 25) PC" + (streak > 1 ? " · \(streak)-day streak" : ""))
                loadWallet()
            } catch {
                loadWallet()
                toast(describe(error), isError: true)
            }
        }
    }

    // MARK: transfer

    func transfer(handle: String, amount: Int, note: String?, completion: @escaping (Bool) -> Void) {
        Task { @MainActor in
            guard let api = api else { return }
            do {
                let result = try await api.transferCoins(toUsername: handle, amount: amount, note: note)
                toast("Sent \(amount) PC to @\(result.to?.username ?? result.to?.name ?? "?")")
                loadWallet()
                completion(true)
            } catch {
                toast(describe(error), isError: true)
                completion(false)
            }
        }
    }

    // MARK: swap

    func loadSwap() {
        Task { @MainActor in
            guard let api = api else { return }
            swap = try? await api.swapRates()
        }
    }

    func swap(direction: String, amount: Int, completion: @escaping (Bool) -> Void) {
        Task { @MainActor in
            guard let api = api else { return }
            do {
                let result = try await api.swap(direction: direction, amount: amount)
                toast(result.note ?? "Swap complete")
                loadWallet()
                completion(true)
            } catch {
                toast(describe(error), isError: true)
                completion(false)
            }
        }
    }

    // MARK: tasks

    func loadTasks() {
        Task { @MainActor in
            guard let api = api else { return }
            tasks = (try? await api.hubTasks().tasks) ?? []
        }
    }

    func createTask(_ title: String) {
        Task { @MainActor in
            guard let api = api else { return }
            do {
                _ = try await api.createHubTask(title: title, status: "todo")
                loadTasks()
                toast("Task added")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func moveTask(_ task: WireHubTask, to status: String) {
        Task { @MainActor in
            guard let api = api else { return }
            do {
                _ = try await api.updateHubTask(task.id, title: nil, status: status)
                loadTasks()
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func deleteTask(_ task: WireHubTask) {
        Task { @MainActor in
            guard let api = api else { return }
            do {
                try await api.deleteHubTask(task.id)
                loadTasks()
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    // MARK: market

    func loadMarket() {
        Task { @MainActor in
            guard let api = api else { return }
            listings = (try? await api.market().listings) ?? []
        }
    }

    func createListing(_ title: String, price: Int) {
        Task { @MainActor in
            guard let api = api else { return }
            do {
                let listing = try await api.createListing(title: title, description: nil, price: price)
                loadMarket()
                toast("Listed \"\(listing.title ?? title)\" for \(price) PC")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func buy(_ listing: WireMarketListing) {
        Task { @MainActor in
            guard let api = api else { return }
            do {
                _ = try await api.buyListing(listing.id)
                toast("Bought \"\(listing.title ?? "")\"")
                loadWallet()
                loadMarket()
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    // MARK: logs

    func loadLogs() {
        Task { @MainActor in
            guard let api = api else { return }
            logs = (try? await api.hubLogs(limit: 80, kind: nil).logs) ?? []
        }
    }

    // MARK: apps

    func loadInstallState(appId: String) {
        Task { @MainActor in
            guard let api = api else { return }
            if let state = try? await api.appInstallState(appId: appId) {
                installs[appId] = state
            }
        }
    }

    func toggleInstall(_ app: HubCatalogApp) {
        let appId = String(app.n)
        Task { @MainActor in
            guard let api = api else { return }
            do {
                let connected = installs[appId]?.installed == true
                let result = connected ? try await api.uninstallApp(appId: appId) : try await api.installApp(appId: appId)
                toast((result.installed ?? false) ? "Connected to \(app.name)" : "Disconnected from \(app.name)")
                loadInstallState(appId: appId)
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func joinCommunity(_ app: HubCatalogApp, completion: @escaping (WireConversationSummary) -> Void) {
        Task { @MainActor in
            guard let api = api else { return }
            do {
                let result = try await api.joinAppCommunity(appId: String(app.n))
                if let conversation = result.conversation {
                    toast("Welcome to the \(app.name) community")
                    completion(conversation)
                } else {
                    toast("Community unavailable", isError: true)
                }
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    /// F-HB-10 — My apps fan-out (web hub-data.tsx:61,107 parity).
    func loadMyApps(_ catalog: HubCatalog) {
        Task { @MainActor in
            guard let api = api else { return }
            for app in catalog.apps.prefix(24) {
                let appId = String(app.n)
                if let state = try? await api.appInstallState(appId: appId) {
                    installs[appId] = state
                }
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
            .background(Color(.systemBackground))
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
                tagline: catalog.taglines[String(target.app.n)],
                onOpenCommunity: { app in
                    vm.joinCommunity(app) { conversation in
                        expandedApp = nil
                        onOpenRoom?(conversation)
                    }
                },
            )
        }
        .task(id: expandedApp?.n) {
            if let app = expandedApp { vm.loadInstallState(appId: String(app.n)) }
        }
    }

    // ── F-HB-01 + F-HB-02 — wallet hero ──
    private var walletHero: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Pulse Wallet")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                Spacer()
                if vm.wallet.stale {
                    Text("offline — cached").font(.caption2).foregroundStyle(.white.opacity(0.6))
                }
                if vm.wallet.loading {
                    ProgressView().tint(.white)
                }
            }
            HStack {
                hubStat("PC", vm.wallet.page?.wallet?.coins)
                hubStat("GEM", vm.wallet.page?.wallet?.gems)
                hubStat("Streak", vm.wallet.page?.wallet?.streak.map(Int64.init))
            }
            let checkedIn = vm.wallet.page?.wallet?.checkedInToday == true
            Button {
                vm.checkin()
            } label: {
                Text(checkedIn ? "Checked in today ✓" : "Daily check-in · +25 PC")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(checkedIn || vm.wallet.loading)
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
private func hubStat(_ label: String, _ value: Int64?) -> some View {
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
                        .foregroundStyle((entry.amount ?? 0) >= 0 ? PulseTheme.emerald : Color.red)
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
                Text("Whole PC only — max 100,000 per transfer.")
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
                    if !trimmed.isEmpty { vm.createTask(trimmed); title = "" }
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
                            Button { vm.deleteTask(task) } label: { Text("✕").foregroundStyle(.red) }
                                .buttonStyle(.borderless)
                        }
                    }
                }
            }
        }
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

    var body: some View {
        List {
            Section {
                Button(creating ? "Close" : "+ List") { creating.toggle() }
                if creating {
                    TextField("Title (1–80)", text: $title)
                    TextField("Price PC", text: $price)
                        .keyboardType(.numberPad)
                    Button("Post listing") {
                        let t = title.trimmingCharacters(in: .whitespaces)
                        let p = Int(price) ?? 0
                        guard !t.isEmpty else { vm.toast("Title must be 1–80 characters.", isError: true); return }
                        guard p >= 1, p <= 100_000 else { vm.toast("Price must be a positive whole number (max 100,000 PC).", isError: true); return }
                        vm.createListing(t, price: p)
                        title = ""; price = ""; creating = false
                    }
                }
            }
            Section("Listings") {
                if vm.listings.isEmpty {
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
                            Text("Yours").font(.caption).foregroundStyle(PulseTheme.emerald)
                        } else if confirmBuy == listing.id {
                            Button("No") { confirmBuy = nil }.buttonStyle(.borderless)
                            Button("Buy") { vm.buy(listing); confirmBuy = nil }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                        } else {
                            Button("Buy") { confirmBuy = listing.id }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                        }
                    }
                }
            }
        }
        .navigationTitle("Market")
        .navigationBarTitleDisplayMode(.inline)
        .task { vm.loadMarket() }
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
            Section(myAppsOnly ? "Installed & connected apps" : "\(catalog.apps.count) apps · \(catalog.categories.count) categories — browsable offline") {
                if visible.isEmpty {
                    Text(myAppsOnly ? "No apps connected yet — open Mini apps to connect." : "No apps match.")
                        .foregroundStyle(.secondary)
                }
                ForEach(visible, id: \.n) { app in
                    HStack {
                        Text(String(app.name.prefix(1)))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(PulseTheme.emerald, in: Circle())
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
                                .foregroundStyle(PulseTheme.emerald)
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
                Capsule().fill(category == slug ? PulseTheme.emerald.opacity(0.25) : Color(uiColor: .secondarySystemBackground))
            }
            .onTapGesture { category = slug }
    }
}

private struct HubAppDetailSheet: View {
    let app: HubCatalogApp
    @ObservedObject var vm: HubViewModel
    let tagline: String?
    let onOpenCommunity: (HubCatalogApp) -> Void
    @Environment(\.dismiss) private var dismiss

    private var appId: String { String(app.n) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Text(String(app.name.prefix(1)))
                            .font(.title.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .background(PulseTheme.emerald, in: Circle())
                        VStack(alignment: .leading) {
                            Text(app.name).font(.headline)
                            Text("\(app.category ?? "") · \(vm.installs[appId]?.installs ?? 0) connected")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    if let tagline {
                        Text(tagline).font(.subheadline)
                    }
                    if let nav = app.nav { Text("Nav: \(nav)").font(.caption).foregroundStyle(.secondary) }
                    if let input = app.input { Text("Input: \(input)").font(.caption).foregroundStyle(.secondary) }
                    if let secret = app.secret { Text("Secret: \(secret)").font(.caption).foregroundStyle(.secondary) }
                }
                Section {
                    Button(vm.installs[appId]?.installed == true ? "Disconnect" : "Install / Connect") {
                        vm.toggleInstall(app)
                    }
                    Button("Community") { onOpenCommunity(app) }
                }
            }
            .navigationTitle(app.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}

