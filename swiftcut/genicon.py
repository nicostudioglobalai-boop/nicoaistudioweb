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

C1 = (246, 81, 168); C2 = (110, 12, 88)

def inside_rounded(x, y, S, r):
    if x < 0 or y < 0 or x >= S or y >= S: return False
    cx = min(max(x, r), S - r)
    cy = min(max(y, r), S - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r or (r <= x < S - r) or (r <= y < S - r)

def in_tri(px, py, a, b, c):
    def sign(x1,y1,x2,y2,x3,y3):
        return (x1-x3)*(y2-y3)-(x2-x3)*(y1-y3)
    d1=sign(px,py,*a,*b); d2=sign(px,py,*b,*c); d3=sign(px,py,*c,*a)
    neg = (d1<0)or(d2<0)or(d3<0); pos=(d1>0)or(d2>0)or(d3>0)
    return not(neg and pos)

def seg_dist(px,py,a,b):
    ax,ay=a; bx,by=b
    dx,dy=bx-ax,by-ay
    t=max(0,min(1,((px-ax)*dx+(py-ay)*dy)/(dx*dx+dy*dy)))
    return math.hypot(px-(ax+t*dx), py-(ay+t*dy))

def make(S):
    SS=4; N=S*SS; r=N*0.22
    lw=N*0.05
    cx=cy=N/2; s=N*0.30
    tri=[(cx-s*0.75,cy-s),(cx-s*0.75,cy+s),(cx+s*1.15,cy)]
    slash=[(cx+s*0.15,cy-s*1.30),(cx-s*0.50,cy+s*1.30)]
    rows=[]
    for y in range(S):
        row=[]
        for x in range(S):
            cnt=0
            for sy in range(SS):
                for sx in range(SS):
                    px=x*SS+sx+0.5; py=y*SS+sy+0.5
                    if not inside_rounded(px,py,N,r): continue
                    if seg_dist(px,py,*slash)<=lw: cnt+=1; continue
                    if in_tri(px,py,*tri) != (seg_dist(px,py,*slash)<=lw):
                        if in_tri(px,py,*tri): cnt+=1; continue
                    else:
                        continue
            cnt=cnt  # no-op
        row.append(None)
        rows.append(row)
    # simpler second pass with proper accumulation
    rows=[]
    alpha_bg = {}
    for y in range(S):
        row=[]
        for x in range(S):
            hits=0; trih=0; slashh=0
            for sy in range(SS):
                for sx in range(SS):
                    px=x*SS+sx+0.5; py=y*SS+sy+0.5
                    if not inside_rounded(px,py,N,r): continue
                    hits+=1
                    if seg_dist(px,py,*slash)<=lw: slashh+=1; continue
                    if in_tri(px,py,*tri): trih+=1
            t=y/max(1,S-1)
            col=tuple(int(C1[i]+(C2[i]-C1[i])*t) for i in range(3))
            if hits==0:
                row.extend([0,0,0,0])
            else:
                base=col
                # mix: slash draws accent-ish light over triangle/bg
                if trih or slashh:
                    if slashh*2 >= (trih+slashh):
                        base=(255,255,255)
                a=int(255*hits/ (SS*SS))
                row.extend([base[0],base[1],base[2],a])
        rows.append(bytes(row))
    return png_bytes(S,S,rows)

for dpi,px in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)]:
    open("/workspace/swiftcut/res/mipmap-%s/ic_launcher.png"%dpi,"wb").write(make(px))
print("icons ok")
