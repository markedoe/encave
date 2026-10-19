import Foundation
import Security

/// Stores secrets (API keys, this device's identity key) in the Keychain, on this device only.
enum Keychain {
    private static let service = "app.encave.secrets"

    /// What a read found. A failure (device locked, Keychain unavailable) is not
    /// "missing": callers must not replace a key they could not read.
    enum Read {
        case found(Data)
        case missing
        case failed(OSStatus)
    }

    static func set(_ value: String?, for account: String) {
        setData(value.flatMap { $0.isEmpty ? nil : Data($0.utf8) }, for: account)
    }

    static func get(_ account: String) -> String? {
        getData(account).flatMap { String(data: $0, encoding: .utf8) }
    }

    private static func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }

    /// Stores raw bytes (a private key). Nil deletes. Returns whether it was stored.
    /// Readable after the first unlock, so sync and Siri also work while the device is locked.
    @discardableResult
    static func setData(_ value: Data?, for account: String) -> Bool {
        let query = query(account)
        guard let value, !value.isEmpty else {
            let status = SecItemDelete(query as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        let attributes: [String: Any] = [
            kSecValueData as String: value,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        // Updated in place: the old value stays if the new one cannot be written.
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
        // A failed write (device locked, a build without Keychain access) is reported to the
        // caller; never a crash, even in Debug.
        if status != errSecSuccess { print("Keychain write failed: \(status)") }
        return status == errSecSuccess
    }

    static func read(_ account: String) -> Read {
        var query = query(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data else { return .failed(errSecDecode) }
            return .found(data)
        case errSecItemNotFound:
            return .missing
        default:
            return .failed(status)
        }
    }

    static func getData(_ account: String) -> Data? {
        if case .found(let data) = read(account) { return data }
        return nil
    }
}
