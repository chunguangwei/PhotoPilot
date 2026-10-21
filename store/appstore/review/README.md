# PhotoPilot — App Review preparation

This is a local preparation packet. It has not been submitted to App Review. Local unsigned Release inspection and remaining submission requirements are recorded in [ReleaseAudit.md](ReleaseAudit.md).

`ReviewPhotos.zip` contains the six synthetic PNG images used by the repository's UI tests (`ios/PhotoPilotUITests/SeedMedia`). It contains no customer photos or credentials. Before submission, attach the archive to the review information or provide a verified download link, and confirm the steps below against the exact Release build.

## Suggested review notes

PhotoPilot is an offline photo-frame app for iPhone and iPad. No PhotoPilot account, subscription, in-app purchase, or sign-in is required.

1. On launch, the brand presentation lasts up to six seconds. **Skip** is available immediately.
2. **Take a look first** on the welcome screen opens a sample slideshow without requesting photo access. This preview does not import the samples into the library or exercise frame editing.
3. To test the full workflow, save images from the attached `ReviewPhotos.zip` to Photos or Files. Choose **Choose photos → Photos on this device**, then use the Photos or Files option. With Photos, grant access to the test images; Files provides an alternative without granting access to the photo library.
4. After import, choose what to display, select a few images, name and save the frame, configure playback, and start the slideshow. Importing images alone does not add them to a displayed frame.
5. Tap the slideshow to reveal pause, previous/next, and exit controls. Playback settings include transition, image fitting, music and rest hours. Offline playback uses imported local copies.
6. Weather is optional and initially off. In Settings, enable Weather and choose a city. Location permission is only needed for **Use current location**; choosing a city manually does not require it. Weather failure does not block local photo playback. When no valid weather is available, the player and rest screen hide the weather row without an unavailable placeholder; still-valid cached weather remains visible.
7. Settings → About provides the Privacy Policy and scrollable open-source license texts.
8. Network downloads default to unmetered networks; a user can explicitly disable this restriction in Settings. Local files and already-downloaded photos remain available offline. NAS sources are optional connections to a server configured by the user. The app does not create an account or scan the LAN. WebDAV and SMB test access must be supplied separately as described below.

## Submission items still to prepare

- Confirm these notes and all button labels against the final Release binary and its chosen language.
- Privately enter the prepared read-only NAS environment details (see NASEnvironment.md): protocol, host, port, share/path, scoped credentials and six-image expected result. Recheck reachability and the final submitted binary before submission. Do not put credentials in this repository; the bundled sample slideshow does not cover NAS.
- Verify the online privacy policy matches the submitted binary and is readable without authentication.
- Complete the actual App Store Connect privacy, age-rating, encryption, territory and review-contact fields.
- Resolve the dependency-distribution and weather-privacy items in `10-合规与上架材料.md` before submitting.

Supporting a review does not require access to the developer's household NAS, personal photo library or personal account.

## Submission worksheet (1.1.55 preparation)

- Bundle ID currently `dev.photopilot.scaffold`; final owning distribution team and Bundle ID decision remain unconfirmed. Development installation is not App Store signing.
- Native UI languages: English and Simplified Chinese. Traditional Chinese store copy is a listing localization only.
- Proposed primary category: Photo & Video; no PhotoPilot login, advertising, purchases or public user-content sharing in this build. The user confirmed on 2026-10-21: free download, no advertising and no In-App Purchases/subscriptions in this release. Future monetisation is not implemented and requires a new service-licensing/privacy/pricing review before activation.
- Privacy/support URLs and contact are in the repository README and policy. Verify their final published versions before submission.
- Age-rating questionnaire: answer from the actual private/local photo-frame features, not arbitrary customer photo content; do not predeclare Apple's resulting age rating.
- Export compliance: include SMB's non-system cryptographic implementations in the assessment; the archive does not predeclare an encryption exemption.
- Availability: exclude mainland China as already decided; exact other territories and EU DSA status still need account-level completion.
- NAS review access must be supplied privately: protocol, externally reachable host, port, share/path, scoped username/password, validity period, six non-private photos and expected import result. No production/household NAS access is needed. Keep the environment available throughout review.
- Source delivery/rebuild evidence: [SMB distribution record](SMBDistribution.md). Standard EULA/third-party permissions and source/rebuild delivery are prepared; the final signed modified-library distribution route remains a release-stage verification.
- Weather data-flow worksheet: the app's own photo data stays on device; manual city search sends query/language to Open-Meteo geocoding, forecast sends coordinates rounded to0.1° to Open-Meteo. Version55 removes the automatic wttr.in fallback. These services see connection IPs. Apple's system geocoder receives original system location or fallback search input. Open-Meteo logs may contain coordinates and remain for90days; use the prepared conservative category/linkage worksheet in WeatherPrivacyEvidence.md when completing the actual ASC form. Empty manifest collection arrays cannot settle the App Privacy answers.

These are prepared inputs, not evidence that App Store Connect fields have been submitted or accepted.


## Prepared supporting documents

- [Submission materials and remaining decisions](SubmissionMaterials.md): business model, App Store statistics, form inputs and specific unverified items.
- [Privacy data-flow evidence](WeatherPrivacyEvidence.md): per-provider processing and proposed privacy-label inputs; not a submitted questionnaire.
- [User terms](UserTerms.md): non-commercial app/source terms, Apple Standard EULA and explicit third-party/LGPL permissions; real publisher/account fields are completed at release.
- [NAS environment verification](NASEnvironment.md): credential-free technical evidence. Live access details are delivered privately and must be pasted only into private review information.

- [Rights evidence register](RightsRegister.md): source/licence/provenance inputs and remaining scope checks.

- [Physical regression record](PhysicalRegression.md): version/launch/library checks and exact NOT_RUN blockers; no personal-data reset.
