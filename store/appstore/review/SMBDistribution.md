# SMB corresponding sources and rebuild record

PhotoPilot 1.1.52 embeds the dynamically linked AMSMB2 framework, which includes libsmb2. The Swift wrapper uses MIT terms; libsmb2 uses LGPL-2.1. The PhotoPilot non-commercial license does not replace third-party licenses. This document records technical preparation; it is not a legal conclusion about App Store distribution.

## Exact corresponding sources

- [AMSMB2 4.0.3 source archive](https://github.com/chunguangwei/PhotoPilot/releases/download/v1.1.52/AMSMB2-4.0.3-source.tar.gz), including the checked-out libsmb2 submodule, original license texts and build files.
- AMSMB2 commit: `1726aaaf7adf63d7d1d2a0c5d1b0e635028215c0`.
- libsmb2 commit: `aff9fa6ba9f41cfd3c15d184554601ec3f6d8d03`.
- Archive SHA-256: `0b4bd6046f2f06d79cd82a36f967f11b85b8e39aade627e1d6f5504ddd2e5077`.
- [Per-file source manifest](https://github.com/chunguangwei/PhotoPilot/releases/download/v1.1.52/SMBSourceManifest.json): 218 original files. The archive has no local source modifications, credentials, private keys or user photos.

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

For application-level rebuilding, use [PhotoPilot's source repository](https://github.com/chunguangwei/PhotoPilot) and its documented XcodeGen build. The default project resolves the pinned upstream package. To experiment with modified library sources, work in a separate checkout, replace the remote package reference with the extracted local package, regenerate the Xcode project and rebuild. This last modified-library/application replacement route has not yet been validated end to end; there is no claim that simply copying a framework into an App Store-signed app will work.

## Still open before App Store submission

- Validate the complete modified-library/rebuild or other applicable replacement route and retain the necessary deliverables for the final signed product.
- Resolve the applicable LGPL obligations, reverse-engineering/replacement permissions and their interaction with the app EULA and App Store distribution terms.
- Confirm the final app provides the appropriate license/source information, with tested public links.
- A successful independent framework build and a public source archive close technical evidence gaps only; they do not by themselves close the distribution-compliance decision.
