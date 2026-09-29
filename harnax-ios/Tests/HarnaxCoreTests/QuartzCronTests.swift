import XCTest

@testable import HarnaxCore

/// The client-side half of `§2.6` and `§3.3`: the two checks the form runs before sending, and the next-fire
/// computation the server never answers with.
///
/// Every expectation below is anchored to wall-clock readings in **Asia/Shanghai**, because that is the zone
/// the scheduler node triggers in (`application.yml:14,31-32`) and a test that read the clock in the machine's
/// own zone would pass or fail on where the CI box happens to live.
final class QuartzCronTests: XCTestCase {
    /// Friday 2026-09-25 10:00:00.
    private let friday = cronDay(2026, 9, 25, 10, 0, 0)

    // MARK: - the two form checks

    /// The backend's only structural rule, reproduced: split on whitespace and count 6 or 7
    /// (`AgentTaskCrudServiceImpl.kt:212-225`). Nothing semantic survives it.
    func testArityIsCountedNotInterpreted() {
        XCTAssertTrue(QuartzCron.hasServerAcceptedArity("0 0 9 * * ?"))
        XCTAssertTrue(QuartzCron.hasServerAcceptedArity("0 0 9 * * ? 2027"))
        XCTAssertTrue(
            QuartzCron.hasServerAcceptedArity("  0\t0  9 * * ? "),
            "the split is on any whitespace run, so a pasted expression with a tab still counts"
        )
        XCTAssertFalse(QuartzCron.hasServerAcceptedArity("0 0 9 * *"), "five fields is a Unix cron, not a Quartz one")
        XCTAssertFalse(QuartzCron.hasServerAcceptedArity("* * * * * * * *"))
        XCTAssertFalse(QuartzCron.hasServerAcceptedArity(""))
    }

    /// Quartz builds no trigger unless exactly one of the two day slots is `?` — and `*` is not `?`: it names
    /// every value in its column, which is what "both columns name days" means to the scheduler (`§3.3`'s
    /// `0 0 0 * * *`, valid to the field count and rejected at `start`).
    func testTheDayPairMustCarryExactlyOneMarker() {
        XCTAssertTrue(QuartzCron.hasConflictFreeDayFields("0 0 9 * * ?"))
        XCTAssertTrue(QuartzCron.hasConflictFreeDayFields("0 0 9 ? * MON"))
        XCTAssertFalse(QuartzCron.hasConflictFreeDayFields("0 0 0 * * *"))
        XCTAssertFalse(QuartzCron.hasConflictFreeDayFields("0 0 0 15 * MON"))
        XCTAssertFalse(
            QuartzCron.hasConflictFreeDayFields("0 0 0 ? * ?"),
            "two markers leave no column to pick the day from"
        )
        XCTAssertFalse(QuartzCron.hasConflictFreeDayFields("0 0 9 * *"), "a miscounted expression is not conflict-free")
    }

    /// A check that only counts fields cannot be the last word, so the pair is asserted together: every
    /// expression the form may send is one `nextMatch` can also read.
    func testTheTwoChecksAgreeOnThePresets() {
        for expression in [
            "0 */5 * * * ?", "0 0 * * * ?", "0 0 9 * * ?", "0 0 9 ? * MON", "0 0 0 * * ?",
        ] {
            XCTAssertTrue(QuartzCron.hasServerAcceptedArity(expression), expression)
            XCTAssertTrue(QuartzCron.hasConflictFreeDayFields(expression), expression)
            XCTAssertNotNil(QuartzCron.nextMatch(of: expression, after: friday), expression)
        }
    }

    // MARK: - next fire

