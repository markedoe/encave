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

    /// Away from home: where the encrypted files are (iCloud or a relay), if anywhere.
    private(set) var remoteStorage: RemoteStorage?
    /// The key of the cellar's remote files (Keychain).
    private(set) var remoteKey: CellarKey?
    private(set) var lastRemoteSync: Date?
    private(set) var isSyncingRemotely = false
    private(set) var remoteProblem: String?

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
    @ObservationIgnored private var remoteProgress = RemoteProgress()
    @ObservationIgnored private var remoteLoop: Task<Void, Never>?
    @ObservationIgnored private var pendingRemote: Task<Void, Never>?

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
        remoteStorage = UserDefaults.standard.string(forKey: Self.storageKey).flatMap(RemoteStorage.init)
        remoteKey = Keychain.getData(Self.cellarKeyAccount).flatMap { try? JSONDecoder().decode(CellarKey.self, from: $0) }
        remoteProgress = files.loadRemoteProgress()
        model.onLocalChange = { [weak self] old, new in self?.recordLocalChange(from: old, to: new) }
    }

    // MARK: Lifecycle

    /// Starts listening and looking for the other devices. Call when the app becomes active.
    func resume() {
        guard isSetUp, !isDemo, !otherDevices.isEmpty else { return }
        startListener()
        startBrowser()
        startRemoteLoop()
    }

    /// Stops the network (the app goes to the background, where iOS would suspend it anyway).
    func pause() {
        remoteLoop?.cancel(); remoteLoop = nil
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
        // Away from home: a new key, so the removed device cannot read anything new.
        // Remembered until done, in case the relay cannot be reached now.
        if remoteKey != nil {
            UserDefaults.standard.set(true, forKey: Self.rotationKey)
            Task { await remoteRound() }
        }
    }

    /// Rotates the remote key if a removal is waiting for it, and returns the key to use.
    private func rotateIfNeeded(store: MailboxStore, key: CellarKey, local: SyncSnapshot) async throws -> CellarKey {
        guard UserDefaults.standard.bool(forKey: Self.rotationKey) else { return key }
        let newKey = try await RemoteSync.rotate(store: store, identity: identity, from: key, local: local,
                                                 storage: remoteStorage?.string)
        storeRemoteKey(newKey)
        UserDefaults.standard.removeObject(forKey: Self.rotationKey)
        return newKey
    }

    /// Stops syncing on this device. The cellar stays here as it is.
    func leave() {
        pause()
        membership = nil
        state = nil
        lastSync = [:]
        wasRemoved = false
        problem = nil
        remoteStorage = nil
        UserDefaults.standard.removeObject(forKey: Self.storageKey)
        UserDefaults.standard.removeObject(forKey: Self.rotationKey)
        storeRemoteKey(nil)
        remoteProgress = RemoteProgress()
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
            self?.syncRemotely()
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
                let (result, remote) = try await SyncExchange.exchange(over: channel, isInitiator: true, local: local,
                                                                       photos: Self.photos, remote: remoteInfo)
                apply(result, from: peer.id)
                adoptRemote(remote)
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
                let (result, remote) = try await SyncExchange.exchange(over: channel, isInitiator: false, local: local,
                                                                       photos: Self.photos, remote: remoteInfo)
                apply(result, from: peer.id)
                adoptRemote(remote)
            } catch {
                // Someone else's connection failing is not this device's problem to show.
                if case SyncError.unknownDevice = error { return }
                if case SyncError.removedDevice = error { return }
                report(error)
            }
        }
    }

    func report(_ error: Error) {
        switch error {
        case SyncError.removedDevice:
            wasRemoved = true
            problem = String(localized: "This device was removed from the cellar on another device.")
            pause()
        case SyncError.unknownDevice:
            // The other device has not heard of this one yet: it will after syncing with
            // the device that added this one. Nothing to show.
            break
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

    // MARK: Away from home

    static let storageKey = "sync.remoteStorage"
    static let cellarKeyAccount = "sync.cellarKey"
    static let rotationKey = "sync.rotationPending"

    var remoteInfo: RemoteInfo? {
        remoteKey.map { RemoteInfo(key: $0, storage: remoteStorage?.string) }
    }

    /// Turns syncing away from home on with a storage (or off with nil). Checks the
    /// storage first. The first device to turn it on creates the cellar's key and
    /// gives it to the others.
    func setRemoteStorage(_ storage: RemoteStorage?) async throws {
        guard let storage else {
            remoteStorage = nil
            UserDefaults.standard.removeObject(forKey: Self.storageKey)
            remoteLoop?.cancel(); remoteLoop = nil
            return
        }
        guard let store = storage.store() else { throw RemoteSyncError.unavailable("storage") }
        try await storage.check()
        setUpIfNeeded()
        remoteStorage = storage
        UserDefaults.standard.set(storage.string, forKey: Self.storageKey)
        if remoteKey == nil, let membership {
            // Maybe another device already turned it on and left a key for this one.
            if let grant = try? await RemoteSync.receiveGrant(store: store, identity: identity, membership: membership,
                                                             newerThan: 0) {
                storeRemoteKey(grant.cellarKey)
            } else {
                let key = CellarKey.random(cellarID: membership.cellarID)
                storeRemoteKey(key)
                try await RemoteSync.grant(key, to: membership.active, store: store, identity: identity,
                                           storage: storage.string)
            }
        }
        startRemoteLoop()
        await remoteRound()
    }

    /// A key and storage received from a device on the network.
    func adoptRemote(_ info: RemoteInfo?) {
        guard let info, let membership, info.key.cellarID == membership.cellarID else { return }
        if info.key.epoch > (remoteKey?.epoch ?? 0) { storeRemoteKey(info.key) }
        // Only a storage this device can use: an HTTPS relay, or iCloud in a build that has it.
        if remoteStorage == nil, let storage = info.storage.flatMap(RemoteStorage.init), storage.isAcceptedFromPeer {
            remoteStorage = storage
            UserDefaults.standard.set(storage.string, forKey: Self.storageKey)
            startRemoteLoop()
        }
    }

    /// Soon after a change, sends it away from home.
    func syncRemotely() {
        guard remoteStorage != nil else { return }
        pendingRemote?.cancel()
        pendingRemote = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            await self?.remoteRound()
        }
    }

    /// While the app is open: a round every minute, to pick up changes from devices away.
    private func startRemoteLoop() {
        guard remoteStorage != nil, remoteLoop == nil, !isDemo else { return }
        remoteLoop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.remoteRound()
                try? await Task.sleep(for: .seconds(60))
            }
        }
    }

    func remoteRound() async {
        guard let store = remoteStorage?.store(), !isSyncingRemotely, !wasRemoved, let local = snapshot() else { return }
        isSyncingRemotely = true
        defer { isSyncingRemotely = false }
        do {
            var key = remoteKey
            if key == nil {
                key = try await RemoteSync.receiveGrant(store: store, identity: identity, membership: local.membership,
                                                        newerThan: 0)?.cellarKey
                if let key { storeRemoteKey(key) }
            }
            guard var key else { return }
            do {
                key = try await rotateIfNeeded(store: store, key: key, local: local)
            } catch {
                remoteProblem = String(localized: "The removed device could not be shut out of syncing away from home yet. It will be tried again.")
                return
            }
            let result = try await RemoteSync.run(store: store, identity: identity, key: key, local: local,
                                                  progress: remoteProgress, photos: Self.photos)
            if let newKey = result.newKey { storeRemoteKey(newKey) }
            remoteProgress = result.progress
            files?.saveRemoteProgress(remoteProgress)
            if let snapshot = result.snapshot { apply(snapshot, from: nil) }
            lastRemoteSync = .now
            remoteProblem = nil
        } catch RemoteSyncError.unauthorized {
            remoteProblem = remoteStorage == .iCloud
                ? String(localized: "iCloud is not available. Check that you are signed in to iCloud.")
                : String(localized: "The relay refused this device. Check the relay address.")
        } catch RemoteSyncError.quotaExceeded {
            remoteProblem = remoteStorage == .iCloud
                ? String(localized: "Your iCloud storage is full.")
                : String(localized: "The relay is full. Remove old photos or use another relay.")
        } catch {
            remoteProblem = String(localized: "The storage cannot be reached. Syncing will resume when it can.")
        }
    }

    private func storeRemoteKey(_ key: CellarKey?) {
        remoteKey = key
        Keychain.setData(key.flatMap { try? JSONEncoder().encode($0) }, for: Self.cellarKeyAccount)
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
        private var remoteURL: URL { folder.appendingPathComponent("remote.json") }

        func loadRemoteProgress() -> RemoteProgress {
            guard let data = try? Data(contentsOf: remoteURL) else { return RemoteProgress() }
            return (try? JSONDecoder().decode(RemoteProgress.self, from: data)) ?? RemoteProgress()
        }

        func saveRemoteProgress(_ progress: RemoteProgress) {
            try? JSONEncoder().encode(progress).write(to: remoteURL, options: [.atomic])
        }

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
            for url in [devicesURL, lastSyncURL, remoteURL, syncState.fileURL] { try? FileManager.default.removeItem(at: url) }
        }
    }
}

