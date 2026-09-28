import Foundation
import HarnaxCore

/// The seam behind every admin domain facade. Each domain adds its own `extension AdminClient: …` in its
/// own file, so one client with its header injection and token refresh serves all of them.
public struct AdminClient {
    let client: APIClient

    public init(client: APIClient) {
        self.client = client
    }
}
