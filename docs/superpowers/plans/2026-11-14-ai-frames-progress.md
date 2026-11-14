# AI Frames progress — plan: docs/superpowers/plans/2026-11-14-ai-frames.md

## Final verified1.2.0(59) candidate

- Final models are EmbeddingGemma2 Standard387710976bytes and Multilingual CLIP Lite201547675bytes, approximately48% smaller. Both run on-device with English/Chinese category descriptions. Classification preview, editable names, one-time default and later-editable optional automatic classification are implemented.
- Android final415/415 JVM,7/7 native regression, five UI configurations/15 screenshots. Debug/Release/lint passed with0errors and194warnings. APK153726018bytes, SHA2565bf6d3bd148d2ad38dee36953b04977bc59d8347f37cadd09b286a35fff5988c. Original release certificate and fourABIs verified.
- iOS final227/227 unit tests plus5/5 iPhone UI and5/5 iPad13 UI, no failures/skips. Build/Analyze/Archive succeeded. Development signature independently verified. Final archives `/private/tmp/PhotoPilot-1.2.0-59-ai-lite-{signed,unsigned}.xcarchive`. Signed app40058542bytes, unsigned39710509bytes. Both exact1.2.0/59/minOS15.0/families1+2.
- Both production packages match catalogbb654fafba45429fb7e7139a2b4bbb4f94f80ab83598337cc9885f49aec44da4 and noticesedc8e4cf9fc789df613fc002bca7531de93033745a297e80f7359566a146f4b3. No weights/private test image/test runner or checked unused recording APIs in finaliOS package.
- Root shared final `make check` passed57 tool tests and6 source tests. All model files independently SHA256 verified. ModelScope fixed domestic sources, HFglobal andhf-mirror fallback use identical content hashes.
- Targeted Lite review's onlyImportant retired-threshold defect is fixed with actualRoom/UI RED→GREEN. Earlier complete review's confirmed issues also have regressions.
- Eight native RGB PNGs and ScreenshotEvidence120.json are prepared under store/appstore/ai-frames-v1.2.0, alongside bilingual update/review text and revisedprivacy policy. The selected screenshots show forms with production display namePhotoPilot and201.5MB download, without photo imagery.
- App Store IPA export requires the original publisher Xcode account login to refresh the Store profile for the existing Distribution certificate. Export refresh returnedNo Accounts. Development-signed archive is retained. No App Store upload/review submission was performed. Physical-device performance, minimum-supported-OS execution and domestic carrier network remain unverified.
- GitHub integration/publication and remote asset verification are the remaining release operations at this snapshot.

## Earlier steering — genuinely smaller Lite model

User found the382MB compact download too close to388MB standard. Publication is held while replacing the compact backend with Multilingual CLIP Lite,201547675bytes total. Four exact public files and full SHA256 verified. PythonORT1.27 real image/text inference produced512dim, English/Chinese cat top1 at.263549/.264495. HFtokenizers generated12 reference cases. NativeORT1.20/1.23.2, revised tokenization/preprocessing, final UI/build/regression are pending. Earlier complete suites below apply to the previous SigLIP2 candidate. No remote push/tag/release has occurred.

- New catalog idmultilingual-clip-vitb32-lite, composition revisionbbc629465ebb027948d64b7261011165076ffacc, default.24. Standard.61 unchanged.
- Catalog regression added three meaningful RED failures and then13/13 GREEN. Full package hashes and resource sync pass.
- Notices now2878133bytes, SHAedc8e4cf9fc789df613fc002bca7531de93033745a297e80f7359566a146f4b3, exact prior text plus author notices for Sentence Transformers and OpenAI CLIP.

## Confirmed decisions

- User approved automatic classification as optional and editable later. Default is off/one-time to require explicit consent for future photos; this is an engineering choice disclosed in design.
- Existing checkout migrated to /Users/weichunguang/个人文件/开发/Code/PhotoPilot; native managed worktree at /Users/weichunguang/.codex/worktrees/photopilot-ai-frames/PhotoPilot, branch feat/ai-frames.
- Baseline make check:43 passed. No implementation commit/push/tag yet.
- Independent iOS and Android domains assigned under dispatching-parallel-agents, root owns shared model catalog/test vectors/tools/docs/release.

## Task1 in progress

