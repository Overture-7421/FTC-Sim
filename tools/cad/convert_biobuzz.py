"""
Converts the Onshape glTF export of the BIOBUZZ robot (23619-26-A-0000 Main) into the compact model FTC-Sim draws.

    python convert_biobuzz.py <robot.gltf> <out biobuzz.model> [triangle budget]

The export is Draco-compressed, 24 million unique triangles, so the converter decodes it, leaves out the fasteners and
other parts too small to see, simplifies every part to a size-based triangle count, splits the robot into the rigid
groups that move (wheels, turret, hood, flywheel, rollers, colibri arms, stopper, encoder gears) and writes each group
as quantised vertices with a colour per vertex. Coordinates leave in the simulator's frame: X forward, Y left, Z up, the
origin at the centre of the four wheels, on the chassis body's height. The CAD faces -Y with +X to its left.

Needs: pip install DracoPy numpy fast-simplification
"""
import base64, collections, gzip, json, math, re, struct, sys
import numpy as np
import DracoPy
import fast_simplification

CAD_CENTRE_Y = 0.0385            # the middle of the wheels' Y positions in the CAD
BODY_ORIGIN_Z = 0.1264           # the chassis body's centre above the CAD's wheel-axle height (0.1824 - 0.056)
MIN_SIZE = 0.016                 # parts whose largest side is under this are left out, unless they are in KEEP_SMALL
PLATE_TRIANGLES = 100000         # a plate with no more triangles than this is kept as the CAD has it
FASTENER = re.compile(r'(?i)bolt|screw|\bnut\b|washer|rivet|standoff|spacer|insert|bhcs|shcs|set screw|retaining|e-clip|button m|hex m|socket m|lock ?nut|\bpin\b')
KEEP_SMALL = re.compile(r'(?i)servo|encoder|sprocket|gear|pulley|bearing|roller|wheel|chain|sensor|octoquad|pinpoint|hub|switch|motor')

WHEEL_AXES = {  # name: (CAD x of the axle centre, CAD y)
    'wheel.fl': (0.177, -0.083), 'wheel.fr': (-0.177, -0.083), 'wheel.rl': (0.177, 0.160), 'wheel.rr': (-0.177, 0.160)}
X = (1.0, 0.0, 0.0)              # CAD axes
Z = (0.0, 0.0, 1.0)
# name: (parent, joint, CAD pivot, CAD axis, sign). The sign makes a positive joint value turn the way the sim defines it.
GROUPS = collections.OrderedDict([
    ('base', (None, '', (0, CAD_CENTRE_Y, BODY_ORIGIN_Z), Z, 1)),
    ('wheel.fl', ('base', 'wheel.fl', (0.177, -0.083, 0), X, 1)),
    ('wheel.fr', ('base', 'wheel.fr', (-0.177, -0.083, 0), X, 1)),
    ('wheel.rl', ('base', 'wheel.rl', (0.177, 0.160, 0), X, 1)),
    ('wheel.rr', ('base', 'wheel.rr', (-0.177, 0.160, 0), X, 1)),
    ('turret', ('base', 'turret', (0.0, -0.0505, 0.2835), Z, 1)),
    ('hood', ('turret', 'hood', (0.0, -0.1212, 0.341), X, -1)),
    ('flywheel', ('hood', 'flywheel', (0.0, -0.1235, 0.341), X, 1)),
    ('feeder', ('hood', 'flywheel', (0.0, -0.004, 0.343), X, 1)),
    ('intake', ('base', 'intake', (0.0, -0.186, 0.040), X, 1)),
    ('colibri.left', ('base', 'colibri.left', (0.2143, 0.016, 0.023), X, -1)),
    ('colibri.right', ('base', 'colibri.right', (-0.2096, 0.016, 0.023), X, -1)),
    ('stopper', ('base', 'stopper', (-0.003, 0.0463, 0.1707), X, 1)),
    ('encoder.a', ('base', 'encoder.a', (0.0470, -0.1190, 0.2800), Z, 1)),
    ('encoder.b', ('base', 'encoder.b', (-0.0470, -0.1190, 0.2800), Z, 1)),
])
HOOD_REST_RADIANS = math.radians(1.9)   # the hood stands this far from level in the CAD

HOOD_NAMES = ('Placa Hood', '14t Steel Spur Gear', '28t Aluminum Spur Gear')
FEEDER_NAMES = ('Sushi Roller', '3/8" Rounded Hex (3.758" L')
FLYWHEEL_NAMES = ('3" Stealth Wheel', '3/8" Rounded Hex (5.507" L')
INTAKE_NAMES = ('WCP-0353-001', 'WCP-0354-001', '2" Compliant Wheel', 'Part 1', '16.875 in. Rounded Hex Shaft',
                '8t Press Fit Sprocket', '0303 Adaptador sprocket')
