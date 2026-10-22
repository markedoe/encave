import SwiftUI

/// The libraries in the app and their licences, from Licences.json (apple/tools/licences.py).
struct Acknowledgements: Decodable {
    struct Dependency: Decodable, Identifiable {
        let name: String
        let version: String
        let url: String
        let licence: String
        let copyright: String
        var id: String { name }
    }

    struct Licence: Decodable {
        let name: String
        let text: String
    }

    let dependencies: [Dependency]
    let licences: [String: Licence]

    static func load() -> Acknowledgements? {
        guard let url = Bundle.main.url(forResource: "Licences", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Acknowledgements.self, from: data)
    }
}

/// Settings › About › Licences: Encave's licence, then each library with its licence.
struct LicencesView: View {
    private let acknowledgements = Acknowledgements.load()

    var body: some View {
        List {
            Section {
                Text("Encave is free software: you can redistribute it and modify it under the terms of the GNU Affero General Public License, version 3, as published by the Free Software Foundation. It comes with ABSOLUTELY NO WARRANTY, to the extent permitted by law.")
                Text("Copyright © 2026 the Encave contributors")
                    .foregroundStyle(.secondary)
                if let agpl = acknowledgements?.licences["AGPL-3.0"] {
                    NavigationLink {
                        LicenceTextView(title: agpl.name, copyright: nil, text: agpl.text)
                    } label: {
                        Text(verbatim: agpl.name)
                    }
                }
                Link(destination: ProjectLinks.source) {
                    Label("Source code", systemImage: "chevron.left.forwardslash.chevron.right")
                }
            } header: {
                Text(verbatim: "Encave")
            }

            if let acknowledgements {
                Section {
                    ForEach(acknowledgements.dependencies) { dependency in
                        let licence = acknowledgements.licences[dependency.licence]
                        NavigationLink {
                            LicenceTextView(title: dependency.name, copyright: dependency.copyright,
                                            text: licence?.text ?? dependency.licence)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: dependency.name)
                                Text(verbatim: "\(dependency.version) · \(licence?.name ?? dependency.licence)")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("Libraries")
                } footer: {
                    Text("Open-source libraries included in Encave, each under its own licence.")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Licences")
        .inlineTitle()
    }
}

/// The full text of one licence.
private struct LicenceTextView: View {
    let title: String
    let copyright: String?
    let text: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let copyright {
                    Text(verbatim: copyright).font(.subheadline)
                }
                Text(verbatim: text)
                    .font(.footnote.monospaced())
                    .textSelection(.enabled)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .navigationTitle(Text(verbatim: title))
        .inlineTitle()
    }
}
