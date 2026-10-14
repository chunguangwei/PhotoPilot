import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_design_assets import svg_errors, validate_files


class DesignAssetTests(unittest.TestCase):
    def svg_check(self, markup):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.svg"
            path.write_text(markup, encoding="utf-8")
            return svg_errors(path)

    def test_editable_svg_is_accepted(self):
        self.assertEqual(self.svg_check('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><text x="10" y="20">光屿</text></svg>'), [])

    def test_script_is_rejected(self):
        self.assertTrue(self.svg_check('<svg xmlns="http://www.w3.org/2000/svg"><script>test</script></svg>'))

    def test_remote_image_is_rejected(self):
        self.assertTrue(self.svg_check('<svg xmlns="http://www.w3.org/2000/svg"><image href="https://example.com/image.png"/></svg>'))

    def test_missing_image_is_rejected(self):
        self.assertTrue(self.svg_check('<svg xmlns="http://www.w3.org/2000/svg"><image href="missing.png"/></svg>'))

    def test_invalid_pdf_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.pdf"
            path.write_bytes(b"not a pdf")
            self.assertTrue(validate_files([path]))

    def test_inline_image_does_not_require_network(self):
        self.assertEqual(self.svg_check('<svg xmlns="http://www.w3.org/2000/svg"><image href="data:image/png;base64,AA=="/></svg>'), [])


if __name__ == "__main__":
    unittest.main()
