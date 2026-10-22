import CaveKit
import CoreImage.CIFilterBuiltins
import SwiftUI
#if os(iOS)
import VisionKit
#endif

/// Settings › Devices: the devices that share this cellar, and adding one.
struct DevicesView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @State private var inviting = false
    @State private var joining = false
    @State private var removing: Member?
    @State private var confirmingLeave = false
    @State private var notifiesChanges = ChangeNotifications.isEnabled
    @State private var notificationsRefused = false

    var body: some View {
        let sync = model.sync
        Form {
            // A failed session is shown with the status below instead.
            if let problem = sync.problem, sync.localFailedAt == nil {
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

            if !sync.otherDevices.isEmpty {
                SyncStatusSection(sync: sync)
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
                        sync.syncEverywhereNow()
                    } label: {
                        Label("Sync Now", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .disabled((sync.nearby.isEmpty && sync.remoteStorage == nil) || !sync.syncing.isEmpty
                              || sync.isSyncingRemotely)
                    .accessibilityIdentifier("devices.syncNow")
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
                            case .webDAV: Text(verbatim: "WebDAV")
                            case .relay: Text("Relay")
                            }
                        } label: {
                            Label("Sync Away from Home", systemImage: "globe")
                        }
                    }
                    .accessibilityIdentifier("devices.remote")
                    if sync.needsWebDAVSignIn {
                        // The folder came from another device (this one needs its own login), or
                        // the server refused the login.
                        NavigationLink {
                            RemoteSyncView()
                        } label: {
                            Label("Sign in to this WebDAV folder", systemImage: "person.badge.key")
                                .foregroundStyle(.orange)
                        }
                        .accessibilityIdentifier("devices.webdavSignIn")
                    }
                } footer: {
                    Text("Keeps devices in sync when they are not on the same Wi-Fi, through storage that only holds encrypted data: iCloud, a WebDAV folder or a relay.")
                }

                Section {
                    Toggle(isOn: notificationsBinding) {
                        Label("Tell Me About Changes from Other Devices", systemImage: "bell.badge")
                    }
                    .accessibilityIdentifier("devices.notifyChanges")
                } footer: {
                    if notificationsRefused {
                        Text("Notifications are off for Encave. Turn them on in the system settings.")
                    } else {
                        Text("A notification on this device when bottles are opened, added or removed on another device. Nothing is sent anywhere.")
                    }
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
        .onChange(of: scenePhase) { _, phase in
            // Back from the system settings: drop the message once notifications are allowed.
            guard phase == .active, notificationsRefused else { return }
            Task { if await Reminders.notificationsAllowed() { notificationsRefused = false } }
        }
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

    /// Asks for permission when turned on, as for reminders.
    private var notificationsBinding: Binding<Bool> {
        Binding(get: { notifiesChanges }, set: { on in
            notifiesChanges = on
            if on {
                Task {
                    let granted = await ChangeNotifications.enable()
                    notifiesChanges = granted
                    notificationsRefused = !granted
                    if !granted { Reminders.announceRefused() }
                }
            } else {
                ChangeNotifications.disable()
                notificationsRefused = false
            }
        })
    }

    private func status(of member: Member, _ sync: SyncService) -> Text {
        if sync.syncing.contains(member.id) { return Text("Syncing…") }
        if let date = sync.lastSync[member.id] {
            return Text("Synced \(date.formatted(.relative(presentation: .named)))")
        }
        if sync.nearby.contains(member.id) { return Text("On this network") }
        return Text("Not synced yet")
    }
}

/// Where sync stands, on the same Wi-Fi and away from home: what is happening now,
/// the last success or failure, changes not sent yet and the next automatic sync.
/// Said in words and symbols, never by color alone.
private struct SyncStatusSection: View {
    let sync: SyncService

    var body: some View {
        Section {
            LabeledContent("On the same Wi-Fi") {
                if !sync.syncing.isEmpty {
                    SyncingLabel()
                } else if let date = sync.lastSync.values.max() {
                    Ticking { Text("Synced \(date.formatted(.relative(presentation: .named)))") }
                } else {
                    Text("Not synced yet")
                }
            }
            if let failureLabel = sync.problem, let date = sync.localFailedAt {
                FailureRow(failureLabel: failureLabel, date: date)
            } else if sync.nearby.isEmpty, sync.syncing.isEmpty {
                Label("No device found on this Wi-Fi", systemImage: "wifi.exclamationmark")
                    .foregroundStyle(.secondary)
            }

            if sync.remoteStorage != nil {
                LabeledContent("Away from home") {
                    if sync.needsWebDAVSignIn {
                        Text("Sign in to this WebDAV folder")
                    } else if sync.isSyncingRemotely {
                        SyncingLabel()
                    } else if let date = sync.lastRemoteSync {
                        Ticking { Text("Synced \(date.formatted(.relative(presentation: .named)))") }
                    } else {
                        Text("Not synced yet")
                    }
                }
                if let failureLabel = sync.remoteProblem, let date = sync.remoteFailedAt {
                    FailureRow(failureLabel: failureLabel, date: date)
                } else if let problem = sync.remoteProblem {
                    Label(problem, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
                if sync.hasUnsentChanges {
                    Label("Changes on this device not sent yet", systemImage: "arrow.up.circle")
                }
                if sync.remoteFrequency.interval == nil {
                    Label("Only when you tap Sync Now", systemImage: "hand.tap")
                        .foregroundStyle(.secondary)
                } else {
                    if let next = sync.nextRemoteRound, !sync.isSyncingRemotely {
                        Ticking {
                            Label {
                                let durationLabel = Self.minutes(until: next)
                                Text("Next sync in about \(durationLabel)")
                            } icon: {
                                Image(systemName: "clock")
                            }
                            .foregroundStyle(.secondary)
                        }
                    }
                    #if os(iOS)
                    Label("In the background, when iOS allows", systemImage: "moon.zzz")
                        .foregroundStyle(.secondary)
                    #endif
                }
            }
        } header: {
            Text("Status")
        }
    }

    /// "4 minutes": rounded up, one minute at least.
    private static func minutes(until date: Date) -> String {
        let minutes = max(1, Int((date.timeIntervalSinceNow / 60).rounded(.up)))
        return Duration.seconds(minutes * 60).formatted(.units(allowed: [.minutes], width: .wide))
    }
}

/// Refreshed now and then, for "3 minutes ago" and "in about 4 minutes".
private struct Ticking<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        TimelineView(.periodic(from: .now, by: 15)) { _ in content() }
    }
}

private struct SyncingLabel: View {
    var body: some View {
        HStack(spacing: 6) {
            ProgressView()
                .controlSize(.small)
                .accessibilityHidden(true)
            Text("Syncing…")
        }
    }
}

/// "Last attempt failed: The relay could not be reached." and when.
private struct FailureRow: View {
    let failureLabel: String
    let date: Date

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text("Last attempt failed: \(failureLabel)")
                Ticking {
                    Text(date.formatted(.relative(presentation: .named)))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } icon: {
            Image(systemName: "exclamationmark.triangle")
                .foregroundStyle(.orange)
        }
        .accessibilityElement(children: .combine)
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

/// Syncing away from home: through the user's iCloud, a WebDAV folder or a relay; and what each can see.
struct RemoteSyncView: View {
    @Environment(CellarModel.self) private var model
    @State private var address = ""
    @State private var webDAVAddress = ""
    @State private var username = ""
    @State private var password = ""
    @State private var changingLogin = false
    @State private var checking = false
    @State private var error: String?

    var body: some View {
        let sync = model.sync
        Form {
            if let storage = sync.remoteStorage {
                Section {
                    switch storage {
                    case .iCloud:
                        LabeledContent("Storage") { Text(verbatim: "iCloud") }
                    case .webDAV(let url):
                        LabeledContent("WebDAV folder") { Text(verbatim: url.host() ?? url.absoluteString) }
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
                    Text("Your other devices that do not sync away from home yet receive this setting the next time they sync on the same Wi-Fi. Turning it off, or choosing another storage, applies to this device only: do the same on the others.")
                }
                Section {
                    Picker("How often", selection: Binding(get: { sync.remoteFrequency },
                                                           set: { sync.setRemoteFrequency($0) })) {
                        Text("Every minute").tag(RemoteSyncFrequency.everyMinute)
                        Text("Every 5 minutes").tag(RemoteSyncFrequency.everyFiveMinutes)
                        Text("Every 15 minutes").tag(RemoteSyncFrequency.everyFifteenMinutes)
                        Text("Only when I tap Sync Now").tag(RemoteSyncFrequency.manual)
                    }
                    .accessibilityIdentifier("remote.frequency")
                } footer: {
                    Text("On the same Wi-Fi, changes are sent a few seconds after you make them. This setting is for this device only.")
                }
                if case .webDAV = storage {
                    if sync.needsWebDAVSignIn || changingLogin {
                        Section {
                            loginFields
                            Button {
                                signIn()
                            } label: {
                                if checking { ProgressView() } else { Text("Check and Sign In") }
                            }
                            .disabled(checking || trimmedUsername.isEmpty || password.isEmpty)
                            .accessibilityIdentifier("remote.webdavSignIn")
                        } header: {
                            Text("Sign in to this WebDAV folder")
                        } footer: {
                            Text("Each device signs in with its own username and password, kept in the Keychain on that device. An app password is recommended.")
                        }
                    } else {
                        Section {
                            Button("Change Username or Password") { changingLogin = true }
                        }
                    }
                    if let error {
                        Section {
                            Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                        }
                    }
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
                    TextField("Server address", text: $webDAVAddress, prompt: Text(verbatim: "https://"))
                        .urlField()
                        .accessibilityIdentifier("remote.webdavAddress")
                    loginFields
                    Button {
                        if let url = WebDAVMailboxStore.folderURL(webDAVAddress) {
                            turnOn(.webDAV(url), login: WebDAVLogin(folder: url.absoluteString, username: trimmedUsername,
                                                                     password: password))
                        }
                    } label: {
                        if checking { ProgressView() } else { Text("Check and Turn On") }
                    }
                    .disabled(checking || WebDAVMailboxStore.folderURL(webDAVAddress) == nil || trimmedUsername.isEmpty
                              || password.isEmpty)
                    .accessibilityIdentifier("remote.webdavTurnOn")
                } header: {
                    Text("WebDAV folder (NAS, Nextcloud, pCloud…)")
                } footer: {
                    Text("Storage you already have, for every device. Only encrypted files are stored in this folder. Use an app password if the server offers one; it stays in the Keychain on this device. For example: the WebDAV address of a Synology or QNAP NAS, https://…/remote.php/dav/files/username/Encave for Nextcloud, https://webdav.pcloud.com/Encave for pCloud.")
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
                Label("Only encrypted files are stored. Neither Apple, the WebDAV server nor a relay can see your wines, photos or device names.",
                      systemImage: "lock.shield")
                Label("The storage sees when devices sync, how much they send and, for a WebDAV server or a relay, their IP addresses.",
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

    private var trimmedUsername: String { username.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Username and password, for turning on a WebDAV folder or signing in to one.
    @ViewBuilder private var loginFields: some View {
        TextField("Username", text: $username)
            .plainInput()
            .textContentType(.username)
            .accessibilityIdentifier("remote.webdavUsername")
        SecureField("Password", text: $password)
            .plainInput()
            .textContentType(.password)
            .fieldHelp("An app password created on the server is recommended. Stored in the Keychain on this device.")
            .accessibilityIdentifier("remote.webdavPassword")
    }

    private func turnOn(_ storage: RemoteStorage, login: WebDAVLogin? = nil) {
        checking = true
        error = nil
        Task {
            do {
                try await model.sync.setRemoteStorage(storage, login: login)
                password = ""
            } catch {
                switch storage {
                case .iCloud: self.error = String(localized: "iCloud is not available. Check that you are signed in to iCloud.")
                case .webDAV: self.error = Self.webDAVMessage(error)
                case .relay: self.error = String(localized: "This address does not answer as an Encave relay.")
                }
            }
            checking = false
        }
    }

    private func signIn() {
        checking = true
        error = nil
        Task {
            do {
                try await model.sync.signInToWebDAV(username: trimmedUsername, password: password)
                password = ""
                changingLogin = false
            } catch {
                self.error = Self.webDAVMessage(error)
            }
            checking = false
        }
    }

    /// What went wrong when checking a WebDAV folder.
    static func webDAVMessage(_ error: Error) -> String {
        switch error as? RemoteSyncError {
        case .unauthorized:
            String(localized: "The server refused this username or password. With two-step verification, use an app password.")
        case .quotaExceeded:
            String(localized: "The WebDAV folder is full. Free some space on the server.")
        default:
            String(localized: "This address does not answer as a WebDAV folder. Check the address and that WebDAV is turned on on the server.")
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
                    } else if model.cellar.containsSample {
                        Text("Both cellars are put together. The sample cellar is removed first.")
                    } else {
                        Text("Both cellars are put together.")
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