AXLE_NAMES = ('48mm Gecko Wheel', '2" Compliant Wheel', '8t Press Fit Sprocket', 'AluminumShaft',
              '3/8" Rounded Hex (5.04" L', '3/8" Rounded Hex (5.669" L', '20t Slip Fit Gear', '40t Slip Fit Gear')


def mat(node):
    return np.array(node['matrix'], dtype=float).reshape(4, 4).T if 'matrix' in node else np.eye(4)


def material_colour(material):
    """The material's base colour, or, when it has none, the colour its name spells out ("r_g_b_..." in 0 to 1)."""
    factor = material.get('pbrMetallicRoughness', {}).get('baseColorFactor')
    if factor:
        return factor
    parts = re.findall(r'\d+\.\d+', material.get('name', ''))
    if len(parts) >= 3:
        return [float(v) for v in parts[:3]]
    return [0.6, 0.6, 0.6]


def to_sim(points):
    out = np.empty_like(points)
    out[:, 0] = -(points[:, 1] - CAD_CENTRE_Y)
    out[:, 1] = points[:, 0]
    out[:, 2] = points[:, 2] - BODY_ORIGIN_Z
    return out


def dir_to_sim(v):
    return (-v[1], v[0], v[2])


def point_to_sim(p):
    return (-(p[1] - CAD_CENTRE_Y), p[0], p[2] - BODY_ORIGIN_Z)


def weld(points, faces):
    key = np.ascontiguousarray(np.round(points * 1e6).astype(np.int32))
    _, first, inverse = np.unique(key.view([('', key.dtype)] * 3).ravel(), return_index=True, return_inverse=True)
    welded = points[first]
    f = inverse[faces]
    good = (f[:, 0] != f[:, 1]) & (f[:, 1] != f[:, 2]) & (f[:, 0] != f[:, 2])
    return welded, f[good]


def simplify(points, faces, target, plate):
    points, faces = weld(points.astype(np.float64), faces.astype(np.int64))
    points, faces = points.astype(np.float32), faces.astype(np.int32)
    if len(faces) <= target or (plate and len(faces) <= PLATE_TRIANGLES):
        # Thin plates with holes tear under any reduction (even a lossless one), so they stay as the CAD has them.
        return points, faces
    for agg in (5, 7, 9):
        points, faces = fast_simplification.simplify(points, faces, target_count=int(target), agg=agg)
        if len(faces) <= target * 2:
            break
    return points, faces


