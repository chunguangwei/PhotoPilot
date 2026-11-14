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

## iOS native protocol evidence

iOS12 domain/protocol tests passed in photopilot-remote-protocol-green.xcresult. Metadata scan creates0Asset/ImportJob/ImportItem rows and keeps17tables. Savedsource selection producesremote queue, cachefetch occurs once, epoch/disconnect invalidates it. URLProtocol AsyncBytes tests reject unknown/understated-length overflows before write and remove partials. Exact UTF8 distinctfolders remain isolated. This remains injected protocol/native evidence, with ordinaryUI integration and release checks pending.

## Storage and remote AI integration

Android native scoped sync/release/rehydration passed with the sameAssetID and canonical source references. Remote AI native test used the actualLite encoders for English/Chinese rules, preview-without-frame-write, confirmation ownership and manual exclusion. Its realHTTP/WebDAV PlayerEngine test displayed a bitmap, fetched only current+next from4items and cleared after source revoke. Production private-host policy remained intact. UI captures are being corrected and are not yet final.

iOS22 native integration tests passed in photopilot-remote-storage-green2.xcresult, including16 priorAIStore cases. RemoteAI candidates/confirmation/exclusions,1-of3 scoped sync, canonical source reference, confirmed release without privacy tombstone, same-ID rehydration and protection by another local frame all passed. UI controls/screenshots and fullplatform suites remain pending.

Root prepared the1.3.0 Store packet and bilingual privacy change, with explicit extra storage for models/metadata/synced originals. The lockedSMB source archive/manifest was hash-verified and copied into the ignored1.3.0 release directory. No native candidate has been published.

## Independent Android candidate verification and remaining fixes

Root independently ran makeandroid-build/android-lint/android-test successfully. XML reports425tests,0failures,0errors,0skips; lint0fatal/0error/201warnings. The preliminary1.3.0(60)APK was153939010bytes, SHA2566718da0b19cd342bf87db934e75c0e303610aa6baec4eec10ba343766f2dcf7c, original9cd985f16aec8a0e492ff834aa7a07de6b426f224b2fd6d788a5965cc1b4f37c certificate and4ABIs. Root verified no weights/keystore and exactcatalog/notices hashes. This APK is copied to the ignoredrelease directory but is now superseded by pending fixes and MUST be rebuilt/rehashed before publication.

Root visually caught Android large-text photo-row clipping. Native RED→GREEN fixed rows to single-line ellipsis, full accessibility name and long-press full-name access, with48dp hit bounds. Refreshed sixconfiguration18light captures and twoconfiguration6dark captures are under /private/tmp/photopilot-remote-ui-android. Root inspected key English/Chinese portrait/landscape/large/dark screens. Skill search returned no verified dynamic-type match; the built-in native checklist and actual screenshots were used. These are isolatedfixture debug UI evidence, with fake metadata and no COCO photo.

Android fullnative14JUnit and legacy settings/source/player/frameeditor/selection cases passed before later authority compatibility fixes. A realSave test caught AIownership being rewritten during mode-only edits and now saves onlyselectiondelta. KeepCopies disconnect/reconnect uncovered a second realgap. Native regressions and fixes are in progress. Root also found a hardcoded6extension remote whitelist differing from existing17static formats; Android is reusing the existingformat whitelist. Sources edit/latest-scope must preserve catalogOnly and excludeframe jobs to avoid accidental fullimports. FinalAPK hash above is stale for these corrections.

On iOS keepCopies was reproduced RED then fixed GREEN. Only verified allowed sameepoch/revision/hash copies have provenance moved to the disconnectepoch. Detachedcopy playback is local-mode only, while remote access remains rejected. Frame-scoped reconnect sync verifies before restoringactive origin. Nested playback settings now transactionally merge playback fields into currentJSON to preserve newstorage mode. New nativeprotocol26tests passed. A full-suite Swift6 UIKit executor crash from background notifications was diagnosed and MainActor delivery fixed, with fullrerun pending.

iOS source-edit/refresh/startup paths were checked to route catalogOnly sources to index only. Preferences read Source.settings_json, not latestjobscope. Frame-scoped startup recovery is delegated toFrameStorageService. The actualUI has completedremote→localready→remote once; bilingual/large/dark captures, complete suites andarchive remain in progress. Fresh integrated review has NOT started and must happen once bothplatforms freeze.

## Single fresh integrated review in progress

Reviewer /root/remote_frames_integrated_review was spawned with fork none under requesting-code-review. It is read-only and does not delegate. Native trees had reported subjectfreeze, then iOS found legacyAsset-only migration and CoverPreview revoke gaps and resumed specific fixes. Reviewer was asked to review frozenAndroid first and hold finaliOS conclusions until finaltree. It has read bothplatform foundations and is issuing concrete findings; all below were independently checked against actual source byroot before assignment to original nativeagents. One comprehensive report/verdict will follow the fix deltas, with no repeated fullreview.

Confirmed Android findings being fixed in this one pass

