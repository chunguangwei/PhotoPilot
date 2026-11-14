import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class ModelSourceTests(unittest.TestCase):
    def setUp(self):
        path = ROOT / "tools/ai_models/source_probe.py"
        self.assertTrue(path.is_file(), "source probe must exist")
        spec = importlib.util.spec_from_file_location("source_probe", path)
        self.probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.probe)
        self.headers = {"Content-Range": "bytes 64-127/1000"}

    def test_accepts_exact_nonzero_partial_response(self):
        self.assertEqual(self.probe.response_errors(206, self.headers, b"x" * 64, 64, 1000), [])

    def test_rejects_server_ignoring_resume_range(self):
        self.assertTrue(self.probe.response_errors(200, self.headers, b"x" * 64, 64, 1000))

    def test_rejects_wrong_resume_offset(self):
        self.headers["Content-Range"] = "bytes 0-63/1000"
        self.assertTrue(self.probe.response_errors(206, self.headers, b"x" * 64, 64, 1000))

    def test_rejects_wrong_asset_length(self):
        self.headers["Content-Range"] = "bytes 64-127/2000"
        self.assertTrue(self.probe.response_errors(206, self.headers, b"x" * 64, 64, 1000))

    def test_rejects_truncated_sample(self):
        self.assertTrue(self.probe.response_errors(206, self.headers, b"x", 64, 1000))

    def test_accepts_lowercase_header_names(self):
        self.assertEqual(self.probe.response_errors(206, {"content-range": "bytes 64-127/1000"}, b"x" * 64, 64, 1000), [])


if __name__ == "__main__":
    unittest.main()
