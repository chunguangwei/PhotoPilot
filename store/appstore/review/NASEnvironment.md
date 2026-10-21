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

Initial scaffolding runs found a read-only extracted-package setup issue, a test-link setup issue, a fixture hash prefix mismatch and the server directory-redirect incompatibility. Those preliminary runs are retained separately; only the final passing run supports the assertions above. The initial server/client-adapter check did not change production app code; the subsequent reviewer-path audit below identified application issues.

## Operation and closure

Dedicated service names: photopilot-review-dav.service, photopilot-review-smb.service; maintenance: photopilot-review-maintenance.timer. Services start on boot and restart on failure. A separate review-only publicly trusted certificate was successfully issued using TLS-ALPN443, expiring2027-01-19. Daily scoped renewal/check/reload targets its separate certificate directory and the review service only. The original Nginx certificate hash stayed unchanged. The domain and ACME443 reachability must remain available for future renewal;443 is not left listening between validations. Initial issuance and maintenance checks passed; no future renewal is guaranteed.

The owner receives private access instructions and the closure command. After approval/review completion, invoke the dedicated closure script to disable review services/timer and revoke the review credentials; existing Nginx/Docker services remain running. This task does not schedule a shutdown based on an unknown approval date. Do not stop the test environment while a resubmission still needs it.

Dedicated service logs rotate daily with up to seven retained rotations, maxage7; underlying host SSH/security logs follow host policy. The review server is not an automatic installation counter, telemetry system or user-photo backend. Normal installations do not contact it without explicit source configuration.

## Remaining review work

Paste actual endpoints/credentials only into private App Review information, verify labels against the final submitted language/build, and test the final Distribution build's full import/curation/playback UI. Server accessibility from the developer's external network does not guarantee every reviewer network permits every port. If App Review cannot reach SMB, communicate the observed constraint and agree a verification path; do not claim WebDAV substitutes for all SMB testing.

## Browser directory access follow-up

The owner reported browser GET /photos/ returning404 after successful authentication. Reproduced root/subdirectory GET404 while PROPFIND207 and direct PNG GET200 confirmed protocol access still worked. The server had no browser directory-index module/options; the earlier protocol checks did not cover browser directory listing.

Enabled authenticated autoindex only inside the existing sample-photo directory; no new files, credentials or write methods added. Final checks: root and album browser GET200 with image links, direct PNG200/hash match, root and slashless album PROPFIND207, anonymous401, write403 and parent-directory403. Evidence `.build/review-environment/browser-index/verification.json`. This server-only correction requires no app version update. Existing no-redirect PROPFIND behaviour is preserved.


## Reviewer-path audit follow-up — 2026-10-21

Repeated the external encrypted SMB/HTTPS six-photo hash, directory, invalid-credential and write-denial checks successfully. Additional HTTP checks covered GET/HEAD directory paths with/without trailing slash, non-redirecting PROPFIND on all four directory paths, six image HEAD/content lengths, Range downloads (206, exact first128 bytes), full hashes, denied parent/traversal paths and18 concurrent/reconnected reads. All37 checks passed, then passed again across two deliberate dedicated-service graceful reloads. Evidence: `.build/review-environment/reviewer-audit/` (private local evidence).

A configuration weakness was confirmed in Apache's log: ServerLimit1 left no slots for old graceful processes and caused three AH03490 errors during the earlier reload. Increased ServerLimit to4 while keeping16 active request workers, validated configuration and restarted only the dedicated DAV process. Removed the maintenance script's duplicate reload; the existing ACME certificate-install hook remains the single renewal reload point. Both deliberate subsequent reloads completed without additional AH03490 errors; the scoped maintenance job returned success and the original Nginx certificate hash still matched. One earlier external TLS handshake probe timed out; subsequent checks succeeded. These observations do not establish reachability from every review network.

Browser GET/HEAD of the two known sample directories now canonicalise missing trailing slashes; PROPFIND retains its no-redirect behaviour. Private instructions list each app field separately, with WebDAV path photos already applied before the folder picker, SMB share reviewphotos and empty subpath, whole-root selection and Include subfolders. This avoids accidentally entering photos twice or importing only the album's three images.


## Application fixes and final Release UI evidence — 1.1.53(53)

