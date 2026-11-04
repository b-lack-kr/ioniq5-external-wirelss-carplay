"""Rebuild the in-memory patch for the captured NEEV 260902 libCarPlayCoreUtils.so."""
import argparse
from hashlib import md5, sha256
from pathlib import Path
import struct
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
DISPATCH = 0x4dcce
CAVE = 0x9b2b0


def text_section(data):
    table, = struct.unpack_from('<I', data, 32)
    entsize, count, strindex = struct.unpack_from('<HHH', data, 46)
    sections = [struct.unpack_from('<10I', data, table + i * entsize) for i in range(count)]
    _, _, _, _, start, size, *_ = sections[strindex]
    names = data[start:start+size]
    matches = [s for s in sections if names[s[0]:].split(b'\x00', 1)[0] == b'.text']
    assert len(matches) == 1
    section = matches[0]
    return data[section[4]:section[4] + section[5]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stock', type=Path, help='Unmodified libCarPlayCoreUtils.so from the compatible head unit')
    parser.add_argument('--clang', default='clang')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    stock = args.stock.read_bytes()
    assert sha256(stock).hexdigest() == '94d62db87d9e5dbf2214a99a4a9518329a983732b1931f629474921defc12283'
    with tempfile.TemporaryDirectory() as tmp:
        obj = Path(tmp) / 'classify.o'
        subprocess.run([args.clang, '-target', 'armv7-none-linux-gnueabi', '-c', str(HERE/'classify.S'), '-o', str(obj)], check=True)
        code = text_section(obj.read_bytes())
    # Both virtual addresses are in the RX page mapping based at ELF file offset zero.
    load_off, = struct.unpack_from('<I', stock, 28)
    load_ent, load_count = struct.unpack_from('<HH', stock, 42)
    loads = [struct.unpack_from('<8I', stock, load_off+i*load_ent) for i in range(load_count)]
    rx, rw = [p for p in loads if p[0] == 1]
    assert rx[1:3] == (0,0) and rx[4:7] == (0x9b2a4,0x9b2a4,5)
    end = len(code)
    assert rx[4] <= CAVE < end <= rw[1] == 0x9b52c
    assert (end+4095) & ~4095 == (rx[4]+4095) & ~4095 == 0x9c000
    assert (rw[2] & ~4095) == 0x9c000
    assert stock[CAVE:end] == bytes(end-CAVE)
    assert stock[DISPATCH:DISPATCH+4] == bytes.fromhex('40f0d980')
    patched = bytearray(stock)
    patched[CAVE:end] = code[CAVE:]
    patched[DISPATCH:DISPATCH+4] = code[DISPATCH:DISPATCH+4]
    # Verify the real classifier comparisons and both original store paths. For
    # a stable "already applied" result, each verify window includes changed bytes.
    windows = [(CAVE-8, end-CAVE+16), (0x4dc42,0x4de90-0x4dc42)]
    lines = ['# Captured NEEV.KOR.260902 CoreUtils, package target MD5 cf3cc23dad25b181a9fc12d606468715',
             '# Write the complete stub first. Publish the new branch last.',
             'id carplay-usb-dongle-transport', 'file /system/lib/libCarPlayCoreUtils.so']
    for offset,size in windows:
        before=md5(stock[offset:offset+size]).hexdigest()
        after=md5(patched[offset:offset+size]).hexdigest()
        lines.append(f'verify 0x{offset:x} {size} {before} {after}')
    for offset,size in [(CAVE,end-CAVE),(DISPATCH,4)]:
        lines.append(f'patch 0x{offset:x} {patched[offset:offset+size].hex()}')
    args.output.write_text('\n'.join(lines)+'\n')
    print(f'{end-CAVE} stub bytes, 4 dispatch bytes; patched file view SHA256 {sha256(patched).hexdigest()}')


if __name__ == '__main__':
    main()
