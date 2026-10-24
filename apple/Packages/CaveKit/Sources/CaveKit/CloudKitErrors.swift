#if canImport(CloudKit)
import CloudKit

/// What a CloudKit error means for remote sync (`CloudKitMailboxStore` in the app).
public enum CloudKitErrors {
    public static func map(_ error: Error) -> RemoteSyncError {
        guard let error = error as? CKError else { return .unavailable(error.localizedDescription) }
        switch error.code {
        case .notAuthenticated, .permissionFailure: return .unauthorized
        case .quotaExceeded: return .quotaExceeded
        // A record over the request limit: that file is skipped, the others still sync.
        case .limitExceeded: return .fileTooLarge
        default: return .unavailable(error.localizedDescription)
        }
    }
}
#endif
