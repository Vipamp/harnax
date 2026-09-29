import Foundation

/// A Quartz-flavoured cron reader: `second minute hour day-of-month month day-of-week [year]`.
///
/// Two separate jobs live here, and they answer to different authorities:
///
/// - `hasServerAcceptedArity` mirrors the only check the backend performs — split on whitespace, count 6 or 7
///   (`AgentTaskCrudServiceImpl.kt:212-225`). Nothing semantic gets through it, so a client that stops there
///   discovers the problem when `toggle?status=1` is refused with "CronExpression '…' is invalid" (`§4.1`).
/// - `hasConflictFreeDayFields` and `nextMatch` are iOS's own: they catch what Quartz rejects outright, and
///   they produce the next-run time the server never sends (`§2.6`).
///
/// Parsing is deliberately conservative — an expression this type cannot read answers `nil` from `nextMatch`
/// and the row prints an em dash rather than a wrong minute (`§6.3`). Every token is range-checked, so a typo
/// is a parse failure and not a silently clipped set.
public enum QuartzCron {
    /// The scheduler triggers on its own node's clock, and that node is configured to GMT+8
    /// (`application.yml:14,31-32`; the job body uses `ZoneId.systemDefault()`). A phone in another zone still
    /// has to read `0 0 9 * * ?` as 09:00 in Beijing, so the expression is interpreted in this zone and the
    /// instant it names is formatted in the device's zone by the caller.
    public static let schedulerTimeZone =
        TimeZone(identifier: "Asia/Shanghai") ?? TimeZone(secondsFromGMT: 8 * 3600) ?? .gmt

    /// How far ahead a fire may be; past this the answer is "unreadable" rather than a century of looping.
    /// Only a year field or a `Feb 30`-style impossibility gets near it.
    static let horizonYears = 5

    struct Fields {
        let seconds: [Int]
        let minutes: [Int]
        let hours: [Int]
        let daysOfMonth: [Int]
        let months: [Int]
        /// `Calendar.weekday` numbering, which is Quartz's: 1 = Sunday … 7 = Saturday.
        let daysOfWeek: [Int]
        let years: [Int]?
        /// Whether the slot holds `?`, Quartz's "this column takes no part in picking the day".
        let dayOfMonthIsUnspecified: Bool
        let dayOfWeekIsUnspecified: Bool

        /// With neither slot marked `?`, both name days and Quartz builds no trigger at all, so such an
        /// expression has no next fire rather than an intersecting one.
        var isSchedulable: Bool { dayOfMonthIsUnspecified || dayOfWeekIsUnspecified }

        func matches(month: Int, year: Int) -> Bool {
            months.contains(month) && (years?.contains(year) ?? true)
        }

        func matchesDay(day: Int, weekday: Int) -> Bool {
            switch (dayOfMonthIsUnspecified, dayOfWeekIsUnspecified) {
            case (true, true): return true
            case (true, false): return daysOfWeek.contains(weekday)
            case (false, true): return daysOfMonth.contains(day)
            case (false, false): return daysOfMonth.contains(day) && daysOfWeek.contains(weekday)
            }
        }
    }

    // MARK: - form-side validation

    /// The server's own structural rule, byte for byte: 6 or 7 whitespace-separated fields.
    public static func hasServerAcceptedArity(_ expression: String) -> Bool {
        let parts = segments(expression)
        return parts.count == 6 || parts.count == 7
    }

    /// Whether the two day fields can coexist. Quartz requires **exactly one** of them to be `?`: it refuses
    /// to build a trigger for anything else, including `0 0 0 * * *`, which passes the backend's field count
    /// and only fails at `start` (`§3.3`, `§4.1`). `*` is therefore not an escape — it names every value in
    /// its column, which is exactly what "both columns name days" means to the scheduler.
    public static func hasConflictFreeDayFields(_ expression: String) -> Bool {
        let parts = segments(expression)
        guard parts.count == 6 || parts.count == 7 else { return false }
        return (parts[3] == "?") != (parts[5] == "?")
    }

    // MARK: - next fire

    /// The first instant strictly after `date` at which `expression` fires. `nil` for an expression that
    /// cannot be parsed, conflicts, or never fires inside `horizonYears`.
    public static func nextMatch(
        of expression: String,
        after date: Date,
        in timeZone: TimeZone = schedulerTimeZone
    ) -> Date? {
        guard let fields = parse(expression), fields.isSchedulable else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        guard let firstDay = calendar.dateInterval(of: .day, for: date)?.start,
              let horizon = calendar.date(byAdding: .year, value: horizonYears, to: firstDay) else { return nil }
        // Strictly after: a pattern landing on the very second of `date` has already fired.
        let floor = date.addingTimeInterval(1)
        var day = firstDay
        while day < horizon {
            let parts = calendar.dateComponents([.year, .month, .day, .weekday], from: day)
            if let year = parts.year, let month = parts.month, let dayOfMonth = parts.day, let weekday = parts.weekday,
               fields.matches(month: month, year: year),
               fields.matchesDay(day: dayOfMonth, weekday: weekday) {
                // The three time sets are sorted, so the first candidate at or past `floor` is the answer:
                // on the opening day the earlier ones are skipped, on every later day the very first wins.
                for hour in fields.hours {
                    for minute in fields.minutes {
                        for second in fields.seconds {
                            let components = DateComponents(
                                year: year, month: month, day: dayOfMonth, hour: hour, minute: minute, second: second
                            )
                            if let fire = calendar.date(from: components), fire >= floor { return fire }
                        }
                    }
                }
            }
            guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { return nil }
            day = next
        }
        return nil
    }

