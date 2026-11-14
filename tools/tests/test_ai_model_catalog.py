import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("ai_catalog", ROOT / "tools/ai_models/validate_catalog.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class AIModelCatalogTests(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((ROOT / "contracts/ai-models.json").read_text())

    def test_current_catalog_is_pinned_and_bilingual(self):
        self.assertEqual(module.validate(self.catalog), [])

    def test_compact_package_is_at_most_sixty_percent_of_standard(self):
        standard, compact = self.catalog["models"]
        standard_size = sum(file["size"] for file in standard["files"])
        compact_size = sum(file["size"] for file in compact["files"])
        self.assertLessEqual(compact_size, standard_size * 0.6)

    def test_allows_an_explicit_pinned_revision_for_each_component(self):
        asset = self.catalog["models"][1]["files"][0]
        previous = asset.get("revision", self.catalog["models"][1]["revision"])
        asset["revision"] = "a" * 40
        for urls in asset["urls"].values():
            urls[:] = [url.replace(previous, asset["revision"]) for url in urls]
        model = self.catalog["models"][1]
        model["revision"] = module.composition_revision(model["files"])
        self.assertEqual(module.validate(self.catalog), [])

    def test_allows_an_independently_pinned_domestic_host_revision(self):
        asset = self.catalog["models"][1]["files"][0]
        revision = "b" * 40
        asset["urls"]["domestic"] = ["https://modelscope.cn/models/example/resolve/" + revision + "/model.onnx"]
        asset["sourceRevisions"] = {"modelscope.cn": revision}
        self.assertEqual(module.validate(self.catalog), [])

    def test_rejects_mutable_component_revision(self):
        self.catalog["models"][1]["files"][0]["revision"] = "main"
        self.assertTrue(module.validate(self.catalog))

    def test_rejects_mutable_model_revision(self):
        self.catalog["models"][0]["revision"] = "main"
        self.assertTrue(module.validate(self.catalog))

    def test_rejects_path_traversal_in_download_filename(self):
        self.catalog["models"][0]["files"][0]["name"] = "../library.db"
        self.assertTrue(module.validate(self.catalog))

    def test_rejects_download_without_integrity_hash(self):
        self.catalog["models"][0]["files"][0]["sha256"] = ""
        self.assertTrue(module.validate(self.catalog))

    def test_rejects_non_https_source(self):
        self.catalog["models"][0]["files"][0]["urls"]["international"] = ["http://example.com/model"]
        self.assertTrue(module.validate(self.catalog))

    def test_rejects_english_only_compact_model(self):
        self.catalog["models"][1]["languages"] = ["en"]
        self.assertTrue(module.validate(self.catalog))

    def test_compact_model_accounts_for_tokenizer_download(self):
        next(file for file in self.catalog["models"][1]["files"] if file["role"] == "tokenizer")["size"] = 100000000
        self.assertTrue(module.validate(self.catalog))

    def test_rejects_invalid_default_threshold(self):
        self.catalog["models"][0]["defaultThreshold"] = 1.1
        self.assertTrue(module.validate(self.catalog))

    def test_rejects_duplicate_model_identity(self):
        self.catalog["models"].append(copy.deepcopy(self.catalog["models"][0]))
        self.assertTrue(module.validate(self.catalog))

    def test_requires_runtime_and_model_asset(self):
        self.catalog["models"][0]["runtime"] = "remote-script"
        self.assertTrue(module.validate(self.catalog))

if __name__ == "__main__":
    unittest.main()
