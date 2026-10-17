import CaveKit
import Foundation
import Network
import Observation
#if os(iOS)
import UIKit
#endif

/// Keeps this device's cellar in step with the user's other devices on the same
/// network. Devices find each other with Bonjour; every session is authenticated
/// with the devices' identity keys and encrypted (see CaveKit `SyncProtocol`).
///
/// Nothing runs until the user adds or joins a device: before that the app never
/// listens on the network, so it never asks for local network access.
@MainActor
@Observable
final class SyncService {
    /// The devices of this cellar; nil until sync is set up.
    private(set) var membership: Membership?
    /// Devices of the cellar seen on the network right now.
    private(set) var nearby: Set<UUID> = []
    private(set) var syncing: Set<UUID> = []
    private(set) var lastSync: [UUID: Date] = [:]
    /// The last problem, to show in Devices.
    private(set) var problem: String?
    /// This device was removed from the cellar by another device.
    private(set) var wasRemoved = false

    let identity: DeviceIdentity
    let deviceName: String
    let platform: String
    /// Demo mode (screen tour): shows the screens, never touches the network.
    let isDemo: Bool

    @ObservationIgnored private weak var model: CellarModel?
    @ObservationIgnored private var state: SyncState?
    @ObservationIgnored private var listener: NWListener?
    @ObservationIgnored private var browser: NWBrowser?
    @ObservationIgnored private var endpoints: [UUID: NWEndpoint] = [:]
    @ObservationIgnored private var pendingSync: Task<Void, Never>?
    @ObservationIgnored private let files: Files?

    var isSetUp: Bool { membership != nil }
    /// Other devices of the cellar.
    var otherDevices: [Member] { (membership?.active ?? []).filter { $0.id != identity.id } }

    init(model: CellarModel, demo: Bool) {
        self.model = model
        isDemo = demo
        platform = Self.currentPlatform
        deviceName = Self.currentDeviceName
        files = demo ? nil : Files()
        identity = demo ? DeviceIdentity.generate().identity : Self.loadIdentity()
        guard let files else { return }
        membership = files.loadMembership()
        state = (try? files.syncState.load()) ?? nil
        lastSync = files.loadLastSync()
        if membership != nil, state == nil { state = .starting(with: model.cellar, device: identity.id) }
        model.onLocalChange = { [weak self] old, new in self?.recordLocalChange(from: old, to: new) }
    }

    // MARK: Lifecycle

    /// Starts listening and looking for the other devices. Call when the app becomes active.
    func resume() {
        guard isSetUp, !isDemo, !otherDevices.isEmpty else { return }
        startListener()
        startBrowser()
    }

    /// Stops the network (the app goes to the background, where iOS would suspend it anyway).
    func pause() {
        listener?.cancel(); listener = nil
        browser?.cancel(); browser = nil
        nearby = []
        endpoints = [:]
    }

    /// Syncs now with every device of the cellar on the network.
    func syncNow() {
        for (device, endpoint) in endpoints { sync(with: device, at: endpoint) }
    }

    // MARK: Setting up and leaving

    /// First device of a cellar: creates the list of devices with this one.
    func setUpIfNeeded() {
        guard membership == nil, let model else { return }
        var members = Membership()
        var clock = state?.clock ?? 0
        members.add(me, clock: &clock, by: identity.id)
        var newState = state ?? SyncState.starting(with: model.cellar, device: identity.id)
        newState.clock = max(newState.clock, clock)
        state = newState
        membership = members
        save()
    }

    /// Removes another device: it can no longer sync with any device of the cellar.
    func remove(_ device: UUID) {
        guard var members = membership, var state else { return }
        members.remove(device, clock: &state.clock, by: identity.id)
        membership = members
        self.state = state
        lastSync[device] = nil
        save()
        syncNow()
    }

    /// Stops syncing on this device. The cellar stays here as it is.
    func leave() {
        pause()
        membership = nil
        state = nil
        lastSync = [:]
        wasRemoved = false
        problem = nil
        files?.deleteAll()
    }

    var me: Member {
        Member(id: identity.id, name: deviceName, publicKey: identity.publicKey, platform: platform)
    }

    // MARK: Recording changes

