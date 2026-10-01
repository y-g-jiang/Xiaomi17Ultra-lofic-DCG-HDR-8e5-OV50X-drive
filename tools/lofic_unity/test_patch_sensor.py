import argparse
import hashlib
import struct
from pathlib import Path

from patch_sensor import STOCK_SHA256, UNITY_SHA256, branch, bne, patch


def rejected(call):
    try:
        call()
    except ValueError:
        return
    raise AssertionError('accepted')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('stock', type=Path)
    parser.add_argument('verified_unity', type=Path)
    args = parser.parse_args()
    source = args.stock.read_bytes()
    known = args.verified_unity.read_bytes()
    assert hashlib.sha256(source).hexdigest() == STOCK_SHA256
    assert hashlib.sha256(known).hexdigest() == UNITY_SHA256
    actual = patch(source)
    assert actual == known and len(actual) == len(source)
    assert sum(a != b for a, b in zip(source, actual)) == 57
    assert struct.unpack_from('<I', actual, 0x4e50)[0] == 0x6b08003f
    for address in (0x7900, 0x7920):
        instruction = struct.unpack_from('<I', actual, address + 4)[0]
        assert instruction & 0xffc0001f == 0x7100001f
        assert (instruction >> 10) & 0xfff == 5
    rejected(lambda: patch(actual))
    rejected(lambda: patch(source[:-1]))
    changed = bytearray(source)
    changed[0x4e08] ^= 1
    rejected(lambda: patch(changed))
    rejected(lambda: branch(0, 1))
    rejected(lambda: branch(0, 1 << 27))
    rejected(lambda: bne(0, 1 << 20))
    print('PASS')


if __name__ == '__main__':
    main()
