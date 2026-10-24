/// Tells whether the answer to an asynchronous request is still wanted: each new request
/// (a new label photo, a new search) takes a new token, and an answer is used only when
/// its token is still the current one, so a late answer never fills in the wrong thing.
public struct RequestToken: Sendable, Equatable {
    public private(set) var current = 0

    public init() {}

    /// Starts a new request; answers to the earlier ones become stale.
    @discardableResult
    public mutating func next() -> Int {
        current &+= 1
        return current
    }

    public func isCurrent(_ token: Int) -> Bool { token == current }
}
