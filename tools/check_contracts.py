import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "contracts"
ACCEPTANCE_COUNT = 27
ALLOWED_TYPES = {"str", "int", "bool", "blob"}
ALLOWED_ON_DELETE = {"CASCADE", "RESTRICT", "SET NULL", "NO ACTION"}
ALLOWED_METRICS = {f"PM{i}" for i in range(1, 13)}
TELEMETRY_ROLES = {"required", "supportive", "manual"}
TRISTATE = {"yes", "no", "conditional", "unknown"}
ERROR_CODE_RE = re.compile(r"^[A-Z][A-Z0-9]*_[A-Z0-9_]+$")


class YamlError(ValueError):
    pass


def _strip_comment(line):
    in_quote = None
    for index, char in enumerate(line):
        if in_quote:
            if char == in_quote:
                in_quote = None
        elif char in "\"'":
            in_quote = char
        elif char == "#" and (index == 0 or line[index - 1] in " \t"):
            return line[:index]
    return line


def _split_flow(text):
    parts = []
    depth = 0
    in_quote = None
    start = 0
    for index, char in enumerate(text):
        if in_quote:
            if char == in_quote:
                in_quote = None
        elif char in "\"'":
            in_quote = char
        elif char in "[{":
            depth += 1
        elif char in "]}":
            depth -= 1
        elif char == "," and depth == 0:
            parts.append(text[start:index])
            start = index + 1
    parts.append(text[start:])
    return [part.strip() for part in parts if part.strip()]


def _scalar(text):
    text = text.strip()
    if text == "":
        raise YamlError("空标量")
    if text.startswith('"'):
        if len(text) < 2 or not text.endswith('"'):
            raise YamlError(f"引号未闭合：{text}")
        body = text[1:-1]
        return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), body)
    if text.startswith("'"):
        if len(text) < 2 or not text.endswith("'"):
            raise YamlError(f"引号未闭合：{text}")
        return text[1:-1].replace("''", "'")
    if text.startswith("["):
        if not text.endswith("]"):
            raise YamlError(f"行内列表未闭合：{text}")
        return [_scalar(part) for part in _split_flow(text[1:-1])]
    if text.startswith("{"):
        if not text.endswith("}"):
            raise YamlError(f"行内映射未闭合：{text}")
        result = {}
        for part in _split_flow(text[1:-1]):
            key, rest = _split_key(part)
            if key in result:
                raise YamlError(f"行内映射重复键：{key}")
            result[key] = _scalar(rest)
        return result
    if text == "null":
        return None
    if text == "true":
        return True
    if text == "false":
        return False
    if re.fullmatch(r"-?\d+", text):
        return int(text)
    if text.startswith(("[", "]", "{", "}", "- ")) or ": " in text:
        raise YamlError(f"歧义标量，请加引号：{text}")
    return text


def _split_key(text):
    in_quote = None
    for index, char in enumerate(text):
        if in_quote:
            if char == in_quote:
                in_quote = None
        elif char in "\"'":
            in_quote = char
        elif char == ":" and (index + 1 == len(text) or text[index + 1] in " \t"):
            return text[:index].strip(), text[index + 1:].strip()
    raise YamlError(f"缺少键值分隔符：{text}")


