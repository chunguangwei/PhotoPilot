import hashlib
import json
import sys
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests" / "fixtures" / "smoke-v1"


def check(directory=FIXTURES):
    errors = []
    try:
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        return [f"无法读取夹具清单：{error}"]
    generator = ROOT / "tools" / "generate_fixtures.py"
    if manifest.get("generatorSha256") != hashlib.sha256(generator.read_bytes()).hexdigest():
        errors.append("夹具生成器已变化，请运行 make fixtures-generate")
    entries = manifest.get("entries", [])
    if len(entries) != 120:
        errors.append("轻量集应为 120 个文件")
    seen = set()
    files = set()
    hashes = {}
    orientations = set()
    for entry in entries:
        identifier = entry.get("id")
        if identifier in seen:
            errors.append(f"重复夹具 ID：{identifier}")
        seen.add(identifier)
        relative = entry.get("file", "")
        path = directory / relative
        if not relative or not path.resolve().is_relative_to(directory.resolve()):
            errors.append(f"非法夹具路径：{identifier}")
            continue
        if relative in files:
            errors.append(f"重复夹具路径：{identifier}")
        files.add(relative)
        try:
            data = path.read_bytes()
        except OSError:
            errors.append(f"夹具缺失：{identifier}")
            continue
        digest = hashlib.sha256(data).hexdigest()
        hashes[identifier] = digest
        if digest != entry.get("sha256") or len(data) != entry.get("bytes"):
            errors.append(f"夹具内容与清单不一致：{identifier}")
        if entry.get("origin") != "original-script-synthetic" or entry.get("containsPersonalData") is not False:
            errors.append(f"夹具来源登记无效：{identifier}")
        if not entry.get("expected") or not entry.get("acceptance"):
            errors.append(f"夹具缺预期或验收映射：{identifier}")
        try:
            with Image.open(path) as image:
                image.load()
                if not entry.get("validImage"):
                    errors.append(f"预期损坏样本却可正常解码：{identifier}")
                else:
                    displayed = ImageOps.exif_transpose(image)
                    actual = (image.width, image.height, displayed.width, displayed.height, image.getexif().get(274, 1), image.getexif().get(36867))
                    expected = tuple(entry.get(key) for key in ("width", "height", "displayWidth", "displayHeight", "orientation", "capturedAt"))
                    if actual != expected:
                        errors.append(f"夹具图像元数据不一致：{identifier}")
                    if "F09" in entry.get("categories", []):
                        orientations.add(entry["orientation"])
                        if hashlib.sha256(displayed.tobytes()).hexdigest() != entry.get("normalizedPixelSha256"):
                            errors.append(f"EXIF 校正后像素不一致：{identifier}")
        except (OSError, ValueError):
            if entry.get("validImage"):
                errors.append(f"有效样本无法解码：{identifier}")
    if orientations != set(range(1, 9)):
        errors.append("EXIF 方向未完整覆盖 1–8")
    for entry in entries:
        duplicate = entry.get("duplicateOf")
        if duplicate and hashes.get(duplicate) != hashes.get(entry.get("id")):
            errors.append(f"重复素材字节不一致：{entry.get('id')}")
    scenario = manifest.get("selectionScenario", {})
    if not set(scenario.get("selectedIds", [])).issubset(set(scenario.get("importIds", []))):
        errors.append("选片场景引用未导入 ID")
    if scenario.get("automaticDisplayOfNewImports") is not False:
        errors.append("选片场景不得默认自动展示新增")
    return errors


def main():
    errors = check()
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        return 1
    print("夹具校验通过：120 个文件、哈希、EXIF、去重输入与来源登记；未执行业务测试。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
