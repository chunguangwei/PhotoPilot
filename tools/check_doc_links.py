import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
LINK = re.compile(r"!?\[[^\]]*\]\(([^\s)]+)(?:\s+\"[^\"]*\")?\)")


def documents(root=ROOT):
    return sorted(path for path in root.glob("*.md") if re.match(r"^\d{2}-", path.name) or path.name == "AGENTS.md")


def local_link_errors(path, root=ROOT):
    errors = []
    in_fence = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        for match in LINK.finditer(line):
            target = match.group(1).strip("<>")
            parts = urlsplit(target)
            if parts.scheme or parts.netloc or not parts.path:
                continue
            linked = (path.parent / unquote(parts.path)).resolve()
            if not linked.is_relative_to(root.resolve()):
                errors.append(f"{path.name}:{number} 本地链接越出项目：{target}")
            elif not linked.exists():
                errors.append(f"{path.name}:{number} 本地链接不存在：{target}")
    return errors


def main():
    errors = []
    paths = documents()
    if len(paths) != 18:
        errors.append("预期包含 00–16 共17份主文档及 AGENTS.md")
    for path in paths:
        errors.extend(local_link_errors(path))
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        return 1
    print(f"文档链接校验通过：{len(paths)} 份；未检查远端 URL、锚点与法律/业务结论。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
