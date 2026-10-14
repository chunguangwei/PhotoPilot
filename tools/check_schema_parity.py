import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_contracts import load_contract

ROOT = Path(__file__).resolve().parents[1]
IOS_MODEL = ROOT / "ios" / "PhotoPilot" / "Data" / "Persistence" / "Model" / "SchemaModel.swift"
ANDROID_ENTITIES = ROOT / "android" / "app" / "src" / "main" / "java" / "dev" / "photopilot" / "scaffold" / "data" / "db" / "Entities.kt"
EXCLUDED_TABLES = set()

IOS_TYPE = {"stringAttr": "str", "intAttr": "int", "boolAttr": "bool", "blobAttr": "blob"}
KOTLIN_TYPE = {"String": "str", "Long": "int", "Boolean": "bool", "ByteArray": "blob"}


def pascal_to_snake(name):
    return re.sub(r"(?<=[a-z0-9])([A-Z])", r"_\1", name).lower()


def contract_columns(document):
    tables = {}
    for table in document["tables"]:
        name = table["name"]
        if name in EXCLUDED_TABLES:
            continue
        columns = {}
        for column in table.get("columns", []):
            columns[column["name"]] = {
                "type": column["type"],
                "nullable": column.get("null", False) is True,
            }
        tables[name] = columns
    return tables


def parse_ios_model(text, contract):
    entities = {}
    declarations = dict(re.findall(r'let (\w+) = entity\("(\w+)"\)', text))
    to_ones = {}
    for match in re.finditer(r'let (\w+) = toOne\("(\w+)", \w+([^)]*)\)', text):
        var, rel_name, rest = match.groups()
        to_ones[var] = {
            "column": pascal_to_snake(rel_name) + "_id",
            "nullable": "optional: true" in rest,
        }
    for var, pascal in declarations.items():
        table = pascal_to_snake(pascal)
        columns = {}
        block = re.search(var + r"\.properties = \[(.*?)\]", text, re.S)
        if block:
            for match in re.finditer(r'(stringAttr|intAttr|boolAttr|blobAttr)\("(\w+)"([^)]*)\)', block.group(1)):
                kind, column, rest = match.groups()
                columns[column] = {
                    "type": IOS_TYPE[kind],
                    "nullable": "optional: true" in rest,
                }
        for append in re.finditer(var + r"\.properties \+= \[(.*?)\]", text, re.S):
            for rel_var in re.findall(r"\w+", append.group(1)):
                if rel_var in to_ones and to_ones[rel_var]["column"] in contract.get(table, {}):
                    columns[to_ones[rel_var]["column"]] = {
                        "type": "str",
                        "nullable": to_ones[rel_var]["nullable"],
                    }
        entities[table] = columns
    return entities


def parse_android_entities(text):
    entities = {}
    for match in re.finditer(r'@Entity\(\s*tableName = "(\w+)".*?data class \w+\((.*?)\n\)', text, re.S):
        table, body = match.groups()
        columns = {}
        for col in re.finditer(r'@ColumnInfo\(name = "(\w+)"[^)]*\)\s*val \w+: (\w+)(\?)?', body):
            column, kotlin_type, nullable = col.groups()
            columns[column] = {
                "type": KOTLIN_TYPE.get(kotlin_type, "str"),
                "nullable": nullable == "?",
            }
        entities[table] = columns
    return entities


def compare(contract, actual, label, errors):
    for table in sorted(set(contract) - set(actual)):
        errors.append(f"{label} 缺少表：{table}")
    for table in sorted(set(actual) - set(contract)):
        errors.append(f"{label} 多出契约外表：{table}")
    for table in sorted(set(contract) & set(actual)):
        expected = contract[table]
        got = actual[table]
        for column in sorted(set(expected) - set(got)):
            errors.append(f"{label} {table} 缺少列：{column}")
        for column in sorted(set(got) - set(expected)):
            errors.append(f"{label} {table} 多出契约外列：{column}")
        for column in sorted(set(expected) & set(got)):
            if expected[column]["type"] != got[column]["type"]:
                errors.append(f"{label} {table}.{column} 类型不符：契约 {expected[column]['type']}，实现 {got[column]['type']}")
            if expected[column]["nullable"] != got[column]["nullable"]:
                errors.append(f"{label} {table}.{column} NULL 语义不符：契约 {'NULL' if expected[column]['nullable'] else 'NOT NULL'}，实现相反")


def main():
    errors = []
    document = load_contract("schema/v1.yaml")
    contract = contract_columns(document)
    compare(contract, parse_ios_model(IOS_MODEL.read_text(encoding="utf-8"), contract), "iOS", errors)
    compare(contract, parse_android_entities(ANDROID_ENTITIES.read_text(encoding="utf-8")), "Android", errors)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        print(f"schema parity 校验失败：{len(errors)} 处漂移（豁免表：{sorted(EXCLUDED_TABLES)}）。", file=sys.stderr)
        return 1
    print(f"schema parity 校验通过：{len(contract)} 表与双端实现一致（豁免表：{sorted(EXCLUDED_TABLES)}）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
