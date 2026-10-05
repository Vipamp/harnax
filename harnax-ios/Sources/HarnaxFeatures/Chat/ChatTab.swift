import SwiftUI
import HarnaxCore
import HarnaxKit

/// The chat tab: the conversation list, the chat window a row opens, and the review queue the entry row opens.
///
/// Only the destination lives here. The list decides *which* conversation a row addresses (`SessionListView`
/// hands over a `ChatConversation` and refuses the tap for a row with no business key), so this screen has no
/// read of its own and no view model. The queue gets the same treatment: it has no address to carry, which is
/// why it opens with `isPresented` while the queue's *own* detail push — addressed by `SkillDraftRef` — is
/// registered inside `SkillDraftListView` (§5.1).
public struct ChatTabView: View {
    private let dependencies: HarnaxDependencies
    @State private var open: ChatConversation?
    @State private var isDraftQueueOpen = false

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
        } onOpenDraftQueue: {
            isDraftQueueOpen = true
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
        .navigationDestination(isPresented: $isDraftQueueOpen) {
            if let drafts = dependencies.drafts {
                SkillDraftListView(drafts: drafts, skills: dependencies.skills)
            }
        }
    }
}
