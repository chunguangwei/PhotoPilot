# Submission materials — 2026-10-23

This packet describes the prepared 1.1.58 build, not an App Store submission or approval. Facts marked unverified are not to be copied as completed form answers.

提交入口与打包步骤见 [SubmissionRunbook.md](SubmissionRunbook.md)。截图尺寸、文件摘要和候选归档核对结果见 [PreparationEvidence.json](PreparationEvidence.json)。

## Confirmed business model

The owner confirmed: free download, no advertising, no In-App Purchases and no subscription. Possible future charges depend on adoption; there is no paid feature or purchase flow in this binary. Do not advertise hypothetical prices, future subscriptions or a perpetual-free guarantee. Before monetising, recheck weather API commercial terms, third-party and app distribution rights, Apple commercial agreements, purchase implementation and updated disclosures.

Open-Meteo gives non-commercial private/non-profit apps without advertising or subscriptions as examples permitted on its free service. Its rate limits and attribution still apply. Free pricing alone cannot classify every business use: if this app becomes a commercial product/promotional activity, use an appropriate commercial licence. [Provider terms](https://open-meteo.com/en/terms).

## Adoption measurements without an SDK

Use App Store Connect's acquisition/download reports and Analytics first. Apple provides downloads and, where available, sessions, active devices and retention. Usage metrics are limited by opt-in and privacy thresholds; installations are not unique people and include reinstalls/multiple devices. Do not infer an exact installed-user total, or assume these reports reveal whether someone used SMB/weather. No analytics SDK, custom installation ping or device identifier has been added.

Sources: [Analytics overview](https://developer.apple.com/help/app-store-connect-analytics/), [usage definitions and privacy](https://developer.apple.com/help/app-store-connect-analytics/engagement/app-usage).

## Ready inputs

- Prepared candidate binary: iOS1.1.58(58), iPhone/iPad, minimum iOS15, English/Simplified Chinese. Traditional Chinese listing copy is not native UI support.
- Proposed category Photo & Video; no PhotoPilot registration/login, social feed, advertising or purchases.
- Public support and privacy URLs are in the main README. Contact email chunguangwee@gmail.com. Keep the policy consistent with the final submitted binary.
- Eleven current Release simulator screenshots, six synthetic review images, English review steps, source notices and SMB corresponding sources are prepared in this folder and public GitHub Releases. These are not Distribution-build acceptance evidence.
- The app never uploads user photos to the review server. NAS uses explicit configuration and a read-only synthetic test directory.

## Account-specific and evidence gaps

| Item | Required before final submission |
|---|---|
| Distribution identity | Verify authorised membership/team, agreements/ASC role and app record; decide current Bundle ID; prepare Distribution archive/Validate/upload only with submission authorisation |
| Publisher/contact | Real account/legal publisher identity, review contact name and reachable phone; use Apple Standard EULA and the prepared third-party permission supplement. Do not invent a company or substitute an email for required fields |
| Territories | [TerritoryPlan.md](TerritoryPlan.md): intended all selectable non-mainland territories, Hong Kong/Macao/Taiwan included separately; France documentation and factual EU DSA status remain conditional, not silently excluded |
| Privacy | [AppPrivacyQuestionnaire.md](AppPrivacyQuestionnaire.md): complete bilingual data-type/purpose/linking/tracking worksheet, Open-Meteo only, support-collection conditional branch; candidate manifest includes the three weather categories; the ASC form remains separate |
| Export compliance | [ExportComplianceQuestionnaire.md](ExportComplianceQuestionnaire.md): actual58 candidate archive hashes and SMB crypto evidence, standard/additional encryption branch, France/CCATS conditions; no blanket HTTPS-only exemption |
| Licensing | Standard EULA plus explicit LGPL permission exception adopted in product terms/root licence/notices; finish the final signed modified-library distribution path and matching app-source delivery at release |
| Material rights | 55 replaces eight AI demo images with project-owned procedural artwork and three uncertain melodies with original synthesis. Keep the specific historic melody/provenance evidence and verify final package hashes |
| Release smoke | Final signed build: fresh install, limited/denied permissions, offline playback, both NAS protocols, cold-start skip, rotation/keyboard/large text. Current dev installs/UI tests do not cover every item |

Future App Store upload/submission and charging remain separate actions. Credentials belong only in private App Review information, never public metadata or Git.
