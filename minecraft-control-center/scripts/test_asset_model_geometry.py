import unittest
from asset_model_geometry import model_geometry

class GeometryTests(unittest.TestCase):
    def test_distinct_client_boxes_are_preserved_without_guessing_item_name(self):
        texture = lambda key: '/game-assets/textures/block/oak_planks.png'
        slab = {'elements': [{'from': [0, 0, 0], 'to': [16, 8, 16], 'faces': {'up': {'texture': '#all'}, 'north': {'texture': '#all', 'uv': [0, 8, 16, 16]}}}]}
        geometry = model_geometry(slab, texture)
        self.assertEqual([16, 8, 16], geometry['elements'][0]['to'])
        self.assertEqual([0, 8, 16, 16], geometry['elements'][0]['faces']['north']['uv'])
        stairs = {'elements': [*slab['elements'], {'from': [0, 8, 8], 'to': [16, 16, 16], 'faces': {'up': {'texture': '#all'}}}]}
        self.assertEqual(2, len(model_geometry(stairs, texture)['elements']))

    def test_hidden_faces_and_missing_textures_are_not_shipped(self):
        model = {'elements': [{'from': [0, 0, 0], 'to': [16, 16, 16], 'faces': {'south': {'texture': '#all'}, 'up': {'texture': '#missing'}, 'east': {'texture': '#all'}}}]}
        geometry = model_geometry(model, lambda key: '/game-assets/textures/block/oak_planks.png' if key == '#all' else None)
        self.assertEqual(['east'], list(geometry['elements'][0]['faces']))

    def test_nonfinite_coordinates_and_element_overflow_are_refused(self):
        self.assertIsNone(model_geometry({'elements': [{}] * 33}, lambda key: 'unused'))
        self.assertIsNone(model_geometry({'elements': [{'from': [float('nan'), 0, 0], 'to': [16, 16, 16]}]}, lambda key: 'unused'))

    def test_tint_metadata_preserves_client_overlay_without_tinting_dirt(self):
        model = {'elements': [{'from': [0, 0, 0], 'to': [16, 16, 16], 'faces': {'up': {'texture': '#top', 'tintindex': 0}, 'east': {'texture': '#dirt'}, 'north': {'texture': '#overlay', 'tintindex': 0}}}]}
        geometry = model_geometry(model, lambda key: '/game-assets/textures/block/' + key[1:] + '.png')
        faces = geometry['elements'][0]['faces']
        self.assertEqual(0, faces['up']['tintindex'])
        self.assertEqual(0, faces['north']['tintindex'])
        self.assertNotIn('tintindex', faces['east'])
        model['elements'][0]['faces']['east']['tintindex'] = True
        self.assertNotIn('tintindex', model_geometry(model, lambda _: 'local')['elements'][0]['faces']['east'])

if __name__ == '__main__':
    unittest.main()
