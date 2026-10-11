"""Add verified client tint indices to an existing catalog; never rewrite game images."""
import argparse
import copy
import hashlib
import json
import zipfile
from pathlib import Path
from asset_model_geometry import model_geometry

EXPECTED_CLIENT_SHA1 = 'e877b6a07acd633fb3bb475002175cec036e7b87'

def signature(geometry):
    value = copy.deepcopy(geometry)
    for element in value['elements']:
        for face in element['faces'].values():
            for key in ('tintindex', 'textureHeight', 'frameIndex'):
                face.pop(key, None)
    return json.dumps(value, sort_keys=True, separators=(',', ':'))

def refresh(data, archive):
    names = set(archive.namelist())
    sources = sorted(name for name in names if name.startswith('assets/minecraft/models/') and name.endswith('.json'))
    if len(sources) > 8192:
        raise ValueError('Client model budget exceeded')
    cache = {}
    def model(key, depth=0):
        key = key.removeprefix('minecraft:')
        if key in cache:
            return cache[key]
        name = 'assets/minecraft/models/' + key + '.json'
        if depth > 20 or name not in names:
            return {}
        if archive.getinfo(name).file_size > 1048576:
            raise ValueError('Client model size exceeded')
        obj = json.loads(archive.read(name))
        parent = model(obj['parent'], depth + 1) if obj.get('parent') else {}
        result = {**parent, **obj, 'textures': {**parent.get('textures', {}), **obj.get('textures', {})}}
        cache[key] = result
        return result
    references = {}
    for name in sources:
        obj = model(name.removeprefix('assets/minecraft/models/').removesuffix('.json'))
        def texture(value):
            for _ in range(20):
                if isinstance(value, dict):
                    value = value.get('sprite', value.get('texture'))
                if not isinstance(value, str):
                    return None
                if not value.startswith('#'):
                    break
                value = obj.get('textures', {}).get(value[1:])
            if not value or value.startswith('#'):
                return None
            path = 'textures/' + value.removeprefix('minecraft:') + '.png'
            return '/game-assets/' + path if 'assets/minecraft/' + path in names else None
        geometry = model_geometry(obj, texture)
        if geometry:
            fingerprint = signature(geometry)
            existing = references.get(fingerprint)
            if existing is not None and existing != geometry:
                references[fingerprint] = False  # Reject ambiguous matching models rather than guess by item name.
            elif fingerprint not in references:
                references[fingerprint] = geometry
    output = copy.deepcopy(data)
    models, mapping, changed, tinted = {}, {}, 0, 0
    for old_key, geometry in data.get('models', {}).items():
        reference = references.get(signature(geometry))
        if reference is False:
            raise ValueError('Ambiguous client model tint metadata')
        value = copy.deepcopy(geometry)
        if reference:
            for element, original in zip(value['elements'], reference['elements']):
                for side, face in element['faces'].items():
                    tint = original['faces'][side].get('tintindex')
                    if tint is not None:
                        face['tintindex'] = tint
                        tinted += 1
        changed += value != geometry
        raw = json.dumps(value, sort_keys=True, separators=(',', ':')).encode('utf-8')
        key = hashlib.sha256(raw).hexdigest()[:16]
        if key in models and models[key] != value:
            raise ValueError('Model digest collision')
        models[key] = value
        mapping[old_key] = key
    output['models'] = models
    for category in ('items', 'blocks'):
        for entry in output.get(category, {}).values():
            if isinstance(entry, dict) and isinstance(entry.get('model'), str):
                entry['model'] = mapping[entry['model']]
    return output, {'changedModels': changed, 'clientTintedFaces': tinted, 'modelCount': len(models)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client', type=Path, required=True)
    parser.add_argument('--catalog', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='Create-only output; caller reviews before replacing catalog')
    args = parser.parse_args()
    with args.client.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha1').hexdigest() != EXPECTED_CLIENT_SHA1:
            raise ValueError('Client does not match the verified official hash')
    raw = args.catalog.read_bytes()
    data = json.loads(raw)
    if data.get('sourceSha1') != EXPECTED_CLIENT_SHA1:
        raise ValueError('Catalog client provenance differs')
    with zipfile.ZipFile(args.client) as archive:
        result, facts = refresh(data, archive)
    output = json.dumps(result, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    with args.output.open('xb') as stream:
        stream.write(output)
    print(json.dumps({**facts, 'sourceSha256': hashlib.sha256(raw).hexdigest(), 'outputSha256': hashlib.sha256(output).hexdigest(), 'imagesWritten': 0}))

if __name__ == '__main__':
    main()
