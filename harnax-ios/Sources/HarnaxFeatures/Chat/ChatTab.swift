import SwiftUI
import HarnaxCore
import HarnaxKit

/// The chat tab: the conversation list, the chat window a row opens, and the review queue the entry row opens.
///
/// Only the destination lives here. The list decides *which* conversation a row addresses (`SessionListView`
/// hands over a `ChatConversation` and refuses the tap for a row with no business key), so this screen has no
/// read of its own and no view model. The queue is answered the same way its rows answer a draft — by value
/// (`SkillDraftQueueRoute`) — because the queue is not a leaf: it pushes a detail one level deeper, and a
/// screen presented by an item or `isPresented` binding is re-pushed by that deeper push's own stack update
/// (`SkillNavigationTests` records the defect from 2026-10-03).
public struct ChatTabView: View {
    private let dependencies: HarnaxDependencies
    @State private var open: ChatConversation?

    public init(dependencies: HarnaxDependencies) {
        self.dependencies = dependencies
    }

    public var body: some View {
        SessionListView(
            sessions: dependencies.sessions,
            creating: dependencies.sessionCreate,
            config: dependencies.sessionConfig,
            workspace: dependencies.workspace,
            teamArtifacts: dependencies.teamArtifacts,
            executor: dependencies.executor,
            drafts: dependencies.drafts
        ) { conversation in
            open = conversation
        }
        .navigationDestination(item: $open) { conversation in
            ChatView(
                streaming: dependencies.streaming,
                commands: dependencies.commands,
                history: dependencies.chatHistory,
                config: dependencies.sessionConfig,
                workspace: dependencies.workspace,
                confirming: dependencies.toolConfirm,
                plan: dependencies.plan,
                conversation: conversation
            )
        }
        .navigationDestination(for: SkillDraftQueueRoute.self) { _ in
            if let drafts = dependencies.drafts {
                SkillDraftListView(drafts: drafts, skills: dependencies.skills)
            }
        }
    }
}
