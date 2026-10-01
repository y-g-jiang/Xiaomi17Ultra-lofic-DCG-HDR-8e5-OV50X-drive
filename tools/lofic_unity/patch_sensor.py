import argparse
import hashlib
import struct
from pathlib import Path

STOCK_SHA256 = '145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b'
UNITY_SHA256 = '3af9d5797de98db030d1b737983a60100a02a43c269d5e6d5850c324d8341233'


def branch(source, target):
    delta = target - source
    if delta % 4 or not -(1 << 27) <= delta < (1 << 27):
        raise ValueError('branch')
    return 0x14000000 | ((delta // 4) & 0x3ffffff)


def bne(source, target):
    delta = target - source
    if delta % 4 or not -(1 << 20) <= delta < (1 << 20):
        raise ValueError('bne')
    return 0x54000001 | (((delta // 4) & 0x7ffff) << 5)


def movz(register, immediate, shift=0):
    return 0x52800000 | ((shift // 16) << 21) | (immediate << 5) | register


def cmp_imm(register, immediate):
    return 0x7100001f | (immediate << 10) | (register << 5)


def patch(source):
    if hashlib.sha256(source).hexdigest() != STOCK_SHA256:
        raise ValueError('stock_sha256')
    data = bytearray(source)
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('elf')
    if any(data[0x7900:0x7940]):
        raise ValueError('code_cave')
    def word(address, value):
        struct.pack_into('<I', data, address, value)
    for original, base, register, old, new, shift in [
            (0x4e08, 0x7900, 3, 0x3fe3, 0x3f80, 16),
            (0x4e10, 0x7920, 8, 454, 256, 0)]:
        if struct.unpack_from('<I', data, original)[0] != movz(register, old, shift):
            raise ValueError('instruction')
        ops = [0xb9400000 | (6 << 10) | (21 << 5) | register,
               cmp_imm(register, 5), movz(register, old, shift),
               bne(base + 12, base + 20), movz(register, new, shift),
               branch(base + 20, original + 4)]
        for i, value in enumerate(ops):
            word(base + i * 4, value)
        word(original, branch(original, base))
    if struct.unpack_from('<I', data, 0x4e50)[0] != cmp_imm(1, 454):
        raise ValueError('comparison')
    word(0x4e50, 0x6b08003f)
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entsize, count = struct.unpack_from('<HH', data, 54)
    if entsize != 56 or phoff + count * entsize > len(data):
        raise ValueError('program_headers')
    found = []
    for i in range(count):
        at = phoff + i * entsize
        kind, flags, offset, address, _, filesz, memsz, _ = struct.unpack_from('<IIQQQQQQ', data, at)
        if kind == 1 and flags == 5:
            if (offset, address, filesz, memsz) != (0x4000, 0x4000, 0x38f8, 0x38f8):
                raise ValueError('executable_segment')
            found.append(at)
    if len(found) != 1:
        raise ValueError('executable_segment_count')
    struct.pack_into('<QQ', data, found[0] + 32, 0x3940, 0x3940)
    result = bytes(data)
    if hashlib.sha256(result).hexdigest() != UNITY_SHA256:
        raise ValueError('unity_sha256')
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('stock', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.stock.resolve() == args.output.resolve():
        raise ValueError('in_place')
    result = patch(args.stock.read_bytes())
    with args.output.open('xb') as output:
        output.write(result)
    print(hashlib.sha256(result).hexdigest())


if __name__ == '__main__':
    main()