def main():
    src, dst = sys.argv[1], sys.argv[2]
    budget = int(sys.argv[3]) if len(sys.argv) > 3 else 330000
    g = json.load(open(src, 'r', encoding='utf-8'))
    uri = g['buffers'][0]['uri']
    blob = base64.b64decode(uri.split(',', 1)[1])
    nodes, meshes, acc, views = g['nodes'], g['meshes'], g['accessors'], g['bufferViews']
    materials = g['materials']
    parent = {}
    for i, n in enumerate(nodes):
        for c in n.get('children', []):
            parent[c] = i
    world = {}

    def walk(i, m):
        w = m @ mat(nodes[i])
        world[i] = w
        for c in nodes[i].get('children', []):
            walk(c, w)
    walk(g['scenes'][0]['nodes'][0], np.eye(4))

    def top(i):
        while parent.get(i) not in (None, 0):
            i = parent[i]
        return nodes[i].get('name', '')

    def mesh_box(mi):
        lo = np.full(3, np.inf); hi = np.full(3, -np.inf)
        for p in meshes[mi]['primitives']:
            a = acc[p['attributes']['POSITION']]
            lo = np.minimum(lo, a['min']); hi = np.maximum(hi, a['max'])
        return lo, hi

    keep = []  # (node index, mesh index, name, top, world centre, world size)
    for i, n in enumerate(nodes):
        if 'mesh' not in n:
            continue
        name = n.get('name', '?')
        lo, hi = mesh_box(n['mesh'])
        corners = np.array([[x, y, z, 1] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])])
        w = (world[i] @ corners.T).T[:, :3]
        wlo, whi = w.min(0), w.max(0)
        size, centre = whi - wlo, (wlo + whi) / 2
        big = size.max() >= MIN_SIZE
        if FASTENER.search(name) and size.max() < 0.05:
            continue
        if not big and not KEEP_SMALL.search(name):
            continue
        if size.max() < 0.006:
            continue
        keep.append((i, n['mesh'], name, top(i), centre, size))
    print('kept', len(keep), 'of', sum(1 for n in nodes if 'mesh' in n), 'mesh nodes')

    def assign(name, topname, c, s):
        t = topname
        if 'Shooter' in t:
            if any(name.startswith(k) for k in FLYWHEEL_NAMES):
                return 'flywheel'
            if any(name.startswith(k) for k in FEEDER_NAMES):
                return 'feeder'
            if any(name.startswith(k) for k in HOOD_NAMES):
                return 'hood'
            if name.startswith('0406 - 32T'):
                return 'encoder.a'
            if name.startswith('0406 - 28T'):
                return 'encoder.b'
            if name.startswith(('Topcase', 'Midcase', 'Botcase', 'REV Through Bore', '0407', '1628-0105-0001-Inner', 'CAGE', 'IR:', '3D - Spacer', '2302-', '1910-', '1805-', '1231-', 'Spline', '2800', '2802', 'Screw')):
                return 'base'
            if c[2] >= 0.2925 or name.startswith('1628-0105-0001-Outer') or name.startswith('OR:'):
                if abs(c[0]) < 0.03 and abs(c[1] - 0.045) < 0.03:
                    return 'base'
                return 'turret'
            return 'base'
        if 'Intake' in t:
            return 'intake' if any(name.startswith(k) for k in INTAKE_NAMES) else 'base'
        if 'Indexer' in t:
            if name.startswith('goBilda Floodgate'):
                return 'stopper'
            if any(name.startswith(k) for k in AXLE_NAMES):
                return 'axle'
            return 'base'
        if 'Chassis' in t:
            for gname, (ax, ay) in WHEEL_AXES.items():
                if abs(c[0] - ax) < 0.03 and abs(c[1] - ay) < 0.03 and abs(c[2]) < 0.03 and s[1] >= 0.085 and s[2] >= 0.085:
                    return gname
            if name.startswith(('0104 - Placa Colibr', 'Part 10')):
                return 'colibri.left' if c[0] > 0 else 'colibri.right'
            if name.startswith('40T Spur Gear') and abs(c[0]) > 0.15:
                return 'colibri.left' if c[0] > 0 else 'colibri.right'
            if name.startswith('3/8" Rounded Hex (2.778" L') and abs(c[0]) > 0.15:
                return 'colibri.left' if c[0] > 0 else 'colibri.right'
        return 'base'

    # the indexer's axles: one group per shaft, found by where the wheels and sprockets sit
    axles = {}
    assigned = []
    for (i, mi, name, topname, c, s) in keep:
        gname = assign(name, topname, c, s)
        if gname == 'axle':
            key = (round(c[1] / 0.006), round(c[2] / 0.006))
            axles.setdefault(key, []).append(c)
            gname = 'axle%d_%d' % key
        assigned.append(gname)

    groups = collections.OrderedDict(GROUPS)
    for key, centres in sorted(axles.items()):
        c = np.mean(centres, axis=0)
        groups['axle%d_%d' % key] = ('base', 'conveyor', (0.0, float(c[1]), float(c[2])), X, 1)

    # triangle budget per unique mesh: a size based share, scaled to the budget
    counts = collections.Counter()
    for (i, mi, name, topname, c, s), gname in zip(keep, assigned):
        counts[mi] += 1
    dims = {}
    orig = {}
    for mi in counts:
        lo, hi = mesh_box(mi)
        dims[mi] = float((hi - lo).max())
        orig[mi] = sum(acc[p['indices']]['count'] // 3 for p in meshes[mi]['primitives'])

    def wanted(mi, k):
        return int(min(orig[mi], max(48, k * (dims[mi] / 0.05) ** 0.8)))
    lo_k, hi_k = 100.0, 20000.0
    for _ in range(40):
        k = math.sqrt(lo_k * hi_k)
        total = sum(wanted(mi, k) * n for mi, n in counts.items())
        if total > budget:
            hi_k = k
        else:
            lo_k = k
    k = lo_k
    print('size factor', round(k), 'estimated triangles', sum(wanted(mi, k) * n for mi, n in counts.items()))

    decoded = {}
    for n_done, mi in enumerate(sorted(counts)):
        # A mesh may be thousands of small primitives of one material (a wheel's rollers): simplify them together.
        by_material = collections.OrderedDict()
        for p in meshes[mi]['primitives']:
            view = views[p['extensions']['KHR_draco_mesh_compression']['bufferView']]
            raw = blob[view['byteOffset']:view['byteOffset'] + view['byteLength']]
            d = DracoPy.decode(raw)
            pts = np.asarray(d.points, dtype=np.float64).reshape(-1, 3)
            fcs = np.asarray(d.faces, dtype=np.int64).reshape(-1, 3)
            by_material.setdefault(p['material'], []).append((pts, fcs))
        parts = []
        for material, pieces in by_material.items():
            offset, all_pts, all_fcs = 0, [], []
            for pts, fcs in pieces:
                all_pts.append(pts)
                all_fcs.append(fcs + offset)
                offset += len(pts)
            pts, fcs = np.concatenate(all_pts), np.concatenate(all_fcs)
            if len(pts) == 0 or len(fcs) == 0:
                continue
            rgb = np.clip(np.round(np.array(material_colour(materials[material])[:3]) * 255), 0, 255).astype(np.uint8)
            share = max(1, int(wanted(mi, k) * len(fcs) / max(1, orig[mi])))
            dims_sorted = sorted(float(v) for v in (pts.max(axis=0) - pts.min(axis=0)))
            plate = dims_sorted[0] < 0.012 and dims_sorted[2] > 0.06
            sp, sf = simplify(pts, fcs, share, plate)
            if len(sf) == 0:
                continue
            parts.append((sp, sf, rgb))
        decoded[mi] = parts
        if sum(len(p[1]) for p in parts) > 20000:
            print('  heavy mesh', mi, 'originally', orig[mi], 'now', sum(len(p[1]) for p in parts), 'used', counts[mi], 'x', flush=True)
        if n_done % 25 == 0:
            print('decoded', n_done + 1, '/', len(counts), flush=True)

    per_group = collections.defaultdict(lambda: {'pos': [], 'rgb': [], 'idx': [], 'count': 0})
    for (i, mi, name, topname, c, s), gname in zip(keep, assigned):
        w = world[i]
        for (sp, sf, rgb) in decoded[mi]:
            wp = (w @ np.c_[sp.astype(np.float64), np.ones(len(sp))].T).T[:, :3]
            sim = to_sim(wp)
            buf = per_group[gname]
            base = buf['count']
            buf['pos'].append(sim)
            buf['rgb'].append(np.tile(rgb, (len(sim), 1)))
            buf['idx'].append(sf + base)
            buf['count'] += len(sim)

    header = {'version': 1, 'groups': []}
    blobs = []
    for gname, (par, joint, pivot, axis, sign) in groups.items():
        buf = per_group.get(gname)
        pivot_sim = point_to_sim(pivot)
        entry = {'name': gname, 'parent': par or '', 'joint': joint, 'sign': sign,
                 'rest': HOOD_REST_RADIANS if gname == 'hood' else 0.0,
                 'pivot': [round(v, 6) for v in pivot_sim], 'axis': [round(v, 6) for v in dir_to_sim(axis)], 'chunks': []}
        if buf and buf['count']:
            pos = np.concatenate(buf['pos']) - np.array(pivot_sim)
            rgb = np.concatenate(buf['rgb'])
            idx = np.concatenate(buf['idx']).astype(np.int64)
            order = np.argsort(idx.min(axis=1), kind='stable')
            idx = idx[order]
            start = 0
            while start < len(idx):
                # At most 20000 triangles name at most 60000 vertices, which a 16-bit index reaches.
                end = min(len(idx), start + 20000)
                tri = idx[start:end]
                used, inverse = np.unique(tri.ravel(), return_inverse=True)
                cp, cc = pos[used], rgb[used]
                mn, mx = cp.min(axis=0), cp.max(axis=0)
                span = np.maximum(mx - mn, 1e-9)
                q = np.round((cp - mn) / span * 65535 - 32768).astype(np.int16)
                entry['chunks'].append({'vertices': len(used), 'triangles': len(tri), 'min': [float(v) for v in mn], 'max': [float(v) for v in mx]})
                blobs += [q.tobytes(), cc.astype(np.uint8).tobytes(), inverse.reshape(-1, 3).astype(np.uint16).tobytes()]
                start = end
        header['groups'].append(entry)
    total_tris = sum(c['triangles'] for e in header['groups'] for c in e['chunks'])
    header['triangles'] = total_tris
    print('groups', len(header['groups']), 'triangles', total_tris,
          {e['name']: sum(c['triangles'] for c in e['chunks']) for e in header['groups'] if e['chunks']})

    def text(s):
        b = s.encode('utf-8')
        return struct.pack('<B', len(b)) + b
    head = bytearray(b'FTCM') + struct.pack('<II', 1, len(header['groups']))
    for e in header['groups']:
        head += text(e['name']) + text(e['parent']) + text(e['joint'])
        head += struct.pack('<ff', e['sign'], e['rest']) + struct.pack('<3f', *e['pivot']) + struct.pack('<3f', *e['axis'])
        head += struct.pack('<B', len(e['chunks']))
        for c in e['chunks']:
            head += struct.pack('<II', c['vertices'], c['triangles']) + struct.pack('<3f', *c['min']) + struct.pack('<3f', *c['max'])
    with gzip.open(dst, 'wb', compresslevel=9) as out:
        out.write(bytes(head))
        for b in blobs:
            out.write(b)
    print('wrote', dst)


if __name__ == '__main__':
    main()
