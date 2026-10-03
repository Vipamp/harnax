import SwiftUI
import HarnaxCore
import HarnaxKit

/// The skill domain's page: the source list, and the skills of one source behind it.
///
/// The Web page is a 6/18 column split (`harnax-webui/src/pages/skill/index.tsx:183,220`). On a phone the
/// two columns cannot sit side by side, so the right one pushes: tapping a source replaces the split with
/// the same pair in sequence, and the pushed table belongs to that source alone — a keyword, a scrolled
/// offset and a half-loaded page tail from the previous source cannot follow it across.
public struct SkillHomeView: View {
    private let skills: any SkillCataloging
    private let account: AccountSnapshot?

    public init(skills: any SkillCataloging, account: AccountSnapshot? = nil) {
        self.skills = skills
        self.account = account
    }

    public var body: some View {
        SkillSourceListView(skills: skills, account: account)
            .navigationDestination(for: SkillSourceRoute.self) { route in
                SkillTableView(sourceID: route.id, sourceName: route.name, skills: skills)
            }
    }
}

/// The address of one source's table, carried by the source row's own link.
///
/// Its own type rather than the bare id: `SkillTableView` registers a destination of its own for the skill
/// detail one level deeper, and one stack may only carry one destination per type.
///
/// Pushed by value, not presented by item: a screen presented through `navigationDestination(item:)` is
/// re-presented while its binding still holds a value, and the path change made by the row one level deeper
/// is what re-evaluates the view owning that binding — so tapping a skill used to leave a second copy of this
/// table on top of the detail. `SkillNavigationTests.testNoDomainPresentsAScreenThatPushesDeeper` holds that
/// shape shut.
struct SkillSourceRoute: Hashable {
    let id: Int64
    let name: String?
}
