# Remote frames execution ledger

## User intent and decisions

User wants old low-storage devices to select/play remote photos online and optionally enable per-frame local sync. User explicitly delegated product logic and ended product/design questioning. Defaults and implementation decisions are recorded in the design.

Branchfeat/remote-frames frommain1ef9477, managed worktree path unchanged. Prior AI1.2.0 published. New work uses existing native stacks/SDKs and planned1.3.0(60). Native implementation is in progress. End-to-end remote playback is not yet verified.

## Reconnaissance

Both platform agents independently read current metadata/import/selection/player/AI. Confirmed that adding a toggle alone cannot work. SourceAsset metadata already exists but pending SourceAsset refs never enter local-only queues. Import paths currently fetch full files and rebind refs. iOS programmatic model migration failure can recreate storage, so the design deliberately keeps17tables and derives remote directory membership from source/path metadata. Shared cache must countpartials and guard late completion at revoke.

## Work ownership

Root owns shared contract/vectors/tools/design/plan/16 and publication. iOS and Android agents will implement their whole vertical slice after reading fixed spec. The fresh builder review is complete. Native vertical slices are in progress.

## Existing deployment dependency

Apple profile refresh was rejected by automatic approval in prior task. StoreIPA still needs explicit owner authorization and usable original publishing account. This dependency does not prevent local development, GitHub delivery or Store materials. No repeated remote signing-asset actions are authorized in this slice.

## Design check decisions

Fresh builder review found7 concrete implementation issues. Resolved in design without reopening user questions. No schema change. Import provenance is versionedSettingsKV, legacy unknown copies need explicit remote hash verification before cleanup. Hash-dedup import must rehydrate missing originals. Decode-scoped leases avoid deadlock on two70MiB photos. Old reused source preferences stay explicit, new catalogOnly sources index only. Mode-present frames retain source refs during import. AI/manual/history identity work covers every new remote path. Platform implementation may now start.

## Implementation started

Both platform agents authorized for complete native vertical slices. Android reported initialshared11path+10eligibility/defaultmode and cachedomain RED→GREEN. iOS reported cache domainRED and verified existing SDK provides boundedSMB range reads plus FoundationURLSession.bytes for controlledWebDAV streaming. Baseline makecheck57tool+6source tests passed. Root shared compiled contract is now frozen, including16MiB vector cache and32MiB decoded-cache budgets. Native path tests additionally cover exactUnicode directory identity without changing shared casecount. No UI/player/cleanup completion claim yet.

## Native foundation update

iOS reports9 cache/domain tests GREEN, exactUTF8 directory comparison regression RED→GREEN and native build success. Android reports shared path/eligibility and bounded-cache tests GREEN. Native teams are connecting normal directory selection, playback, AI and local-sync/cleanup entry points. Root corrected the UI addendum into the existing02 design document. No end-to-end feature completion claimed.

## First native workflow evidence

Android RemoteFrameWorkflowTest passed on the dedicatedAVD. Metadata enumeration opened0 photo bodies, explicitSourceAsset selection produced typedremote queue, firstPNG decode opened1 photo, and noAsset/AssetOrigin original was created. Disconnect plus epoch change rejected olditem and removed it fromqueue. This is an injected nativeRoom workflow, with UI screenshots, offline-sync cleanup andfinal full regression stillpending. Rootmakecheck afterdocs passed57tooltests+6AItests.
