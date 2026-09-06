import unittest
import numpy as np
from PIL import Image
from key_workout_chroma import extract


class ChromaTests(unittest.TestCase):
    def test_known_green_removed_and_white_foreground_kept(self):
        source = np.zeros((100, 100, 3), dtype=np.uint8)
        source[:] = (12, 245, 14)
        source[30:70, 30:70] = (255, 255, 255)
        image = np.asarray(extract(Image.fromarray(source)))
        self.assertEqual(image[0, 0, 3], 0)
        self.assertEqual(tuple(image[50, 50]), (255, 255, 255, 255))

    def test_warm_skin_is_unchanged(self):
        source = np.zeros((100, 100, 3), dtype=np.uint8)
        source[:] = (0, 255, 0)
        source[30:70, 30:70] = (210, 140, 90)
        image = np.asarray(extract(Image.fromarray(source)))
        self.assertEqual(tuple(image[50, 50]), (210, 140, 90, 255))

    def test_refuses_non_keyed_artwork(self):
        with self.assertRaises(ValueError):
            extract(Image.new('RGB', (100, 100), 'white'))

    def test_refuses_empty_generated_asset(self):
        with self.assertRaises(ValueError):
            extract(Image.new('RGB', (100, 100), '#00ff00'))


if __name__ == '__main__':
    unittest.main()
