import CoreData
enum PersistenceSchema {
    static let version = 2

    static func makeModel() -> NSManagedObjectModel {
        let model = NSManagedObjectModel()

        let source = entity("Source")
        let sourceAsset = entity("SourceAsset")
        let asset = entity("Asset")
        let assetOrigin = entity("AssetOrigin")
        let collection = entity("Collection")
        let collectionAsset = entity("CollectionAsset")
        let frame = entity("Frame")
        let frameSelection = entity("FrameSelection")
        let frameDirectoryRule = entity("FrameDirectoryRule")
        let importJob = entity("ImportJob")
        let importItem = entity("ImportItem")
        let localTombstone = entity("LocalTombstone")
        let playbackSnapshot = entity("PlaybackSnapshot")
        let settingsKV = entity("SettingsKV")
        let weatherSnapshot = entity("WeatherSnapshot")
        let schemaMeta = entity("SchemaMeta")
        let holidaySchedule = entity("HolidaySchedule")

        source.properties = [
            stringAttr("id"),
            stringAttr("type"),
            stringAttr("display_name"),
            stringAttr("endpoint_key", optional: true),
            stringAttr("credential_ref", optional: true),
            stringAttr("account_key", optional: true),
            intAttr("capability_flags"),
            stringAttr("retention_policy"),
            stringAttr("status"),
            intAttr("epoch", defaultValue: 1),
            intAttr("last_sync_at", optional: true),
            stringAttr("settings_json", optional: true),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        sourceAsset.properties = [
            stringAttr("id"),
            stringAttr("remote_key"),
            stringAttr("stable_id", optional: true),
            stringAttr("revision"),
            intAttr("byte_size", optional: true),
            intAttr("remote_taken_at", optional: true),
            intAttr("last_seen_at"),
            intAttr("missing_scan_count", defaultValue: 0),
            stringAttr("access_state"),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        asset.properties = [
            stringAttr("id"),
            stringAttr("content_hash"),
            intAttr("byte_size"),
            stringAttr("format"),
            intAttr("pixel_width"),
            intAttr("pixel_height"),
            intAttr("exif_orientation"),
            stringAttr("orientation_class"),
            intAttr("captured_at", optional: true),
            stringAttr("captured_at_source"),
            intAttr("imported_at"),
            stringAttr("original_rel_path"),
            stringAttr("original_file_name", optional: true),
            stringAttr("media_kind", defaultValue: "image"),
            intAttr("duration_ms", optional: true),
            stringAttr("readiness"),
            stringAttr("failure_code", optional: true),
            boolAttr("privacy_excluded", defaultValue: false),
            stringAttr("edge_color", optional: true),
            intAttr("deleted_at", optional: true),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        assetOrigin.properties = [
            intAttr("imported_at"),
            stringAttr("policy_state"),
            stringAttr("origin_state", defaultValue: OriginState.active.rawValue),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        collection.properties = [
            stringAttr("id"),
            stringAttr("name"),
            stringAttr("rel_path", optional: true),
            stringAttr("type"),
            stringAttr("sort_key"),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        collectionAsset.properties = [
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        frame.properties = [
            stringAttr("id"),
            stringAttr("name"),
            stringAttr("settings_json"),
            intAttr("selection_version", defaultValue: 0),
            stringAttr("order_mode"),
            intAttr("random_seed", defaultValue: 0),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        frameSelection.properties = [
            stringAttr("ref_key"),
            stringAttr("mode"),
            intAttr("manual_order", optional: true),
            intAttr("added_at"),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        frameDirectoryRule.properties = [
            boolAttr("recursive", defaultValue: false),
            boolAttr("include_future", defaultValue: false),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        importJob.properties = [
            stringAttr("id"),
            stringAttr("scope_json"),
            intAttr("epoch_at_create"),
            stringAttr("cursor", optional: true),
            intAttr("total_known", optional: true),
            stringAttr("counts_json", defaultValue: "{}"),
            stringAttr("state"),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        importItem.properties = [
            stringAttr("id"),
            stringAttr("state"),
            stringAttr("temp_rel_path", optional: true),
            intAttr("retry_count", defaultValue: 0),
            intAttr("retry_at", optional: true),
            stringAttr("failure_code", optional: true),
            intAttr("bytes_done", defaultValue: 0),
            intAttr("bytes_total", optional: true),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        localTombstone.properties = [
            stringAttr("id"),
            stringAttr("content_hash", optional: true),
            stringAttr("source_fingerprint", optional: true),
            stringAttr("remote_id", optional: true),
            intAttr("byte_size", optional: true),
            stringAttr("head64k_hash", optional: true),
            stringAttr("reason"),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        playbackSnapshot.properties = [
            intAttr("remaining_hold_ms"),
            stringAttr("cursor_json"),
            intAttr("seed"),
            intAttr("selection_version"),
            intAttr("saved_at"),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        settingsKV.properties = [
            stringAttr("key"),
            stringAttr("value_json"),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        schemaMeta.properties = [
            stringAttr("key"),
            stringAttr("value"),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        weatherSnapshot.properties = [
            stringAttr("place_id"),
            stringAttr("condition_code"),
            intAttr("temperature_milli"),
            intAttr("observed_at"),
            intAttr("fetched_at"),
            intAttr("expires_at"),
            stringAttr("attribution", optional: true),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        holidaySchedule.properties = [
            stringAttr("id"),
            stringAttr("name"),
            stringAttr("start_date"),
            stringAttr("end_date"),
            intAttr("start_time", optional: true),
            intAttr("end_time", optional: true),
            intAttr("position", defaultValue: 0),
            boolAttr("enabled", defaultValue: true),
            intAttr("created_at"),
            intAttr("updated_at"),
        ]

        let sourceSourceAsset = toMany("sourceAssets", sourceAsset, deleteRule: .cascadeDeleteRule)
        let sourceAssetSource = toOne("source", source)
        let sourceCollections = toMany("collections", collection, deleteRule: .cascadeDeleteRule)
        let collectionSource = toOne("source", source, optional: true)
        let sourceImportJobs = toMany("importJobs", importJob, deleteRule: .cascadeDeleteRule)
        let importJobSource = toOne("source", source)

        let assetAssetOrigins = toMany("assetOrigins", assetOrigin, deleteRule: .cascadeDeleteRule)
        let assetOriginAsset = toOne("asset", asset)
        let sourceAssetOrigins = toMany("assetOrigins", assetOrigin, deleteRule: .noActionDeleteRule)
        let assetOriginSourceAsset = toOne("sourceAsset", sourceAsset)

        let assetCollectionAssets = toMany("collectionAssets", collectionAsset, deleteRule: .cascadeDeleteRule)
        let collectionAssetAsset = toOne("asset", asset)
        let collectionCollectionAssets = toMany("collectionAssets", collectionAsset, deleteRule: .cascadeDeleteRule)
        let collectionAssetCollection = toOne("collection", collection)

        let collectionSubcollections = toMany("subcollections", collection, deleteRule: .denyDeleteRule)
        let collectionParent = toOne("parent", collection, optional: true)

        let assetSelections = toMany("selections", frameSelection, deleteRule: .cascadeDeleteRule)
        let frameSelectionAsset = toOne("asset", asset, optional: true)
        let sourceAssetSelections = toMany("selections", frameSelection, deleteRule: .cascadeDeleteRule)
        let frameSelectionSourceAsset = toOne("sourceAsset", sourceAsset, optional: true)

        let assetCoveredFrames = toMany("coveredFrames", frame, deleteRule: .nullifyDeleteRule)
        let frameCoverAsset = toOne("coverAsset", asset, optional: true)

        let frameSelections = toMany("selections", frameSelection, deleteRule: .cascadeDeleteRule)
        let frameSelectionFrame = toOne("frame", frame)

        let frameDirectoryRules = toMany("directoryRules", frameDirectoryRule, deleteRule: .cascadeDeleteRule)
        let frameDirectoryRuleFrame = toOne("frame", frame)
        let collectionDirectoryRules = toMany("directoryRules", frameDirectoryRule, deleteRule: .cascadeDeleteRule)
        let frameDirectoryRuleCollection = toOne("collection", collection)

        let importJobItems = toMany("items", importItem, deleteRule: .cascadeDeleteRule)
        let importItemJob = toOne("job", importJob)
        let sourceAssetImportItems = toMany("importItems", importItem, deleteRule: .cascadeDeleteRule)
        let importItemSourceAsset = toOne("sourceAsset", sourceAsset)

        let assetPlaybackSnapshots = toMany("playbackSnapshots", playbackSnapshot, deleteRule: .nullifyDeleteRule)
        let playbackSnapshotAsset = toOne("asset", asset, optional: true)
        let framePlaybackSnapshot = toOne("playbackSnapshot", playbackSnapshot, optional: true, deleteRule: .cascadeDeleteRule)

        let frameHolidaySchedules = toMany("holidaySchedules", holidaySchedule, deleteRule: .cascadeDeleteRule)
        let holidayScheduleFrame = toOne("frame", frame)
        let playbackSnapshotFrame = toOne("frame", frame)

        wire(sourceSourceAsset, sourceAssetSource)
        wire(sourceCollections, collectionSource)
        wire(sourceImportJobs, importJobSource)
        wire(assetAssetOrigins, assetOriginAsset)
        wire(sourceAssetOrigins, assetOriginSourceAsset)
        wire(assetCollectionAssets, collectionAssetAsset)
        wire(collectionCollectionAssets, collectionAssetCollection)
        wire(collectionSubcollections, collectionParent)
        wire(assetSelections, frameSelectionAsset)
        wire(sourceAssetSelections, frameSelectionSourceAsset)
        wire(assetCoveredFrames, frameCoverAsset)
        wire(frameSelections, frameSelectionFrame)
        wire(frameDirectoryRules, frameDirectoryRuleFrame)
        wire(collectionDirectoryRules, frameDirectoryRuleCollection)
        wire(importJobItems, importItemJob)
        wire(sourceAssetImportItems, importItemSourceAsset)
        wire(assetPlaybackSnapshots, playbackSnapshotAsset)
        wire(framePlaybackSnapshot, playbackSnapshotFrame)
        wire(frameHolidaySchedules, holidayScheduleFrame)

        source.properties += [sourceSourceAsset, sourceCollections, sourceImportJobs]
        sourceAsset.properties += [sourceAssetSource, sourceAssetOrigins, sourceAssetSelections, sourceAssetImportItems]
        asset.properties += [assetAssetOrigins, assetCollectionAssets, assetSelections, assetCoveredFrames, assetPlaybackSnapshots]
        assetOrigin.properties += [assetOriginAsset, assetOriginSourceAsset]
        collection.properties += [
            collectionSource,
            collectionCollectionAssets,
            collectionSubcollections,
            collectionParent,
            collectionDirectoryRules,
        ]
        collectionAsset.properties += [collectionAssetCollection, collectionAssetAsset]
        frame.properties += [frameCoverAsset, frameSelections, frameDirectoryRules, framePlaybackSnapshot, frameHolidaySchedules]
        frameSelection.properties += [frameSelectionFrame, frameSelectionAsset, frameSelectionSourceAsset]
        frameDirectoryRule.properties += [frameDirectoryRuleFrame, frameDirectoryRuleCollection]
        importJob.properties += [importJobSource, importJobItems]
        importItem.properties += [importItemJob, importItemSourceAsset]
        playbackSnapshot.properties += [playbackSnapshotFrame, playbackSnapshotAsset]
        holidaySchedule.properties += [holidayScheduleFrame]

        model.entities = [
            source,
            sourceAsset,
            asset,
            assetOrigin,
            collection,
            collectionAsset,
            frame,
            frameSelection,
            frameDirectoryRule,
            importJob,
            importItem,
            localTombstone,
            playbackSnapshot,
            settingsKV,
            schemaMeta,
            weatherSnapshot,
            holidaySchedule,
        ]
        return model
    }

    private static func entity(_ name: String) -> NSEntityDescription {
        let entity = NSEntityDescription()
        entity.name = name
        entity.managedObjectClassName = "NSManagedObject"
        return entity
    }

    private static func stringAttr(
        _ name: String,
        optional: Bool = false,
        defaultValue: String? = nil
    ) -> NSAttributeDescription {
        attr(name, .stringAttributeType, optional: optional, defaultValue: defaultValue)
    }

    private static func intAttr(
        _ name: String,
        optional: Bool = false,
        defaultValue: Int? = nil
    ) -> NSAttributeDescription {
        attr(name, .integer64AttributeType, optional: optional, defaultValue: defaultValue)
    }

    private static func boolAttr(
        _ name: String,
        optional: Bool = false,
        defaultValue: Bool? = nil
    ) -> NSAttributeDescription {
        attr(name, .booleanAttributeType, optional: optional, defaultValue: defaultValue)
    }

    private static func attr(
        _ name: String,
        _ type: NSAttributeType,
        optional: Bool,
        defaultValue: Any?
    ) -> NSAttributeDescription {
        let attribute = NSAttributeDescription()
        attribute.name = name
        attribute.attributeType = type
        attribute.isOptional = optional
        attribute.defaultValue = defaultValue
        return attribute
    }

    private static func wire(_ a: NSRelationshipDescription, _ b: NSRelationshipDescription) {
        a.inverseRelationship = b
        b.inverseRelationship = a
    }

    private static func toOne(
        _ name: String,
        _ destination: NSEntityDescription,
        optional: Bool = false,
        deleteRule: NSDeleteRule = .noActionDeleteRule
    ) -> NSRelationshipDescription {
        let relationship = NSRelationshipDescription()
        relationship.name = name
        relationship.destinationEntity = destination
        relationship.minCount = optional ? 0 : 1
        relationship.maxCount = 1
        relationship.isOptional = optional
        relationship.deleteRule = deleteRule
        return relationship
    }

    private static func toMany(
        _ name: String,
        _ destination: NSEntityDescription,
        deleteRule: NSDeleteRule
    ) -> NSRelationshipDescription {
        let relationship = NSRelationshipDescription()
        relationship.name = name
        relationship.destinationEntity = destination
        relationship.minCount = 0
        relationship.maxCount = 0
        relationship.isOptional = true
        relationship.deleteRule = deleteRule
        return relationship
    }
}