- LegacyAsset-only NAS selections cannot become remote/releasable through mode edit. Migrate confirmedNAS-only source identity, order, excludes andAI ownership transactionally, keeping true localorigins protected.
- Remote selector Preview bitmap uses ordinaryImageView dialog without display-time revalidation or revoke observation. Cover delayedcompletion and alreadydisplayed revoke, with dismiss cleanup.
- Cache and local import/sync physicalspace checks do not share reservations. Introduce shared64MiB write guard/reservation and lowspace concurrency regression, preserving independent128MiB cachequota.
- PlayerEngine single-thread executor cannot cancel oldnext prefetch on manual navigation/queuegeneration. Hold cancelable consumers and rejectstale generation beforework, so slowB cannot block manualD untiltimeout or drain obsolete queuedfetches.
- HistoricalAsset exclude aliases can bypass remote AI when no localready candidate is attached. Reuse historicalorigins for exclusions andmanual protection.
- ImportCoordinator MergeExisting rehydrate readsAsset outside transaction then fullrow restore fromstale copy. Concurrent hide/delete duringcommitStaged can be overwritten. Re-read privacy/deleted/tombstone transactionally, updateonlyready/failure fields and preserveprivacy with deterministicinterleaving regression.
- Cacheclear cancels/deletes then immediatelydrops reservations while oldwriter may stillholdfd/write/sync beforefinally. Keep oldgeneration accounting until actualcompletion/cancellation, and prove clear/newacquire cannot exceedbudget.

Confirmed iOS findings assigned to same nativefixpass

- remoteResolution excludesonlySourceAsset rawID, bypassing historicalAsset EXCLUDE throughdirectoryrule. AI manualChange/apply/candidate identity needs the same historicalorigin aliases, including no-localready state.
- prefetchNext infinitely retries a permanentbad/404 next photo every3seconds, leaving currentA forever and never reaching validC. Bound retries, skip failednext and provide retry/back whennoneusable.

Latest iOS completeQA native suite254tests0failure0skip withactualLite encoders, iPhone3UI initiallygreen, finaliPhone+iPad3each light/large/true-dark andarchive/analyze running. Earlier“-dark”iPhone files are stillinvaliddark evidence. Production newstorage/sourcepicker now usesexistingThemeColor.accentAction andpanel/text tokens afterrootvisualcheck caughtsystemblue drift. Finalarchive beforelaterfixes must be refreshed.

## Final local delivery verification

Both implementations are frozen. The single read-only reviewer completed the same-pass delta verification and returned Ready to merge with0 openCritical/Important/Minor findings. Root verified each reported cause against actualcode before assigning fixes. The final reports include legacy selected-cover preservation on bothplatforms and source-alias exclusions inAI.

Root independently ran Androidbuild/lint/test on finalsource. XML435tests/0failure/0error/0skip, lint0error/204warnings. Finalnative22/22 passed,6light configurations passed and2dark configurations passed their corrected contrast assertions. The earlier exact-color assertions were superseded by actualcontrast checks, with productionstyling unchanged. Five legacyinstrumentation groups also passed. Root inspected refreshed small/large/landscape/dark captures, retaining24PNG snapshots under the ignoredrelease directory.

Final AndroidAPK153988162bytes, SHA224743a5fde03991a010d0e8fe27c643186326814d837e8bfe64cb8e00e3a372. Root verified originalcertificate9cd985f16aec8a0e492ff834aa7a07de6b426f224b2fd6d788a5965cc1b4f37c,4ABIs, version1.3.0/60, API29 minimum, target36, catalog/notices exact and weight/QA exclusion. Root replaced the preliminary6718candidate in the private release directory.

iOS finalproduction includes historicalvideo guard and completed-local-frame AI incremental sync with manualpause protection. Finalnative260/0failure/0skip, iPhone3UI+iPad3UI andincremental1UI passed. Root independently rebuilt/analyzed finalsource. Compiler output has existing Sendable and AppIntents warnings. Correct finalarchive source is /private/tmp/PhotoPilot-1.3.0-60-remote-ready.xcarchive, executableSHA11a23b91b508f592f0a3ef609ad9a943a50bd35f31d51fd7ba5ff301f75718c6. The earlier sameversion archive was superseded. Root replaced its private retained archive, verified min15/devicefamilies/catalog/notices/privacy and unsignedstatus. Final21iOS captures were checked against their source hashes and actualRGB dimensions, then stored in the versionedStore directory. Landscape2752×2064 captures use nativeUIKit orientation normalization, with opaquealpha removal preservingRGB samples. The newstorage footer contrast was measured5.14:1 on lighttheme after semantic-token correction.

Store materials and privacy policy are prepared. Publicpacket states the actual simulator/protocol scope and the remaining owner signing/account dependency. Physicalolddevice/minimumOS/realNAS/longrun remain pending. Source model catalogue and LGPL sourcearchive are unchangedlocked inputs. GitHub integration/publication is the remaining authorized delivery action.

## Published and anonymously verified

Featurecommit `c24aefe481456625e1a7e6382ed06325d1d55765` is merged into the originalmain and pushed with annotatedtagv1.3.0. The mergedmain passed makecheck, ios-build, ios-analyze, android-build, android-lint andandroid-test. The originalmain was clean at the1ef9477 base, and the merge was fast-forward. No source edits followed finalverification.

[GitHub v1.3.0](https://github.com/chunguangwei/PhotoPilot/releases/tag/v1.3.0) is public andLatest. AnonymousAPI resolvedthe tag to the exactfeaturecommit and all4complete assetdownloads matched localsize/SHA plusGitHubdigest. SignedAPK SHA224743a5fde03991a010d0e8fe27c643186326814d837e8bfe64cb8e00e3a372. The publicprivacy policy was anonymouslyread and matched sourceSHA36c4a8c4e309aa0273416c45dd7c6eb901ecb7c455e394993e62550372cc6516. Privateverification JSON and mainchecklogs are retained withthe originalproject's `.build/releases/v1.3.0` artifacts. Host-managedworktree is retained forunsignedarchive continuation.

Authorized implementation, verification, packaging, repositorypublication andStore preparation are complete. Storeexport/account/signing approval, physicalolddevices, minimumOS runtime, physicalNAS andlongrun are the actual remaining externalvalidation items. Production Apple assets were not changed inthisslice.
