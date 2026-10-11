"""Pure UV metadata helpers; no client, image, server or filesystem access."""
from copy import deepcopy

GHAST_IMAGE = '/game-assets/textures/entity/ghast/ghast.png'

def ghast_head(atlas_width, atlas_height):
    """Scale the classic (16,16,16,16) crop on a 64x32 texture atlas.

    Minecraft 26.3 uses a 128x64 atlas. X and Y scale independently;
    coordinates remain exact integers rather than rounding to another face.
    """
    if (type(atlas_width) is not int or type(atlas_height) is not int
            or not 0 < atlas_width <= 16384 or not 0 < atlas_height <= 16384
            or atlas_width % 4 or atlas_height % 2):
        raise ValueError('Ghast atlas dimensions cannot represent exact classic UV coordinates')
    return {'x': atlas_width // 4, 'y': atlas_height // 2,
            'width': atlas_width // 4, 'height': atlas_height // 2,
            'atlasWidth': atlas_width, 'atlasHeight': atlas_height}

def repair_ghast_manifest(manifest, atlas_width, atlas_height):
    """Copy the manifest and change only the reviewed Ghast head metadata."""
    entry = manifest.get('mobs', {}).get('minecraft:ghast')
    if not isinstance(entry, dict) or entry.get('image') != GHAST_IMAGE:
        raise ValueError('The Ghast entry does not refer to the known local entity texture')
    result = deepcopy(manifest)
    result['mobs']['minecraft:ghast']['head'] = ghast_head(atlas_width, atlas_height)
    return result