def parse_yaml(path):
    tokens = []
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if "\t" in raw[: len(raw) - len(raw.lstrip())]:
            raise YamlError(f"{path.name}:{number} 缩进不得使用制表符")
        content = _strip_comment(raw).rstrip()
        if not content.strip():
            continue
        indent = len(content) - len(content.lstrip(" "))
        tokens.append((indent, content.strip(), number))
    if not tokens:
        raise YamlError(f"{path.name} 内容为空")
    state = {"pos": 0}

    def parse_block(indent):
        if state["pos"] >= len(tokens):
            raise YamlError(f"{path.name} 意外结束")
        current_indent, text, number = tokens[state["pos"]]
        if current_indent < indent:
            raise YamlError(f"{path.name}:{number} 缩进不足")
        if text.startswith("- ") or text == "-":
            return parse_sequence(current_indent)
        return parse_mapping(current_indent)

    def parse_mapping(indent):
        result = {}
        while state["pos"] < len(tokens):
            current_indent, text, number = tokens[state["pos"]]
            if current_indent < indent:
                break
            if current_indent > indent:
                raise YamlError(f"{path.name}:{number} 意外缩进")
            if text.startswith("- ") or text == "-":
                break
            key, rest = _split_key(text)
            if key in result:
                raise YamlError(f"{path.name}:{number} 重复键：{key}")
            state["pos"] += 1
            if rest:
                result[key] = _scalar(rest)
            elif state["pos"] < len(tokens) and tokens[state["pos"]][0] > indent:
                result[key] = parse_block(tokens[state["pos"]][0])
            else:
                result[key] = None
        return result

    def parse_sequence(indent):
        result = []
        while state["pos"] < len(tokens):
            current_indent, text, number = tokens[state["pos"]]
            if current_indent != indent or not (text.startswith("- ") or text == "-"):
                break
            item_text = text[2:].strip() if text.startswith("- ") else ""
            state["pos"] += 1
            if not item_text:
                if state["pos"] < len(tokens) and tokens[state["pos"]][0] > indent:
                    result.append(parse_block(tokens[state["pos"]][0]))
                else:
                    result.append(None)
            elif re.match(r"^[^:\[\]{}\",']+:(\s|$)", item_text) or _has_key_colon(item_text):
                key, rest = _split_key(item_text)
                item = {key: _scalar(rest)} if rest else {key: None}
                if not rest and state["pos"] < len(tokens) and tokens[state["pos"]][0] > indent:
                    item[key] = parse_block(tokens[state["pos"]][0])
                if state["pos"] < len(tokens) and tokens[state["pos"]][0] > indent:
                    continuation_indent = tokens[state["pos"]][0]
                    extra = parse_mapping(continuation_indent)
                    for extra_key, extra_value in extra.items():
                        if extra_key in item:
                            raise YamlError(f"{path.name}:{number} 序列项重复键：{extra_key}")
                        item[extra_key] = extra_value
                result.append(item)
            else:
                result.append(_scalar(item_text))
        return result

    def _has_key_colon(text):
        try:
            _split_key(text)
            return True
        except YamlError:
            return False

    document = parse_block(tokens[0][0])
    if state["pos"] != len(tokens):
        _, _, number = tokens[state["pos"]]
        raise YamlError(f"{path.name}:{number} 无法解析的尾部内容")
    return document


def load_contract(relative):
    return parse_yaml(CONTRACTS / relative)


