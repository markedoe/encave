import SwiftUI

extension View {
    /// Gives sheets a comfortable size on the Mac; no effect on iPhone and iPad.
    func macSheetFrame(width: CGFloat = 460, height: CGFloat = 620) -> some View {
        #if os(macOS)
        return frame(minWidth: width, idealWidth: width, minHeight: height, idealHeight: height)
        #else
        return self
        #endif
    }

    /// Inline navigation titles in sheets on iOS.
    func inlineTitle() -> some View {
        #if os(iOS)
        return navigationBarTitleDisplayMode(.inline)
        #else
        return self
        #endif
    }

    /// Number keyboard on iOS.
    func numericKeyboard() -> some View {
        #if os(iOS)
        return keyboardType(.decimalPad)
        #else
        return self
        #endif
    }
}
