import SwiftUI

/// The in-app help: a list of topics, each a short article.
struct HelpView: View {
    var body: some View {
        List {
            Section {
                ForEach(HelpArticle.all) { article in
                    NavigationLink(value: article.topic) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(article.title).font(.headline)
                                Text(article.summary).font(.subheadline).foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: article.systemImage).foregroundStyle(Color.accentColor)
                        }
                    }
                }
            } footer: {
                Text("Encave is open source. Questions and ideas are welcome on the project page.")
            }
            Section {
                Link(destination: ProjectLinks.source) {
                    Label("Project page", systemImage: "chevron.left.forwardslash.chevron.right")
                }
            }
        }
        .navigationTitle("Help")
        .navigationDestination(for: HelpTopic.self) { HelpArticleView(topic: $0) }
    }
}

/// One help article.
struct HelpArticleView: View {
    let topic: HelpTopic

    var body: some View {
        if let article = HelpArticle.article(topic) {
            List {
                Section {
                    Label {
                        Text(article.summary).font(.title3)
                    } icon: {
                        Image(systemName: article.systemImage)
                            .font(.title2)
                            .foregroundStyle(Color.accentColor)
                    }
                    .padding(.vertical, 4)
                }
                ForEach(article.sections.indices, id: \.self) { index in
                    let section = article.sections[index]
                    Section {
                        VStack(alignment: .leading, spacing: 6) {
                            if let title = section.title {
                                HStack(spacing: 8) {
                                    if let image = section.systemImage {
                                        Image(systemName: image).foregroundStyle(.secondary)
                                    }
                                    Text(title).font(.headline)
                                }
                            }
                            Text(section.body)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.vertical, 4)
                    }
                }
                if topic == .privacy {
                    Section {
                        Link(destination: ProjectLinks.privacyPolicy) {
                            Label("Privacy Policy", systemImage: "hand.raised")
                        }
                    }
                }
                if topic == .ai {
                    Section("Create an API key") {
                        ForEach(AIProvider.allCases.filter { $0.keyPage != nil }, id: \.self) { provider in
                            Link(destination: provider.keyPage!) {
                                LabeledContent {
                                    Image(systemName: "arrow.up.right.square")
                                } label: {
                                    if provider == .local {
                                        Text("Local server: install Ollama")
                                    } else {
                                        Text(verbatim: provider.name)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(article.title)
            .inlineTitle()
        }
    }
}

/// "?" button that opens the help for the current screen.
struct HelpButton: View {
    let topic: HelpTopic
    @State private var showing = false

    var body: some View {
        Button {
            showing = true
        } label: {
            Label("Help", systemImage: "questionmark.circle")
        }
        .help(Text("Help"))
        .sheet(isPresented: $showing) {
            NavigationStack {
                HelpArticleView(topic: topic)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { showing = false }
                        .accessibilityIdentifier("sheet.done")
                        }
                    }
            }
            .macSheetFrame(width: 520, height: 620)
        }
    }
}

/// A row that opens a help topic, for forms and settings.
struct HelpLink: View {
    let topic: HelpTopic
    let title: LocalizedStringKey
    @State private var showing = false

    var body: some View {
        Button {
            showing = true
        } label: {
            Label(title, systemImage: "questionmark.circle")
        }
        .sheet(isPresented: $showing) {
            NavigationStack {
                HelpArticleView(topic: topic)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { showing = false }
                        .accessibilityIdentifier("sheet.done")
                        }
                    }
            }
            .macSheetFrame(width: 520, height: 620)
        }
    }
}
