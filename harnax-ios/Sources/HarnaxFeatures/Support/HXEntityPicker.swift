import SwiftUI
import HarnaxCore
import HarnaxKit

/// One candidate a picker offers. Every domain's row type maps into this, because the picker's job is
/// choosing and not knowing what it is a choice of.
///
/// `subtitle` carries whatever the row has to say under its name — a description, a package version, a
/// repository, an environment variable's display value. It is server text and renders verbatim.
public struct HXEntityPickerOption: Identifiable, Equatable, Sendable {
    public let id: Int64
    public let title: String
    public let subtitle: String?
    /// `true` when `subtitle` is a mask the server printed rather than the value itself. The lock is the
    /// only difference in how the row reads, and it matters because an operator who cannot see the value
    /// must still be able to tell that what is on screen is not it.
    public let isSecret: Bool

    public init(id: Int64, title: String, subtitle: String? = nil, isSecret: Bool = false) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.isSecret = isSecret
    }
}

/// A single-select, searchable picker sheet — the wizard's answer to the web form's four dropdowns.
///
/// Web: the tool, MCP, skill and CLI pickers are `Select`s with no `showSearch` at all, and their candidate
/// set is one page of 100 (`harnax-webui/src/pages/agent/components/CreateForm.tsx:81`-`:127`). Searching is
/// the iOS improvement the spec asks for (`specs/01-agent-team.md`:「iOS 加搜索是改进」, 注意点 4), the
/// truncation is not something iOS gets to fix — it is what the endpoint answers.
///
/// What is *not* an improvement to give up: `excludedIDs`. The web drops from every dropdown the entity
/// another row has already taken (`ToolConfigPanel.tsx:37`-`:41`, `McpConfigPanel.tsx:110`-`:112`,
/// `SkillConfigPanel.tsx:71`-`:74`, `CliConfigPanel.tsx:93`-`:95`), because the backend de-duplicates after
/// ordering — `distinctBy { it.toolId / it.mcpId / it.cliId }` (`AgentServiceImpl.kt:432`, `:469`, `:594`) —
/// so a second row holding the same id silently loses the value typed into it. Hiding the choice is the only
/// client-side way to keep that from happening, which is why the rows are filtered out rather than merely
/// dimmed: the caller still owns the *set*, and the caller's own validator is what stops a duplicate that
/// came back from an edit instead.
public struct HXEntityPicker: View {
    private let titleKey: String
    private let searchKey: String
    private let emptyKey: String?
    private let options: [HXEntityPickerOption]
    private let selectedID: Int64?
    private let excludedIDs: Set<Int64>
    private let onPick: (HXEntityPickerOption) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    /// - Parameters:
    ///   - selectedID: the row this control already holds. It stays listed and gets the checkmark, even
    ///     though the *other* rows' pickers exclude it — re-picking the same entity is a no-op, not an error.
    ///   - excludedIDs: ids another row has taken. They are not offered at all.
    ///   - onPick: called once, with the chosen option, before the sheet closes.
    public init(
        titleKey: String,
        searchKey: String,
        emptyKey: String? = nil,
        options: [HXEntityPickerOption],
        selectedID: Int64? = nil,
        excludedIDs: Set<Int64> = [],
        onPick: @escaping (HXEntityPickerOption) -> Void
    ) {
        self.titleKey = titleKey
        self.searchKey = searchKey
        self.emptyKey = emptyKey
        self.options = options
        self.selectedID = selectedID
        self.excludedIDs = excludedIDs
        self.onPick = onPick
    }

    public var body: some View {
        NavigationStack {
            List {
                ForEach(Array(visible.enumerated()), id: \.element.id) { index, option in
                    row(option, isLast: index == visible.count - 1)
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden)
            .overlay {
                // A picker with nothing to offer says why: "all taken" and "no such search" are different
                // news to someone deciding whether to add another row first.
                if visible.isEmpty {
                    HXStateView(.empty, message: hx(emptyKey ?? "state.empty.title"))
                }
            }
            .navigationTitle(Text(verbatim: hx(titleKey)))
            .searchable(text: $query, prompt: Text(verbatim: hx(searchKey)))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        HXText("common.cancel")
                    }
                }
            }
            .harnaxScreen()
        }
    }

    /// Exclusion first, then the search: a row another line owns must not resurface because its name
    /// happens to match the query.
    private var visible: [HXEntityPickerOption] {
        let choices = options.filter { !excludedIDs.contains($0.id) }
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else { return choices }
        return choices.filter { option in
            option.title.localizedCaseInsensitiveContains(needle)
                || (option.subtitle?.localizedCaseInsensitiveContains(needle) ?? false)
        }
    }

    private func row(_ option: HXEntityPickerOption, isLast: Bool) -> some View {
        Button {
            onPick(option)
            dismiss()
        } label: {
            HXRow(
                text: option.title,
                subtitle: option.subtitle,
                divider: !isLast
            ) {
                HStack(spacing: 8) {
                    // The mask is only ever a label, so it reads as one: locked, secondary, monospaced.
                    if option.isSecret {
                        Image(systemName: "lock.fill")
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }
                    if option.id == selectedID {
                        Image(systemName: "checkmark")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.hx(.brand))
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }
}
