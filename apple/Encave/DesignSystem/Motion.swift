import SwiftUI

/// Reduce Motion: slides become a fade and springs, zooms and moves happen at once.
extension View {
    /// `transition`, or a fade when Reduce Motion is on.
    func motionTransition(_ transition: AnyTransition) -> some View {
        modifier(MotionTransition(transition: transition))
    }

    /// `animation` when `value` changes, or `reduced` (none by default) when Reduce Motion is on.
    func motionAnimation<V: Equatable>(_ animation: Animation?, reduced: Animation? = nil, value: V) -> some View {
        modifier(MotionAnimation(animation: animation, reduced: reduced, value: value))
    }
}

/// `withAnimation`, with a fade instead of `animation` when Reduce Motion is on, for
/// changes shown with `motionTransition`.
@MainActor @discardableResult
func withMotion<Result>(_ animation: Animation? = .default, reduceMotion: Bool,
                        _ body: () throws -> Result) rethrows -> Result {
    try withAnimation(reduceMotion ? .easeInOut(duration: 0.2) : animation, body)
}

private struct MotionTransition: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let transition: AnyTransition

    func body(content: Content) -> some View {
        content.transition(reduceMotion ? .opacity : transition)
    }
}

private struct MotionAnimation<V: Equatable>: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let animation: Animation?
    let reduced: Animation?
    let value: V

    func body(content: Content) -> some View {
        content.animation(reduceMotion ? reduced : animation, value: value)
    }
}
