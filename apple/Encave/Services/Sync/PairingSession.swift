import CaveKit
import Foundation
import Network
import Observation

/// One attempt to add a device, seen from either side: the device that invites
/// (shows a code) or the device that joins (types or scans it).
@MainActor
@Observable
final class PairingSession {
    enum Phase: Equatable {
        /// Inviting: waiting for the other device. Joining: looking for it.
        case waiting
        /// Both devices show this code; the user checks they match.
        case confirm(code: String, device: String)
        /// The user accepted; waiting for the other device to accept too.
        case waitingForOther
        case syncing
        case done(device: String)
        case failed(String)
    }

    enum Role { case inviting, joining }

    let role: Role
    private(set) var phase: Phase = .waiting
    /// Inviting: the code to show and to put in the QR code.
    let code: PairingCode
    private(set) var qrPayload = ""
    /// When the code stops working.
    let expiresAt: Date

    @ObservationIgnored private weak var service: SyncService?
    @ObservationIgnored private var decision: CheckedContinuation<Bool, Never>?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var listener: NWListener?
    @ObservationIgnored private var browser: NWBrowser?
    @ObservationIgnored private var transport: NetworkTransport?

    static let lifetime: TimeInterval = 120

    private init(role: Role, code: PairingCode, service: SyncService) {
        self.role = role
        self.code = code
        self.service = service
        expiresAt = .now.addingTimeInterval(Self.lifetime)
    }

    // MARK: Inviting

    /// Shows a new code and waits for a device to join with it.
    static func invite(using service: SyncService) -> PairingSession {
        service.setUpIfNeeded()
        let session = PairingSession(role: .inviting, code: .random(), service: service)
        let host = PairingHost(identity: service.identity, name: service.deviceName, platform: service.platform)
        session.qrPayload = session.code.qrPayload(commitment: host.commitment)
        if !service.isDemo { session.listen(host: host) }
        return session
    }

    private func listen(host: PairingHost) {
        do {
            let listener = try NWListener(using: .tcp)
            var txt = NWTXTRecord()
            txt["p"] = code.rendezvous
            listener.service = NWListener.Service(name: "Encave-pair-\(code.rendezvous.prefix(6))",
                                                  type: SyncProtocol.serviceType, txtRecord: txt)
            listener.newConnectionHandler = { [weak self] connection in
                MainActor.assumeIsolated { self?.accept(connection, host: host) }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            phase = .failed(String(localized: "This device could not wait for the other one on the network."))
        }
        task = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.lifetime))
            guard let self, phase == .waiting else { return }
            stop()
            phase = .failed(String(localized: "The code expired. Show a new code."))
        }
    }

    private func accept(_ connection: NWConnection, host: PairingHost) {
        // One pairing per code: the first device to connect is the only one.
        guard transport == nil, phase == .waiting else { connection.cancel(); return }
        listener?.cancel(); listener = nil
        let transport = NetworkTransport(connection: connection)
        self.transport = transport
        task?.cancel()
        task = Task { [weak self] in
            guard let self else { return }
            do {
                try await transport.start()
                let keys = try await host.exchangeKeys(over: transport)
                let accepted = await ask(code: keys.confirmationCode, device: keys.peer.name)
                guard let service, let membership = service.membership else { throw SyncError.closed }
                let (channel, member) = try await host.finish(over: transport, keys: keys, accepted: accepted,
                                                              membership: membership)
                service.addMember(member)
                phase = .syncing
                guard let local = service.snapshot() else { throw SyncError.closed }
                let result = try await SyncExchange.run(over: channel, isInitiator: true, local: local,
                                                        photos: SyncService.photos)
                service.apply(result, from: member.id)
                service.resume()
                phase = .done(device: member.name)
            } catch {
                fail(error)
            }
            transport.close()
        }
    }

    // MARK: Joining

    /// Looks for the device showing `code` and joins its cellar.
    static func join(code: PairingCode, commitment: Data?, replace: Bool, using service: SyncService) -> PairingSession {
        let session = PairingSession(role: .joining, code: code, service: service)
        if !service.isDemo { session.find(commitment: commitment, replace: replace) }
        return session
    }

    private func find(commitment: Data?, replace: Bool) {
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: SyncProtocol.serviceType, domain: nil), using: .tcp)
        let rendezvous = code.rendezvous
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let match = results.first { result in
                if case .bonjour(let txt) = result.metadata { return txt["p"] == rendezvous }
                return false
            }
            guard let match else { return }
            MainActor.assumeIsolated { self?.connect(to: match.endpoint, commitment: commitment, replace: replace) }
        }
        browser.start(queue: .main)
        self.browser = browser
        task = Task { [weak self] in
            try? await Task.sleep(for: .seconds(30))
            guard let self, phase == .waiting, transport == nil else { return }
            stop()
            phase = .failed(String(localized: "No device shows this code on this network. Check the code, and that both devices are on the same Wi-Fi."))
        }
    }

    private func connect(to endpoint: NWEndpoint, commitment: Data?, replace: Bool) {
        guard transport == nil, let service else { return }
        browser?.cancel(); browser = nil
        let transport = NetworkTransport(connection: NWConnection(to: endpoint, using: .tcp))
        self.transport = transport
        let joiner = PairingJoiner(identity: service.identity, name: service.deviceName, platform: service.platform,
                                   expectedCommitment: commitment)
        task?.cancel()
        task = Task { [weak self] in
            guard let self else { return }
            do {
                try await transport.start()
                let keys = try await joiner.exchangeKeys(over: transport)
                let accepted = await ask(code: keys.confirmationCode, device: keys.peer.name)
                let (channel, received) = try await joiner.finish(over: transport, keys: keys, accepted: accepted)
                service.adoptCellar(received, replace: replace)
                phase = .syncing
                guard let local = service.snapshot() else { throw SyncError.closed }
                let result = try await SyncExchange.run(over: channel, isInitiator: false, local: local,
                                                        photos: SyncService.photos)
                service.apply(result, from: keys.peer.device)
                service.resume()
                phase = .done(device: keys.peer.name)
            } catch {
                fail(error)
            }
            transport.close()
        }
    }

    // MARK: Confirmation

    private func ask(code: String, device: String) async -> Bool {
        phase = .confirm(code: code, device: device)
        let accepted = await withCheckedContinuation { decision = $0 }
        if accepted { phase = .waitingForOther }
        return accepted
    }

    /// The user's answer to "Do the codes match?".
    func answer(_ accepted: Bool) {
        decision?.resume(returning: accepted)
        decision = nil
    }

    func cancel() {
        answer(false)
        stop()
    }

    private func stop() {
        task?.cancel()
        listener?.cancel(); listener = nil
        browser?.cancel(); browser = nil
        transport?.close()
    }

    private func fail(_ error: Error) {
        stop()
        if case .done = phase { return }
        switch error {
        case SyncError.rejected:
            phase = .failed(String(localized: "The codes were not confirmed on both devices. Nothing was shared."))
        case SyncError.authenticationFailed:
            phase = .failed(String(localized: "The other device could not be verified. Nothing was shared. Try again with a new code."))
        case SyncError.unsupportedVersion:
            phase = .failed(String(localized: "The other device runs a different version of Encave. Update both devices."))
        default:
            phase = .failed(String(localized: "The connection was lost. Try again with a new code."))
        }
    }

    // MARK: Screen tour

    /// Shows the confirmation step without a network (screen tour).
    func showDemoConfirmation() {
        phase = .confirm(code: "482 913", device: "iPad")
    }
}
