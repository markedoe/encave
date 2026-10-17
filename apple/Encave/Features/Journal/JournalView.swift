import CaveKit
import SwiftUI

/// Bottles you have opened, most recent first.
struct JournalView: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        NavigationStack {
            let bottles = model.cellar.consumedBottles
            Group {
                if bottles.isEmpty {
                    ContentUnavailableView(
                        "No tastings yet",
                        systemImage: "book.closed",
                        description: Text("Bottles you open and your notes will appear here.")
                    )
                } else {
                    List(bottles) { bottle in
                        JournalRow(bottle: bottle, wine: model.cellar.wines[bottle.wineID])
                    }
                }
            }
            .navigationTitle("Journal")
        }
    }
}

struct JournalRow: View {
    let bottle: Bottle
    let wine: Wine?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                WineColorDot(color: wine?.color)
                Text(wine?.displayName ?? String(localized: "Unknown wine"))
                    .font(.headline)
                Spacer()
                if let date = bottle.tasting?.date {
                    Text(date, format: .dateTime.day().month(.abbreviated).year())
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            if let rating = bottle.tasting?.rating {
                RatingView(rating: rating)
            }
            if let note = bottle.tasting?.note, !note.isEmpty {
                Text(note)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

struct RatingView: View {
    let rating: Int

    var body: some View {
        HStack(spacing: 2) {
            ForEach(1...5, id: \.self) { index in
                Image(systemName: index <= rating ? "star.fill" : "star")
            }
        }
        .font(.caption)
        .foregroundStyle(.tint)
        .accessibilityElement()
        .accessibilityLabel(Text("Rated \(rating) out of 5"))
    }
}
