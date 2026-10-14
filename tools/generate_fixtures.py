import argparse
import hashlib
import io
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps, __version__ as pillow_version

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "tests" / "fixtures" / "smoke-v1"


def encode(image, kind="PNG", **options):
    stream = io.BytesIO()
    image.save(stream, format=kind, **options)
    return stream.getvalue()


def reference_image(size=(240, 160), variant=0):
    image = Image.new("RGB", size, (238, 232, 215))
    draw = ImageDraw.Draw(image)
    width, height = size
    draw.rectangle((0, 0, width // 2, height // 2), fill=(175, 54, 46))
    draw.rectangle((width // 2, 0, width - 1, height // 2), fill=(49, 116, 78))
    draw.rectangle((0, height // 2, width // 2, height - 1), fill=(43, 80, 151))
    draw.rectangle((width // 2, height // 2, width - 1, height - 1), fill=(225, 188, 62))
    if width >= 32 and height >= 32:
        draw.polygon(((width // 4, 8), (width // 4 - 7, 20), (width // 4 + 7, 20)), fill="white")
        draw.rectangle((5, height - 15, 5 + variant % (width - 12), height - 8), fill="white")
    return image


def build(output):
    output.mkdir(parents=True, exist_ok=True)
    entries = []

    def add(name, data, categories, acceptance, behavior, valid=True, **extra):
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        entry = {
            "id": f"fixture-{len(entries) + 1:03d}",
            "file": name,
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "categories": categories,
            "acceptance": acceptance,
            "expected": behavior,
            "validImage": valid,
            "origin": "original-script-synthetic",
            "containsPersonalData": False,
        }
        if valid:
            with Image.open(io.BytesIO(data)) as image:
                displayed = ImageOps.exif_transpose(image)
                entry.update({"format": image.format, "mode": image.mode, "width": image.width, "height": image.height, "displayWidth": displayed.width, "displayHeight": displayed.height, "orientation": image.getexif().get(274, 1), "capturedAt": image.getexif().get(36867)})
        entry.update(extra)
        entries.append(entry)

    image = reference_image()
    base_jpeg = encode(image, "JPEG", quality=95, subsampling=0)
    add("F01/baseline.jpg", base_jpeg, ["F01", "F10"], ["A02", "A04", "A06"], "正常解码；无拍摄时间，浏览回退导入时间，播放 NULLS LAST。")
    for mode in ("P", "RGB", "RGBA"):
        add(f"F02/png-{mode.lower()}.png", encode(image.convert(mode)), ["F02"], ["A09"], "按 PNG 色彩模式解码，失败仅影响单项。")
    for category, sizes in (("F04", [(240, 160), (240, 180), (256, 144)]), ("F05", [(160, 240), (144, 256)]), ("F06", [(160, 160)]), ("F07", [(10000, 1200)]), ("F08", [(600, 8000)]), ("F14", [(1, 1), (16, 16), (32, 24)])):
        for width, height in sizes:
            add(f"{category}/{width}x{height}.png", encode(reference_image((width, height))), [category], ["A06", "A09"], "保留完整构图、不拉伸；极端尺寸须受控下采样。")
    inverses = {2: Image.Transpose.FLIP_LEFT_RIGHT, 3: Image.Transpose.ROTATE_180, 4: Image.Transpose.FLIP_TOP_BOTTOM, 5: Image.Transpose.TRANSPOSE, 6: Image.Transpose.ROTATE_90, 7: Image.Transpose.TRANSVERSE, 8: Image.Transpose.ROTATE_270}
    for orientation in range(1, 9):
        stored = image if orientation == 1 else image.transpose(inverses[orientation])
        exif = Image.Exif()
        exif[274] = orientation
        add(f"F09/orientation-{orientation}.png", encode(stored, exif=exif), ["F09"], ["A06"], "方向校正后应与 orientation-1 逐像素相同。", normalizedPixelSha256=hashlib.sha256(image.tobytes()).hexdigest())
    exif = Image.Exif()
    exif[274] = 1
    add("F11/no-date.jpg", encode(image, "JPEG", exif=exif), ["F11"], ["A06", "A25"], "存在 EXIF 但无 DateTimeOriginal；不得回填虚假拍摄时间。")
    for index in range(20):
        exif = Image.Exif()
        exif[36867] = "2024:06:01 09:00:00"
        add(f"F12/tie-{index:02d}.jpg", encode(reference_image(variant=index), "JPEG", exif=exif, quality=90), ["F12"], ["A04", "A25"], "同拍摄时间按稳定 ID 排序，不依据文件遍历顺序。")
    add("F15/progressive.jpg", encode(image, "JPEG", progressive=True), ["F15"], ["A09"], "完整下载校验后方可提交。")
    add("F16/cmyk.jpg", encode(image.convert("CMYK"), "JPEG"), ["F16"], ["A09"], "CMYK JPEG 颜色路径；不代表 ICC 广色域覆盖。")
    transparent = image.convert("RGBA")
    transparent.putalpha(Image.linear_gradient("L").resize(image.size))
    add("F17/alpha.png", encode(transparent), ["F17"], ["A06", "A09"], "透明度合成，不包含 alpha HEIC 覆盖。")
    add("F19/single.gif", encode(image, "GIF"), ["F19"], ["A09"], "取静态代表帧并明示。")
    add("F19/animated.gif", encode(image, "GIF", save_all=True, append_images=[reference_image(variant=60)], duration=100, loop=0), ["F19"], ["A09"], "多帧 GIF 只取静态代表帧。")
    add("F22/empty.jpg", b"", ["F22"], ["A09"], "空文件必须单项失败。", valid=False)
    add("F22/not-image.jpg", b"PhotoPilot synthetic non-image fixture\n", ["F22"], ["A09"], "非图片不得作为已导入可播项。", valid=False)
    add("F22/truncated.jpg", base_jpeg[:32], ["F22"], ["A09"], "不完整文件必须单项失败，不提交可播副本。", valid=False)
    add("F22/png-with-jpeg-extension.jpg", encode(image), ["F22"], ["A09"], "真实格式识别或明确拒绝；不得只信任扩展名。")
    for name in ("山与海.png", "name with spaces.png", "a.b.c.png", "صورة.png", "long-" + "x" * 160 + ".png"):
        add(f"F23/{name}", encode(image), ["F23"], ["A09", "A25"], "合成文件名，只用于本地解析和搜索测试，不写业务日志。")
    for index in range(5):
        add(f"F24/folder-{index}/duplicate.jpg", base_jpeg, ["F24"], ["A02", "A03", "A21"], "同字节内容须去重，墓碑跨目录有效。", duplicateOf="fixture-001")
    for kind, quality, suffix in (("JPEG", 95, "q95.jpg"), ("JPEG", 60, "q60.jpg"), ("PNG", None, "lossless.png")):
        data = encode(image, kind, **({"quality": quality} if quality else {}))
        add(f"F25/{suffix}", data, ["F25"], ["A02"], "不同编码、不同字节应各自保留；不按视觉相似度合并。")
    while len(entries) < 120:
        index = len(entries)
        add(f"F26/smoke-{index:03d}.png", encode(reference_image((96, 64), index)), ["F26"], ["A01", "A02", "A04"], "轻量批次样本；导入不自动展示。")
    manifest = {
        "version": "smoke-v1",
        "generator": "tools/generate_fixtures.py",
        "generatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "pillowVersion": pillow_version,
        "license": "项目原创脚本合成，仅用作 PhotoPilot 开发与测试；无第三方摄影、无真人数据。",
        "reproducibility": "相同生成器和 Pillow 版本输出固定哈希；版本变化需重建并评审 manifest。",
        "coverage": "120 个轻量文件，覆盖可本地合成的子集，不等于 F01–F28 全覆盖或业务测试通过。",
        "unavailable": ["F03 HEIC/HEIF", "F13 超大像素", "F15 交错 PNG", "F16 ICC 广色域", "F17 alpha HEIC", "F18 Live Photo", "F20 视频", "F21 RAW", "F23 emoji/近文件系统路径上限", "F25 HEIC", "F27 千张混合", "F28 万张元数据"],
        "entries": entries,
        "selectionScenario": {"importIds": [entry["id"] for entry in entries[:24]], "selectedIds": ["fixture-001", "fixture-005", "fixture-008"], "automaticDisplayOfNewImports": False, "scope": "仅给未来业务测试提供输入，不是 SelectionResolver 实现或通过证据。"},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    manifest = build(args.output)
    print(f"已生成 {len(manifest['entries'])} 个独立测试夹具；不是业务测试结果。")


if __name__ == "__main__":
    main()
