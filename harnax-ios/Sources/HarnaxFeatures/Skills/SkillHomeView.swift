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
    @State private var openSource: SkillSourceRoute?

    public init(skills: any SkillCataloging) {
        self.skills = skills
    }

    public var body: some View {
        SkillSourceListView(skills: skills, onSelect: { openSource = $0.map(SkillSourceRoute.init) })
            .navigationDestination(item: $openSource) { route in
                SkillTableView(sourceID: route.id, skills: skills)
            }
    }
}

/// Its own type rather than the bare id: `SkillTableView` already registers an `Int64` destination for the
/// skill detail one level deeper, and one stack may only carry one destination per type.
private struct SkillSourceRoute: Identifiable, Hashable {
    let id: Int64
}
