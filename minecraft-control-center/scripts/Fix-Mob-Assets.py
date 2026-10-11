"""Repair Ghast UV metadata only; preserve all textures and other catalog entries."""
import hashlib, json, struct
from pathlib import Path
from asset_head_uv import repair_ghast_manifest

def main():
    root = Path(__file__).resolve().parents[1]
    assets = root / 'frontend/public/game-assets'
    manifest_path = assets / 'catalog.json'
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw.decode('utf8'))
    texture = assets / 'textures/entity/ghast/ghast.png'
    png = texture.read_bytes()
    if png[:8] != bytes([137,80,78,71,13,10,26,10]) or png[12:16] != b'IHDR':
        raise ValueError('Ghast texture is not a PNG with an IHDR')
    width, height = struct.unpack('>II', png[16:24])
    repaired = repair_ghast_manifest(manifest, width, height)
    # Extraction imports the same helper. Do not rewrite its source or
    # reconstruct unrelated mobs, items or translation tables.
    if repaired != manifest:
        if manifest_path.read_bytes() != raw:
            raise RuntimeError('Catalog changed during UV preparation')
        temporary = manifest_path.with_name('catalog.json.ghast-next')
        with temporary.open('xb') as stream:
            stream.write(json.dumps(repaired, ensure_ascii=False, separators=(',', ':')).encode('utf8'))
        temporary.replace(manifest_path)
    if texture.read_bytes() != png or json.loads(manifest_path.read_bytes()) != repaired:
        raise RuntimeError('Ghast repair readback failed')
    print(json.dumps({'passed': True, 'changed': repaired != manifest,
                      'scope': 'ghast-head-metadata-only', 'originalTexturesUnmodified': True,
                      'textureSha256': hashlib.sha256(png).hexdigest(),
                      'head': repaired['mobs']['minecraft:ghast']['head']}))

if __name__ == '__main__':
    main()
