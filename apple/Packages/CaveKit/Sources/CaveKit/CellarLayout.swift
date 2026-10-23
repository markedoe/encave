import Foundation

/// How the figures at the top of the Cellar are laid out for a text size.
public enum StatTileLayout: Sendable, Equatable {
    /// Side by side tiles, each with a large figure.
    case tiles
    /// A short list, one line a figure, so the tiles do not fill the first screen at the
    /// largest text sizes.
    case compact

    /// From this text scale (1 is the default size) the figures become a short list: the
    /// Android 150 % font scale, and the first accessibility Dynamic Type size on Apple.
    public static let compactScale = 1.5

    public static func of(textScale: Double) -> StatTileLayout {
        textScale >= compactScale ? .compact : .tiles
    }
}

/// The parts of the Add Bottles form, for a new wine.
public enum AddFormSection: String, Sendable, CaseIterable {
    /// Scan the label, or the label photo.
    case label
    /// The wine fields, the name first.
    case wine
    /// A wine of the cellar the name typed matches, and others whose name is close.
    case cellarWines
    /// How many bottles, their size and whether they are here yet.
    case bottles
    /// Where they go.
    case location
    /// Invoices and other documents.
    case documents
}

public enum AddFormLayout {
    /// Adding by hand, without a scan: the wine first (its name the first field), then
    /// its bottles, where they go and their documents; the label photo can still be added
    /// after. With a scan, each app keeps the order it has, the label first.
    public static let byHand: [AddFormSection] = [.wine, .cellarWines, .bottles, .location, .documents, .label]
}
