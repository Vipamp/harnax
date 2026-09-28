import SwiftUI
import HarnaxCore
import HarnaxKit

/// The skill domain's page: sources on the left, their skills on the right, one detail stack behind them.
///
/// The Web page is a 6/18 column split (`harnax-webui/src/pages/skill/index.tsx:183,220`); on iOS that maps
/// to `NavigationSplitView`, which collapses to a push stack at compact width on its own. Selecting a source
/// rebuilds the right column from scratch, so a keyword, a scrolled offset and a half-loaded page tail from
/// the previous source cannot follow it across.
public struct SkillHomeView: View {
    private let skills: any SkillCataloging
    @State private var selection: Int64?

    public init(skills: any SkillCataloging) {
        self.skills = skills
    }

    public var body: some View {
        NavigationSplitView {
            SkillSourceListView(skills: skills, onSelect: { selection = $0 })
                .navigationSplitViewColumnWidth(min: 280, ideal: 320)
        } detail: {
            NavigationStack {
                // `.id` is the reset: the table's state object is thrown away with the source it belonged to.
                SkillTableView(sourceID: selection, skills: skills)
                    .id(selection)
            }
        }
        .harnaxThemed()
    }
}
