import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// A file a drawer handed over, captured instead of shared.
///
/// The macOS test host has no share sheet, which is exactly why `WorkspaceViewModel` and
/// `TeamArtifactsViewModel` take the share step as a closure over plain `Data`
/// (`Chat/HXFileShare.swift:31-35`): the assertion a download needs is *what left the screen and under what
/// name*, and that is answerable without a window.
final class ShareBox: @unchecked Sendable {
    private(set) var shared: [HXSharedFile] = []

    var receiver: @Sendable (HXSharedFile) -> Void {
        { file in self.shared.append(file) }
    }

    var last: HXSharedFile? { shared.last }
}

/// The sandbox workspace routes, on the reply-queue discipline the other fakes use: a request nobody queued
/// answers as a decoding failure and still shows up in the call log, so one extra listing reads as a wrong
/// count rather than as a silent pass.
final class FakeWorkspaceSandbox: SessionWorkspaceReading, @unchecked Sendable {
    private(set) var statusRequests: [String] = []
    var statusReplies: [Result<SandboxStatus, APIError>] = []

    private(set) var listRequests: [String] = []
    var listReplies: [Result<[WorkspaceFile], APIError>] = []

    private(set) var readRequests: [String] = []
    var readReplies: [Result<WorkspaceFileContent, APIError>] = []

    private(set) var uploadRequests: [(path: String, fileName: String, mimeType: String, bytes: Int)] = []
    var uploadReplies: [Result<WorkspaceUpload, APIError>] = []

    private(set) var downloadRequests: [String] = []
    var downloadReplies: [Result<WorkspaceDownload, APIError>] = []

    func sandboxStatus(sessionId: String) async -> Result<SandboxStatus, APIError> {
        statusRequests.append(sessionId)
        return statusReplies.isEmpty ? .failure(.decoding) : statusReplies.removeFirst()
    }

    func workspaceFiles(sessionId: String, path: String) async -> Result<[WorkspaceFile], APIError> {
        listRequests.append(path)
        return listReplies.isEmpty ? .failure(.decoding) : listReplies.removeFirst()
    }

    func readWorkspaceFile(sessionId: String, path: String) async -> Result<WorkspaceFileContent, APIError> {
        readRequests.append(path)
        return readReplies.isEmpty ? .failure(.decoding) : readReplies.removeFirst()
    }

    func uploadWorkspaceFile(
        sessionId: String,
        path: String,
        fileName: String,
        mimeType: String,
        payload: Data
    ) async -> Result<WorkspaceUpload, APIError> {
        uploadRequests.append((path: path, fileName: fileName, mimeType: mimeType, bytes: payload.count))
        return uploadReplies.isEmpty ? .failure(.decoding) : uploadReplies.removeFirst()
    }

    func downloadWorkspaceFile(sessionId: String, path: String) async -> Result<WorkspaceDownload, APIError> {
        downloadRequests.append(path)
        return downloadReplies.isEmpty ? .failure(.decoding) : downloadReplies.removeFirst()
    }

    /// `ChatViewModel`'s route rather than a drawer's, gated the way `FakeTeamArtifactStore` gates its own — so
    /// a row tapped twice while its bytes are in flight can be shown to have sent one request. The recorded
    /// session id is the point of the whole call: the frame carries no conversation of its own.
    private(set) var attachmentRequests: [(fileId: String, sessionId: String)] = []
    var attachmentReplies: [Result<WorkspaceDownload, APIError>] = []
    var gateAttachments = false
    private var parkedAttachments: [() -> Void] = []

    func downloadAttachment(
        _ attachment: ChatFileAttachment,
        sessionId: String
    ) async -> Result<WorkspaceDownload, APIError> {
        attachmentRequests.append((fileId: attachment.fileId, sessionId: sessionId))
        guard gateAttachments else { return nextAttachment() }
        return await withCheckedContinuation { continuation in
            parkedAttachments.append { continuation.resume(returning: self.nextAttachment()) }
        }
    }

    func releaseAttachments() {
        let waiting = parkedAttachments
        parkedAttachments = []
        for resume in waiting { resume() }
    }

    private func nextAttachment() -> Result<WorkspaceDownload, APIError> {
        attachmentReplies.isEmpty ? .failure(.decoding) : attachmentReplies.removeFirst()
    }
}

/// The two team-artifact routes, with the download gated so a row tapped twice while its bytes are in flight
/// can be shown to send one request.
final class FakeTeamArtifactStore: TeamArtifactReading, @unchecked Sendable {
    private(set) var listRequests: [String] = []
    var listReplies: [Result<[TeamArtifact], APIError>] = []

    private(set) var downloadRequests: [(fileId: String, sessionId: String)] = []
    var downloadReplies: [Result<TeamArtifactFile, APIError>] = []

    var gateDownloads = false
    private var parked: [() -> Void] = []

    func teamArtifacts(sessionId: String) async -> Result<[TeamArtifact], APIError> {
        listRequests.append(sessionId)
        return listReplies.isEmpty ? .failure(.decoding) : listReplies.removeFirst()
    }

    func downloadTeamArtifact(fileId: String, sessionId: String) async -> Result<TeamArtifactFile, APIError> {
        downloadRequests.append((fileId: fileId, sessionId: sessionId))
        guard gateDownloads else { return nextDownload() }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.nextDownload()) }
        }
    }

    func releaseDownloads() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func nextDownload() -> Result<TeamArtifactFile, APIError> {
        downloadReplies.isEmpty ? .failure(.decoding) : downloadReplies.removeFirst()
    }
}

private extension WorkspaceFile {
    /// The four rows the drawer actually distinguishes, built from the wire's own words.
    static func wire(_ name: String, _ type: String, size: Int64? = nil) -> WorkspaceFile {
        WorkspaceFile(type: WorkspaceFileKind(wireValue: type), name: name, size: size, modified: nil)
    }
}
