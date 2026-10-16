import Foundation

struct SelectionRef: Sendable {
    let assetId: String?
    let sourceAssetId: String?
    let mode: String
}

struct DirectoryRuleRef: Sendable {
    let collectionId: String
    let recursive: Bool
}

struct ResolvableAsset: Sendable {
    let id: String
    let readiness: String
    let privacyExcluded: Bool
    let deleted: Bool
    let fileExists: Bool
    let policyAllowed: Bool
    var mediaKind: String = "image"
}

struct SelectionResolveInput: Sendable {
    let selections: [SelectionRef]
    let rules: [DirectoryRuleRef]
    let collectionChildren: [String: [String]]
    let collectionAssets: [String: [String]]
    let assets: [String: ResolvableAsset]
}

struct SelectionResolution: Sendable, Equatable {
    let candidates: [String]
    let pending: [String]
    let allowed: [String]
    let playable: [String]
}

enum SelectionResolver {
    static let modeInclude = "include"
    static let modeExclude = "exclude"
    static let readinessReady = "ready"

    static func resolve(_ input: SelectionResolveInput) -> SelectionResolution {
        var includedAssets = Set<String>()
        var pendingSources = Set<String>()
        var excludedRefs = Set<String>()
        for selection in input.selections {
            let ref = selection.assetId ?? selection.sourceAssetId ?? ""
            switch selection.mode {
            case modeInclude:
                if let assetId = selection.assetId { includedAssets.insert(assetId) }
                if let sourceAssetId = selection.sourceAssetId { pendingSources.insert(sourceAssetId) }
            case modeExclude:
                excludedRefs.insert(ref)
            default:
                break
            }
        }

        var directoryAssets = Set<String>()
        for rule in input.rules {
            for collectionId in expand(rule: rule, children: input.collectionChildren) {
                directoryAssets.formUnion(input.collectionAssets[collectionId] ?? [])
            }
        }

        let candidates = includedAssets.union(directoryAssets).sorted()
        let pending = pendingSources.sorted()

        var allowed: [String] = []
        for id in candidates {
            guard let asset = input.assets[id] else { continue }
            if excludedRefs.contains(id) { continue }
            if asset.privacyExcluded || asset.deleted { continue }
            allowed.append(id)
        }
        for id in pending where !excludedRefs.contains(id) {
            allowed.append(id)
        }

        let playable = allowed.filter { id in
            guard let asset = input.assets[id] else { return false }
            return asset.readiness == readinessReady && asset.fileExists && asset.policyAllowed
                && asset.mediaKind != "video"
        }

        return SelectionResolution(candidates: candidates, pending: pending, allowed: allowed, playable: playable)
    }

    private static func expand(rule: DirectoryRuleRef, children: [String: [String]]) -> Set<String> {
        var visited: Set<String> = [rule.collectionId]
        guard rule.recursive else { return visited }
        var queue = [rule.collectionId]
        while !queue.isEmpty {
            let current = queue.removeFirst()
            for child in children[current] ?? [] where !visited.contains(child) {
                visited.insert(child)
                queue.append(child)
            }
        }
        return visited
    }
}

struct QueueAsset: Sendable {
    let id: String
    let capturedAt: Int64?
    let importedAt: Int64
    let manualOrder: Int64?
    let addedAt: Int64
}

struct MonthBuckets: Sendable, Equatable {
    let bucketOrder: [String]
    let buckets: [String: [String]]
    let noCapturedTime: [String]

    static func == (lhs: MonthBuckets, rhs: MonthBuckets) -> Bool {
        lhs.bucketOrder == rhs.bucketOrder &&
            lhs.noCapturedTime == rhs.noCapturedTime &&
            lhs.buckets == rhs.buckets
    }
}

enum PlaybackQueue {
    static let orderCapturedAsc = "captured_asc"
    static let orderManual = "manual"
    static let orderRandom = "random"

