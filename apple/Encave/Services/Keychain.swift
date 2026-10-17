import Foundation
import Security

/// Stores secrets (API keys, this device's identity key) in the Keychain, on this device only.
enum Keychain {
    private static let service = "app.encave.secrets"

    static func set(_ value: String?, for account: String) {
        setData(value.flatMap { $0.isEmpty ? nil : Data($0.utf8) }, for: account)
    }

    static func get(_ account: String) -> String? {
        getData(account).flatMap { String(data: $0, encoding: .utf8) }
    }

    /// Stores raw bytes (a private key). Nil deletes.
    static func setData(_ value: Data?, for account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: true,
        ]
        SecItemDelete(query as CFDictionary)
        guard let value, !value.isEmpty else { return }
        var item = query
        item[kSecValueData as String] = value
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        assert(status == errSecSuccess, "Keychain write failed: \(status)")
    }

    static func getData(_ account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: true,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return data
    }
}
