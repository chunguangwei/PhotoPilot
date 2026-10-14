import argparse
import hashlib
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote, urlsplit

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
EXPORT = ROOT / "design" / "export" / "design-assets-v1.1"
INVENTORY = EXPORT / "asset-inventory.json"
SVG = "{http://www.w3.org/2000/svg}"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_paths():
    paths = list((ROOT / "design" / "source").rglob("*"))
    paths.extend((ROOT / "design" / "motion").glob("render_design*.py"))
    paths.extend((ROOT / "design" / "motion").glob("render_components*.py"))
    paths.extend((ROOT / "design" / "icons").glob("*.svg"))
    paths.extend((ROOT / "design" / "assets").rglob("*"))
    paths.append(ROOT / "contracts" / "strings" / "strings.json")
    return sorted({path for path in paths if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc"})


def asset_paths():
    return sorted(path for path in EXPORT.rglob("*") if path.is_file() and path != INVENTORY and path.suffix in {".svg", ".png", ".jpg", ".jpeg", ".pdf", ".xml", ".json"})


def svg_errors(path):
    errors = []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        return [f"SVG 无法解析 {path.name}: {error}"]
    if root.tag != SVG + "svg":
        errors.append(f"非 SVG 根节点：{path.name}")
    for element in root.iter():
        if element.tag == SVG + "script" or any(key.lower().startswith("on") for key in element.attrib):
            errors.append(f"设计资产不得包含脚本事件：{path.name}")
        for key in ("href", "{http://www.w3.org/1999/xlink}href"):
            href = element.attrib.get(key)
            if not href or href.startswith(("#", "data:")):
                continue
            parts = urlsplit(href)
            if parts.scheme or parts.netloc:
                errors.append(f"设计稿依赖外部资源：{path.name}")
            else:
                resource = (path.parent / unquote(parts.path)).resolve()
                if not resource.is_relative_to(ROOT.resolve()) or not resource.is_file():
                    errors.append(f"设计稿本地资源失效：{path.name} -> {href}")
    return errors


def validate_files(paths):
    errors = []
    for path in paths:
        try:
            if path.suffix == ".svg":
                errors.extend(svg_errors(path))
            elif path.suffix == ".xml":
                ET.parse(path)
            elif path.suffix == ".json":
                json.loads(path.read_text(encoding="utf-8"))
            elif path.suffix in {".png", ".jpg", ".jpeg"}:
                with Image.open(path) as image:
                    image.verify()
            elif path.suffix == ".pdf":
                data = path.read_bytes()
                if not data.startswith(b"%PDF-") or b"%%EOF" not in data[-64:]:
                    errors.append(f"PDF 结构头尾缺失：{path.name}")
        except (OSError, ValueError, ET.ParseError) as error:
            errors.append(f"资产解析失败 {path.name}: {error}")
    return errors


def inventory_data():
    sources = {str(path.relative_to(ROOT)): digest(path) for path in source_paths()}
    assets = {str(path.relative_to(ROOT)): {"sha256": digest(path), "bytes": path.stat().st_size} for path in asset_paths()}
    return {"version": "1.1", "scope": "本地设计资产完整性与来源摘要；不代表原生渲染、交互或人工签字。", "sources": sources, "assets": assets}


def check():
    errors = []
    required = ("screens", "components", "icons", "launch", "app-icons", "foundations", "flows")
    for name in required:
        folder = EXPORT / name
        if not folder.is_dir() or not any(folder.rglob("*.png")):
            errors.append(f"设计交付目录缺失或未产 PNG：{name}")
    paths = asset_paths()
    if not paths:
        errors.append("没有设计导出资产")
    errors.extend(validate_files(paths))
    try:
        expected = json.loads(INVENTORY.read_text(encoding="utf-8"))
        current = inventory_data()
        for key in ("sources", "assets"):
            if expected.get(key) != current[key]:
                errors.append(f"设计 {key} 摘要过期：完成生成和评审后运行 make design-freeze")
    except (OSError, ValueError):
        errors.append("未建立有效设计资产摘要清单")
    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--freeze", action="store_true")
    args = parser.parse_args()
    if args.freeze:
        paths = asset_paths()
        if not paths:
            print("禁止冻结空设计包", file=sys.stderr)
            return 1
        errors = validate_files(paths)
        if errors:
            print("\n".join(errors), file=sys.stderr)
            return 1
        INVENTORY.write_text(json.dumps(inventory_data(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    errors = check()
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        return 1
    print(f"设计资产完整性通过：{len(asset_paths())} 个导出文件；不等于视觉签字或原生验收。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
