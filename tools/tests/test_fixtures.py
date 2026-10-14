import hashlib
import sys
import unittest
from pathlib import Path

from PIL import ImageOps

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generate_fixtures import encode, reference_image
from check_fixtures import check


class FixtureChecksTests(unittest.TestCase):
    def test_local_fixture_manifest_is_valid(self):
        self.assertEqual(check(), [])

    def test_synthetic_encoding_is_repeatable(self):
        first = encode(reference_image(variant=3))
        second = encode(reference_image(variant=3))
        self.assertEqual(hashlib.sha256(first).digest(), hashlib.sha256(second).digest())

    def test_variants_are_not_identical(self):
        self.assertNotEqual(encode(reference_image(variant=3)), encode(reference_image(variant=4)))

    def test_original_orientation_is_stable(self):
        image = reference_image()
        self.assertEqual(image.tobytes(), ImageOps.exif_transpose(image).tobytes())


if __name__ == "__main__":
    unittest.main()