- Exact fixed revisions, sizes and SHA256 written in contracts/ai-models.json. Standard387710976bytes; compact including tokenizer382241138bytes (only slightly smaller, no exaggerated size claim).
- Full downloaded Gemma and SigLIP model SHA256 checks passed. Temporary cache=/private/tmp/photopilot-ai-models, not included in app.
- Nine catalog regression tests:8 meaningful failures with missing validation, then9/9 green after implementation.
- Source probe:12 Range requests passed, cross-source sample hashes equal, hf-mirror redirects overseasCDN. China mainland no-proxy runtime network unverified, not an independent China hosting guarantee.
- Same catalog bundled to native assets by validate_catalog.py --write-bundles; final freshness check required after all changes.
- Shared18 rule score vectors in contracts/vectors/ai-frame-classify.json: exclusions override, effectiveScore min(category,includeAggregate), 0.04 review band, invalid/missing scores reject. Default threshold0.20 pending actual-model calibration.
- SigLIP ONNX outputs768dim, image and text inputs required simultaneously. SentencePiece is BPE(type2), identity normalizer, no dummy prefix/removal of extra whitespace, byte fallback enabled. Do not substitute unigram/NFKC. Python raw tokenizer reference saved in ignored temporary model cache for both agents.

## Task2/3 in progress

- iOS official MediaPipe1.2.3 real UniversalEmbedder interfaces inspected, iOS15 deployment floor; SPM huge repo clone being replaced by pinned upstream minimal binary-wrapper source.
- Android actualtasks-retrieval1.2.3 API inspected; minimumSDK24, compatible with project29. SDK/runtime downloads and emulator provisioning underway.
- Android downloader review found marker-recovery and malformed resume-JSON defects; assigned regression fixes to Android owner before release.

## Remaining

Actual two-model inference on both platforms, complete UI/service/ownership integration, persistence/recovery and localization tests, full builds/lints, final source review, signed packages, GitHub release, and AppStore update materials. No release readiness claim until these pass.

## Additional verification

- Source model/tokenizer files verified together by validate_catalog.py --verify-directory; bundled catalogs match exact shared bytes.
- Catalog threshold validation added RED1/10 then GREEN10/10; compact default0.075 based on true cross-modal output, not an arbitrary0.20 threshold that rejected all tested positives.
- Python ONNX Runtime true cat-photo classification: English cat0.099832 vs beach0.038230/car0.055836; Chinese cat0.083141 vs beach0.064660/car0.056458. Both languages top1 cat. This is a functional example, not a general accuracy benchmark.
- Android API35 arm64 true native compact inference passed both English/Chinese top1cat (scores differ slightly due image path). Gemma initial SDK transitive dependency corrected; native emulator SIGILL traced to SME rdsvl instruction, runtime/CPU feature compatibility still under investigation. Standard model not yet accepted as verified.
- iOS source compiled; binary-link/old-OS compatibility and isolated true-device model smoke pending. Simulator runtime currently downloading (8.32GB, slow); iPhone13 available read-only, no production-app install/reset performed.
- Review confirmed and assigned fixes for cancelled jobs restarting unexpectedly, corrupt resume JSON, marker crash recovery, rules changing mid-run, review transition preserving stale AI membership and manual overrides. Final per-platform tests must prove each fix.
- Privacy and store update preparation drafted for1.2.0. No GitHub publication, store upload or review submission performed.

- Actual Android standard Gemma inference succeeded using unmodified official0.17.1SDK and fixed440M weights in a standalone Android process after masking the Apple-M4-AVD falsely advertised SME HWCAP for this test only. English cat/beach/car cosine=[.672742,.548048,.541629]; Chinese cat/beach/car=[.662181,.582861,.602556], both top1cat. Standard default threshold corrected from.20 to.63 based on these actual values. No inference mocks or patched model weights. This is not production APK on unmodified AVD or Android physical-device acceptance.
- Production Android standard backend remains in private :ai_runtime process; native child death is surfaced as runtime_unavailable without killing the UI or removing existing frame members. AVD SME failure upstream: https://github.com/google-ai-edge/mediapipe/issues/6293 .
- Standard catalog runtime family renamed litertlm to accurately cover iOSMediaPipe wrapper and AndroidofficialLiteRTLM0.17.1 adapter. Both platform owners notified; bundle regeneration and fresh tests required.