    func testEveryFiveMinutesReadsTheMinuteColumnAsAStep() {
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 */5 * * * ?", after: cronDay(2026, 9, 25, 9, 3, 20)),
            "2026-09-25 09:05:00"
        )
    }

    /// The list's own next-run line would be wrong by a day if a pattern landing on this very second counted
    /// as still to come.
    func testTheSearchIsStrictlyAfterTheGivenInstant() {
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 0 9 * * ?", after: cronDay(2026, 9, 25, 9, 0, 0)),
            "2026-09-26 09:00:00"
        )
    }

    func testAListOfMinutesAndAListOfHoursBothExpand() {
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 0,30 * * * ?", after: friday),
            "2026-09-25 10:30:00",
            "10:00 has itself already fired, so the next entry of the list is 10:30"
        )
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "30 0 8,20 * * ?", after: friday),
            "2026-09-25 20:00:30"
        )
    }

    /// `22-2` wraps the end of the column, which is how an overnight window is written by hand.
    func testADescendingRangeWrapsAroundTheColumn() {
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 30 22-2 * * ?", after: cronDay(2026, 9, 25, 22, 45, 0)),
            "2026-09-25 23:30:00",
            "23 is past the range's named start, so the column has to run up to its ceiling"
        )
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 30 22-2 * * ?", after: cronDay(2026, 9, 25, 23, 45, 31)),
            "2026-09-26 00:30:00",
            "the wrapped values are reachable from the far end of the day as well"
        )
    }

    /// Weekday numbering is Quartz's and `Calendar.weekday`'s: 1 is Sunday, and a 0 typed out of Unix habit
    /// means the same day.
    func testSundayIsReachableAsZeroOneAndName() {
        for expression in ["0 0 8 ? * 0", "0 0 8 ? * 1", "0 0 8 ? * SUN", "0 0 8 ? * sun"] {
            XCTAssertEqualText(
                QuartzCron.nextMatch(of: expression, after: friday),
                "2026-09-27 08:00:00",
                expression
            )
        }
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 0 8 ? * MON", after: friday),
            "2026-09-28 08:00:00"
        )
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 0 8 ? * FRI", after: friday),
            "2026-10-02 08:00:00",
            "today at 08:00 has already passed, so the same weekday means next week"
        )
    }

    /// A day-of-month slot with `?` opposite it means "the 30th of every month", not "any day".
    func testCalendarDayAndMonthColumnsNarrowTheSearch() {
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 0 9 30 * ?", after: friday),
            "2026-09-30 09:00:00"
        )
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 0 12 1 1 ?", after: friday),
            "2027-01-01 12:00:00"
        )
    }

    /// The optional seventh field is a year filter, and past the horizon the answer is "no next run" rather
    /// than a loop through the century.
    func testTheYearFieldFiltersRatherThanDescribes() {
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 0 9 * * ? 2027", after: friday),
            "2027-01-01 09:00:00"
        )
        XCTAssertNil(QuartzCron.nextMatch(of: "0 0 9 * * ? 2040", after: friday))
        XCTAssertNil(
            QuartzCron.nextMatch(of: "0 0 9 30 2 ?", after: friday),
            "there is no 30 February, and an expression that can never fire says so"
        )
    }

    /// A token this type cannot read is a missing next-run line on the row, never a guessed minute — and each
    /// case below fails on a field outside its own column or on no field at all.
    func testAnythingOutOfRangeOrMalformedIsUnreadable() {
        for expression in [
            "", "   ", "not a cron at all", "0 0 9 * *", "0 0 9 * * ? 2026 1",
            "0 60 * * * ?", "0 0 24 * * ?", "0 0 9 32 * ?", "0 0 9 * 13 ?", "0 0 9 * * 8",
            "0 0 9 * * MON-", "0 0 9 * * MON/0", "0 0 9 * * ?,",
        ] {
            XCTAssertNil(QuartzCron.nextMatch(of: expression, after: friday), "\(expression) should not parse")
        }
    }

    /// `?` in a time column is what people paste out of the console's own samples; it means the whole column,
    /// so the first hit is the top of the only specified hour.
    func testAMarkerInAnyColumnExpandsToThatColumn() {
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "? ? 9 * * ?", after: cronDay(2026, 9, 25, 8, 30, 0)),
            "2026-09-25 09:00:00"
        )
    }

    /// The zone the expression is read in is the caller's business, and the default is Beijing's clock: an
    /// expression read in GMT would land eight hours off for a travelling user, which is the one thing this
    /// line exists to get right.
    func testTheExpressionIsReadInTheZoneItIsGiven() throws {
        let tokyo = try XCTUnwrap(TimeZone(identifier: "Asia/Tokyo"))
        XCTAssertEqualText(
            QuartzCron.nextMatch(of: "0 0 9 * * ?", after: friday, in: tokyo),
            "2026-09-26 08:00:00",
            "09:00 in Tokyo is 08:00 in Beijing, which is the hour the assertions above print in"
        )
        XCTAssertEqual(QuartzCron.schedulerTimeZone, TimeZone(identifier: "Asia/Shanghai"))
    }

    // MARK: - helpers

    private func XCTAssertEqualText(
        _ date: Date?,
        _ expected: String,
        _ message: String = "",
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(date.map(cronText), expected, message, file: file, line: line)
    }
}

/// A wall-clock reading in the scheduler's zone.
private func cronDay(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int, _ s: Int) -> Date {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = QuartzCron.schedulerTimeZone
    return calendar.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi, second: s))!
}

/// Printed back in the same zone, so a wrong zone shows up as a shifted hour rather than as a confusing diff.
private func cronText(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = QuartzCron.schedulerTimeZone
    formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
    return formatter.string(from: date)
}
