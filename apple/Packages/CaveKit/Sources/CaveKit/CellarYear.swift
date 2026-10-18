import Foundation

/// Vintages and drinking windows are years of the Gregorian calendar, whatever
/// calendar the user has chosen (Buddhist, Japanese, Hebrew…): "this year" must
/// be counted the same way, or a 2020 wine would look centuries young.
public enum CellarYear {
    /// The Gregorian calendar in the user's time zone.
    public static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        calendar.locale = .current
        return calendar
    }

    /// The Gregorian year of a date.
    public static func of(_ date: Date) -> Int {
        calendar.component(.year, from: date)
    }

    /// This year, in the Gregorian calendar.
    public static var current: Int { of(.now) }
}