The full UI walkthrough found that both platforms applied the private-network/plaintext exception rule before trying encrypted SMB. Corrected both connection entry points and the underlying clients: encrypted public shares can connect; plaintext remains private-only and explicitly authorised. Android now checks the negotiated session property before connecting a share and when reusing a session. iOS only considers fallback for an explicit unsupported-encryption result; the pinned library's specific ENOMEM/description result is handled narrowly, while ordinary memory, authentication and network failures do not downgrade. Independent review found that compatibility case and verified its correction.

iOS AMSMB2's automatic share-discovery API opens IPC$ without an encryption option. It is therefore restricted to the private-network exception; public review access requires the explicitly supplied share. Do not leave Share empty for this endpoint. iOS also stops with a visible error when secure credential storage fails, and folder selection now displays a folder count instead of a photo count.

Final live-server Release simulator workflows all passed:

| Device | SMB | HTTPS WebDAV |
| --- | --- | --- |
| iPhone | `iphone-nas-v53-root-confirmed.xcresult` | same passing bundle |
| iPad | `ipad-smb-v53-final-confirmed.xcresult` | WebDAV case in `ipad-nas-v53-final-flow.xcresult` |

All bundles are under `.build/review-environment/reviewer-audit/`. Each passing case entered the actual NAS fields, checked encrypted connection status, selected the root and subfolders, imported six photos, explicitly curated all six, saved, started slideshow playback and returned to Sources. Playback screenshots for all four cases were visually checked. The iPad combined preliminary bundle has one failed SMB attempt and a passed WebDAV case; only the separate final SMB rerun supports the iPad SMB completion claim.

The isolated Release harness uses production1.1.53 Swift files and resources, a local AMSMB2 source copy with only the previously described unused exported rebuild marker, and simulator-specific Keychain identity. It does not represent an App Store Distribution signature. Initial unsigned attempts returned Keychain -34018; early harness attempts also missed the system save-password prompt or assumed an unsuitable scroll container. These preliminary failures are retained and are not passing evidence. Final interaction handles the system prompt and confirms root selection. Production archives are built separately with the original pinned dependency.

Additional validation: iOS176 full tests and19 NAS-specific tests passed; current iOS build/analyze and final unsigned Release archive succeeded. Android393 tests, build/lint and signed Release succeeded (0 lint errors,183 warnings); two temporary tests using the actual Android SMB/WebDAV clients passed against the live server, including encrypted SMB, both directories and six-photo hashes. Those temporary private-environment tests were removed, then the normal393-test suite was rerun successfully. This does not establish Android physical-device or every-server compatibility.

The final archive contains no private review fixture. Four temporary credential JSON copies were removed and the test runners uninstalled from the two simulators; plain-text test logs were redacted and private evidence remains access-restricted. The operator's private access file remains outside Git. Existing simulator NAS credentials are normal local Keychain data; do not describe this as erasing every trace from the simulator.

The final1.1.53 archive was signed with the existing development identity and installed over the current iPhone app without uninstalling. Version/launch and unchanged library counts were verified. Follow-up on2026-10-21: the physical iPad Air4 and iPhone13 both received the final1.1.53(53) development-signed app by in-place installation and launched successfully. Before/after DB quick_check was OK; iPad796 assets/2 frames/2 sources and iPhone112/1/1 were unchanged. Evidence: `.build/device-install-v1.1.53-both/`. This installation check does not establish the full physical-device NAS workflow. Before App Store submission, repeat the reviewer flow using the final Distribution build on physical devices and enter the private instructions in App Review information; final signing, all reviewer networks and M0 are not established by this evidence.


## 2026-10-21 physical55 UI follow-up

The final55 isolated app completed the reviewer UI workflow on both iPhone13 and iPadAir4 for encrypted SMB5005 and trusted HTTPS WebDAV5006: recursive six-image import, explicit six-image selection, frame creation, playback and exit. iPhone passed its final2/2 NAS suite; iPad passed SMB in the initial suite and WebDAV in a separate corrected-driver retry. The original personal applications were not reset. Four private playback captures were inspected; all display in portrait after EXIF orientation is applied. They establish photo rendering, not stable transition timing or actual rotation acceptance. See [PhysicalRegression.md](PhysicalRegression.md) for exact result paths, test-driver failures, cleanup and remaining device coverage. This does not replace final Distribution/TestFlight verification before submission.
