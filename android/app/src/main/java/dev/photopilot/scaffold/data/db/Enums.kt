package dev.photopilot.scaffold.data.db

interface EnumWithWire {
    val wire: String
}

enum class SourceType(override val wire: String) : EnumWithWire {
    LOCAL_PHOTOS("local_photos"),
    LOCAL_FILES("local_files"),
    ICLOUD_PHOTOS("icloud_photos"),
    GOOGLE_PICKER("google_picker"),
    SMB("smb"),
    WEBDAV("webdav"),
}

enum class RetentionPolicy(override val wire: String) : EnumWithWire {
    USER_MANAGED("user_managed"),
    POLICY_GATED("policy_gated"),
}

enum class SourceStatus(override val wire: String) : EnumWithWire {
    ACTIVE("active"),
    NEEDS_AUTH("needs_auth"),
    UNAVAILABLE("unavailable"),
    DISCONNECTED("disconnected"),
}

enum class AccessState(override val wire: String) : EnumWithWire {
    ACCESSIBLE("accessible"),
    UNAUTHORIZED("unauthorized"),
    MISSING("missing"),
    REMOVED("removed"),
}

enum class AssetFormat(override val wire: String) : EnumWithWire {
    JPEG("jpeg"),
    PNG("png"),
    HEIC("heic"),
    MP4("mp4"),
    MOV("mov"),
}

enum class MediaKind(override val wire: String) : EnumWithWire {
    IMAGE("image"),
    VIDEO("video"),
}

enum class OrientationClass(override val wire: String) : EnumWithWire {
    LANDSCAPE("landscape"),
    PORTRAIT("portrait"),
    SQUARE("square"),
}

enum class CapturedAtSource(override val wire: String) : EnumWithWire {
    EXIF("exif"),
    CONTAINER("container"),
    UNKNOWN("unknown"),
}

enum class AssetReadiness(override val wire: String) : EnumWithWire {
    READY("ready"),
    PENDING("pending"),
    FAILED("failed"),
    DELETED("deleted"),
}

enum class CollectionType(override val wire: String) : EnumWithWire {
    SOURCE_FOLDER("source_folder"),
    IMPORT_BATCH("import_batch"),
    ALBUM_MAP("album_map"),
    USER_FOLDER("user_folder"),
}

enum class FrameOrderMode(override val wire: String) : EnumWithWire {
    CAPTURED_ASC("captured_asc"),
    RANDOM("random"),
    MANUAL("manual"),
}

enum class FrameSelectionMode(override val wire: String) : EnumWithWire {
    INCLUDE("include"),
    EXCLUDE("exclude"),
}

enum class PolicyState(override val wire: String) : EnumWithWire {
    ALLOWED("allowed"),
    GATED("gated"),
    REVOKED("revoked"),
}

enum class OriginState(override val wire: String) : EnumWithWire {
    ACTIVE("active"),
    DETACHED("detached"),
}

enum class ImportJobState(override val wire: String) : EnumWithWire {
    RUNNING("running"),
    PAUSED("paused"),
    PAUSED_AUTO("paused_auto"),
    COMPLETED("completed"),
    PARTIAL_FAILED("partial_failed"),
    FAILED("failed"),
    CANCELED("canceled"),
}

enum class ImportItemState(override val wire: String) : EnumWithWire {
    QUEUED("queued"),
    ENUMERATING("enumerating"),
    PENDING_DOWNLOAD("pending_download"),
    DOWNLOADING("downloading"),
    VERIFYING("verifying"),
    COMMITTING("committing"),
    IMPORTED("imported"),
    PAUSED("paused"),
    WAITING_NETWORK("waiting_network"),
    WAITING_SPACE("waiting_space"),
    NEEDS_AUTH("needs_auth"),
    FAILED("failed"),
    CANCELED("canceled"),
    SKIPPED("skipped"),
}

inline fun <reified E> enumFromWire(wire: String): E where E : Enum<E>, E : EnumWithWire {
    for (value in enumValues<E>()) {
        if (value.wire == wire) return value
    }
    throw IllegalArgumentException("unknown wire value '$wire' for ${E::class.simpleName}")
}