## Independent final review and current fix pass

- Reviewer ai_frames_final_review found three Important items: Android preview failure incorrectly deleting old owned members; iOS savePreview reading latest configuration as authority for older results; Android preview progress only in memory. Assigned once-only fix pass with actual RED→GREEN regression and full suites before release.
- Root regraded two preview behaviors Important by user impact: silently ignored explicit re-selection of previously excluded photos, and preview running without background/low-battery/thermal guard. Both assigned to Android owner in same fix pass.
- iOS exact-preview-credential race reproduced using real CoreData ContextDidSave barrier: three assertions failed under previous implementation; configure now returns exact transaction credential for CAS, GREEN pending final evidence.
- iOS simulator runtime27.2 installed; no phone installation performed (permission question unanswered, simulator validation can continue independently).
- Real iOS simulator inference now passed both models: GemmaEnglishcat.615408, Chinese.664274; SiglipEnglishcat.086971, Chinese.085523. Each above the other tested beach/car categories. Gemma500Chinese returned768dim; compact long prompt rejected explicitly. Shared standard threshold lowered finally to.61 after both backend outputs; compact.075 unchanged.
- Simulator memory observation high for standard (1.616GB RSS), compact.927GB; this is not physical iPhone memory evidence. Investigating runtime signature/delegate configuration and memory-pressure guard before release.
- New complete third-party notices copied exactly to both resources, SHAe6778190add5761118fa593ba7b1447e0d0e07a07e173674380a1253e3e7ea26. Native paginated readers avoid loading/layout entire2.86MB on initial about-page opening.

## Confirmed fix-pass evidence

- iOS exact-preview credential race now GREEN with the same deterministic CoreData barrier test used for RED, `/private/tmp/photopilot-ios-preview-race-green.log`.
- Android native Room fix pass GREEN, 2 tests in 7.688s, `/private/tmp/photopilot-preview-fixes-native.log`. Failed preview assets preserve old eligible AI-owned members. Interrupted previews restore a durable, revision-bound checkpoint and infer only remaining photos. Privacy mutations invalidate old checkpoint writers.
- Android manual exclusions remain excluded from preview with explicit guidance to re-add from photo editing. Preview now obeys foreground, battery and thermal pause conditions.
- Exact singular English Gemma prompt reconciled platform scores, Android .612952 and iOS .615408. Final shared threshold .61. Both native backends return 768-dimensional embeddings for a 500-character Chinese prompt.
- iOS independent-process context-size profiling showed variable allocator/runtime costs. 512, automatic and 2048 settings all accepted the long Chinese test. Keeping verified 2048 context; simulator RSS cannot establish physical-device memory safety. Resource preflight and pressure cleanup are being added before final build.

## Android final verification

- Implementation committed as `de1c3fc`. Final build `/private/tmp/photopilot-android-final-validation.log` passed Debug, test APK, signed Release, JVM and lint. 411 tests, zero failures/errors/skips. Lint has 194 warnings and zero errors.
- Actual native final regression `/private/tmp/photopilot-android-native-final.log` passed 3 tests in 27.323s, including durable preview resume, foreground pause, privacy checkpoint generation, old-member preservation, manual ownership and real HTTPS downloader recovery.
- Five UI configurations passed with 15 fresh screenshots under `/private/tmp/photopilot-ai-ui-android`. Root visually inspected English large-text portrait categories, Chinese large-text landscape rules and Chinese portrait models. Labels, options and actions were readable.
- APK1.2.0(59), 153708346 bytes, SHA256 `5e75081947b4cdcae9d1f98ecac7ce0f432e923ab89285cb5a48d92f527a6004`. Certificate matches1.1.58, all four ABIs retained, no model weights/private test image/keystore bundled. Exact catalog and notices hashes checked from APK. Staged under `.build/releases/v1.2.0`.
- Root shared `make check` passed with53 tool tests and6 source tests. Exact downloaded model/tokenizer hashes verified again. Original upstream notice whitespace is preserved verbatim, accounting for notice-only whitespace diagnostics.
- iOS full native XCTest before dependency/memory final refinements passed220/220. Official SDK wrapper refinement requires a fresh real inference and final suite/archive. GitHub publication and App Store submission have not occurred.

## iOS final verification and signing preparation

