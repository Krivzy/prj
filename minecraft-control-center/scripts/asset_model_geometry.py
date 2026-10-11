"""Bounded exact element geometry from the client model; no fabricated shape by item name."""
import math

def model_geometry(model, resolve_texture):
    elements = model.get('elements', [])
    if not isinstance(elements, list) or not elements or len(elements) > 32:
        return None
    out = []
    for element in elements:
        points = [element.get('from'), element.get('to')]
        if any(not isinstance(p, list) or len(p) != 3 or any(not isinstance(n, (int, float)) or not math.isfinite(n) or abs(n) > 64 for n in p) for p in points):
            return None
        faces = {}
        for side, face in element.get('faces', {}).items():
            if side not in ('north', 'east', 'up'):
                continue  # Fixed catalogue camera; do not ship invisible texture faces.
            image = resolve_texture(face.get('texture'))
            if not image:
                continue
            value = {'texture': image}
            for key in ('uv', 'rotation'):
                if key in face:
                    value[key] = face[key]
            tint = face.get('tintindex')
            if isinstance(tint, int) and not isinstance(tint, bool) and 0 <= tint <= 15:
                value['tintindex'] = tint
            faces[side] = value
        if not faces:
            continue
        value = {'from': points[0], 'to': points[1], 'faces': faces}
        if element.get('rotation'):
            value['rotation'] = element['rotation']
        out.append(value)
    return {'elements': out} if out else None
