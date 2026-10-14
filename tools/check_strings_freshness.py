import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gen_strings import load_entries, render_all


def main():
    try:
        entries = load_entries()
    except (OSError, ValueError, KeyError) as error:
        print(f"词条真源校验失败：{error}", file=sys.stderr)
        return 1
    outputs = render_all(entries)
    root = Path(__file__).resolve().parents[1]
    errors = []
    for relative, expected in outputs.items():
        path = root / relative
        if not path.is_file():
            errors.append(f"缺少生成产物：{relative}")
            continue
        actual = path.read_text(encoding="utf-8")
        if actual != expected:
            errors.append(f"产物与真源不一致（已过期或被手改）：{relative}")
    pbxproj = root / "ios" / "PhotoPilot.xcodeproj" / "project.pbxproj"
    if pbxproj.is_file() and "Localizable.stringsdict" not in pbxproj.read_text(encoding="utf-8", errors="replace"):
        errors.append("Localizable.stringsdict 未在 Xcode 工程中引用，不会进 app bundle")
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        print("请运行 python3 tools/gen_strings.py 重新生成；若提示工程未引用 stringsdict，请重跑 make ios-generate。", file=sys.stderr)
        return 1
    plural_count = sum(1 for e in entries if e.get("plural"))
    print(f"词条新鲜度校验通过：{len(outputs)} 份产物与 contracts/strings 一致（词条 {len(entries)} 条，复数 {plural_count} 条）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
