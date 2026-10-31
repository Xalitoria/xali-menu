import gzip, struct, sys, json
def read(b, i=0):
    # returns (name, value, next) of a named tag
    t = b[i]; i += 1
    if t == 0: return None, None, i
    n = struct.unpack('>H', b[i:i+2])[0]; i += 2
    name = b[i:i+n].decode(); i += n
    v, i = payload(b, i, t)
    return name, v, i
def payload(b, i, t):
    if t == 1: return struct.unpack('>b', b[i:i+1])[0], i+1
    if t == 2: return struct.unpack('>h', b[i:i+2])[0], i+2
    if t == 3: return struct.unpack('>i', b[i:i+4])[0], i+4
    if t == 4: return struct.unpack('>q', b[i:i+8])[0], i+8
    if t == 5: return round(struct.unpack('>f', b[i:i+4])[0], 4), i+4
    if t == 6: return struct.unpack('>d', b[i:i+8])[0], i+8
    if t == 7:
        n = struct.unpack('>i', b[i:i+4])[0]; return list(b[i+4:i+4+n]), i+4+n
    if t == 8:
        n = struct.unpack('>H', b[i:i+2])[0]; return b[i+2:i+2+n].decode(), i+2+n
    if t == 9:
        et = b[i]; n = struct.unpack('>i', b[i+1:i+5])[0]; i += 5; out = []
        for _ in range(n):
            v, i = payload(b, i, et); out.append(v)
        return out, i
    if t == 10:
        d = {}
        while True:
            name, v, i = read(b, i)
            if name is None: return d, i
            d[name] = v
    if t == 11:
        n = struct.unpack('>i', b[i:i+4])[0]; return list(struct.unpack('>%di' % n, b[i+4:i+4+4*n])), i+4+4*n
    if t == 12:
        n = struct.unpack('>i', b[i:i+4])[0]; return list(struct.unpack('>%dq' % n, b[i+4:i+4+8*n])), i+4+8*n
    raise Exception('tag %d' % t)
data = gzip.open(sys.argv[1]).read()
_, root, _ = read(data)
for item in root['Inventory']:
    print(json.dumps(item, ensure_ascii=False))
