import Foundation

struct HolidayRule: Sendable, Equatable {
    let id: String
    let frameID: String
    let name: String
    let startDate: String
    let endDate: String
    let startTime: Int?
    let endTime: Int?
    let position: Int
    let enabled: Bool
}

enum HolidayTiming {
    static func isActive(rule: HolidayRule, date: String, minute: Int) -> Bool {
        guard rule.startDate <= rule.endDate else { return false }
        guard rule.startDate <= date, date <= rule.endDate else { return false }
        switch (rule.startTime, rule.endTime) {
        case (nil, nil):
            return true
        case let (start?, end?):
            guard start != end else { return false }
            if start > end {
                return minute >= start || minute < end
            }
            return minute >= start && minute < end
        default:
            return false
        }
    }

    static func pick(rules: [HolidayRule], date: String, minute: Int) -> String? {
        rules
            .filter { $0.enabled && isActive(rule: $0, date: date, minute: minute) }
            .sorted {
                if $0.position != $1.position { return $0.position < $1.position }
                return $0.id < $1.id
            }
            .first?.id
    }

    static func overlap(a: HolidayRule, b: HolidayRule) -> Bool {
        guard a.startDate <= a.endDate, b.startDate <= b.endDate else { return false }
        guard a.startDate <= b.endDate, b.startDate <= a.endDate else { return false }
        if (a.startTime == nil) != (a.endTime == nil) || (b.startTime == nil) != (b.endTime == nil) {
            return false
        }
        let left = minuteSet(start: a.startTime, end: a.endTime)
        let right = minuteSet(start: b.startTime, end: b.endTime)
        if left == nil, right == nil { return true }
        guard let left, let right else {
            return !(left ?? right ?? []).isEmpty
        }
        return !left.isDisjoint(with: right)
    }

    static func minuteSet(start: Int?, end: Int?) -> Set<Int>? {
        guard let start, let end else { return nil }
        guard start != end else { return [] }
        if start > end {
            return Set(start..<1440).union(Set(0..<end))
        }
        return Set(start..<end)
    }
}
