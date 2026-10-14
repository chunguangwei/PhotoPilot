import Foundation

enum SourceType: String {
    case localPhotos = "local_photos"
    case localFiles = "local_files"
    case icloudPhotos = "icloud_photos"
    case googlePicker = "google_picker"
    case smb
    case webdav
}

enum RetentionPolicy: String {
    case userManaged = "user_managed"
    case policyGated = "policy_gated"
}

enum SourceStatus: String {
    case active
    case needsAuth = "needs_auth"
    case unavailable
    case disconnected
}

enum AccessState: String {
    case accessible
    case unauthorized
    case missing
    case removed
}

enum AssetFormat: String {
    case jpeg
    case png
    case heic
    case mp4
    case mov
}

enum OrientationClass: String {
    case landscape
    case portrait
    case square
}

enum CapturedAtSource: String {
    case exif
    case container
    case unknown
}

enum Readiness: String {
    case ready
    case pending
    case failed
    case deleted
}

enum OriginState: String {
    case active
    case detached
}

enum PolicyState: String {
    case allowed
    case gated
    case revoked
}

enum CollectionType: String {
    case sourceFolder = "source_folder"
    case importBatch = "import_batch"
    case albumMap = "album_map"
    case userFolder = "user_folder"
}

enum OrderMode: String {
    case capturedAsc = "captured_asc"
    case random
    case manual
}

enum FrameSelectionMode: String {
    case include
    case exclude
}

enum ImportJobState: String {
    case running
    case paused
    case pausedAuto = "paused_auto"
    case completed
    case partialFailed = "partial_failed"
    case failed
    case canceled
}

enum ImportJobSignal: String {
    case none
    case userCancel = "user_cancel"
    case fatalError = "fatal_error"
    case userPause = "user_pause"
    case autoPause = "auto_pause"
}

enum ImportItemState: String {
    case queued
    case enumerating
    case pendingDownload = "pending_download"
    case downloading
    case verifying
    case committing
    case imported
    case paused
    case waitingNetwork = "waiting_network"
    case waitingSpace = "waiting_space"
    case needsAuth = "needs_auth"
    case failed
    case canceled
    case skipped
}

enum PersistenceError: String, Error {
    case storeLowSpace = "STORE_LOW_SPACE"
    case storeWriteFailed = "STORE_WRITE_FAILED"
    case storeFileLocked = "STORE_FILE_LOCKED"
    case dbMigrationFailed = "DB_MIGRATION_FAILED"
    case dbConstraintViolation = "DB_CONSTRAINT_VIOLATION"
}

enum DataValidationFailure: String, Error {
    case selectionBothNil = "SELECTION_BOTH_NIL"
    case selectionBothSet = "SELECTION_BOTH_SET"
    case selectionRefKeyMismatch = "SELECTION_REF_KEY_MISMATCH"
    case collectionCycle = "COLLECTION_PARENT_CYCLE"
    case duplicateSourceEndpoint = "DUPLICATE_SOURCE_ENDPOINT"
    case duplicateSourceRelPath = "DUPLICATE_SOURCE_REL_PATH"
    case duplicateActiveContentHash = "DUPLICATE_ACTIVE_CONTENT_HASH"
    case includeFutureWithoutConfirmation = "INCLUDE_FUTURE_WITHOUT_CONFIRMATION"
    case stateWriteNotDerived = "IMPORT_JOB_STATE_NOT_DERIVED"
}