    private func recordLocalChange(from old: Cellar, to new: Cellar) {
        guard isSetUp, var state else { return }
        state.record(from: old, to: new)
        self.state = state
        try? files?.syncState.save(state)
        // Wait for a pause in editing, then send the change to the devices around.
        pendingSync?.cancel()
        pendingSync = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            self?.syncNow()
        }
    }

    // MARK: Sessions

    func snapshot() -> SyncSnapshot? {
        guard let model, let state, let membership else { return nil }
        return SyncSnapshot(cellar: model.cellar, state: state, membership: membership)
    }

    /// Takes in the result of a session. The cellar may have changed during it, so the
    /// result is merged again with what is here now: no local edit is lost.
    func apply(_ result: SyncSnapshot, from peer: UUID?) {
        guard let model, let state, let membership else { return }
        let merge = CellarMerge.merge(local: model.cellar, state, remote: result.cellar, result.state)
        var newState = state
        newState.stamps = merge.stamps
        newState.clock = max(merge.clock, newState.clock)
        self.state = newState
        model.applySynced(merge.cellar)
        let members = membership.merged(with: result.membership)
        self.membership = members
        if let peer { lastSync[peer] = .now }
        if members.members[identity.id] == nil {
            wasRemoved = true
            pause()
        }
        problem = nil
        save()
    }

    private func sync(with device: UUID, at endpoint: NWEndpoint) {
        guard !syncing.contains(device), let membership, !wasRemoved else { return }
        syncing.insert(device)
        let identity = identity
        Task {
            defer { syncing.remove(device) }
            let transport = NetworkTransport(connection: NWConnection(to: endpoint, using: .tcp))
            defer { transport.close() }
            do {
                try await transport.start()
                let (channel, peer) = try await SyncHandshake.initiate(over: transport, identity: identity, membership: membership)
                guard let local = snapshot() else { return }
                let result = try await SyncExchange.run(over: channel, isInitiator: true, local: local, photos: Self.photos)
                apply(result, from: peer.id)
            } catch {
                report(error)
            }
        }
    }

    private func respond(to connection: NWConnection) {
        guard let membership, !wasRemoved else { connection.cancel(); return }
        let identity = identity
        Task {
            let transport = NetworkTransport(connection: connection)
            defer { transport.close() }
            do {
                try await transport.start()
                let (channel, peer) = try await SyncHandshake.respond(over: transport, identity: identity, membership: membership)
                syncing.insert(peer.id)
                defer { syncing.remove(peer.id) }
                guard let local = snapshot() else { return }
                let result = try await SyncExchange.run(over: channel, isInitiator: false, local: local, photos: Self.photos)
                apply(result, from: peer.id)
            } catch {
                // Someone else's connection failing is not this device's problem to show.
                if case SyncError.unknownDevice = error { return }
                report(error)
            }
        }
    }

    func report(_ error: Error) {
        switch error {
        case SyncError.unknownDevice:
            wasRemoved = true
            problem = String(localized: "This device was removed from the cellar on another device.")
            pause()
        case SyncError.authenticationFailed:
            problem = String(localized: "A device could not prove it belongs to this cellar. Nothing was exchanged.")
        case SyncError.unsupportedVersion:
            problem = String(localized: "Another device runs a different version of Encave. Update both devices.")
        default:
            problem = String(localized: "The last sync did not finish. It will try again.")
        }
    }

    nonisolated static var photos: PhotoAccess {
        PhotoAccess(data: { PhotoStore.data(for: $0) }, save: { PhotoStore.store($1, id: $0) })
    }

    // MARK: Network

    private var serviceTXT: NWTXTRecord {
        var record = NWTXTRecord()
        record["d"] = identity.id.uuidString.lowercased()
        record["c"] = membership?.networkTag ?? ""
        record["v"] = String(SyncProtocol.version)
        return record
    }

    private func startListener() {
        guard listener == nil else { return }
        do {
            let listener = try NWListener(using: .tcp)
            // The name others see on the network: not the device name, which may be personal.
            listener.service = NWListener.Service(name: "Encave-\(identity.id.uuidString.prefix(8))",
                                                  type: SyncProtocol.serviceType, txtRecord: serviceTXT)
            // Handlers run on the main queue (see `start(queue:)`).
            listener.newConnectionHandler = { [weak self] connection in
                MainActor.assumeIsolated { self?.respond(to: connection) }
            }
            listener.stateUpdateHandler = { [weak self] state in
                if case .failed = state { MainActor.assumeIsolated { self?.listener = nil } }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            problem = String(localized: "Sync could not start on this network.")
        }
    }

    private func startBrowser() {
        guard browser == nil else { return }
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: SyncProtocol.serviceType, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            MainActor.assumeIsolated { self?.update(results) }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        guard let membership else { return }
        var found: [UUID: NWEndpoint] = [:]
        for result in results {
            guard case .bonjour(let txt) = result.metadata, txt["c"] == membership.networkTag,
                  let id = txt["d"].flatMap(UUID.init(uuidString:)), id != identity.id,
                  membership.members[id] != nil else { continue }
            found[id] = result.endpoint
        }
        let appeared = Set(found.keys).subtracting(nearby)
        endpoints = found
        nearby = Set(found.keys)
        // One side starts the session: the device with the smaller ID.
        for id in appeared where identity.id.uuidString.lowercased() < id.uuidString.lowercased() {
            if let endpoint = found[id] { sync(with: id, at: endpoint) }
        }
    }

    // MARK: Pairing

    /// Adopts the cellar and devices received from the device that invited this one.
    /// `replace`: start from that cellar instead of combining it with this one.
    func adoptCellar(_ received: Membership, replace: Bool) {
        guard let model else { return }
        if replace {
            model.applySynced(Cellar(settings: model.cellar.settings))
            state = SyncState(device: identity.id)
        } else if state == nil {
            state = .starting(with: model.cellar, device: identity.id)
        }
        var members = received
        var clock = max(state?.clock ?? 0, received.stamps.values.map(\.counter).max() ?? 0)
        members.add(me, clock: &clock, by: identity.id)
        state?.clock = clock
        membership = members
        wasRemoved = false
        problem = nil
        save()
    }

    /// Adds the device that just joined.
    func addMember(_ member: Member) {
        guard var members = membership, var state else { return }
        members.add(member, clock: &state.clock, by: identity.id)
        membership = members
        self.state = state
        save()
    }

    // MARK: Storage

    private func save() {
        guard let files else { return }
        if let membership { files.saveMembership(membership) }
        if let state { try? files.syncState.save(state) }
        files.saveLastSync(lastSync)
    }

    private static func loadIdentity() -> DeviceIdentity {
        let account = "device.identity"
        if let idString = UserDefaults.standard.string(forKey: "sync.deviceID"), let id = UUID(uuidString: idString),
           let key = Keychain.getData(account), let identity = try? DeviceIdentity(id: id, privateKeyData: key) {
            return identity
        }
        let (identity, key) = DeviceIdentity.generate()
        Keychain.setData(key, for: account)
        UserDefaults.standard.set(identity.id.uuidString, forKey: "sync.deviceID")
        return identity
    }

    private static var currentPlatform: String {
        #if os(macOS)
        "macos"
        #else
        UIDevice.current.userInterfaceIdiom == .pad ? "ipados" : "ios"
        #endif
    }

    private static var currentDeviceName: String {
        #if os(macOS)
        Host.current().localizedName ?? "Mac"
        #else
        UIDevice.current.name
        #endif
    }

    /// Files next to the cellar: the devices (public keys only) and the change stamps.
    struct Files {
        let folder: URL
        let syncState: SyncStateFile

        init?() {
            guard let store = try? JSONFileCellarStore.defaultStore() else { return nil }
            folder = store.fileURL.deletingLastPathComponent()
            syncState = SyncStateFile(fileURL: folder.appendingPathComponent("sync.json"))
        }

        private var devicesURL: URL { folder.appendingPathComponent("devices.json") }
        private var lastSyncURL: URL { folder.appendingPathComponent("last-sync.json") }

        func loadMembership() -> Membership? {
            guard let data = try? Data(contentsOf: devicesURL) else { return nil }
            return try? JSONDecoder().decode(Membership.self, from: data)
        }

        func saveMembership(_ membership: Membership) {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try? encoder.encode(membership).write(to: devicesURL, options: [.atomic])
        }

        func loadLastSync() -> [UUID: Date] {
            guard let data = try? Data(contentsOf: lastSyncURL) else { return [:] }
            return (try? JSONDecoder().decode([UUID: Date].self, from: data)) ?? [:]
        }

        func saveLastSync(_ dates: [UUID: Date]) {
            try? JSONEncoder().encode(dates).write(to: lastSyncURL, options: [.atomic])
        }

        func deleteAll() {
            for url in [devicesURL, lastSyncURL, syncState.fileURL] { try? FileManager.default.removeItem(at: url) }
        }
    }
}
