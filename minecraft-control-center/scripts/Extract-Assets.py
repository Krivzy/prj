"""Copy unmodified game textures and derive UI metadata from the user's installed client."""
import argparse, hashlib, json, struct, zipfile
from pathlib import Path
from asset_head_uv import ghast_head

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description="Extract UI textures from your legally installed Minecraft 26.3 client and verified local language cache.")
parser.add_argument('--client', type=Path, required=True, help='Installed versions/26.3/26.3.jar; its JSON and local assets cache must be present.')
CLIENT = parser.parse_args().client.resolve()
if CLIENT.suffix != '.jar' or not CLIENT.is_file():
    parser.error('A local client JAR is required')
OUT = ROOT / 'frontend/public/game-assets'
EXPECTED = 'e877b6a07acd633fb3bb475002175cec036e7b87'
if hashlib.file_digest(CLIENT.open('rb'), 'sha1').hexdigest() != EXPECTED:
    raise RuntimeError('Installed client does not match its official client hash')
OUT.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(CLIENT) as archive:
    names = set(archive.namelist())
    copied = 0
    for entry in sorted(names):
        if entry.startswith('assets/minecraft/textures/') and entry.endswith(('.png', '.mcmeta')):
            target = OUT / entry.removeprefix('assets/minecraft/')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
            copied += 1

    def read_json(path):
        return json.loads(archive.read(path)) if path in names else {}

    cache = {}
    def model(key, depth=0):
        key = key.removeprefix('minecraft:')
        if key in cache:
            return cache[key]
        if depth > 20:
            return {}
        obj = read_json('assets/minecraft/models/' + key + '.json')
        parent = model(obj.get('parent', ''), depth + 1) if obj.get('parent') else {}
        result = {**parent, **obj, 'textures': {**parent.get('textures', {}), **obj.get('textures', {})}}
        cache[key] = result
        return result

    def texture(value, textures):
        for _ in range(20):
            if isinstance(value, dict):
                value = value.get('sprite', value.get('texture'))
            if not value or not value.startswith('#'):
                break
            value = textures.get(value[1:])
        if not value or value.startswith('#'):
            return None
        path = 'textures/' + value.removeprefix('minecraft:') + '.png'
        if 'assets/minecraft/' + path not in names:
            return None
        return '/game-assets/' + path

    def icon(key):
        obj = model(key)
        textures = obj.get('textures', {})
        faces = {}
        for element in obj.get('elements', []):
            for face, props in element.get('faces', {}).items():
                image = texture(props.get('texture'), textures)
                if image and face not in faces:
                    faces[face] = image
        image = texture(textures.get('layer0'), textures) or faces.get('north') or faces.get('up') or texture(textures.get('particle'), textures)
        if not image:
            for value in textures.values():
                image = texture(value, textures)
                if image:
                    break
        if not image:
            return None
        result = {'image': image}
        if faces:
            result['faces'] = faces
        source = image.removeprefix('/game-assets/')
        metadata = read_json('assets/minecraft/' + source + '.mcmeta')
        if metadata.get('animation'):
            png = archive.read('assets/minecraft/' + source)
            w, h = struct.unpack('>II', png[16:24])
            anim = metadata['animation']
            size = anim.get('height', min(w, h))
            result['animation'] = {'width': w, 'height': h, 'frameHeight': size, 'frames': anim.get('frames', list(range(h // size))), 'frameTime': anim.get('frametime', 1)}
        return result

    def first_model(obj):
        if isinstance(obj, dict):
            if obj.get('type') == 'minecraft:model' and isinstance(obj.get('model'), str):
                return obj['model']
            for k in ('fallback', 'on_false', 'on_true', 'model', 'entries', 'cases', 'models'):
                result = first_model(obj.get(k))
                if result:
                    return result
        if isinstance(obj, list):
            for value in obj:
                result = first_model(value)
                if result:
                    return result
        return None

    items, blocks, missing = {}, {}, []
    for entry in sorted(names):
        if entry.startswith('assets/minecraft/items/') and entry.endswith('.json'):
            name = Path(entry).stem
            ref = first_model(read_json(entry).get('model'))
            value = icon(ref) if ref else None
            value = value or icon('item/' + name) or icon('block/' + name)
            if not value:
                direct = 'textures/item/' + name + '.png'
                if 'assets/minecraft/' + direct in names:
                    value = {'image': '/game-assets/' + direct}
            if value:
                items['minecraft:' + name] = value
            else:
                missing.append(name)
        if entry.startswith('assets/minecraft/blockstates/') and entry.endswith('.json'):
            name = Path(entry).stem
            state = read_json(entry)
            ref = None
            variants = state.get('variants', {})
            for v in variants.values():
                v = v[0] if isinstance(v, list) else v
                if v.get('model'):
                    ref = v['model']; break
            if not ref:
                for part in state.get('multipart', []):
                    v = part.get('apply', {})
                    v = v[0] if isinstance(v, list) else v
                    if v.get('model'):
                        ref = v['model']; break
            value = icon(ref) if ref else icon('block/' + name)
            if value:
                blocks['minecraft:' + name] = value

    effects = {}
    for entry in sorted(names):
        if entry.startswith('assets/minecraft/textures/mob_effect/') and entry.endswith('.png'):
            effects['minecraft:' + Path(entry).stem] = '/game-assets/' + entry.removeprefix('assets/minecraft/')
    mobs = {key.removesuffix('_spawn_egg'): {'image': entry['image']} for key, entry in items.items() if key.endswith('_spawn_egg')}
    base_heads = {'zombie':'zombie/zombie','husk':'zombie/husk','drowned':'zombie/drowned','skeleton':'skeleton/skeleton','stray':'skeleton/stray','wither_skeleton':'skeleton/wither_skeleton','creeper':'creeper/creeper','wither':'wither/wither','giant':'zombie/zombie','mannequin':'player/wide/steve','illusioner':'illager/illusioner','ghast':'ghast/ghast'}
    for entity, path in base_heads.items():
        source = 'assets/minecraft/textures/entity/' + path + '.png'
        w, h = struct.unpack('>II', archive.read(source)[16:24])
        head = ghast_head(w, h) if entity == 'ghast' else {'x':8,'y':8,'width':8,'height':12 if entity == 'illusioner' else 8,'atlasWidth':w,'atlasHeight':h}
        mobs['minecraft:' + entity] = {'image':'/game-assets/textures/entity/' + path + '.png','head':head}
    mobs['minecraft:armor_stand'] = dict(items['minecraft:armor_stand'])
    mobs['minecraft:mushroom_cow'] = dict(mobs['minecraft:mooshroom'])
    heads = {'creeper_head':'entity/creeper/creeper','piglin_head':'entity/piglin/piglin','player_head':'entity/player/wide/steve','skeleton_skull':'entity/skeleton/skeleton','wither_skeleton_skull':'entity/skeleton/wither_skeleton','zombie_head':'entity/zombie/zombie'}
    for name, path in heads.items():
        source = 'assets/minecraft/textures/' + path + '.png'
        if source in names:
            w, h = struct.unpack('>II', archive.read(source)[16:24])
            items['minecraft:' + name] = {'image': '/game-assets/textures/' + path + '.png', 'head': {'x':8,'y':8,'width':8,'height':8,'atlasWidth':w,'atlasHeight':h}}
    banner = 'assets/minecraft/textures/entity/banner/banner_base.png'
    w, h = struct.unpack('>II', archive.read(banner)[16:24])
    for name in missing:
        if name.endswith('_banner'):
            items['minecraft:' + name] = {'image':'/game-assets/textures/entity/banner/banner_base.png','head':{'x':1,'y':1,'width':20,'height':40,'atlasWidth':w,'atlasHeight':h},'tint':name.removesuffix('_banner')}
        elif name.startswith('waxed_') and 'minecraft:' + name.removeprefix('waxed_') in items:
            items['minecraft:' + name] = dict(items['minecraft:' + name.removeprefix('waxed_')])
    missing = [name for name in missing if 'minecraft:' + name not in items]
    # The client JAR embeds English; other languages live in the verified asset index.
    game_root = CLIENT.parents[2]
    metadata = json.loads(CLIENT.with_suffix('.json').read_text(encoding='utf8'))
    asset_index_path = game_root / 'assets/indexes' / (metadata['assetIndex']['id'] + '.json')
    index_bytes = asset_index_path.read_bytes()
    assert hashlib.sha1(index_bytes).hexdigest() == metadata['assetIndex']['sha1']
    language_entry = json.loads(index_bytes)['objects']['minecraft/lang/ru_ru.json']
    language_bytes = (game_root / 'assets/objects' / language_entry['hash'][:2] / language_entry['hash']).read_bytes()
    assert len(language_bytes) == language_entry['size']
    assert hashlib.sha1(language_bytes).hexdigest() == language_entry['hash']
    translations = json.loads(language_bytes)
    for category, prefix in ((items, 'item'), (blocks, 'block'), (mobs, 'entity')):
        for key, value in category.items():
            if isinstance(value, dict):
                value['name'] = translations.get(prefix + '.' + key.replace(':', '.'), translations.get('block.' + key.replace(':', '.'), key.split(':')[-1].replace('_', ' ')))
    enchantments = {key.removeprefix('enchantment.').replace('.', ':', 1): {'image': '/game-assets/textures/item/enchanted_book.png', 'name': value} for key, value in translations.items() if key.startswith('enchantment.minecraft.') and key.count('.') == 2}
    labels = {}
    for lang in ('ru_ru', 'en_us'):
        table = translations if lang == 'ru_ru' else read_json('assets/minecraft/lang/' + lang + '.json')
        labels[lang[:2]] = {}
        for kind, category, prefix in [('items', items, 'item'),('blocks',blocks,'block'),('mobs',mobs,'entity'),('effects',effects,'effect'),('enchantments',enchantments,'enchantment')]:
            labels[lang[:2]][kind] = {key:table.get(prefix + '.' + key.replace(':','.'),table.get('block.' + key.replace(':','.'),key.split(':')[-1].replace('_',' '))) for key in category}
    manifest = {'version': '26.3', 'sourceSha1': EXPECTED, 'items': items, 'blocks': blocks, 'mobs': mobs, 'effects': effects, 'enchantments': enchantments, 'names':labels, 'art': {'hero': '/game-assets/art/hero.png'}}
    (OUT / 'catalog.json').write_text(json.dumps(manifest, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    report = {'clientVersion': '26.3', 'clientSha1': EXPECTED, 'assetIndexSha1': metadata['assetIndex']['sha1'], 'russianLanguageSha1': language_entry['hash'], 'unmodifiedTextures': copied, 'itemIcons': len(items), 'blockIcons': len(blocks), 'effectIcons': len(effects), 'enchantmentIcons': len(enchantments), 'mobTextureEntries': len(mobs), 'missingSpecialItemModels': missing}
    (ROOT / 'docs/ASSET-REPORT.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k != 'missingSpecialItemModels'}))