- Final selective SDK linkage passed real standard and compact English/Chinese inference again. Running-image and Release scans found none of the checked recording terms. Required category artifact is deterministically rebuildable with input/member/output hashes and original licensing.
- Final iPhone simulator suite `/private/tmp/photopilot-ios-final-iphone-v3.log` passed222 native unit tests and4 UI workflows. iPad13-inch same-binary UI suite passed4 workflows, `/private/tmp/photopilot-ios-final-ipad13.log`. Real UI flows covered both models, category preview, multi-frame save, editable optional automatic classification and large type.
- Root inspected English/Chinese iPhone and iPad forms, plus iPad large-type landscape. Existing iPhone non-player portrait policy remains in force. The first phone screenshot named landscape was actually portrait; test expectations/capture names are being corrected.
- Final Release build/analyze/archive succeeded. Unsigned app40,020,761 bytes, bundledev.photopilot.scaffold,1.2.0(59), minimumiOS15.0, both device families. Root independently verified exact bundled catalog/notices and no model weights/test images/test runner.
- Root checked the actual historically uploaded58 IPA. Its Store profile proves production teamCCTFP9X3SW, so the stale L35RLT89XN project default is corrected to the existing publisher team. Development-signed archive exists. Existing Store profile lacks the current Distribution certificate. Automatic export refresh failed with `No Accounts`; user was informed to log into the original Xcode publishing account. No App Store upload/submission occurred.

## Lite replacement verification

- Native iOSORT1.20 and AndroidORT1.23.2 both executed the exactq4vision+int8text package. iOS English/Chinese cat.264398/.262395; Android.264831/.263312. Each exceeds tested beach/car alternatives. These examples establish functional behavior, with broader accuracy and physical-device performance still unmeasured.
- iOS fresh-process RSS baseline170950656, loaded514244608, afterimage522469376, aftershorttext523108352 bytes. Model-related increment about352MB in simulator. Lite load budget550MB available with128MiB running-pressure floor.
- Shared HFWordPiece references now20 cases, including combining accents, specials, PUA/FFFD/ZWJ, astralCJK and U+2028/U+2029. Swift usesUTF8Data keys to preserve tokenizer/cache identity. Both source bundles and static contracts frozen for final tests.
- Android actualPillow reference alignment maxerror.015007794 (oneRGBlevel), meanabs9.68e-7. Actual native newmodel tests pass.
- Targeted independent review of only the new Lite replacement found oneImportant Android retired-model configuration defect. Actual native RED→GREEN proves first-open and restored old draft selectLite/.24, explicit save persists the correct model/threshold and preserves owned/manual selections. Final native7/7 passed, JVM415/415 and fullbuild/lint passed. New UI captures and final artifact hash pending.
- Current final catalog SHA256bb654fafba45429fb7e7139a2b4bbb4f94f80ab83598337cc9885f49aec44da4. All five model files now have fixed ModelScope domestic sources with independent MScommits and unchanged fileSHA, plus HuggingFace international andhf-mirror fallback. Four binary assets support correct206 Range withETag. The1MBvocab returns200 and noETag for Range, so safe fresh-download behavior is used. Its fullSHA was independently checked.
- New clean iPad screenshots show201.5MB and productiondisplaynamePhotoPilot. Root visually inspected normalEnglish model selection and Chinese large-type landscape. English singular photo-count copy is receiving a final one-line correction and UI regression.

## Public release verification

GitHubmain and annotatedtagv1.2.0 were pushed atomically atsource6df1cd0b559a870ee70b305ab17878b09abb65ea. PublicLatest/release APIs and tag peel matched. Four assets were anonymously downloaded in full and all size/SHA256 matched. Release notes matched the committed bilingualbody. Evidence `.build/releases/v1.2.0/remote-verification.json` and public `store/appstore/review/AIFramesRelease120.json`.

The main checkout now has the merged source, saved original untracked design draft and retained final archives/APK under`.build/releases/v1.2.0`. Local manual and automatic exports using existing assets both failed on the Store-profile/certificate mismatch. Final requested profile refresh was rejected before execution by automatic approval review because it could mutate Apple signing assets. Explicit owner approval plus original Xcode publisher account access are required to proceed with that step. StoreIPA remains pending. All authorized GitHub publication and local update-packet work is complete.