/// Where a cellar's encrypted files are kept for devices that are apart.
enum RemoteStorage: Equatable {
    case iCloud
    case relay(URL)

    /// As stored and as passed to other devices: "icloud" or the relay URL.
    var string: String {
        switch self {
        case .iCloud: "icloud"
        case .relay(let url): url.absoluteString
        }
    }

    init?(_ string: String) {
        if string == "icloud" {
            self = .iCloud
        } else if let url = URL(string: string), let scheme = url.scheme, ["https", "http"].contains(scheme), url.host() != nil {
            self = .relay(url)
        } else {
            return nil
        }
    }

    /// A storage told by another device: an HTTPS relay, or iCloud if this build has it.
    var isAcceptedFromPeer: Bool {
        switch self {
        case .iCloud: CloudKitMailboxStore.isConfigured
        case .relay(let url): url.scheme == "https"
        }
    }

    func store() -> MailboxStore? {
        switch self {
        case .iCloud: CloudKitMailboxStore.make()
        case .relay(let url): RelayMailboxStore(baseURL: url)
        }
    }

    func check() async throws {
        switch self {
        case .iCloud:
            guard await CloudKitMailboxStore.accountAvailable() else { throw RemoteSyncError.unauthorized }
        case .relay(let url):
            try await RelayMailboxStore(baseURL: url).check()
        }
    }
}