    static func buildQueue(assets: [QueueAsset], orderMode: String, randomSeed: UInt64) -> [String] {
        switch orderMode {
        case orderManual:
            return manualOrder(assets)
        case orderRandom:
            return shuffle(ids: assets.map(\.id), seed: randomSeed)
        default:
            return capturedAsc(assets)
        }
    }

    static func capturedAsc(_ assets: [QueueAsset]) -> [String] {
        assets.sorted { lhs, rhs in
            queueCompare(lhs.capturedAt, rhs.capturedAt, lhsId: lhs.id, rhsId: rhs.id)
        }.map(\.id)
    }

    static func manualOrder(_ assets: [QueueAsset]) -> [String] {
        assets.sorted { lhs, rhs in
            switch (lhs.manualOrder, rhs.manualOrder) {
            case let (l?, r?):
                if l != r { return l < r }
            case (_?, nil):
                return true
            case (nil, _?):
                return false
            case (nil, nil):
                break
            }
            return queueCompare(lhs.capturedAt, rhs.capturedAt, lhsId: lhs.id, rhsId: rhs.id)
        }.map(\.id)
    }

    static func browseOrder(_ assets: [QueueAsset]) -> [String] {
        assets.sorted { lhs, rhs in
            let lKey = lhs.capturedAt ?? lhs.importedAt
            let rKey = rhs.capturedAt ?? rhs.importedAt
            if lKey != rKey { return lKey < rKey }
            return lhs.id < rhs.id
        }.map(\.id)
    }

    static func monthBuckets(_ assets: [QueueAsset]) -> MonthBuckets {
        var buckets: [String: [String]] = [:]
        var noCaptured: [String] = []
        let capturedById = Dictionary(uniqueKeysWithValues: assets.map { ($0.id, $0.capturedAt) })
        for asset in assets {
            if let captured = asset.capturedAt {
                buckets[monthKey(captured), default: []].append(asset.id)
            } else {
                noCaptured.append(asset.id)
            }
        }
        let ordered = buckets.mapValues { ids in
            Array(Set(ids)).sorted { lhs, rhs in
                let lKey = capturedById[lhs] ?? nil
                let rKey = capturedById[rhs] ?? nil
                if lKey != rKey { return (lKey ?? 0) < (rKey ?? 0) }
                return lhs < rhs
            }
        }
        return MonthBuckets(
            bucketOrder: ordered.keys.sorted(),
            buckets: ordered,
            noCapturedTime: Array(Set(noCaptured)).sorted()
        )
    }

    static func monthKey(_ epochMillis: Int64) -> String {
        let days = epochMillis.divideFloor(by: 86_400_000)
        let z = days + 719_468
        let era = z.divideFloor(by: 146_097)
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        let y = yoe + era * 400
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let month = mp + (mp < 10 ? 3 : -9)
        let year = month <= 2 ? y + 1 : y
        return String(format: "%04d-%02d", Int(year), Int(month))
    }

    static func shuffle(ids: [String], seed: UInt64) -> [String] {
        var result = ids
        var random = SplitMix64(seed: seed)
        guard result.count > 1 else { return result }
        for i in stride(from: result.count - 1, through: 1, by: -1) {
            let j = Int(random.next() % UInt64(i + 1))
            result.swapAt(i, j)
        }
        return result
    }

    private static func queueCompare(_ lhs: Int64?, _ rhs: Int64?, lhsId: String, rhsId: String) -> Bool {
        switch (lhs, rhs) {
        case let (l?, r?):
            if l != r { return l < r }
        case (_?, nil):
            return true
        case (nil, _?):
            return false
        case (nil, nil):
            break
        }
        return lhsId < rhsId
    }
}

struct SplitMix64 {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

private extension Int64 {
    func divideFloor(by divisor: Int64) -> Int64 {
        let quotient = self / divisor
        return (self % divisor != 0) && ((self < 0) != (divisor < 0)) ? quotient - 1 : quotient
    }
}
