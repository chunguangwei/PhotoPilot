import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
SAMPLE_SIZE = 64


def response_errors(status, headers, body, offset, expected_size):
    headers = {key.lower(): value for key, value in headers.items()}
    expected_range = f"bytes {offset}-{offset + SAMPLE_SIZE - 1}/{expected_size}"
    errors = []
    if status != 206:
        errors.append("server did not honor byte range")
    if headers.get("content-range") != expected_range:
        errors.append("unexpected content range or asset size")
    if len(body) != SAMPLE_SIZE:
        errors.append("truncated range response")
    return errors


def probe(url, expected_size, offset):
    result = {"url": url, "offset": offset, "expectedSize": expected_size}
    request = Request(url, headers={
        "Range": f"bytes={offset}-{offset + SAMPLE_SIZE - 1}",
        "User-Agent": "PhotoPilot-source-probe/1.0",
        "Accept-Encoding": "identity",
    })
    try:
        with urlopen(request, timeout=30) as response:
            body = response.read(SAMPLE_SIZE)
            result.update({
                "status": response.status,
                "finalHost": urlparse(response.url).hostname,
                "contentRange": response.headers.get("Content-Range"),
                "etag": response.headers.get("ETag"),
                "acceptRanges": response.headers.get("Accept-Ranges"),
                "sampleSha256": hashlib.sha256(body).hexdigest(),
                "errors": response_errors(response.status, dict(response.headers), body, offset, expected_size),
            })
    except Exception as error:
        result["errors"] = [str(error)]
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, default=ROOT / "contracts/ai-models.json")
    parser.add_argument("--region", choices=["domestic", "international", "all"], default="all")
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text())
    results = []
    regions = ["international", "domestic"] if args.region == "all" else [args.region]
    for model in catalog["models"]:
        for asset in model["files"]:
            for region in regions:
                for url in asset["urls"][region]:
                    for offset in [0, SAMPLE_SIZE]:
                        result = probe(url, asset["size"], offset)
                        result.update({"modelId": model["id"], "file": asset["name"], "region": region})
                        results.append(result)
    print(json.dumps({"checkedAt": datetime.now(timezone.utc).isoformat(), "results": results}, indent=2))
    return int(any(result["errors"] for result in results))


if __name__ == "__main__":
    raise SystemExit(main())
