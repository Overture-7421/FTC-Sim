"""
Converts the Onshape glTF export of the BIOBUZZ robot (23619-26-A-0000 Main) into the compact model FTC-Sim draws.

    python convert_biobuzz.py <robot.gltf> <out biobuzz.model> [triangle budget]

The export is Draco-compressed, 24 million unique triangles. The model has to be tiny, so nothing of the CAD's own triangles
is kept for the round parts and the plates. Instead every part is replaced by a simple shape of the same size and place:

  * wheels, gears, pulleys, bearings, rollers, shafts, motors: a cylinder, or a ring when the part is hollow, fitted to the
    part's axis, radius and length;
  * plates (the purple lattice of the chassis, the shooter and the indexer): the outline of the plate with its large holes,
    simplified and extruded, so the lattice stays and the bolt holes go;
  * the four mecanum wheels: built from scratch (two side discs, an axle and ten angled rollers) at the CAD's diameter and width;
  * anything else: the CAD's mesh decimated to a few dozen triangles, or a box;
  * the lexan sheets (colibri plates, number holders) are marked translucent;
  * the Limelight, which the CAD does not have, is a box and a lens on the 45 degree panel between the two black standoffs.

The robot is split into rigid groups that move (wheels, turret, hood, flywheel, rollers, colibri arms, stopper, encoder
gears). Coordinates leave in the simulator's frame: X forward, Y left, Z up, the origin at the centre of the four wheels at
the chassis body's height. The CAD faces -Y with +X to its left.

Needs: pip install DracoPy numpy fast-simplification shapely mapbox-earcut
"""
import base64, collections, gzip, json, math, re, struct, sys
import numpy as np
import DracoPy
import fast_simplification
import shapely
import mapbox_earcut

CAD_CENTRE_Y = 0.0385            # the middle of the wheels' Y positions in the CAD
BODY_ORIGIN_Z = 0.1264           # the chassis body's centre above the CAD's wheel-axle height
MIN_SIZE = 0.020                 # parts whose largest side is under this are left out, unless they are in KEEP_SMALL
FASTENER = re.compile(r'(?i)bolt|screw|\bnut\b|washer|rivet|standoff|spacer|insert|bhcs|shcs|set screw|retaining|e-clip|button m|hex m|socket m|lock ?nut|\bpin\b|button head|flat head|\bbar\b|housing|mount\b')
KEEP_SMALL = re.compile(r'(?i)servo|encoder|octoquad|pinpoint|hub|switch|sensor|battery')
LEXAN = re.compile(r'(?i)placa colibr|number holder')
BELT = re.compile(r'(?i)chain|banda|belt')
LEXAN_RGB = (0.80, 0.90, 1.00)
LEXAN_ALPHA = 0.28
PLATE_TOLERANCE = 0.0015         # metres: how closely the outline of a plate follows the CAD's
MIN_HOLE_AREA = 3.5e-5           # holes smaller than this (about 6.7 mm across) are not drawn

WHEEL_AXES = {  # name: (CAD x of the axle centre, CAD y)
    'wheel.fl': (0.177, -0.083), 'wheel.fr': (-0.177, -0.083), 'wheel.rl': (0.177, 0.160), 'wheel.rr': (-0.177, 0.160)}
X = (1.0, 0.0, 0.0)              # CAD axes
Z = (0.0, 0.0, 1.0)
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
HOOD_REST_RADIANS = math.radians(1.9)

HOOD_NAMES = ('Placa Hood', '14t Steel Spur Gear', '28t Aluminum Spur Gear')
FEEDER_NAMES = ('Sushi Roller', '3/8" Rounded Hex (3.758" L')
FLYWHEEL_NAMES = ('3" Stealth Wheel', '3/8" Rounded Hex (5.507" L')
INTAKE_NAMES = ('WCP-0353-001', 'WCP-0354-001', '2" Compliant Wheel', 'Part 1', '16.875 in. Rounded Hex Shaft',
                '8t Press Fit Sprocket', '0303 Adaptador sprocket')
AXLE_NAMES = ('48mm Gecko Wheel', '2" Compliant Wheel', '8t Press Fit Sprocket', 'AluminumShaft',
              '3/8" Rounded Hex (5.04" L', '3/8" Rounded Hex (5.669" L', '20t Slip Fit Gear', '40t Slip Fit Gear')

