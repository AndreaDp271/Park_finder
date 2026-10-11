#!/usr/bin/env python3
"""Genera i dati statici di ParkMap da un estratto OSM (.osm.pbf).

Estrae aree di sosta, stalli speciali e parcometri e li scrive in file JSON a
celle fisse di CELL_DEG gradi, più un indice con le celle coperte
dall'estratto. L'app scarica questi file da GitHub Pages invece di
interrogare Overpass, che è spesso lento o irraggiungibile.

Uso: build_tiles.py <estratto.osm.pbf> <confine.poly> <cartella_output>

Il file .poly (formato Geofabrik) definisce l'area coperta: dentro di essa una
cella senza file significa "qui non c'è nulla", fuori l'app usa Overpass.

DEVE restare allineato a index.html: CELL_DEG = TILE_DEG * CELLS_PER_SIDE e
il calcolo delle celle (floor(floor(lat / 0.01) / 5)) è lo stesso dell'app.
"""
import json
import math
import os
import sys
from datetime import datetime, timezone

import osmium
from shapely.geometry import Polygon, box
from shapely.ops import unary_union
from shapely.prepared import prep

TILE_DEG = 0.01
CELLS_PER_SIDE = 5                 # una cella = 5x5 tessere dell'app (~5 x 4 km)
CELL_DEG = TILE_DEG * CELLS_PER_SIDE

# Solo i tag usati dall'app (popup e colori): file più piccoli
KEEP_TAGS = {
    'amenity', 'parking', 'parking_space', 'vending', 'name', 'ref', 'operator',
    'access', 'access:conditional', 'vehicle', 'fee', 'fee:conditional',
    'parking:condition', 'parking:condition:time_interval', 'maxstay',
    'maxstay:conditional', 'capacity', 'charge', 'charge:conditional', 'note',
}


def wanted(tags):
    amenity = tags.get('amenity')
    if amenity == 'parking':
        return True
    if amenity == 'parking_space':
        ps = tags.get('parking_space')
        return bool(ps) and ps != 'normal'
    if amenity == 'vending_machine':
        return tags.get('vending') == 'parking_tickets'
    return False


def slim_tags(tags):
    return {t.k: t.v for t in tags if t.k in KEEP_TAGS or t.k.startswith('payment:')}


def cell_of(lat, lon):
    # Stessa formula dell'app: prima la tessera, poi la cella che la contiene
    return (math.floor(math.floor(lat / TILE_DEG) / CELLS_PER_SIDE),
            math.floor(math.floor(lon / TILE_DEG) / CELLS_PER_SIDE))


class ParkingHandler(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.cells = {}
        self.count = 0

    def add(self, lat, lon, record):
        self.cells.setdefault(cell_of(lat, lon), []).append(record)
        self.count += 1

    def node(self, n):
        if not wanted(n.tags) or not n.location.valid():
            return
        lat, lon = round(n.location.lat, 6), round(n.location.lon, 6)
        self.add(lat, lon, ['n', n.id, slim_tags(n.tags), lat, lon])

    def way(self, w):
        if not wanted(w.tags):
            return
        flat = []
        for nd in w.nodes:
            if not nd.location.valid():
                return  # nodo mancante nell'estratto (bordo): la way verrebbe deformata
            flat.append(round(nd.location.lat, 6))
            flat.append(round(nd.location.lon, 6))
        if len(flat) < 4:
            return
        self.add(flat[0], flat[1], ['w', w.id, slim_tags(w.tags), flat])


def read_poly(path):
    """Legge un file .poly Geofabrik: anelli esterni e buchi (sezioni con '!')."""
    outers, holes = [], []
    with open(path, encoding='utf-8') as f:
        lines = [l.strip() for l in f if l.strip()]
    i = 1  # la prima riga è il nome
    while i < len(lines) and lines[i] != 'END':
        is_hole = lines[i].startswith('!')
        i += 1
        ring = []
        while lines[i] != 'END':
            lon, lat = map(float, lines[i].split()[:2])
            ring.append((lon, lat))
            i += 1
        i += 1
        (holes if is_hole else outers).append(Polygon(ring))
    area = unary_union(outers)
    if holes:
        area = area.difference(unary_union(holes))
    return area


def covered_cells(area):
    minx, miny, maxx, maxy = area.bounds
    shape = prep(area)
    i0, i1 = math.floor(miny / CELL_DEG), math.floor(maxy / CELL_DEG)
    j0, j1 = math.floor(minx / CELL_DEG), math.floor(maxx / CELL_DEG)
    cells = set()
    for i in range(i0, i1 + 1):
        for j in range(j0, j1 + 1):
            if shape.intersects(box(j * CELL_DEG, i * CELL_DEG, (j + 1) * CELL_DEG, (i + 1) * CELL_DEG)):
                cells.add((i, j))
    return cells


def to_runs(cells):
    """{(i, j)} -> [[i, j_inizio, j_fine], ...]: l'indice resta piccolo."""
    runs = []
    for i, j in sorted(cells):
        if runs and runs[-1][0] == i and runs[-1][2] == j - 1:
            runs[-1][2] = j
        else:
            runs.append([i, j, j])
    return runs


def main():
    pbf, poly, out_dir = sys.argv[1:4]
    os.makedirs(os.path.join(out_dir, 'cells'), exist_ok=True)

    handler = ParkingHandler()
    handler.apply_file(pbf, locations=True)

    covered = covered_cells(read_poly(poly))
    with_data = {c for c in handler.cells if c in covered}

    for (i, j) in with_data:
        with open(os.path.join(out_dir, 'cells', f'{i}_{j}.json'), 'w', encoding='utf-8') as f:
            json.dump({'v': 1, 'e': handler.cells[(i, j)]}, f, ensure_ascii=False, separators=(',', ':'))

    timestamp = osmium.io.Reader(pbf, osmium.osm.osm_entity_bits.NOTHING).header().get('osmosis_replication_timestamp')
    index = {
        'v': 1,
        'cell': CELL_DEG,
        'tilesPerCell': CELLS_PER_SIDE,
        'osmTimestamp': timestamp or None,
        'generated': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        'covered': to_runs(covered),
        'data': to_runs(with_data),
    }
    with open(os.path.join(out_dir, 'index.json'), 'w', encoding='utf-8') as f:
        json.dump(index, f, separators=(',', ':'))

    print(f'{handler.count} elementi, {len(with_data)} celle con dati su {len(covered)} coperte, OSM {timestamp}')


if __name__ == '__main__':
    main()
