import Foundation
import HarnaxCore

/// The skill-usage half of the admin surface (`SkillUsageEndpoint.swift`).
extension AdminClient: SkillUsageReading {
    public func summary(days: Int) async -> Result<SkillUsageSummary, APIError> {
        await client.send(SkillUsageSummary.self, SkillUsageEndpoint.summary(days: days))
    }
}