# The Limelight sits on the 45 degree panel between the two black standoffs ahead of the flywheel (CAD y -0.146, z 0.386 and
# y -0.182, z 0.350), facing forward and up. Its centre, in CAD coordinates, and the 63 x 54 x 36 mm body's axes.
PANEL_CENTRE = np.array([0.0035, -0.164, 0.368])
PANEL_NORMAL = np.array([0.0, -1.0, 1.0]) / math.sqrt(2.0)
PANEL_UP = np.array([0.0, 1.0, 1.0]) / math.sqrt(2.0)
LIMELIGHT_CENTRE = PANEL_CENTRE + 0.0205 * PANEL_NORMAL


def mat(node):
    return np.array(node['matrix'], dtype=float).reshape(4, 4).T if 'matrix' in node else np.eye(4)


def material_colour(material):
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


# ---- shapes made from scratch ---------------------------------------------------------------------------------------

def segments_for(radius):
    return 8 if radius < 0.008 else 12 if radius < 0.02 else 16 if radius < 0.04 else 24


def perpendicular(axis):
    helper = np.array([1.0, 0.0, 0.0]) if abs(axis[0]) < 0.9 else np.array([0.0, 1.0, 0.0])
    e1 = np.cross(axis, helper)
    e1 /= np.linalg.norm(e1)
    return e1, np.cross(axis, e1)


def cylinder(centre, axis, e1, e2, radius, half_length, inner=None, segs=None):
    """A cylinder of the radius about the axis, or a ring when `inner` is a radius; (points, triangles)."""
    n = segs or segments_for(radius)
    angles = np.arange(n) * (2.0 * math.pi / n)
    ring = np.cos(angles)[:, None] * e1 + np.sin(angles)[:, None] * e2
    lo, hi = centre - axis * half_length, centre + axis * half_length
    pts = [lo + ring * radius, hi + ring * radius]
    idx = np.arange(n)
    nxt = (idx + 1) % n
    tris = [np.c_[idx, nxt, n + idx], np.c_[nxt, n + nxt, n + idx]]
    if inner is None:
        pts += [lo[None, :], hi[None, :]]
        tris += [np.c_[np.full(n, 2 * n), nxt, idx], np.c_[np.full(n, 2 * n + 1), n + idx, n + nxt]]
    else:
        pts += [lo + ring * inner, hi + ring * inner]
        i_lo, i_hi = 2 * n, 3 * n
        tris += [np.c_[i_lo + idx, i_lo + nxt, idx], np.c_[nxt, idx, i_lo + nxt]]
        tris += [np.c_[i_hi + idx, n + idx, i_hi + nxt], np.c_[n + nxt, i_hi + nxt, n + idx]]
        tris += [np.c_[i_lo + idx, i_hi + idx, i_lo + nxt], np.c_[i_hi + idx, i_hi + nxt, i_lo + nxt]]
    return np.concatenate(pts), np.concatenate(tris).astype(np.int32)


def box(centre, axes, half):
    """An oriented box; axes are three unit vectors."""
    corners = np.array([[sx, sy, sz] for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)], dtype=float)
    pts = centre + (corners * half) @ np.asarray(axes)
    quads = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    tris = []
    for a, b, c, d in quads:
        tris += [(a, b, c), (a, c, d)]
    return pts, np.array(tris, dtype=np.int32)


def mecanum_wheel(cx, cy, flip):
    """A mecanum wheel of the CAD's size (112 mm across, 35 mm wide), axle along the CAD's X; list of (points, tris, rgb)."""
    centre = np.array([cx, cy, 0.0])
    ax = np.array([1.0, 0.0, 0.0])
    e1, e2 = np.array([0.0, 1.0, 0.0]), np.array([0.0, 0.0, 1.0])
    out = []
    for side in (-1.0, 1.0):
        p, t = cylinder(centre + ax * side * 0.0165, ax, e1, e2, 0.040, 0.0015, segs=20)
        out.append((p, t, (0.62, 0.62, 0.64)))
    p, t = cylinder(centre, ax, e1, e2, 0.011, 0.0225, segs=10)
    out.append((p, t, (0.35, 0.35, 0.37)))
    count = 10
    for k in range(count):
        theta = 2.0 * math.pi * k / count
        radial = np.array([0.0, math.cos(theta), math.sin(theta)])
        tangent = np.array([0.0, -math.sin(theta), math.cos(theta)])
        axis = (ax + flip * tangent) / math.sqrt(2.0)
        r1, r2 = perpendicular(axis)
        p, t = cylinder(centre + radial * 0.0455, axis, r1, r2, 0.0112, 0.0185, segs=8)
        out.append((p, t, (0.10, 0.10, 0.11) if k % 2 == 0 else (0.16, 0.16, 0.17)))
    return out


