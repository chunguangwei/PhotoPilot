# App Review environment and materials implementation plan

> For agentic workers: use superpowers:executing-plans to implement this authorized operational task.

**Goal:** Provide temporary isolated read-only SMB/WebDAV review access and accurate free-release submission materials.

**Architecture:** One existing authorized Ubuntu server, dedicated Samba/Apache processes and directory, separate review TLS certificate issued with the existing ACME account, dedicated no-shell testuser. No production service reconfiguration. Credentials remain outside Git. Current build remains free/no ads/no purchases; no analytics SDK added.

**Spec:** User instruction 2026-10-21 and 10 §13.11.

## Global constraints

- Inspect ports5005/5006/445 before starting; preserve other services.
- SMB3 encryption required; WebDAV HTTPS5006, SMB5005 alternate port because external445 timed out.
- Only synthetic review images, read-only; no household data or production credentials.
- Actual protocol, external connectivity and write-denial evidence required.
- Documents distinguish tested facts from App Store account/legal/supplier evidence still missing.
- No App Store upload/submission, purchases, new accounts or analytics SDK.

## Tasks

- [x] Inspect server identity, services, target ports, certificate and DNS.
- [x] Install minimal Samba/Apache packages without starting default services; create isolated config, testuser and image directory.
- [x] Validate encrypted SMB listing/download and HTTPS PROPFIND/download; prove unauthorized/write requests denied; verify original services unaffected.
- [x] Verify actual application clients and preserve evidence; report any device/network limitations.
- [x] Prepare operator-only access and shutdown instructions; deliver password privately in conversation.
- [x] Update privacy/business/analytics/license and review documentation; unknowns remain explicit.
- [x] Run documentation/tool checks, update16, commit/push documents without credentials.

## Review focus

Wrong password/guest denied; path traversal cannot access private paths; DAV/SMB writes denied; TLS trust/expiry correct; only intended listeners/services added. Closing review services must not stop existing nginx/Docker services.

## Reviewer-path audit follow-up

- [x] Fresh external HTTPS/browser/DAV and SMB permission/download checks, including share discovery, range/metadata and connection recovery.
- [x] Release iPhone/iPad real UI: first launch, NAS fields, test connection, folder/recursive selection, six-image import, explicit curation, save and playback.
- [x] Independent read-only server/operation/review-note audit; apply verified corrections.
- [x] Record final checks and private exact-field review notes; exclude credentials from Git and public test artifacts.


## Confirmed application defect from the reviewer walkthrough

The production iOS NAS UI unconditionally applies the private-network/plaintext exception gate before attempting any SMB connection. The live encrypted SMB adapter test passes, but the same public review endpoint is blocked in the actual app by the unencrypted-public-address warning. This contradicts the existing encrypted-by-default requirement in01 §NAS/03 §SMB; the private-only restriction applies to plaintext fallback. The adapter also needs inspection because its existing fallback is unconditional. Fix and test encryption-first/public-no-plaintext across UI, folder discovery, import and sync; audit Android independently. Do not weaken the existing private-only/explicit-opt-in rule for plaintext.

- [x] Fix the confirmed iOS encryption gate with meaningful failing/passing tests; audit Android's corresponding paths and fix confirmed issues.
- [x] Rebuild and rerun both live review workflows after application fixes, then run required platform checks and update actual evidence.

Final result: four device/protocol Release simulator reviewer workflows passed, with preliminary harness failures retained separately. See store/appstore/review/NASEnvironment.md for exact bundles and remaining Distribution/physical-device limitations.
