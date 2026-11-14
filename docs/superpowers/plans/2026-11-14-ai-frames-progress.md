# AI Frames progress — plan: docs/superpowers/plans/2026-11-14-ai-frames.md

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
