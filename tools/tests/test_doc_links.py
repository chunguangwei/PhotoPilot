import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_doc_links import local_link_errors


class DocumentationLinkTests(unittest.TestCase):
    def run_check(self, text, existing=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "test.md"
            path.write_text(text, encoding="utf-8")
            if existing:
                (root / "设计 稿.png").write_bytes(b"placeholder")
            return local_link_errors(path, root)

    def test_missing_target_is_reported(self):
        self.assertEqual(len(self.run_check("[设计](missing.png)")), 1)

    def test_encoded_local_path_is_resolved(self):
        self.assertEqual(self.run_check("[设计](%E8%AE%BE%E8%AE%A1%20%E7%A8%BF.png)", True), [])

    def test_remote_and_anchor_links_are_not_fetched(self):
        self.assertEqual(self.run_check("[远端](https://example.com/path) [章节](#chapter)"), [])

    def test_fenced_examples_are_ignored(self):
        self.assertEqual(self.run_check("```md\n[示例](missing.png)\n```"), [])

    def test_outside_project_link_is_rejected(self):
        self.assertEqual(len(self.run_check("[外部](../outside.md)")), 1)


if __name__ == "__main__":
    unittest.main()
