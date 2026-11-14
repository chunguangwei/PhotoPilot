import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "contracts/ai-models.json"
BUNDLES = [
    ROOT / "ios/PhotoPilot/Resources/ai-models.json",
    ROOT / "android/app/src/main/assets/ai-models.json",
]


def validate(catalog):
    errors = []
    if not isinstance(catalog, dict) or catalog.get("formatVersion") != 1:
        return ["unsupported model catalog version"]
    models = catalog.get("models")
    if not isinstance(models, list) or len(models) != 2:
        return ["catalog must contain exactly two models"]
    seen = set()
    totals = {}
    for model in models:
        if not isinstance(model, dict):
            errors.append("invalid model")
            continue
        identity = model.get("id", "")
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", identity) or identity in seen:
            errors.append("invalid or duplicate model identity")
        seen.add(identity)
        if model.get("runtime") not in {"litertlm", "onnx"}:
            errors.append(identity + ": unsupported runtime")
        threshold = model.get("defaultThreshold")
        if type(threshold) not in {float, int} or not 0 <= threshold <= 1:
            errors.append(identity + ": invalid default similarity threshold")
        revision = model.get("revision", "")
        if not re.fullmatch(r"[a-f0-9]{40}", revision):
            errors.append(identity + ": model revision must be immutable")
        if not {"en", "zh-Hans"}.issubset(model.get("languages", [])):
            errors.append(identity + ": both English and Chinese are required")
        if not all(isinstance(model.get("displayName", {}).get(k), str) and model["displayName"][k].strip() for k in ["en", "zh-Hans"]):
            errors.append(identity + ": missing bilingual display names")
        files = model.get("files", [])
        if not isinstance(files, list) or not files:
            errors.append(identity + ": no downloadable files")
            continue
        roles = set()
        names = set()
        total = 0
        for file in files:
            if not isinstance(file, dict):
                errors.append(identity + ": malformed model file")
                continue
            name = file.get("name", "")
            if not name or Path(name).name != name or "/" in name or "\\" in name or name in {".", ".."} or name in names:
                errors.append(identity + ": unsafe or duplicate filename")
            names.add(name)
            roles.add(file.get("role"))
            size = file.get("size")
            if type(size) is not int or size <= 0:
                errors.append(identity + ": invalid file size")
            else:
                total += size
            if not re.fullmatch(r"[a-f0-9]{64}", file.get("sha256", "")):
                errors.append(identity + ": missing integrity hash")
            urls = file.get("urls", {})
            if not isinstance(urls, dict):
                errors.append(identity + ": invalid sources")
                continue
            if not urls.get("international"):
                errors.append(identity + ": missing international source")
            for region in ["international", "domestic"]:
                candidates = urls.get(region, [])
                if not isinstance(candidates, list):
                    errors.append(identity + ": invalid source list")
                    continue
                for candidate in candidates:
                    if not isinstance(candidate, str):
                        errors.append(identity + ": invalid URL")
                        continue
                    parsed = urlparse(candidate)
                    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or revision not in parsed.path:
                        errors.append(identity + ": download URL must pin HTTPS model revision")
        if "model" not in roles:
            errors.append(identity + ": missing model weights")
        if model.get("runtime") == "onnx" and "tokenizer" not in roles:
            errors.append(identity + ": missing text tokenizer")
        totals[identity] = total
    standard = totals.get("embeddinggemma2-vision")
    compact = totals.get("siglip2-base-int8")
    if standard is None or compact is None or compact >= standard:
        errors.append("compact model including tokenizer must be smaller than standard model")
    return errors


def verify_directory(catalog, directory):
    errors = []
    for model in catalog["models"]:
        for file in model["files"]:
            path = directory / file["name"]
            if not path.is_file() or path.stat().st_size != file["size"]:
                errors.append(model["id"] + ": missing or wrong-sized " + file["name"])
                continue
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            if digest.hexdigest() != file["sha256"]:
                errors.append(model["id"] + ": hash mismatch " + file["name"])
    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--write-bundles", action="store_true")
    parser.add_argument("--verify-directory", type=Path)
    args = parser.parse_args()
    catalog = json.loads(CATALOG.read_text())
    errors = validate(catalog)
    if not errors and args.verify_directory:
        errors += verify_directory(catalog, args.verify_directory)
    data = CATALOG.read_bytes()
    if not errors:
        for bundle in BUNDLES:
            if args.write_bundles:
                bundle.parent.mkdir(parents=True, exist_ok=True)
                bundle.write_bytes(data)
            elif not bundle.is_file() or bundle.read_bytes() != data:
                errors.append(str(bundle.relative_to(ROOT)) + ": stale model catalog")
    if errors:
        for error in errors:
            print(error)
        return 1
    print("AI model catalog verified: 2 bilingual pinned models")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
