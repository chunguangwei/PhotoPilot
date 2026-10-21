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