def content_digest(document):
    meta = document.get("meta")
    if isinstance(meta, dict) and "checksum" in meta:
        stripped = dict(document)
        stripped["meta"] = {key: value for key, value in meta.items() if key != "checksum"}
        document = stripped
    canonical = json.dumps(document, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def freshness_errors(path, document, label, errors):
    stored = (document.get("meta") or {}).get("checksum")
    if not isinstance(stored, str) or not re.fullmatch(r"[0-9a-f]{64}", stored):
        errors.append(f"{label}：缺少 meta.checksum（合法变更后须运行 --freeze 重新冻结）")
        return
    actual = content_digest(document)
    if stored != actual:
        errors.append(f"{label}：内容摘要与 meta.checksum 不符（新鲜度失效；合法变更后须运行 --freeze 重新冻结）")


def freeze(path):
    document = parse_yaml(path)
    digest = content_digest(document)
    text = path.read_text(encoding="utf-8")
    pattern = re.compile(r"^(\s*)checksum: \"[0-9a-f]{64}\"\s*$", re.MULTILINE)
    if pattern.search(text):
        text = pattern.sub(lambda match: f'{match.group(1)}checksum: "{digest}"', text, count=1)
    else:
        text = re.sub(r"^meta:\s*$", f'meta:\n  checksum: "{digest}"', text, count=1, flags=re.MULTILINE)
    path.write_text(text, encoding="utf-8")


def contract_files():
    files = [CONTRACTS / "schema/v1.yaml", CONTRACTS / "capability-flags.yaml", CONTRACTS / "error-codes.yaml"]
    files.extend(sorted((CONTRACTS / "acceptance").glob("A*.yaml")))
    return files


def check_schema(errors):
    data = load_contract("schema/v1.yaml")
    meta = data.get("meta") or {}
    tables = data.get("tables")
    if not isinstance(tables, list) or not tables:
        errors.append("schema：tables 缺失或为空")
        return
    seen = set()
    total_columns = 0
    for table in tables:
        name = table.get("name")
        if not name:
            errors.append("schema：存在缺 name 的表")
            continue
        if name in seen:
            errors.append(f"schema：重复表名 {name}")
        seen.add(name)
        columns = table.get("columns")
        if not isinstance(columns, list) or not columns:
            errors.append(f"schema：{name} 缺少列定义")
            continue
        column_names = set()
        for column in columns:
            column_name = column.get("name")
            if not column_name:
                errors.append(f"schema：{name} 存在缺 name 的列")
                continue
            if column_name in column_names:
                errors.append(f"schema：{name} 重复列 {column_name}")
            column_names.add(column_name)
            column_type = column.get("type")
            if column_type not in ALLOWED_TYPES:
                errors.append(f"schema：{name}.{column_name} 类型非法：{column_type}")
            foreign_key = column.get("fk")
            if foreign_key is not None:
                if not isinstance(foreign_key, dict):
                    errors.append(f"schema：{name}.{column_name} fk 必须是映射")
                elif foreign_key.get("on_delete") not in ALLOWED_ON_DELETE:
                    errors.append(f"schema：{name}.{column_name} on_delete 非法：{foreign_key.get('on_delete')}")
            values = column.get("values")
            if values is not None and (not isinstance(values, list) or not all(isinstance(v, str) for v in values)):
                errors.append(f"schema：{name}.{column_name} values 必须是字符串序列")
        total_columns += len(columns)
        for unique in table.get("unique_constraints") or []:
            if not isinstance(unique, dict) or not unique.get("columns"):
                errors.append(f"schema：{name} 存在非法唯一约束")
        for index in table.get("indexes") or []:
            if not isinstance(index, dict) or not index.get("columns"):
                errors.append(f"schema：{name} 存在非法索引")
        primary_key = table.get("primary_key")
        derived_keys = set(table.get("derived_key_columns") or [])
        if primary_key and not set(primary_key) <= column_names | derived_keys:
            errors.append(f"schema：{name} 主键引用未定义列：{primary_key}")
    expected = meta.get("expected") or {}
    if expected.get("table_count") != len(tables):
        errors.append(f"schema：meta.expected.table_count={expected.get('table_count')} 与实际 {len(tables)} 不符（新鲜度失效）")
    if expected.get("column_count") != total_columns:
        errors.append(f"schema：meta.expected.column_count={expected.get('column_count')} 与实际 {total_columns} 不符（新鲜度失效）")
    return data


def check_capability_flags(errors, schema_data):
    data = load_contract("capability-flags.yaml")
    flags = data.get("flags")
    if not isinstance(flags, list) or not flags:
        errors.append("capability-flags：flags 缺失或为空")
        return
    bits = [flag.get("bit") for flag in flags]
    if sorted(bits) != list(range(7)) or len(set(bits)) != len(bits):
        errors.append(f"capability-flags：位编号必须为 0–6 且不重复：{bits}")
    names = [flag.get("name") for flag in flags]
    if len(set(names)) != len(names) or not all(names):
        errors.append("capability-flags：flag 名称缺失或重复")
    gating = str(data.get("gating_rules") or "")
    if "google_picker" not in gating and "Google" not in gating:
        errors.append("capability-flags：缺少 Google unknown 门控规则")
    expected = (data.get("meta") or {}).get("expected") or {}
    if expected.get("flag_count") != len(flags):
        errors.append(f"capability-flags：meta.expected.flag_count={expected.get('flag_count')} 与实际 {len(flags)} 不符（新鲜度失效）")
    rows = data.get("initial_values") or []
    if not rows:
        errors.append("capability-flags：缺少首期取值表")
        return
    flag_names = set(names)
    for row in rows:
        source = row.get("source")
        values = row.get("values") or {}
        if set(values) != flag_names:
            errors.append(f"capability-flags：{source} 取值键与七位不一致")
        for flag_name, value in values.items():
            if value not in TRISTATE:
                errors.append(f"capability-flags：{source}.{flag_name} 取值非法：{value}")
    if schema_data:
        source_types = set()
        for table in schema_data.get("tables") or []:
            if table.get("name") == "source":
                for column in table.get("columns") or []:
                    if column.get("name") == "type":
                        source_types = set(column.get("values") or [])
        if {row.get("source") for row in rows} != source_types:
            errors.append("capability-flags：首期取值来源与 schema source.type 枚举不一致")


def check_error_codes(errors):
    data = load_contract("error-codes.yaml")
    codes = data.get("error_codes")
    if not isinstance(codes, list) or not codes:
        errors.append("error-codes：error_codes 缺失或为空")
        return
    seen = set()
    for entry in codes:
        code = entry.get("code")
        if not code or not ERROR_CODE_RE.match(str(code)):
            errors.append(f"error-codes：码值不符合 DOMAIN_REASON 格式：{code}")
            continue
        if code in seen:
            errors.append(f"error-codes：重复码值：{code}")
        seen.add(code)
        if not entry.get("domain"):
            errors.append(f"error-codes：{code} 缺少域")
        if not isinstance(entry.get("auto_retry"), bool):
            errors.append(f"error-codes：{code} auto_retry 必须是布尔")
    for required in ("SOURCE_CERT_UNTRUSTED", "SOURCE_CERT_HOSTNAME_MISMATCH", "SOURCE_PLAINTEXT_PRIVATE_ONLY"):
        if required not in seen:
            errors.append(f"error-codes：缺少 R05 码 {required}")
    expected = (data.get("meta") or {}).get("expected") or {}
    if expected.get("code_count") != len(codes):
        errors.append(f"error-codes：meta.expected.code_count={expected.get('code_count')} 与实际 {len(codes)} 不符（新鲜度失效）")


def check_acceptance(errors):
    directory = CONTRACTS / "acceptance"
    files = sorted(directory.glob("A*.yaml"))
    if len(files) != ACCEPTANCE_COUNT:
        errors.append(f"acceptance：应恰有 {ACCEPTANCE_COUNT} 个文件，实际 {len(files)}")
    ids = []
    for path in files:
        data = parse_yaml(path)
        meta = data.get("meta") or {}
        acceptance = data.get("acceptance") or {}
        telemetry = data.get("telemetry") or {}
        item_id = meta.get("id")
        ids.append(item_id)
        if path.stem != item_id:
            errors.append(f"acceptance：文件名 {path.name} 与 meta.id {item_id} 不一致")
        for field in ("title", "source", "telemetry_source"):
            if not meta.get(field):
                errors.append(f"acceptance：{item_id} 缺 meta.{field}")
        if not acceptance.get("scenario"):
            errors.append(f"acceptance：{item_id} 缺验收场景")
        if not acceptance.get("pass_conditions"):
            errors.append(f"acceptance：{item_id} 缺通过条件")
        if telemetry.get("role") not in TELEMETRY_ROLES:
            errors.append(f"acceptance：{item_id} telemetry.role 非法：{telemetry.get('role')}")
        events = telemetry.get("events")
        if not isinstance(events, list):
            errors.append(f"acceptance：{item_id} telemetry.events 必须是序列")
        else:
            for event in events:
                if not isinstance(event, dict) or not event.get("name"):
                    errors.append(f"acceptance：{item_id} 存在缺 name 的事件")
        metrics = telemetry.get("metrics")
        if not isinstance(metrics, list):
            errors.append(f"acceptance：{item_id} telemetry.metrics 必须是序列")
        else:
            for metric in metrics:
                if metric not in ALLOWED_METRICS:
                    errors.append(f"acceptance：{item_id} 指标非法：{metric}")
    expected_ids = [f"A{index:02d}" for index in range(1, ACCEPTANCE_COUNT + 1)]
    if sorted(str(i) for i in ids) != expected_ids:
        errors.append(f"acceptance：编号必须连续且无重复 A01..A{ACCEPTANCE_COUNT:02d}：{sorted(str(i) for i in ids)}")


def selftest():
    import tempfile

    samples = {
        "map_and_nested": "a: 1\nb:\n  c: yes\nd: null\n",
        "quoted": 'a: "x: y # z"\nb: "{}"\n',
        "seq_of_scalars": "list:\n  - one\n  - two\n",
        "seq_of_maps": "items:\n  - name: x\n    bit: 0\n  - name: y\n    values:\n      k: yes\n",
        "comment": "# head\na: 1 # tail\n",
        "flow": 'rulings: ["R01", "R02"]\nfk: {table: source, column: id, on_delete: CASCADE}\n',
    }
    for text in samples.values():
        with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False, encoding="utf-8") as handle:
            handle.write(text)
            tmp = Path(handle.name)
        try:
            parse_yaml(tmp)
        finally:
            tmp.unlink()
    with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False, encoding="utf-8") as handle:
        handle.write('a: "unclosed\n')
        bad = Path(handle.name)
    try:
        try:
            parse_yaml(bad)
        except YamlError:
            pass
        else:
            raise AssertionError("行内列表应被拒绝")
    finally:
        bad.unlink()
    return 0


