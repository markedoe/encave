import SwiftUI
#if os(macOS)
import AppKit
#endif

extension View {
    /// Gives sheets a comfortable size on the Mac; no effect on iPhone and iPad.
    /// The size is an ideal, capped by the window, so a sheet in a small window still
    /// shows its buttons and scrolls its content.
    func macSheetFrame(width: CGFloat = 460, height: CGFloat = 620) -> some View {
        #if os(macOS)
        // Sheets never become the main window: this is the window the sheet attaches to.
        let room = NSApp.mainWindow?.contentLayoutRect.size
        let idealWidth = min(width, max(360, (room?.width ?? width) - 40))
        let idealHeight = min(height, max(360, (room?.height ?? height) - 24))
        return frame(minWidth: min(width, 360), idealWidth: idealWidth,
                     minHeight: 360, idealHeight: idealHeight)
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
    /// Digits only (a code), with no decimal point.
    func digitsKeyboard() -> some View {
        #if os(iOS)
        return keyboardType(.numberPad).textContentType(.oneTimeCode)
        #else
        return self
        #endif
    }

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

    /// iPad pointer: a subtle highlight under the pointer, shaped like the item. The Mac
    /// shows none on these, as in its own apps (List rows highlight natively on iPad).
    func pointerHighlight<S: Shape>(_ shape: S) -> some View {
        #if os(iOS)
        return contentShape(.hoverEffect, shape).hoverEffect(.highlight)
        #else
        return self
        #endif
    }

    /// Half-height sheets are see-through on iOS 26: give forms an opaque background.
    /// Off: the system's own sheet background, for a full-height list with a search field.
    func solidSheetBackground(_ isOn: Bool = true) -> some View {
        #if os(iOS)
        return presentationBackground(isOn ? AnyShapeStyle(Color(uiColor: .systemGroupedBackground)) : AnyShapeStyle(.background))
        #else
        return self
        #endif
    }
}
