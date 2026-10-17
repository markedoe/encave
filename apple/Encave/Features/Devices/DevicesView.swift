import CaveKit
import CoreImage.CIFilterBuiltins
import SwiftUI
#if os(iOS)
import VisionKit
#endif

/// Settings › Devices: the devices that share this cellar, and adding one.
struct DevicesView: View {
    @Environment(CellarModel.self) private var model
    @State private var inviting = false
    @State private var joining = false
    @State private var removing: Member?
    @State private var confirmingLeave = false

    var body: some View {
        let sync = model.sync!
        Form {
            if let problem = sync.problem {
                Section {
                    Label(problem, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                    if sync.wasRemoved {
                        Button("Stop Syncing on This Device", role: .destructive) { sync.leave() }
                    }
                }
            }

            Section {
                DeviceRow(member: sync.me, isThisDevice: true, status: nil)
                ForEach(sync.otherDevices) { member in
                    DeviceRow(member: member, isThisDevice: false, status: status(of: member, sync))
                        .swipeActions {
                            Button("Remove", role: .destructive) { removing = member }
                        }
                        .contextMenu {
                            Button("Remove Device", systemImage: "minus.circle", role: .destructive) { removing = member }
                        }
                }
            } header: {
                Text("Devices sharing this cellar")
            } footer: {
                if sync.otherDevices.isEmpty {
                    Text("Add your iPad or Mac to have the same cellar on each, kept in sync.")
                } else {
                    Text("Devices sync when they are on the same Wi-Fi and Encave is open. Changes made apart merge without losing anything.")
                }
            }

            Section {
                Button {
                    inviting = true
                } label: {
                    Label("Add a Device", systemImage: "plus.circle")
                }
                .accessibilityIdentifier("devices.add")
                Button {
                    joining = true
                } label: {
                    Label("Join Another Device’s Cellar", systemImage: "arrow.down.circle")
                }
                .accessibilityIdentifier("devices.join")
                if !sync.otherDevices.isEmpty {
                    Button {
                        sync.syncNow()
                    } label: {
                        Label("Sync Now", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .disabled(sync.nearby.isEmpty || !sync.syncing.isEmpty)
                }
            } footer: {
                Text("Adding a device shows a code on this one; type or scan it on the other. Both must be on the same Wi-Fi.")
            }

            if !sync.otherDevices.isEmpty {
                Section {
                    NavigationLink {
                        RemoteSyncView()
                    } label: {
                        LabeledContent {
                            switch sync.remoteStorage {
                            case nil: Text("Off")
                            case .iCloud: Text(verbatim: "iCloud")
                            case .relay: Text("Relay")
                            }
                        } label: {
                            Label("Sync Away from Home", systemImage: "globe")
                        }
                    }
                    .accessibilityIdentifier("devices.remote")
                } footer: {
                    Text("Keeps devices in sync when they are not on the same Wi-Fi, through a relay that only stores encrypted data.")
                }
            }

            Section {
                Label("Encrypted from end to end", systemImage: "lock.shield")
                Text("Devices talk directly on your network. Each one proves it belongs to the cellar with its own key, and everything they exchange is encrypted. No server is involved.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                HelpLink(topic: .devices, title: "Help with devices")
            }

            if sync.isSetUp, !sync.otherDevices.isEmpty {
                Section {
                    Button("Stop Syncing on This Device", role: .destructive) { confirmingLeave = true }
                } footer: {
                    Text("The cellar stays on this device as it is. Other devices keep syncing together.")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Devices")
        .sheet(isPresented: $inviting) { InviteDeviceView() }
        .sheet(isPresented: $joining) { JoinDeviceView() }
        .confirmationDialog(
            Text("Remove \(removingName)?"),
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
            titleVisibility: .visible
        ) {
            Button("Remove Device", role: .destructive) {
                if let removing { sync.remove(removing.id) }
            }
        } message: {
            Text("It keeps what it already has, but can no longer sync with your other devices.")
        }
        .confirmationDialog("Stop syncing on this device?", isPresented: $confirmingLeave, titleVisibility: .visible) {
            Button("Stop Syncing", role: .destructive) { sync.leave() }
        }
    }

    private var removingName: String { removing?.name ?? "" }

    private func status(of member: Member, _ sync: SyncService) -> Text {
        if sync.syncing.contains(member.id) { return Text("Syncing…") }
        if let date = sync.lastSync[member.id] {
            return Text("Synced \(date.formatted(.relative(presentation: .named)))")
        }
        if sync.nearby.contains(member.id) { return Text("On this network") }
        return Text("Not synced yet")
    }
}

private struct DeviceRow: View {
    let member: Member
    let isThisDevice: Bool
    let status: Text?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(Color.accentColor)
                .frame(width: 34)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: member.name)
                if isThisDevice {
                    Text("This device").font(.caption).foregroundStyle(.secondary)
                } else if let status {
                    status.font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var icon: String {
        switch member.platform {
        case "macos": "laptopcomputer"
        case "ipados": "ipad"
        case "android": "smartphone"
        default: "iphone"
        }
    }
}

// MARK: - Away from home

/// Syncing away from home: through the user's iCloud, or a relay; and what each can see.
struct RemoteSyncView: View {
    @Environment(CellarModel.self) private var model
    @State private var address = ""
    @State private var checking = false
    @State private var error: String?

    var body: some View {
        let sync = model.sync!
        Form {
            if let storage = sync.remoteStorage {
                Section {
                    switch storage {
                    case .iCloud:
                        LabeledContent("Storage") { Text(verbatim: "iCloud") }
                    case .relay(let url):
                        LabeledContent("Relay") { Text(verbatim: url.host() ?? url.absoluteString) }
                    }
                    LabeledContent("Status") {
                        if sync.isSyncingRemotely {
                            Text("Syncing…")
                        } else if let problem = sync.remoteProblem {
                            Text(problem).foregroundStyle(.orange)
                        } else if let date = sync.lastRemoteSync {
                            Text("Synced \(date.formatted(.relative(presentation: .named)))")
                        } else {
                            Text("Not synced yet")
                        }
                    }
                    Button {
                        Task { await sync.remoteRound() }
                    } label: {
                        Label("Sync Now", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .disabled(sync.isSyncingRemotely)
                    Button("Turn Off", role: .destructive) {
                        Task { try? await sync.setRemoteStorage(nil) }
                    }
                } footer: {
                    Text("Your other devices receive this setting and the key the next time they sync on the same Wi-Fi.")
                }
            } else {
                if CloudKitMailboxStore.isConfigured {
                    Section {
                        Button {
                            turnOn(.iCloud)
                        } label: {
                            Label("Use iCloud", systemImage: "icloud")
                        }
                        .disabled(checking)
                        .accessibilityIdentifier("remote.icloud")
                    } header: {
                        Text("iCloud")
                    } footer: {
                        Text("For devices signed in to the same Apple Account. Uses a little of your iCloud storage; Apple only receives encrypted files.")
                    }
                }
                Section {
                    TextField("Relay address", text: $address, prompt: Text(verbatim: "https://"))
                        .urlField()
                        .accessibilityIdentifier("remote.address")
                    Button {
                        if let url = validURL { turnOn(.relay(url)) }
                    } label: {
                        if checking { ProgressView() } else { Text("Check and Turn On") }
                    }
                    .disabled(checking || validURL == nil)
                } header: {
                    Text("Relay")
                } footer: {
                    Text("For iPhone, iPad and Mac today, and Android later. A relay is a small open-source service: run your own on a home server or a free Cloudflare account, or use one run by someone you trust.")
                }
                if let error {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                    }
                }
            }

            Section {
                Label("Only encrypted files are stored. Neither Apple nor a relay can see your wines, photos or device names.",
                      systemImage: "lock.shield")
                Label("The storage sees when devices sync, how much they send and, for a relay, their IP addresses.",
                      systemImage: "eye")
                Label("Removing a device gives the others a new key, so it can no longer read anything new.",
                      systemImage: "key")
            } header: {
                Text("Privacy")
            } footer: {
                Text("Without syncing away from home, devices still sync on the same Wi-Fi.")
            }

            Section {
                Link(destination: URL(string: "https://github.com/markedoe/encave/blob/main/relay/README.md")!) {
                    Label("How to Run a Relay", systemImage: "server.rack")
                }
                HelpLink(topic: .devices, title: "Help with devices")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Away from Home")
    }

    /// HTTPS, or HTTP only for a server on the local network.
    private var validURL: URL? {
        let text = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: text.contains("://") ? text : "https://" + text), let host = url.host(), !host.isEmpty
        else { return nil }
        if url.scheme == "https" { return url }
        if url.scheme == "http", host == "localhost" || host.hasSuffix(".local") { return url }
        return nil
    }

    private func turnOn(_ storage: RemoteStorage) {
        checking = true
        error = nil
        Task {
            do {
                try await model.sync.setRemoteStorage(storage)
            } catch {
                switch storage {
                case .iCloud: self.error = String(localized: "iCloud is not available. Check that you are signed in to iCloud.")
                case .relay: self.error = String(localized: "This address does not answer as an Encave relay.")
                }
            }
            checking = false
        }
    }
}

// MARK: - Inviting

/// Shows a code and a QR code, then the confirmation step.
struct InviteDeviceView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var session: PairingSession?

    var body: some View {
        NavigationStack {
            Group {
                if let session {
                    PairingProgress(session: session) {
                        self.session = PairingSession.invite(using: model.sync)
                    } waiting: {
                        CodeCard(session: session)
                    }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Add a Device")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: {
                        if isFinished { Text("Done") } else { Text("Cancel") }
                    }
                    .accessibilityIdentifier("sheet.cancel")
                }
            }
        }
        .onAppear {
            if session == nil { session = PairingSession.invite(using: model.sync) }
        }
        .onDisappear { session?.cancel() }
        .macSheetFrame(width: 460, height: 620)
        .solidSheetBackground()
    }

    private var isFinished: Bool {
        if case .done = session?.phase { return true }
        return false
    }
}

/// The code to type and the QR code to scan on the other device.
private struct CodeCard: View {
    let session: PairingSession

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                Text("On the other device, open Settings › Devices › Join Another Device’s Cellar, then scan this code or type the numbers.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                QRCodeView(payload: session.qrPayload)
                    .frame(width: 210, height: 210)
                    .padding(14)
                    // QR codes need a light quiet zone to scan, in dark mode too.
                    .background(.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .accessibilityLabel(Text("QR code to scan with the other device"))
                VStack(spacing: 6) {
                    Text(verbatim: String(session.code.digits.prefix(3)) + " " + String(session.code.digits.suffix(3)))
                        .font(.system(size: 44, weight: .semibold, design: .rounded).monospacedDigit())
                        .accessibilityLabel(Text(verbatim: session.code.digits.map(String.init).joined(separator: " ")))
                        .accessibilityIdentifier("devices.code")
                        .textSelection(.enabled)
                    Text("Valid for 2 minutes, for one device.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                HStack(spacing: 8) {
                    // A spinner never lets UI tests see the app idle: a still symbol in the screen tour.
                    if session.isDemo {
                        Image(systemName: "hourglass").accessibilityHidden(true)
                    } else {
                        ProgressView()
                    }
                    Text("Waiting for the other device…")
                }
                .foregroundStyle(.secondary)
            }
            .padding(24)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
    }
}

/// A QR code drawn crisp at any size.
struct QRCodeView: View {
    let payload: String

    var body: some View {
        if let image = Self.image(for: payload) {
            Image(decorative: image, scale: 1)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
        } else {
            Color.clear
        }
    }

    static func image(for payload: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        return CIContext().createCGImage(output, from: output.extent)
    }
}

// MARK: - Joining

/// Types or scans the code shown on the other device.
struct JoinDeviceView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var digits = ""
    @State private var commitment: Data?
    @State private var replace = false
    @State private var scanning = false
    @State private var session: PairingSession?
    @FocusState private var codeFocused: Bool

    private var code: PairingCode { PairingCode(digits: digits) }
    private var hasOwnBottles: Bool { !model.cellar.bottles.isEmpty }

    var body: some View {
        NavigationStack {
            Group {
                if let session {
                    PairingProgress(session: session) {
                        self.session = nil
                        digits = ""
                        commitment = nil
                    } waiting: {
                        VStack(spacing: 14) {
                            ProgressView()
                            Text("Looking for the device showing \(code.digits)…")
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                } else {
                    form
                }
            }
            .navigationTitle("Join a Cellar")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: {
                        if isFinished { Text("Done") } else { Text("Cancel") }
                    }
                    .accessibilityIdentifier("sheet.cancel")
                }
            }
            #if os(iOS)
            .fullScreenCover(isPresented: $scanning) {
                QRScannerView { payload in
                    scanning = false
                    if let parsed = PairingCode.parse(qr: payload) {
                        digits = parsed.code.digits
                        commitment = parsed.commitment
                        start()
                    }
                }
            }
            #endif
        }
        .onDisappear { session?.cancel() }
        .macSheetFrame(width: 460, height: 560)
        .solidSheetBackground()
    }

    private var isFinished: Bool {
        if case .done = session?.phase { return true }
        return false
    }

    private var form: some View {
        Form {
            #if os(iOS)
            if QRScannerView.isSupported {
                Section {
                    Button {
                        scanning = true
                    } label: {
                        Label("Scan the QR Code", systemImage: "qrcode.viewfinder")
                    }
                    .accessibilityIdentifier("devices.scan")
                }
            }
            #endif
            Section {
                TextField("Code", text: $digits, prompt: Text(verbatim: "123 456"))
                    .font(.system(.title, design: .rounded).monospacedDigit())
                    .multilineTextAlignment(.center)
                    .digitsKeyboard()
                    .focused($codeFocused)
                    .onChange(of: digits) { _, new in
                        let clean = String(new.filter(\.isNumber).prefix(6))
                        if clean != new { digits = clean }
                        commitment = nil
                    }
                    .accessibilityIdentifier("devices.codeField")
            } header: {
                Text("Code shown on the other device")
            } footer: {
                Text("On the device that already has your cellar: Settings › Devices › Add a Device.")
            }
            if hasOwnBottles {
                Section {
                    Picker("This device’s bottles", selection: $replace) {
                        Text("Combine with the other cellar").tag(false)
                        Text("Replace with the other cellar").tag(true)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("This device already has bottles")
                } footer: {
                    if replace {
                        Text("This device will show only the other device’s cellar. Make a backup first if you may need these bottles.")
                    } else {
                        Text("Both cellars are put together. Choose Replace if this device only has the sample cellar.")
                    }
                }
            }
            Section {
                Button(action: start) {
                    Text("Join").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!code.isValid)
                .listRowBackground(Color.clear)
                .accessibilityIdentifier("devices.joinButton")
            }
        }
        .formStyle(.grouped)
        .onAppear { codeFocused = true }
    }

    private func start() {
        guard code.isValid else { return }
        codeFocused = false
        session = PairingSession.join(code: code, commitment: commitment, replace: replace, using: model.sync)
    }
}

// MARK: - Progress and confirmation

/// The steps after the code: compare, wait, sync, done.
private struct PairingProgress<Waiting: View>: View {
    let session: PairingSession
    let retry: () -> Void
    @ViewBuilder let waiting: () -> Waiting

    var body: some View {
        switch session.phase {
        case .waiting:
            waiting()
        case .confirm(let code, let deviceName):
            ConfirmCodeView(code: code, deviceName: deviceName) { session.answer($0) }
        case .waitingForOther:
            StatusView(systemImage: "hourglass", title: Text("Waiting for the other device"),
                       message: Text("Confirm the code on the other device too."), showsProgress: true)
        case .syncing:
            StatusView(systemImage: "arrow.triangle.2.circlepath", title: Text("Syncing"),
                       message: Text("Sending the cellar and its photos. Keep both devices open."), showsProgress: true)
        case .done(let deviceName):
            StatusView(systemImage: "checkmark.circle.fill", title: Text("\(deviceName) added"),
                       message: Text("Your cellar is now on both devices and stays in sync when they are on the same Wi-Fi."),
                       showsProgress: false)
                .accessibilityIdentifier("devices.done")
        case .failed(let message):
            VStack(spacing: 18) {
                StatusView(systemImage: "xmark.octagon", title: Text("Not added"), message: Text(message), showsProgress: false)
                Button("Try Again", action: retry)
                    .buttonStyle(.borderedProminent)
            }
        }
    }
}

/// "Do both devices show this code?"
private struct ConfirmCodeView: View {
    let code: String
    let deviceName: String
    let answer: (Bool) -> Void

    var body: some View {
        VStack(spacing: 22) {
            Image(systemName: "checkmark.shield")
                .font(.system(size: 44))
                .foregroundStyle(Color.accentColor)
                .accessibilityHidden(true)
            Text("Does \(deviceName) show the same code?")
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(verbatim: code)
                .font(.system(size: 48, weight: .bold, design: .rounded).monospacedDigit())
                .accessibilityLabel(Text(verbatim: code.filter(\.isNumber).map(String.init).joined(separator: " ")))
                .accessibilityIdentifier("devices.confirmationCode")
            Text("This makes sure no one else is in between. Different codes: answer No and start again.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            HStack(spacing: 12) {
                Button { answer(false) } label: {
                    ActionLabel("No", systemImage: "xmark")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("action.pair.reject")
                Button { answer(true) } label: {
                    ActionLabel("They Match", systemImage: "checkmark")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("action.pair.accept")
            }
            .controlSize(.large)
        }
        .padding(28)
        .frame(maxWidth: 460)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct StatusView: View {
    let systemImage: String
    let title: Text
    let message: Text
    let showsProgress: Bool

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 48))
                .foregroundStyle(Color.accentColor)
                .symbolEffect(.pulse, isActive: showsProgress)
                .accessibilityHidden(true)
            title.font(.title3.weight(.semibold))
            message
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            if showsProgress { ProgressView() }
        }
        .padding(28)
        .frame(maxWidth: 460)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - QR scanner

#if os(iOS)
/// Full-screen camera that reads one Encave QR code.
struct QRScannerView: View {
    let onScan: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    static var isSupported: Bool { DataScannerViewController.isSupported && DataScannerViewController.isAvailable }

    var body: some View {
        ZStack(alignment: .top) {
            QRScanner(onScan: onScan)
                .ignoresSafeArea()
            HStack {
                Text("Point the camera at the code on the other device")
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule())
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.headline)
                        .padding(12)
                        .background(.regularMaterial, in: Circle())
                }
                .accessibilityLabel(Text("Cancel"))
                .accessibilityIdentifier("sheet.cancel")
            }
            .padding()
        }
    }
}

private struct QRScanner: UIViewControllerRepresentable {
    let onScan: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])],
                                                qualityLevel: .balanced, isHighlightingEnabled: true)
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }

    @MainActor
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScan: (String) -> Void
        private var done = false

        init(onScan: @escaping (String) -> Void) { self.onScan = onScan }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in addedItems {
                guard !done, case .barcode(let code) = item, let payload = code.payloadStringValue,
                      PairingCode.parse(qr: payload) != nil else { continue }
                done = true
                onScan(payload)
            }
        }
    }
}
#endif
