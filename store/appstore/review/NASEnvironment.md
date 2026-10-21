# NAS review environment evidence — 2026-10-21

Live endpoints and credentials are supplied privately, not in this repository. The owner authorised an existing cloud server and dedicated testuser. No NAS hardware was purchased and no production server account was given to reviewers.

## Configuration

- Ubuntu24.04; dedicated Samba and Apache services/configuration, separate from pre-existing Nginx/Docker services.
- Target ports were unused before setup. HTTPS WebDAV5006 and SMB5005 are the externally tested review endpoints. SMB445 also listens, but connections from the tested external network timed out. Do not present445 as externally verified.
- 5005 was initially considered for HTTP redirection; external445 failure led to its final use as the authorised alternate SMB port. There is no plaintext WebDAV endpoint on5005.
- Dedicated no-shell, locked-system-password account; only the synthetic-image share is accessible through the service. Guest/incorrect credentials rejected.
- SMB3 minimum, mandatory signing and required encryption; external verification negotiated SMB3.1.1 with encryption.
- WebDAV uses a dedicated review-only publicly trusted certificate (expires2027-01-19). Authenticated PROPFIND/GET/HEAD/OPTIONS are supported. Other methods are rejected before DAV processing; parent-directory access is forbidden.
- Directory requests with/without a trailing slash are handled without redirecting authenticated PROPFIND. This avoids the observed iOS redirect/authentication mismatch.
- Six original synthetic UI-test PNG files: three in the root and three in an album subdirectory. Recursive root import should yield six images. No customer content, write access, deletion privileges or administrative NAS access.

## Actual verification

External protocol evidence: `.build/review-environment/verification.json` (private local evidence). Both protocols listed the two directories and downloaded all six photos with exact SHA256 matches against SeedMedia. HTTPS anonymous/wrong password401; writes (PUT/MKCOL/DELETE/MOVE/COPY/LOCK/PROPPATCH)403; parent directory403. SMB create/delete denied, guest/wrong password rejected. A generic protocol client is not the same as an app UI walkthrough.

The app's actual iOS SmbSourceAdapter and WebDavSourceAdapter were also exercised in an isolated test build against the live server. Three tests passed: encrypted SMB six-image enumeration/payload/hash checks, HTTPS system-trust WebDAV six-image checks, and runtime loading of a deliberately modified local AMSMB2 framework. Tests use private, ignored fixtures; no credentials were added to tracked tests, release assets or public screenshots. This is a simulator/client integration test, not a physical-device/full-review acceptance claim. Evidence: `.build/review-environment/actual-ios-adapters-tls-final.xcresult` and `actual-ios-adapters-tls-final.log`.

Initial scaffolding runs found a read-only extracted-package setup issue, a test-link setup issue, a fixture hash prefix mismatch and the server directory-redirect incompatibility. Those preliminary runs are retained separately; only the final passing run supports the assertions above. Production app code was not changed.

## Operation and closure

Dedicated service names: photopilot-review-dav.service, photopilot-review-smb.service; maintenance: photopilot-review-maintenance.timer. Services start on boot and restart on failure. A separate review-only publicly trusted certificate was successfully issued using TLS-ALPN443, expiring2027-01-19. Daily scoped renewal/check/reload targets its separate certificate directory and the review service only. The original Nginx certificate hash stayed unchanged. The domain and ACME443 reachability must remain available for future renewal;443 is not left listening between validations. Initial issuance and maintenance checks passed; no future renewal is guaranteed.

The owner receives private access instructions and the closure command. After approval/review completion, invoke the dedicated closure script to disable review services/timer and revoke the review credentials; existing Nginx/Docker services remain running. This task does not schedule a shutdown based on an unknown approval date. Do not stop the test environment while a resubmission still needs it.

Dedicated service logs rotate daily with up to seven retained rotations, maxage7; underlying host SSH/security logs follow host policy. The review server is not an automatic installation counter, telemetry system or user-photo backend. Normal installations do not contact it without explicit source configuration.

## Remaining review work

Paste actual endpoints/credentials only into private App Review information, verify labels against the final submitted language/build, and test the final Distribution build's full import/curation/playback UI. Server accessibility from the developer's external network does not guarantee every reviewer network permits every port. If App Review cannot reach SMB, communicate the observed constraint and agree a verification path; do not claim WebDAV substitutes for all SMB testing.
