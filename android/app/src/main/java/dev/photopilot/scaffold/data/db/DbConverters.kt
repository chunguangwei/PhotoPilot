package dev.photopilot.scaffold.data.db

import androidx.room.TypeConverter

class DbConverters {
    @TypeConverter
    fun sourceTypeToWire(value: SourceType): String = value.wire

    @TypeConverter
    fun wireToSourceType(value: String): SourceType = enumFromWire(value)

    @TypeConverter
    fun retentionPolicyToWire(value: RetentionPolicy): String = value.wire

    @TypeConverter
    fun wireToRetentionPolicy(value: String): RetentionPolicy = enumFromWire(value)

    @TypeConverter
    fun sourceStatusToWire(value: SourceStatus): String = value.wire

    @TypeConverter
    fun wireToSourceStatus(value: String): SourceStatus = enumFromWire(value)

    @TypeConverter
    fun accessStateToWire(value: AccessState): String = value.wire

    @TypeConverter
    fun wireToAccessState(value: String): AccessState = enumFromWire(value)

    @TypeConverter
    fun assetFormatToWire(value: AssetFormat): String = value.wire

    @TypeConverter
    fun wireToAssetFormat(value: String): AssetFormat = enumFromWire(value)

    @TypeConverter
    fun orientationClassToWire(value: OrientationClass): String = value.wire

    @TypeConverter
    fun wireToOrientationClass(value: String): OrientationClass = enumFromWire(value)

    @TypeConverter
    fun capturedAtSourceToWire(value: CapturedAtSource): String = value.wire

    @TypeConverter
    fun wireToCapturedAtSource(value: String): CapturedAtSource = enumFromWire(value)

    @TypeConverter
    fun assetReadinessToWire(value: AssetReadiness): String = value.wire

    @TypeConverter
    fun wireToAssetReadiness(value: String): AssetReadiness = enumFromWire(value)

    @TypeConverter
    fun collectionTypeToWire(value: CollectionType): String = value.wire

    @TypeConverter
    fun wireToCollectionType(value: String): CollectionType = enumFromWire(value)

    @TypeConverter
    fun frameOrderModeToWire(value: FrameOrderMode): String = value.wire

    @TypeConverter
    fun wireToFrameOrderMode(value: String): FrameOrderMode = enumFromWire(value)

    @TypeConverter
    fun frameSelectionModeToWire(value: FrameSelectionMode): String = value.wire

    @TypeConverter
    fun wireToFrameSelectionMode(value: String): FrameSelectionMode = enumFromWire(value)

    @TypeConverter
    fun policyStateToWire(value: PolicyState): String = value.wire

    @TypeConverter
    fun wireToPolicyState(value: String): PolicyState = enumFromWire(value)

    @TypeConverter
    fun originStateToWire(value: OriginState): String = value.wire

    @TypeConverter
    fun wireToOriginState(value: String): OriginState = enumFromWire(value)

    @TypeConverter
    fun importJobStateToWire(value: ImportJobState): String = value.wire

    @TypeConverter
    fun wireToImportJobState(value: String): ImportJobState = enumFromWire(value)

    @TypeConverter
    fun importItemStateToWire(value: ImportItemState): String = value.wire

    @TypeConverter
    fun wireToImportItemState(value: String): ImportItemState = enumFromWire(value)

    @TypeConverter
    fun mediaKindToWire(value: MediaKind): String = value.wire

    @TypeConverter
    fun wireToMediaKind(value: String): MediaKind = enumFromWire(value)
}