    // MARK: - parsing

    private static func segments(_ expression: String) -> [String] {
        expression.split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" }).map(String.init)
    }

    /// Only `?` opts a column out of the match; `*` is the whole column, which is a value and not an absence.
    private static func isNoSpecificValue(_ field: String) -> Bool {
        field == "?"
    }

    /// `nil` when the field count is wrong or any field holds a token, range or step outside its column.
    static func parse(_ expression: String) -> Fields? {
        let parts = segments(expression)
        guard parts.count == 6 || parts.count == 7 else { return nil }
        guard let seconds = field(parts[0], low: 0, high: 59),
              let minutes = field(parts[1], low: 0, high: 59),
              let hours = field(parts[2], low: 0, high: 23),
              let daysOfMonth = field(parts[3], low: 1, high: 31),
              let months = field(parts[4], low: 1, high: 12),
              let daysOfWeek = field(parts[5], low: 1, high: 7, names: weekdayNames) else { return nil }
        var years: [Int]?
        if parts.count == 7 {
            guard let parsed = field(parts[6], low: 1970, high: 2199) else { return nil }
            years = parsed.sorted()
        }
        return Fields(
            seconds: seconds.sorted(),
            minutes: minutes.sorted(),
            hours: hours.sorted(),
            daysOfMonth: daysOfMonth.sorted(),
            months: months.sorted(),
            daysOfWeek: daysOfWeek.sorted(),
            years: years,
            dayOfMonthIsUnspecified: isNoSpecificValue(parts[3]),
            dayOfWeekIsUnspecified: isNoSpecificValue(parts[5])
        )
    }

    /// The three-letter aliases Quartz accepts. Sunday is 1 because that is how both Quartz and
    /// `Calendar.weekday` number the week; a numeric 0 is folded onto Sunday since operators type it out of
    /// Unix-cron habit.
    private static let weekdayNames: [String: Int] = [
        "SUN": 1, "MON": 2, "TUE": 3, "WED": 4, "THU": 5, "FRI": 6, "SAT": 7,
    ]

    private static func field(_ text: String, low: Int, high: Int, names: [String: Int]? = nil) -> Set<Int>? {
        guard !text.isEmpty else { return nil }
        var values = Set<Int>()
        for part in text.split(separator: ",", omittingEmptySubsequences: false) {
            guard let parsed = clause(String(part), low: low, high: high, names: names) else { return nil }
            values.formUnion(parsed)
        }
        return values.isEmpty ? nil : values
    }

    private static func clause(_ text: String, low: Int, high: Int, names: [String: Int]?) -> Set<Int>? {
        let pieces = text.split(separator: "/", omittingEmptySubsequences: false)
        guard pieces.count <= 2 else { return nil }
        var step = 1
        if pieces.count == 2 {
            guard let parsed = Int(pieces[1]), parsed > 0 else { return nil }
            step = parsed
        }
        let head = String(pieces[0])
        // Endpoints rather than a range: `22-2` has its start above its end, and building a `ClosedRange` out
        // of that traps before anything can read it.
        let start: Int
        let end: Int
        if head == "*" || head == "?" {
            start = low
            end = high
        } else if head.contains("-") {
            let ends = head.split(separator: "-", omittingEmptySubsequences: false)
            guard ends.count == 2,
                  let first = value(String(ends[0]), low: low, high: high, names: names),
                  let last = value(String(ends[1]), low: low, high: high, names: names) else { return nil }
            start = first
            end = last
        } else {
            guard let single = value(head, low: low, high: high, names: names) else { return nil }
            // `a/n` is Quartz for "from a, every n, to the end of the column"; a lone `a` is that one value.
            start = single
            end = pieces.count == 2 ? high : single
        }
        var values = Set<Int>()
        if start <= end {
            var current = start
            while current <= end {
                values.insert(current)
                current += step
            }
        } else {
            // A descending range wraps the end of the column (`22-2` hours, `FRI-MON`), so it names two arcs.
            // Walking one cyclic pass with the same step keeps `a-b/n` consistent with `a-b`: the column wraps,
            // the step does not reset at the boundary.
            let size = high - low + 1
            var current = start
            let limit = end + size
            while current <= limit {
                values.insert(current > high ? current - size : current)
                current += step
            }
        }
        return values.isEmpty ? nil : values
    }

    private static func value(_ text: String, low: Int, high: Int, names: [String: Int]?) -> Int? {
        if let names, let named = names[text.uppercased()] { return named }
        guard let number = Int(text) else { return nil }
        // 0 means Sunday only in the weekday column, whose declared range starts at 1.
        if names != nil, number == 0 { return 1 }
        guard number >= low, number <= high else { return nil }
        return number
    }
}