def limelight():
    """The Limelight 3A on its panel: a box and its lens; list of (points, tris, rgb)."""
    across = np.array([1.0, 0.0, 0.0])
    p, t = box(LIMELIGHT_CENTRE, [across, PANEL_UP, PANEL_NORMAL], [0.032, 0.027, 0.018])
    out = [(p, t, (0.10, 0.10, 0.12))]
    e1, e2 = perpendicular(PANEL_NORMAL)
    p, t = cylinder(LIMELIGHT_CENTRE + PANEL_NORMAL * 0.0195, PANEL_NORMAL, e1, e2, 0.0085, 0.0025, segs=12)
    out.append((p, t, (0.05, 0.12, 0.22)))
    return out


# ---- fitting shapes to a part --------------------------------------------------------------------------------------

def frame_of(points):
    mean = points.mean(axis=0)
    _, axes = np.linalg.eigh(np.cov((points - mean).T))
    q = (points - mean) @ axes
    return mean, axes, q, q.min(axis=0), q.max(axis=0)


def fit_cylinder(mean, axes, q, qmin, qmax):
    ext = qmax - qmin
    centre_q = (qmin + qmax) / 2.0
    best = None
    for a in range(3):
        o = [i for i in range(3) if i != a]
        e1, e2 = ext[o[0]], ext[o[1]]
        if max(e1, e2) < 1e-6 or abs(e1 - e2) > 0.12 * max(e1, e2):
            continue
        r = np.hypot(q[:, o[0]] - centre_q[o[0]], q[:, o[1]] - centre_q[o[1]])
        radius = r.max()
        if radius > 1.1 * max(e1, e2) / 2.0:
            continue
        rim = float((r > 0.9 * radius).mean())
        if rim < 0.12:
            continue
        if best is None or rim > best[0]:
            best = (rim, a, o, radius, r)
    if best is None:
        return None
    _, a, o, radius, r = best
    centre = mean + axes @ centre_q
    inner = float(r.min())
    ring = inner if inner > 0.45 * radius and ext[a] < 2.5 * radius else None
    return cylinder(centre, axes[:, a], axes[:, o[0]], axes[:, o[1]], radius, ext[a] / 2.0, ring)


def fit_plate(mean, axes, q, qmin, qmax, faces):
    """The outline of a flat part with its large holes, simplified, as a sheet with walls round its rim."""
    ext = qmax - qmin
    n = int(np.argmin(ext))
    u, v = [i for i in range(3) if i != n]
    corners = q[faces]
    normals = np.cross(corners[:, 1] - corners[:, 0], corners[:, 2] - corners[:, 0])
    length = np.linalg.norm(normals, axis=1)
    ok = length > 1e-14
    flat = ok & (np.abs(normals[:, n]) > 0.98 * np.where(ok, length, 1.0))
    mid = (qmin[n] + qmax[n]) / 2.0
    top = flat & (corners[:, :, n].mean(axis=1) > mid)
    if top.sum() < 2:
        return None
    ring = corners[top][:, :, [u, v]]
    ring = np.concatenate([ring, ring[:, :1]], axis=1)
    polygons = shapely.polygons(np.round(ring, 7))
    polygons = polygons[shapely.area(polygons) > 1e-12]
    union = shapely.union_all(polygons).simplify(PLATE_TOLERANCE, preserve_topology=True)
    geoms = list(union.geoms) if hasattr(union, 'geoms') else [union]
    out_pts, out_tris = [], []
    half = max(ext[n], 0.002) / 2.0
    base = 0
    for poly in geoms:
        if poly.is_empty or poly.geom_type != 'Polygon':
            continue
        exterior = np.asarray(poly.exterior.coords)[:-1]
        rings = [exterior]
        for hole in poly.interiors:
            if shapely.area(shapely.Polygon(hole)) >= MIN_HOLE_AREA:
                rings.append(np.asarray(hole.coords)[:-1])
        flat2 = np.concatenate(rings)
        ends = np.cumsum([len(r) for r in rings]).astype(np.uint32)
        tri = np.asarray(mapbox_earcut.triangulate_float64(flat2, ends), dtype=np.int64).reshape(-1, 3)
        sheet = np.zeros((len(flat2), 3))
        sheet[:, u], sheet[:, v], sheet[:, n] = flat2[:, 0], flat2[:, 1], mid
        out_pts.append(sheet)
        out_tris.append(tri + base)
        base += len(flat2)
        # walls round the outer rim only: they give the plate its edge seen from above and from the side
        m = len(exterior)
        lo_, hi_ = sheet[:m].copy(), sheet[:m].copy()
        lo_[:, n] -= half
        hi_[:, n] += half
        out_pts += [lo_, hi_]
        i = np.arange(m)
        j = (i + 1) % m
        out_tris += [np.c_[base + i, base + j, base + m + i], np.c_[base + j, base + m + j, base + m + i]]
        base += 2 * m
    if not out_pts:
        return None
    local = np.concatenate(out_pts)
    return mean + local @ axes.T, np.concatenate(out_tris).astype(np.int32)


