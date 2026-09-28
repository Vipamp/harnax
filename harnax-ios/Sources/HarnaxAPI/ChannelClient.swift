import Foundation
import HarnaxCore

extension AdminClient: ChannelCataloging {
    public func channelPage(
        keyword: String?,
        type: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ChannelSummary>, APIError> {
        await client.send(
            Page<ChannelSummary>.self,
            ChannelEndpoint.page(keyword: keyword, type: type, status: status, num: num, size: size)
        )
    }

    public func createChannel(_ draft: ChannelDraft) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? ChannelEndpoint.create(draft) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }

    public func updateChannel(id: Int64, _ change: ChannelChange) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? ChannelEndpoint.update(id: id, change) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }

    public func setChannelStatus(id: Int64, running: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, ChannelEndpoint.toggle(id: id, status: running.hxInt))
    }

    public func deleteChannel(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, ChannelEndpoint.delete(id: id))
    }

    public func sandboxStatuses(sessionIds: [String]) async -> Result<SandboxStatusMap, APIError> {
        await client.send(SandboxStatusMap.self, ChannelEndpoint.sandboxStatuses(sessionIds: sessionIds))
    }

    public func startWechatLogin(id: Int64) async -> Result<WechatQrCode, APIError> {
        await client.send(WechatQrCode.self, ChannelEndpoint.startWechatLogin(id: id))
    }

    public func wechatLoginStatus(id: Int64) async -> Result<WechatLoginUpdate, APIError> {
        await client.send(WechatLoginUpdate.self, ChannelEndpoint.wechatLoginStatus(id: id))
    }

    public func cancelWechatLogin(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, ChannelEndpoint.cancelWechatLogin(id: id))
    }
}
