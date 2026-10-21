# SMB corresponding sources and rebuild record

PhotoPilot 1.1.53 embeds the dynamically linked AMSMB2 framework, which includes libsmb2. The Swift wrapper uses MIT terms; libsmb2 uses LGPL-2.1. The PhotoPilot non-commercial license does not replace third-party licenses. This document records technical preparation; it is not a legal conclusion about App Store distribution.

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

## Still open before App Store submission

- Extend the demonstrated local-source app rebuild evidence to the applicable final signed distribution/replacement route and retain the necessary deliverables.
- Resolve the applicable LGPL obligations, reverse-engineering/replacement permissions and their interaction with the app EULA and App Store distribution terms.
- Confirm the final app provides the appropriate license/source information, with tested public links.
- A successful independent framework build and a public source archive close technical evidence gaps only; they do not by themselves close the distribution-compliance decision.

## Modified-library application rebuild evidence (2026-10-21)

An isolated v1.1.52 source copy was generated with AMSMB2 replaced by the extracted local package. Exactly one of the218 original files, AMSMB2/AMSMB2.swift, was deliberately changed to add an exported marker; original source archive and release app were untouched. Since extracted files are read-only, make the experimental copy writable before editing it.

In the copied ios/project.yml replace the AMSMB2 url/from entries with `path: /absolute/path/to/AMSMB2-4.0.3`, then run XcodeGen on that copied specification. Build the copied app with a generic iOS Release archive and CODE_SIGNING_ALLOWED=NO. Do not edit the original generated project or replace the production Release asset for this experiment.

A fresh unsigned arm64 Release archive succeeded. `nm -g` found the deliberate `_photopilot_review_rebuild_marker` in the embedded framework; `otool -L` confirmed the app loads `@rpath/AMSMB2.framework/AMSMB2`. An isolated simulator test also loaded that modified framework at runtime and executed the marker successfully. Alongside it, the app's actual SMB/WebDAV source adapters enumerated/downloaded all six live review images, with exact content hashes and encrypted SMB/system-trusted HTTPS; three tests passed. Private test credentials were not included in the Release archive.

Evidence: `.build/review-environment/modified-library-verification.json`, `modified-library-app.xcarchive`, `modified-library-app-archive.log`, `actual-ios-adapters-final.xcresult`. This proves a local source-level application rebuild with a modified library; it is not Apple Distribution Validate, DRM/re-signing compatibility, legal approval or proof that all LGPL/App Store obligations are discharged. User-term preparation is in [UserTermsDraft.md](UserTermsDraft.md).
