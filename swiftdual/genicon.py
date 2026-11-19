import zlib, struct, math

def png_bytes(w, h, rows):
    raw = b''.join(b'\x00' + row for row in rows)
    def chunk(t, data):
        c = t + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c))
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))

C1 = (45, 212, 191); C2 = (7, 88, 92)

def inside_rounded(x, y, S, r):
    if x < 0 or y < 0 or x >= S or y >= S: return False
    cx = min(max(x, r), S - r)
    cy = min(max(y, r), S - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r or (r <= x < S - r) or (r <= y < S - r)

def in_rrect(px, py, cx, cy, half, rad):
    dx = abs(px - cx) - (half - rad)
    dy = abs(py - cy) - (half - rad)
    if dx > rad or dy > rad: return False
    if dx <= 0 or dy <= 0: return True
    return dx * dx + dy * dy <= rad * rad

def make(S):
    SS = 4; N = S * SS; r = N * 0.18
    half = N * 0.26
    back = (N * 0.42, N * 0.40)   # back square center (right/up)
    front = (N * 0.55, N * 0.60)  # front square center (left-down)
    rad = N * 0.09
    lw = N * 0.035
    rows = []
    for y in range(S):
        row = bytearray()
        for x in range(S):
            hits = 0; sq = 0  # 0 none, 1 outline back, 2 filled front
            for sy in range(SS):
                for sx in range(SS):
                    px = x * SS + sx + 0.5; py = y * SS + sy + 0.5
                    if not inside_rounded(px, py, N, r): continue
                    hits += 1
                    if in_rrect(px, py, front[0], front[1], half, rad):
                        sq += 1
                    elif in_rrect(px, py, back[0], back[1], half, rad):
                        # outline only: on border?
                        outer = in_rrect(px, py, back[0], back[1], half, rad)
                        inner = in_rrect(px, py, back[0], back[1], half - lw, rad)
                        if outer and not inner:
                            sq += 1
            t = y / max(1, S - 1)
            if hits == 0:
                row.extend([0, 0, 0, 0])
                continue
            col = tuple(int(C1[i] + (C2[i] - C1[i]) * t) for i in range(3))
            if sq * 2 >= hits:
                col = (255, 255, 255)
            a = int(255 * hits / (SS * SS))
            row.extend([col[0], col[1], col[2], a])
        rows.append(bytes(row))
    return png_bytes(S, S, rows)

for dpi, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    import os
    d = "/workspace/swiftdual/res/mipmap-%s" % dpi
    os.makedirs(d, exist_ok=True)
    open(d + "/ic_launcher.png", "wb").write(make(px))
print("icons ok")
