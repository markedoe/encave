import Foundation

/// Whether sync needs the user, for a sign outside Settings › Devices (the Settings
/// button, the Devices row). Worked out on the device from what sync knows; the Android
/// app follows the same rules.
public enum SyncHealth: Equatable, Sendable {
    case ok
    /// Sync waits for the user: `reason` says what to do in Devices.
    case needsAction(Reason)
    /// Sync keeps failing: no success for an hour, or a problem with the storage away from home.
    case failing

    public enum Reason: Equatable, Sendable {
        /// Another device removed this one.
        case removed
        /// The WebDAV folder has no login on this device.
        case signInNeeded
        /// This device's key could not be read.
        case keyUnavailable
        /// Local network access is off for the app.
        case localNetworkOff
    }

    /// How long failures may last before sync counts as failing.
    public static let failingAfter: TimeInterval = 60 * 60

    /// - Parameters:
    ///   - localFailingSince, remoteFailingSince: the first failure since the last success.
    ///   - remoteProblem: a problem that is not a failed round (files refused, changes that
    ///     cannot be read, no other device in iCloud).
    public static func evaluate(isShared: Bool, removed: Bool, keyUnavailable: Bool, signInNeeded: Bool,
                                localNetworkOff: Bool, localFailingSince: Date?, remoteFailingSince: Date?,
                                remoteProblem: Bool, now: Date = .now) -> SyncHealth {
        if removed { return .needsAction(.removed) }
        guard isShared else { return .ok }
        if keyUnavailable { return .needsAction(.keyUnavailable) }
        if signInNeeded { return .needsAction(.signInNeeded) }
        if localNetworkOff { return .needsAction(.localNetworkOff) }
        let limit = now.addingTimeInterval(-failingAfter)
        if remoteProblem || [localFailingSince, remoteFailingSince].contains(where: { $0.map { $0 <= limit } ?? false }) {
            return .failing
        }
        return .ok
    }
}

/// When to try a device on the network again after a session with it failed: after
/// 15 seconds, then 1 minute, then 5 minutes; no more until it syncs, leaves the
/// network and comes back, or the user taps Sync Now. The Android app does the same.
public enum SyncRetry {
    public static let delays: [TimeInterval] = [15, 60, 300]

    /// The wait after `failures` failed sessions in a row, nil when there is no more retry.
    public static func delay(afterFailures failures: Int) -> TimeInterval? {
        guard failures >= 1, failures <= delays.count else { return nil }
        return delays[failures - 1]
    }
}
