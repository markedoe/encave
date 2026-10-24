import CaveKit
import XCTest

final class RequestTokenTests: XCTestCase {
    /// Scan Again while the AI reads label A: A's answer is stale, B's is current.
    func testAnswerToAnEarlierRequestIsStale() {
        var token = RequestToken()
        let a = token.next()
        XCTAssertTrue(token.isCurrent(a))
        let b = token.next()
        XCTAssertFalse(token.isCurrent(a))
        XCTAssertTrue(token.isCurrent(b))
    }

    /// Removing the photo (a new token without a request) makes the pending answer stale.
    func testCancellingWithoutANewRequest() {
        var token = RequestToken()
        let a = token.next()
        token.next()
        XCTAssertFalse(token.isCurrent(a))
    }
}
