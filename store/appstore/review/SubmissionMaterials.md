# Submission materials — 2026-10-21

This packet describes the prepared 1.1.55 build, not an App Store submission or approval. Facts marked unverified are not to be copied as completed form answers.

## Confirmed business model

The owner confirmed: free download, no advertising, no In-App Purchases and no subscription. Possible future charges depend on adoption; there is no paid feature or purchase flow in this binary. Do not advertise hypothetical prices, future subscriptions or a perpetual-free guarantee. Before monetising, recheck weather API commercial terms, third-party and app distribution rights, Apple commercial agreements, purchase implementation and updated disclosures.

Open-Meteo gives non-commercial private/non-profit apps without advertising or subscriptions as examples permitted on its free service. Its rate limits and attribution still apply. Free pricing alone cannot classify every business use: if this app becomes a commercial product/promotional activity, use an appropriate commercial licence. [Provider terms](https://open-meteo.com/en/terms).

## Adoption measurements without an SDK

Use App Store Connect's acquisition/download reports and Analytics first. Apple provides downloads and, where available, sessions, active devices and retention. Usage metrics are limited by opt-in and privacy thresholds; installations are not unique people and include reinstalls/multiple devices. Do not infer an exact installed-user total, or assume these reports reveal whether someone used SMB/weather. No analytics SDK, custom installation ping or device identifier has been added.

Sources: [Analytics overview](https://developer.apple.com/help/app-store-connect-analytics/), [usage definitions and privacy](https://developer.apple.com/help/app-store-connect-analytics/engagement/app-usage).

## Ready inputs

- Current binary: iOS1.1.55(55), iPhone/iPad, minimum iOS15, English/Simplified Chinese. Traditional Chinese listing copy is not native UI support.
- Proposed category Photo & Video; no PhotoPilot registration/login, social feed, advertising or purchases.
- Public support and privacy URLs are in the main README. Contact email chunguangwee@gmail.com. Keep the policy consistent with the final submitted binary.
- Eleven current Release simulator screenshots, six synthetic review images, English review steps, source notices and SMB corresponding sources are prepared in this folder and public GitHub Releases. These are not Distribution-build acceptance evidence.
- The app never uploads user photos to the review server. NAS uses explicit configuration and a read-only synthetic test directory.

## Account-specific and evidence gaps

| Item | Required before final submission |
|---|---|
| Distribution identity | Verify authorised membership/team, agreements/ASC role and app record; decide current Bundle ID; prepare Distribution archive/Validate/upload only with submission authorisation |
| Publisher/contact | Real account/legal publisher identity, review contact name and reachable phone; use Apple Standard EULA and the prepared third-party permission supplement. Do not invent a company or substitute an email for required fields |
| Territories | Mainland China excluded per prior decision; confirm exact others and factual EU DSA trader/non-trader status |
| Privacy | Use the prepared55 data-flow/label worksheet: Open-Meteo only, no active wttr.in fallback. Complete the real ASC questionnaire; empty manifest does not prove no collection |
| Export compliance | Classify actual SMB cryptographic code/use and territories through the real questionnaire; no blanket HTTPS-only exemption |
| Licensing | Standard EULA plus explicit LGPL permission exception adopted in product terms/root licence/notices; finish the final signed modified-library distribution path and matching app-source delivery at release |
| Material rights | 55 replaces eight AI demo images with project-owned procedural artwork and three uncertain melodies with original synthesis. Keep the specific historic melody/provenance evidence and verify final package hashes |
| Release smoke | Final signed build: fresh install, limited/denied permissions, offline playback, both NAS protocols, cold-start skip, rotation/keyboard/large text. Current dev installs/UI tests do not cover every item |

Future App Store upload/submission and charging remain separate actions. Credentials belong only in private App Review information, never public metadata or Git.
