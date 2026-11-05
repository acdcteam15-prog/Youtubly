#!/usr/bin/env python3
"""
Builds a small-update patch (YTDIFF01) from the OLD release APK to the NEW one.

  make_patch.py OLD.apk NEW.apk raw-bsdiff.patch OUT.ytpatch

The input is what the `bsdiff` tool produced (BSDIFF40, bzip2 blocks). The app has no bzip2 reader, so the same three
blocks are repacked with gzip, which every phone can read, together with the sizes and SHA-256 of both files.
Before writing the result the script applies it again and checks it reproduces NEW byte for byte.

YTDIFF01 layout (big endian):  "YTDIFF01" | oldSize | newSize | oldSha256 (32) | newSha256 (32) |
                               ctrlLen | diffLen | extraLen | gzip(ctrl) | gzip(diff) | gzip(extra)
ctrl/diff/extra are the unchanged bsdiff blocks (ctrl = triples of 8-byte sign-magnitude little endian numbers).
"""
import bz2, gzip, hashlib, struct, sys, zlib

MAGIC = b"YTDIFF01"
HEADER = 8 + 8 + 8 + 32 + 32 + 8 + 8 + 8   # 112


def offtin(b):
    y = b[7] & 0x7F
    for i in range(6, -1, -1):
        y = y * 256 + b[i]
    return -y if b[7] & 0x80 else y


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.digest()


def gz(data):
    c = zlib.compressobj(9, zlib.DEFLATED, 31)
    return c.compress(data) + c.flush()


def read_bsdiff(path):
    with open(path, "rb") as f:
        raw = f.read()
    if raw[:8] != b"BSDIFF40":
        raise SystemExit("not a BSDIFF40 patch")
    ctrl_len, diff_len, new_size = offtin(raw[8:16]), offtin(raw[16:24]), offtin(raw[24:32])
    ctrl = bz2.decompress(raw[32:32 + ctrl_len])
    diff = bz2.decompress(raw[32 + ctrl_len:32 + ctrl_len + diff_len])
    extra = bz2.decompress(raw[32 + ctrl_len + diff_len:])
    return ctrl, diff, extra, new_size


def apply_ytdiff(old_path, patch_path):
    """Reference implementation (same algorithm the app uses). Returns the new file's bytes."""
    with open(patch_path, "rb") as f:
        p = f.read()
    assert p[:8] == MAGIC
    old_size, new_size = struct.unpack(">QQ", p[8:24])
    old_sha, new_sha = p[24:56], p[56:88]
    ctrl_len, diff_len, extra_len = struct.unpack(">QQQ", p[88:112])
    o = HEADER
    ctrl = gzip.decompress(p[o:o + ctrl_len]); o += ctrl_len
    diff = gzip.decompress(p[o:o + diff_len]); o += diff_len
    extra = gzip.decompress(p[o:o + extra_len])
    with open(old_path, "rb") as f:
        old = f.read()
    assert hashlib.sha256(old).digest() == old_sha, "old file is not the one the patch was made for"
    new = bytearray()
    op = np = ci = di = ei = 0
    while np < new_size:
        x, y, z = offtin(ctrl[ci:ci + 8]), offtin(ctrl[ci + 8:ci + 16]), offtin(ctrl[ci + 16:ci + 24]); ci += 24
        for i in range(x):
            b = diff[di + i]
            j = op + i
            if 0 <= j < len(old):
                b = (b + old[j]) & 0xFF
            new.append(b)
        di += x; np += x; op += x
        new += extra[ei:ei + y]; ei += y; np += y
        op += z
    assert len(new) == new_size and hashlib.sha256(new).digest() == new_sha, "patch does not rebuild the new file"
    return bytes(new)


def main():
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    old_path, new_path, raw_path, out_path = sys.argv[1:]
    ctrl, diff, extra, new_size = read_bsdiff(raw_path)
    import os
    gc, gd, ge = gz(ctrl), gz(diff), gz(extra)
    with open(out_path, "wb") as out:
        out.write(MAGIC)
        out.write(struct.pack(">QQ", os.path.getsize(old_path), new_size))
        out.write(sha256(old_path)); out.write(sha256(new_path))
        out.write(struct.pack(">QQQ", len(gc), len(gd), len(ge)))
        out.write(gc); out.write(gd); out.write(ge)
    # Safety net: the patch must rebuild the new APK exactly, or it is not published.
    rebuilt = hashlib.sha256(apply_ytdiff(old_path, out_path)).digest()
    if rebuilt != sha256(new_path):
        os.remove(out_path)
        raise SystemExit("verification failed")
    print(f"{out_path}: {os.path.getsize(out_path) / 1048576:.2f} MB (new file {new_size / 1048576:.1f} MB)")


if __name__ == "__main__":
    main()
