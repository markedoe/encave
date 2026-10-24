#if canImport(CloudKit)
import CloudKit
import Testing
@testable import CaveKit

/// Runs where CloudKit exists (the macOS model tests), not on Linux.
@Suite struct CloudKitErrorsTests {
    @Test func errorsMapToRemoteSyncErrors() {
        #expect(CloudKitErrors.map(CKError(.notAuthenticated)) == .unauthorized)
        #expect(CloudKitErrors.map(CKError(.permissionFailure)) == .unauthorized)
        #expect(CloudKitErrors.map(CKError(.quotaExceeded)) == .quotaExceeded)
        // One oversized file is skipped instead of stopping the sync.
        #expect(CloudKitErrors.map(CKError(.limitExceeded)) == .fileTooLarge)
        if case .unavailable = CloudKitErrors.map(CKError(.networkFailure)) {} else {
            Issue.record("a network failure is temporary")
        }
        if case .unavailable = CloudKitErrors.map(CocoaError(.fileReadUnknown)) {} else {
            Issue.record("not a CloudKit error")
        }
    }
}
#endif