def fit_box(mean, axes, q, qmin, qmax):
    centre = mean + axes @ ((qmin + qmax) / 2.0)
    return box(centre, axes.T, (qmax - qmin) / 2.0)


def fit_shape(points, faces, name):
    """A simple shape standing for the part, in the part's own coordinates: (points, triangles, kind)."""
    points, faces = weld(points.astype(np.float64), faces.astype(np.int64))
    if len(faces) == 0:
        return None
    mean, axes, q, qmin, qmax = frame_of(points)
    ext = qmax - qmin
    if BELT.search(name):
        if len(faces) > 260:
            p, f = fast_simplification.simplify(points.astype(np.float32), faces.astype(np.int32), target_count=240, agg=5)
            return p.astype(np.float64), f, 'belt'
        return points, faces.astype(np.int32), 'belt'
    cyl = fit_cylinder(mean, axes, q, qmin, qmax)
    if cyl is not None:
        return cyl[0], cyl[1], 'cylinder'
    order = np.sort(ext)
    if order[0] < 0.007 and order[1] > 0.03 and order[0] < 0.15 * order[1]:
        plate = fit_plate(mean, axes, q, qmin, qmax, faces)
        if plate is not None:
            return plate[0], plate[1], 'plate'
    target = int(np.clip(30 + 1500 * ext.max() ** 1.6, 30, 220))
    if len(faces) <= target:
        return points, faces.astype(np.int32), 'kept'
    try:
        p, f = fast_simplification.simplify(points.astype(np.float32), faces.astype(np.int32), target_count=target, agg=7)
        if 8 <= len(f) <= 3 * target:
            return p.astype(np.float64), f, 'decimated'
    except Exception:
        pass
    b = fit_box(mean, axes, q, qmin, qmax)
    return b[0], b[1], 'box'