def check_vectors(errors):
    vectors_dir = CONTRACTS / "vectors"
    if not vectors_dir.is_dir():
        return
    import json
    for path in sorted(vectors_dir.glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            errors.append(f"向量文件非法：{path.name}（{error}）")
            continue
        if "meta" not in data or "cases" not in data or not isinstance(data["cases"], list) or not data["cases"]:
            errors.append(f"向量文件缺 meta/cases 或 cases 为空：{path.name}")
            continue
        ids = [case.get("id") for case in data["cases"]]
        if len(ids) != len(set(ids)) or any(not i for i in ids):
            errors.append(f"向量用例 id 缺失或重复：{path.name}")


def check(root=ROOT):
    global CONTRACTS
    CONTRACTS = root / "contracts"
    errors = []
    schema_data = None
    try:
        for path in contract_files():
            freshness_errors(path.name, parse_yaml(path), path.name, errors)
        schema_data = check_schema(errors)
        check_capability_flags(errors, schema_data)
        check_error_codes(errors)
        check_acceptance(errors)
        check_vectors(errors)
    except YamlError as error:
        errors.append(str(error))
    return errors


def main():
    if "--selftest" in sys.argv:
        try:
            return selftest()
        except (YamlError, AssertionError) as error:
            print(f"契约检查器自测失败：{error}", file=sys.stderr)
            return 1
    if "--freeze" in sys.argv:
        try:
            for path in contract_files():
                freeze(path)
        except (OSError, YamlError) as error:
            print(f"冻结失败：{error}", file=sys.stderr)
            return 1
        print("已重算并写回全部 meta.checksum。")
        return 0
    try:
        errors = check()
    except (OSError, ValueError, KeyError) as error:
        print(f"契约检查失败：{error}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("契约静态检查通过；不代表业务验收、真机或 M0 通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
