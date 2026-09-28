import SwiftUI
import HarnaxCore
import HarnaxKit

/// The team drill-down: the lead's skills and the member agents, both of which the list row already carries.
///
/// A broken reference stays on screen and says what is wrong with it. Hiding the row would leave the
/// operator wondering where a member went
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamResponse.kt:49-84`).
enum TeamBindingsPresenter {
    static func sections(for team: TeamSummary) -> [HXBindingSection] {
        var sections: [HXBindingSection] = []
        if !team.memberList.isEmpty {
            sections.append(HXBindingSection(
                titleKey: "team.section.members",
                rows: team.memberList.map { member in
                    HXBindingRow(
                        title: member.displayName ?? fallback("team.binding.memberFallback", member.agentId),
                        subtitle: member.detail,
                        badges: member.isUnavailable ? [hx("team.binding.unavailable")] : []
                    )
                }
            ))
        }
        if !team.skillList.isEmpty {
            sections.append(HXBindingSection(
                titleKey: "team.section.skills",
                rows: team.skillList.map { skill in
                    var marks: [String] = []
                    if let repository = skill.repository { marks.append(repository) }
                    if skill.isUnavailable { marks.append(hx("team.binding.unavailable")) }
                    return HXBindingRow(
                        title: skill.displayName ?? fallback("team.binding.skillFallback", skill.skillId),
                        subtitle: skill.detail,
                        badges: marks
                    )
                }
            ))
        }
        return sections
    }

    /// The console prints `#<id>` when the name column is blank
    /// (`harnax-webui/src/pages/team/index.tsx:231`).
    private static func fallback(_ key: String, _ id: Int64) -> String { hx(key, Int(id)) }
}

/// C2 drill-down — members and lead skills, with the counts the card shows resolved to rows.
public struct TeamBindingsSheet: View {
    private let team: TeamSummary

    public init(team: TeamSummary) {
        self.team = team
    }

    public var body: some View {
        HXBindingSheet(title: team.title ?? "") {
            HXBindingListView(sections: TeamBindingsPresenter.sections(for: team))
        }
    }
}
