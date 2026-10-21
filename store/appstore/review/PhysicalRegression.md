# Physical regression record — 2026-10-21

This record separates installation, runtime checks and full workflows. NOT_RUN is not a pass. No personal library was reset or erased; production apps were never launched with `-uitest.reset`.

| Check | iPhone13 | iPadAir4 | HuaweiICL-AL20 |
|---|---|---|---|
| Installed version | 1.1.55(55), in-place | 1.1.55(55), in-place | 1.1.54(54) verified;55 install blocked by USB |
| Start/process | PASS | PASS | PASS, WARM launch54 |
| Existing library integrity | quick_check OK;112 assets/1frame/1source unchanged | quick_check OK;796/2/2 unchanged | NOT_RUN, Release app DB not read |
| Native unit/controller/fixture suite | PASS177/177 on isolated physical host | PASS177/177 on isolated physical host | JVM399/399 only; physical instrumentation NOT_RUN |
| Actual client/modified-library checks | Marker PASS;2network tests failed due isolated-app Wi-Fi denial | PASS3/3: encrypted SMB+HTTPS WebDAV6-photo hashes and modified-library marker | NOT_RUN; signed14,208-byte read-only testAPK prepared, no USB installation |
| Public encrypted SMB UI import/select/play | NOT_RUN, UI automation initialization failed | NOT_RUN, UI automation initialization failed | NOT_RUN, USB disconnected |
| Public trusted HTTPS WebDAV UI import/select/play | NOT_RUN, same blocker | NOT_RUN, same blocker | NOT_RUN, same blocker |
| Language/weather/rotation/large-text physical workflow | NOT_RUN | NOT_RUN | NOT_RUN |
| Fresh/denied/limited-permission physical workflow | NOT_RUN; use an isolated test installation, never erase the personal app | NOT_RUN; same safeguard | NOT_RUN |
| Erase-all private audio cleanup | Temporary-directory unit regression only; no physical erase | Same app code/test coverage; no physical erase | Temporary-directory unit regression only; no physical erase |

The standalone iOS runner launches the installed app by bundle identifier without reset or language overrides. Both initial device runs failed before executing the navigation test: `Timed out while enabling automation mode`. Both devices reported unlockedSinceBoot and no passcodeRequired; this does not prove UI automation was authorised. Mac DevToolsSecurity reported disabled; an attempted noninteractive enable required administrator authentication. The operator has been asked to enable device UI automation, approve any system prompt and enable the Mac developer-testing setting. No password was requested for transmission or collected.

Android briefly returned an authorised USB connection, allowing actual54 version/timestamp, WARM launch and initial UI capture. It repeatedly disappeared before the NAS/settings actions. The credential-safe UI harness refuses input when the expected device/app is absent. A temporary `/data/local/tmp/photopilot-review-ui.xml` may remain from the initial capture; remove that exact harness-created file on reconnect. No credentials were entered in that capture.

Private evidence: `.build/physical-regression-2026-10-21/` (restricted), `.build/device-install-v1.1.55/`. Temporary copied iOS databases were removed after aggregate-only verification. Raw runner startup failure bundles are diagnostic evidence, not successful UI results. Earlier actual-client public NAS tests and simulator full workflows remain valid within their own scope; they cannot fill these NOT_RUN cells.

Before a final App Store submission, repeat successful cases on the final Distribution/TestFlight build and refresh screenshot/source references as described in SubmissionMaterials.md. This record is not an M0 acceptance or a claim that all bugs have been eliminated.

## Hosted XCTest follow-up

Hosted unit tests do not require UI automation mode. A separate `dev.photopilot.physicalnas` app, unique sandbox and Keychain group, was built from current55 production sources with the deliberately modified local AMSMB2 package. It never replaced the personal application. Initial177-case runs on both devices produced161pass/16fail because two tests used Mac `#filePath` to locate fixtures; this was a test-resource portability defect. The committed test resolver now loads a bundled smoke-v1 folder, referenced only by the unit-test target. Final repeated physical runs passed177/177 on each device, without skipping tests. This validates native layout/controller and fixture business logic on hardware; it is not the missing personal-app end-to-end UI workflow.

iPad separately passed3/3 live checks: mandatory encrypted SMB, system-trusted HTTPS WebDAV, six recursive photo payload/hash matches and the modified-library marker. iPhone passed the marker but the two network cases failed immediately with CFNetwork-1009/NWPath `Denied over Wi-Fi interface`. The denial was for the isolated test app, not proof that the installed personal app is denied. No code change was justified by that policy error.

Android read-only Instrumentation was prepared without new dependencies and its certificate matched the installed54 release. During20s+60s reconnect windows no device appeared, so there were0installation attempts and no physical client test results. Its private assets, built APK and temporary signing properties were removed; a credential-free reconstruction helper remains privately.

Cleanup completed after hosted tests: the isolated application was uninstalled on both devices, all3 private credential fixture copies were removed, and no password was found in retained logs. The original application remains55. The operator's access file outside Git remains intact. Exact summaries/results: `ios-adapter-work/physical-hosted-summary.json`, `{iphone,ipad}-production-unit-bundled.xcresult`, `ipad-adapters.xcresult`, `iphone-adapters-signed.xcresult`. Final simulator177 also passed after the portable fixture change.

After clone cleanup, the original55 apps were launched again and aggregate DB checks repeated: quick_check OK; iPhone112/1/1 and iPad796/2/2 unchanged. Evidence: `.build/device-install-v1.1.55/*post-tests*`. Last host checks still reported no adb device and disabled Mac DevToolsSecurity; no personal-app UI cases were silently promoted to passing.
