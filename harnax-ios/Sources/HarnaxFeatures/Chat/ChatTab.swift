import SwiftUI
import HarnaxCore
import HarnaxKit

/// The chat tab: the conversation list, and the chat window a row opens.
///
/// Only the destination lives here. The list decides *which* conversation a row addresses (`SessionListView`
/// hands over a `ChatConversation` and refuses the tap for a row with no business key), so this screen has no
/// read of its own and no view model.
public struct ChatTabView: View {
    private let dependencies: HarnaxDependencies
    @State private var open: ChatConversation?

    public init(dependencies: HarnaxDependencies) {
        self.dependencies = dependencies
    }

    public var body: some View {
        SessionListView(sessions: dependencies.sessions, creating: dependencies.sessionCreate) { conversation in
            open = conversation
        }
        .navigationDestination(item: $open) { conversation in
            ChatView(
                streaming: dependencies.streaming,
                commands: dependencies.commands,
                history: dependencies.chatHistory,
                conversation: conversation
            )
        }
    }
}
