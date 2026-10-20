# PhotoPilot — App Review preparation

This is a local preparation packet. It has not been submitted to App Review. Local unsigned Release inspection and remaining submission requirements are recorded in [ReleaseAudit.md](ReleaseAudit.md).

`ReviewPhotos.zip` contains the six synthetic PNG images used by the repository's UI tests (`ios/PhotoPilotUITests/SeedMedia`). It contains no customer photos or credentials. Before submission, attach the archive to the review information or provide a verified download link, and confirm the steps below against the exact Release build.

## Suggested review notes

PhotoPilot is an offline photo-frame app for iPhone and iPad. No PhotoPilot account, subscription, purchase, or sign-in is required.

1. On launch, the brand presentation lasts up to six seconds. **Skip** is available immediately.
2. **Take a look first** on the welcome screen opens a sample slideshow without requesting photo access. This preview does not import the samples into the library or exercise frame editing.
3. To test the full workflow, save images from the attached `ReviewPhotos.zip` to Photos or Files. Choose **Choose photos → Photos on this device**, then use the Photos or Files option. With Photos, grant access to the test images; Files provides an alternative without granting access to the photo library.
4. After import, choose what to display, select a few images, name and save the frame, configure playback, and start the slideshow. Importing images alone does not add them to a displayed frame.
5. Tap the slideshow to reveal pause, previous/next, and exit controls. Playback settings include transition, image fitting, music and rest hours. Offline playback uses imported local copies.
6. Weather is optional and initially off. In Settings, enable Weather and choose a city. Location permission is only needed for **Use current location**; choosing a city manually does not require it. Weather failure does not block local photo playback.
7. Settings → About provides the Privacy Policy and scrollable open-source license texts.
8. NAS sources are optional connections to a server configured by the user. The app does not create an account or scan the LAN. WebDAV and SMB test access must be supplied separately as described below.

## Submission items still to prepare

- Confirm these notes and all button labels against the final Release binary and its chosen language.
- Supply a reachable, isolated NAS test environment with only non-private images, scoped review credentials, protocol, host, port, share/path, and expected result. Do not put credentials in this repository. If this cannot be provided, agree a verification method with App Review; do not claim the sample slideshow covers NAS.
- Verify the online privacy policy matches the submitted binary and is readable without authentication.
- Complete the actual App Store Connect privacy, age-rating, encryption, territory and review-contact fields.
- Resolve the dependency-distribution and weather-privacy items in `10-合规与上架材料.md` before submitting.

Supporting a review does not require access to the developer's household NAS, personal photo library or personal account.
