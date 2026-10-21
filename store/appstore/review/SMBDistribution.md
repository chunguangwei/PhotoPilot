# SMB corresponding sources and rebuild record

PhotoPilot 1.1.53 and the 1.1.55 source candidate embed the dynamically linked AMSMB2 framework, which includes libsmb2. The Swift wrapper uses MIT terms; libsmb2 uses LGPL-2.1. The PhotoPilot non-commercial license does not replace third-party licenses. This document records technical preparation; it is not a legal conclusion about App Store distribution.

## Exact corresponding sources

- [AMSMB2 4.0.3 source archive](https://github.com/chunguangwei/PhotoPilot/releases/download/v1.1.53/AMSMB2-4.0.3-source.tar.gz), including the checked-out libsmb2 submodule, original license texts and build files.
- AMSMB2 commit: `1726aaaf7adf63d7d1d2a0c5d1b0e635028215c0`.
- libsmb2 commit: `aff9fa6ba9f41cfd3c15d184554601ec3f6d8d03`.
- Archive SHA-256: `0b4bd6046f2f06d79cd82a36f967f11b85b8e39aade627e1d6f5504ddd2e5077`.
- [Per-file source manifest](https://github.com/chunguangwei/PhotoPilot/releases/download/v1.1.53/SMBSourceManifest.json): 218 original files. The archive has no local source modifications, credentials, private keys or user photos.

## Independently rebuilding the framework

With a compatible Xcode installed, download the archive and manifest, verify the archive checksum above, then run:

```sh
tar -xzf AMSMB2-4.0.3-source.tar.gz
cd AMSMB2-4.0.3
xcodebuild -scheme AMSMB2 -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath ../derived \
  CODE_SIGNING_ALLOWED=NO build
```

The tested product is `../derived/Build/Products/Release-iphoneos/PackageFrameworks/AMSMB2.framework`. On 2026-10-21, Xcode 27.1 rebuilt this extracted archive successfully for arm64; all 218 original files matched the manifest before and after the build. Swift Package Manager may resolve the test-only Swift Atomics dependency; it is not linked into this production framework. Different toolchain or signing output is not expected to be byte-identical.

For application-level rebuilding, use [PhotoPilot's source repository](https://github.com/chunguangwei/PhotoPilot) and its documented XcodeGen build. The default project resolves the pinned upstream package. To experiment with modified library sources, work in a separate checkout, replace the remote package reference with the extracted local package, regenerate the Xcode project and rebuild. This local-package application rebuild route was exercised on 2026-10-21 in an isolated copy, as recorded below. There is no claim that simply copying a framework into an App Store-signed app will work.

## Final distribution step

- Extend the demonstrated local-source app rebuild evidence to the applicable final signed distribution/replacement route and retain the necessary deliverables.
- Apply the adopted [UserTerms.md](UserTerms.md) LGPL permission exception together with Apple’s Standard EULA. Its scope clause expressly preserves open-source permissions. No custom EULA is needed for the chosen route. Do not turn the remaining signed-binary test into a claim that a generic lawyer signature was obtained or required.
- Confirm the final app provides the appropriate license/source information, with tested public links.
- A successful independent framework build and a public source archive close technical evidence gaps only; they do not by themselves close the distribution-compliance decision.

## Modified-library application rebuild evidence (2026-10-21)

An isolated v1.1.52 source copy was generated with AMSMB2 replaced by the extracted local package. Exactly one of the218 original files, AMSMB2/AMSMB2.swift, was deliberately changed to add an exported marker; original source archive and release app were untouched. Since extracted files are read-only, make the experimental copy writable before editing it.

In the copied ios/project.yml replace the AMSMB2 url/from entries with `path: /absolute/path/to/AMSMB2-4.0.3`, then run XcodeGen on that copied specification. Build the copied app with a generic iOS Release archive and CODE_SIGNING_ALLOWED=NO. Do not edit the original generated project or replace the production Release asset for this experiment.

A fresh unsigned arm64 Release archive succeeded. `nm -g` found the deliberate `_photopilot_review_rebuild_marker` in the embedded framework; `otool -L` confirmed the app loads `@rpath/AMSMB2.framework/AMSMB2`. An isolated simulator test also loaded that modified framework at runtime and executed the marker successfully. Alongside it, the app's actual SMB/WebDAV source adapters enumerated/downloaded all six live review images, with exact content hashes and encrypted SMB/system-trusted HTTPS; three tests passed. Private test credentials were not included in the Release archive.

Evidence: `.build/review-environment/modified-library-verification.json`, `modified-library-app.xcarchive`, `modified-library-app-archive.log`, `actual-ios-adapters-final.xcresult`. This proves a local source-level application rebuild with a modified library; it is not Apple Distribution Validate, DRM/re-signing compatibility, legal approval or proof that all LGPL/App Store obligations are discharged. Adopted product terms and affirmative LGPL permissions are in [UserTerms.md](UserTerms.md).

## Licence decision and delivery checklist — 1.1.55

The selected route retains SMB. MIT covers the Swift wrapper as recorded upstream; libsmb2 lib/include use LGPL-2.1-or-later, and the combined package includes LGPL 2.1 text. Preserve all exact upstream notices. The root non-commercial licence applies to PhotoPilot's own work; it does not restrict independent library permissions. The adopted user terms expressly permit modification of the combination for the customer's own use and reverse engineering to debug those modifications (LGPL 2.1 §6). Apple's [Standard EULA scope clause](https://www.apple.com/legal/internet-services/itunes/dev/stdeula/) also recognises permissions from included open-source components. These statements grant permissions; they do not prove a particular signed executable accepts replacement libraries.

The demonstrated source-and-rebuild route is based on LGPL §6(a), with equivalent public source delivery under §6(d), rather than assuming dynamic linking alone satisfies §6(b). Keep the exact unmodified 218-file library archive/manifest linked above, a complete matching PhotoPilot source revision including build files, and local-package instructions available alongside each distributed release. The library revision is unchanged in the 1.1.55 candidate; earlier source URLs remain the exact library sources, not evidence that the new application source has already been published. At release time publish/verify the matching application source and reference it from release notes.

Already demonstrated: exact library sources, independent framework build, modified-library app source rebuild, exported marker and simulator runtime loading. At the joint online distribution step, verify the final signing/distribution route with a modified compatible library on the intended device and preserve its command/output evidence; verify public source links from the shipped licence page. App Store distribution/re-signing and possible DRM restrictions were not exercised by the unsigned/simulator experiment. A successful check must state its actual installation/signing scope; it must not be described as blanket platform legal approval. This is the concrete remaining delivery check, not a request for fabricated supplier permission or generic legal sign-off.

## Development-signed physical modified-library follow-up (2026-10-21)

The isolated current55-source app was signed under an existing development identity with its own application/Keychain identifiers and installed separately on iPhone13 and iPadAir4. The modified AMSMB2 exported marker executed successfully on both physical devices. iPad additionally passed both actual live adapters with encrypted SMB/system-trusted HTTPS and all six photo hashes. The iPhone isolated-app network checks were denied by system Wi-Fi policy; this does not invalidate its successful library-marker load. The separate full177-case native suite passed on both after test-only fixture bundling was corrected.

This extends unsigned/simulator rebuilding evidence to development-signed physical loading. It does not claim Apple Distribution Validate, App Store DRM testing or permission to replace a library inside an unchanged App Store-signed executable. Final source/package/signing consistency remains a joint release check. Exact results and cleanup are in PhysicalRegression.md and restricted `.build/physical-regression-2026-10-21/` evidence.
