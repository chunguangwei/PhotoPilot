#!/usr/bin/env python3
"""Rebuild the small category-registration artifact from the pinned official SDK.

The original Google SDK remains unmodified. The resulting relocatable object
contains three original conversion categories and one no-op linkage anchor.
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent
EXPECTED = {'ios-arm64': '290ed3d73fda9be0938383aa98eebc9f20e461ba1a387bacbb0217b1c9d85197', 'ios-arm64-simulator': 'fa6f1d994389974a4124ef0cc4a7b92b36400eaea2a041545407536228d54c40'}
MEMBERS = ["MPPBaseOptions+Helpers.o", "MPPEmbeddingResult+Helpers.o", "MPPEmbedding+Helpers.o"]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(args, **kwargs):
    return subprocess.check_output(args, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-xcframework", type=pathlib.Path, required=True)
    args = parser.parse_args()
    output = ROOT / "MediaPipeRequiredCategories.xcframework"
    provenance = {"upstreamRevision": "02ca022d573c004c463d8c61eb3f8ce17e95a7fc",
                  "upstreamZipSHA256": "39990d7ff26dedeef5e0f43e7a8656ead9219b52133ae390af5e9ffcfa208b1f",
                  "anchorSourceSHA256": digest((ROOT / "CategoryAnchor.c").read_bytes()), "architectures": {}}
    with tempfile.TemporaryDirectory(prefix="photopilot-mediapipe-categories-") as temporary:
        temporary = pathlib.Path(temporary)
        libraries = []
        for architecture, expected in EXPECTED.items():
            source = args.sdk_xcframework / architecture / "MediaPipeTasksCommon.framework/MediaPipeTasksCommon"
            if digest(source.read_bytes()) != expected:
                raise ValueError("The SDK archive does not match the pinned upstream input")
            directory = temporary / architecture
            directory.mkdir()
            listing = run(["xcrun", "ar", "-t", str(source)], text=True).splitlines()
            objects = []
            member_hashes = {}
            for member in MEMBERS:
                if listing.count(member) != 1:
                    raise ValueError("A required category member is missing or ambiguous")
                data = run(["xcrun", "ar", "-p", str(source), member])
                path = directory / member
                path.write_bytes(data)
                objects.append(path)
                member_hashes[member] = digest(data)
            simulator = architecture.endswith("simulator")
            sdk = "iphonesimulator" if simulator else "iphoneos"
            triple = "arm64-apple-ios15.0-simulator" if simulator else "arm64-apple-ios15.0"
            sdk_path = run(["xcrun", "--sdk", sdk, "--show-sdk-path"], text=True).strip()
            anchor = directory / "anchor.o"
            run(["xcrun", "clang", "-target", triple, "-isysroot", sdk_path, "-g0", "-c",
                 str(ROOT / "CategoryAnchor.c"), "-o", str(anchor)])
            combined = directory / "MediaPipeRequiredCategories.o"
            run(["xcrun", "ld", "-r", "-arch", "arm64", "-o", str(combined), str(anchor)] + list(map(str, objects)))
            library = directory / "libMediaPipeRequiredCategories.a"
            run(["xcrun", "libtool", "-static", "-o", str(library), str(combined)], env=dict(os.environ, ZERO_AR_DATE="1"))
            libraries += ["-library", str(library)]
            provenance["architectures"][architecture] = {"inputArchiveSHA256": expected, "members": member_hashes,
                                                        "outputLibrarySHA256": digest(library.read_bytes())}
        if output.exists():
            shutil.rmtree(output)
        run(["xcodebuild", "-create-xcframework"] + libraries + ["-output", str(output)])
    (ROOT / "categories-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(output)


if __name__ == "__main__":
    main()
