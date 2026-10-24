import Testing
@testable import CaveKit

@Suite struct MaturityTimelineTests {
    /// "Today" sits under the marker, not halfway between the first and last years.
    @Test func todayLabelFollowsTheMarker() {
        let timeline = MaturityTimeline(from: 2024, until: 2040, year: 2026)
        let width = 300.0
        let center = timeline.marker * width
        let origin = MaturityTimeline.labelOrigin(center: center, labelWidth: 40, width: width,
                                                  leadingReserved: 34, trailingReserved: 34)
        #expect(abs(origin + 20 - center) < 0.001)
        #expect(origin + 20 < width / 2 - 50)
    }

    /// Near an edge, the label stays clear of the year labels and inside the gauge.
    @Test func todayLabelIsClamped() {
        let timeline = MaturityTimeline(from: 2010, until: 2020, year: 2026)
        let width = 300.0
        let origin = MaturityTimeline.labelOrigin(center: timeline.marker * width, labelWidth: 40, width: width,
                                                  leadingReserved: 34, trailingReserved: 34)
        #expect(origin == width - 34 - 40)
        let tight = MaturityTimeline.labelOrigin(center: 290, labelWidth: 40, width: 60,
                                                 leadingReserved: 34, trailingReserved: 34)
        #expect(tight == 20)
    }

    @Test func markerIsInTheMiddleOfTheYear() {
        let timeline = MaturityTimeline(from: 2026, until: 2026, year: 2026)
        #expect(timeline.lower == 2025 && timeline.upper == 2027)
        #expect(timeline.marker == 0.75)
    }
}