def main():
    src, dst = sys.argv[1], sys.argv[2]
    budget = int(sys.argv[3]) if len(sys.argv) > 3 else 30000
    g = json.load(open(src, 'r', encoding='utf-8'))
    blob = base64.b64decode(g['buffers'][0]['uri'].split(',', 1)[1])
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

    keep = []
    for i, n in enumerate(nodes):
        if 'mesh' not in n:
            continue
        name = n.get('name', '?')
        lo, hi = mesh_box(n['mesh'])
        corners = np.array([[x, y, z, 1] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])])
        w = (world[i] @ corners.T).T[:, :3]
        wlo, whi = w.min(0), w.max(0)
        size, centre = whi - wlo, (wlo + whi) / 2
        keepsmall = bool(KEEP_SMALL.search(name))
        if FASTENER.search(name) and size.max() < 0.06 and not keepsmall:
            continue
        if size.max() < (0.012 if keepsmall else MIN_SIZE):
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

    # fit a shape to every mesh the robot uses, once, in the mesh's own coordinates
    wanted = collections.OrderedDict()
    for (i, mi, name, topname, c, s), gname in zip(keep, assigned):
        if gname.startswith('wheel.'):
            continue
        wanted.setdefault(mi, name)
    shapes = {}
    kinds = collections.Counter()
    tri_by_kind = collections.Counter()
    for n_done, (mi, name) in enumerate(wanted.items()):
        by_material = collections.OrderedDict()
        for p in meshes[mi]['primitives']:
            view = views[p['extensions']['KHR_draco_mesh_compression']['bufferView']]
            d = DracoPy.decode(blob[view['byteOffset']:view['byteOffset'] + view['byteLength']])
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
            if len(pts) < 3 or len(fcs) == 0:
                continue
            fit = fit_shape(pts, fcs, name)
            if fit is None:
                continue
            rgb = material_colour(materials[material])[:3]
            alpha = 1.0
            if LEXAN.search(name):
                rgb, alpha = LEXAN_RGB, LEXAN_ALPHA
            parts.append((fit[0], fit[1], tuple(rgb), alpha))
            kinds[fit[2]] += 1
            tri_by_kind[fit[2]] += len(fit[1])
        shapes[mi] = parts
        if n_done % 40 == 0:
            print('fitted', n_done + 1, '/', len(wanted), flush=True)
    print('shapes', dict(kinds), 'triangles per kind (one instance each)', dict(tri_by_kind))

    per_group = collections.defaultdict(list)   # group -> list of (sim points, tris, rgb, alpha)

    def add(gname, cad_points, tris, rgb, alpha=1.0):
        per_group[gname].append((to_sim(cad_points), tris, rgb, alpha))

    kind_total = collections.Counter()
    for (i, mi, name, topname, c, s), gname in zip(keep, assigned):
        if gname.startswith('wheel.'):
            continue
        w = world[i]
        for (sp, sf, rgb, alpha) in shapes.get(mi, []):
            wp = (w @ np.c_[sp, np.ones(len(sp))].T).T[:, :3]
            add(gname, wp, sf, rgb, alpha)
    for gname, (ax, ay) in WHEEL_AXES.items():
        for (p, t, rgb) in mecanum_wheel(ax, ay, 1.0 if gname in ('wheel.fl', 'wheel.rr') else -1.0):
            add(gname, p, t, rgb)
    for (p, t, rgb) in limelight():
        add('turret', p, t, rgb)

    def total(parts):
        return sum(len(t) for _, t, _, _ in parts)
    print('triangles', {k: total(v) for k, v in per_group.items()})
    print('total', sum(total(v) for v in per_group.values()), 'budget', budget)

    blobs, entries = [], []
    for gname, (par, joint, pivot, axis, sign) in groups.items():
        pivot_sim = point_to_sim(pivot)
        entry = {'name': gname, 'parent': par or '', 'joint': joint, 'sign': sign,
                 'rest': HOOD_REST_RADIANS if gname == 'hood' else 0.0,
                 'pivot': [round(v, 6) for v in pivot_sim], 'axis': [round(v, 6) for v in dir_to_sim(axis)], 'chunks': []}
        parts = per_group.get(gname, [])
        for alpha in sorted({a for _, _, _, a in parts}, reverse=True):
            subset = [p for p in parts if p[3] == alpha]
            pos_list, rgb_list, idx_list, count = [], [], [], 0
            for pts, tris, rgb, _ in subset:
                pos_list.append(pts - np.array(pivot_sim))
                rgb_list.append(np.tile(np.clip(np.round(np.array(rgb) * 255), 0, 255).astype(np.uint8), (len(pts), 1)))
                idx_list.append(tris + count)
                count += len(pts)
            pos, rgb, idx = np.concatenate(pos_list), np.concatenate(rgb_list), np.concatenate(idx_list).astype(np.int64)
            start = 0
            while start < len(idx):
                end = min(len(idx), start + 20000)
                tri = idx[start:end]
                used, inverse = np.unique(tri.ravel(), return_inverse=True)
                cp, cc = pos[used], rgb[used]
                mn, mx = cp.min(axis=0), cp.max(axis=0)
                span = np.maximum(mx - mn, 1e-9)
                q = np.round((cp - mn) / span * 65535 - 32768).astype(np.int16)
                entry['chunks'].append({'vertices': len(used), 'triangles': len(tri), 'alpha': alpha,
                                        'min': [float(v) for v in mn], 'max': [float(v) for v in mx]})
                blobs += [q.tobytes(), cc.astype(np.uint8).tobytes(), inverse.reshape(-1, 3).astype(np.uint16).tobytes()]
                start = end
        entries.append(entry)

    def text(s):
        b = s.encode('utf-8')
        return struct.pack('<B', len(b)) + b
    head = bytearray(b'FTCM') + struct.pack('<II', 2, len(entries))
    for e in entries:
        head += text(e['name']) + text(e['parent']) + text(e['joint'])
        head += struct.pack('<ff', e['sign'], e['rest']) + struct.pack('<3f', *e['pivot']) + struct.pack('<3f', *e['axis'])
        head += struct.pack('<B', len(e['chunks']))
        for c in e['chunks']:
            head += struct.pack('<IIf', c['vertices'], c['triangles'], c['alpha']) + struct.pack('<3f', *c['min']) + struct.pack('<3f', *c['max'])
    with gzip.open(dst, 'wb', compresslevel=9) as out:
        out.write(bytes(head))
        for b in blobs:
            out.write(b)
    print('limelight centre (sim)', [round(v, 4) for v in point_to_sim(LIMELIGHT_CENTRE)], 'pitch up 45 degrees')
    print('wrote', dst)


if __name__ == '__main__':
    main()
