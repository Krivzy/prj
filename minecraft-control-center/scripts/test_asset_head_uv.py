"""UV regressions; actual PNG checks need restored assets and Pillow (verification runtime)."""
from copy import deepcopy
import json, unittest
from pathlib import Path
from asset_head_uv import ghast_head, repair_ghast_manifest, GHAST_IMAGE

ASSETS = Path(__file__).resolve().parents[1] / 'frontend/public/game-assets'

class HeadUVTests(unittest.TestCase):
    def test_classic_64x32_retains_exact_front_face(self):
        self.assertEqual(ghast_head(64, 32), {'x':16,'y':16,'width':16,'height':16,'atlasWidth':64,'atlasHeight':32})

    def test_26_3_128x64_scales_front_face_in_both_axes(self):
        self.assertEqual(ghast_head(128, 64), {'x':32,'y':32,'width':32,'height':32,'atlasWidth':128,'atlasHeight':64})

    def test_coordinates_are_scaled_independently_without_rounding(self):
        h = ghast_head(256, 64)
        self.assertEqual((h['x'], h['width'], h['y'], h['height']), (64, 64, 32, 32))

    def test_invalid_or_fractional_atlas_dimensions_fail_closed(self):
        for width, height in [(0,32),(-64,32),(True,32),(64,False),(64.0,32),(127,64),(128,63),(32768,64)]:
            with self.subTest(width=width,height=height), self.assertRaises(ValueError): ghast_head(width,height)

    def test_repair_changes_only_ghast_head_and_does_not_mutate_input(self):
        catalog = {'mobs': {'minecraft:ghast': {'image':GHAST_IMAGE,'name':'fixture-ghast','head':ghast_head(64,32)},
                           'minecraft:zombie': {'image':'unchanged'}}, 'items': {'fixture':'unchanged'}, 'names': {'fixture':'unchanged'}}
        before = deepcopy(catalog); repaired = repair_ghast_manifest(catalog,128,64)
        expected = deepcopy(before); expected['mobs']['minecraft:ghast']['head'] = ghast_head(128,64)
        self.assertEqual(repaired,expected); self.assertEqual(catalog,before)
        self.assertEqual(repair_ghast_manifest(repaired,128,64),repaired)

    def test_unknown_or_external_ghast_image_is_not_overwritten(self):
        for entry in [None, {'image':'https://foreign/image.png'}, {'image':'/custom/image.png'}]:
            with self.assertRaises(ValueError): repair_ghast_manifest({'mobs':{'minecraft:ghast':entry}},128,64)

@unittest.skipUnless((ASSETS/'catalog.json').is_file(), 'Restore installed-client textures before actual PNG checks')
class ActualHeadPixelsTests(unittest.TestCase):
    def test_actual_26_3_crop_is_opaque_and_old_unscaled_crop_was_blank(self):
        from PIL import Image
        im = Image.open(ASSETS/'textures/entity/ghast/ghast.png').convert('RGBA')
        self.assertEqual(im.size,(128,64))
        self.assertEqual(im.crop((16,16,32,32)).getchannel('A').getextrema(),(0,0))
        h = ghast_head(*im.size)
        alpha = im.crop((h['x'],h['y'],h['x']+h['width'],h['y']+h['height'])).getchannel('A')
        self.assertEqual(alpha.getextrema(),(255,255)); self.assertEqual(len(alpha.tobytes()),1024)

    def test_all_34_current_catalog_head_regions_have_visible_pixels(self):
        from PIL import Image
        catalog = json.loads((ASSETS/'catalog.json').read_text(encoding='utf8')); count=0
        for section in ['mobs','items']:
            for key, entry in catalog[section].items():
                if not isinstance(entry,dict) or not entry.get('head'): continue
                with self.subTest(section=section,id=key):
                    image = ASSETS / entry['image'].removeprefix('/game-assets/')
                    im = Image.open(image).convert('RGBA'); h=entry['head']
                    self.assertEqual(im.size,(h['atlasWidth'],h['atlasHeight']))
                    self.assertTrue(0<=h['x']<h['x']+h['width']<=im.width and 0<=h['y']<h['y']+h['height']<=im.height)
                    alpha=im.crop((h['x'],h['y'],h['x']+h['width'],h['y']+h['height'])).getchannel('A')
                    self.assertGreater(alpha.getextrema()[1],0,'Portrait must contain visible pixels')
                    count+=1
        self.assertEqual(count,34)

if __name__=='__main__': unittest.main()
