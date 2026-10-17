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

    /// Codes, keys and model names: no capitals or corrections.
    func plainInput() -> some View {
        #if os(iOS)
        return textInputAutocapitalization(.never).autocorrectionDisabled()
        #else
        return autocorrectionDisabled()
        #endif
    }

    /// URL keyboard on iOS.
    func urlField() -> some View {
        #if os(iOS)
        return plainInput().keyboardType(.URL)
        #else
        return plainInput()
        #endif
    }

    /// Explains a field: a tooltip on the Mac, a hint for VoiceOver everywhere.
    func fieldHelp(_ text: LocalizedStringKey) -> some View {
        help(Text(text))
    }

    /// Half-height sheets are see-through on iOS 26: give forms an opaque background.
    func solidSheetBackground() -> some View {
        #if os(iOS)
        return presentationBackground(Color(uiColor: .systemGroupedBackground))
        #else
        return self
        #endif
    }
}
