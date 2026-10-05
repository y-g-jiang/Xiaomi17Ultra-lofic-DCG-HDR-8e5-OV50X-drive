#!/usr/bin/env python3
import argparse
import hashlib
import struct
from pathlib import Path
from patch_module import PATCHED, patch_module, patch_camx


def registers(blob):
    offset, count = struct.unpack_from("<II", blob, 160)
    sections = {k: (p, n) for k, p, n in
                (struct.unpack_from("<III", blob, offset + 12*i) for i in range(count))}
    symbols, data = sections[3][0], sections[1][0]

    def resolve(index):
        start, size, _ = struct.unpack_from("<III", blob, symbols + 12*index)
        return data + start, size

    modes, size = resolve(73)
    if size != 43*484:
        raise ValueError("Unexpected mode table")
    result = {}
    for mode in range(43):
        count, index = struct.unpack_from("<II", blob, modes + mode*484 + 0x98)
        start, size = resolve(index)
        if size != count*60:
            raise ValueError("Unexpected register table")
        for i in range(count):
            words = struct.unpack_from("<15I", blob, start + i*60)
            pos, size = resolve(words[9])
            if size != words[8]*4:
                raise ValueError("Unexpected register data")
            result[(mode, i, words[7])] = struct.unpack_from("<" + "I"*words[8], blob, pos)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("module", type=Path)
    parser.add_argument("camx", type=Path)
    parser.add_argument("--reference", type=Path)
    args = parser.parse_args()
    source = args.module.read_bytes()
    patched = patch_module(source)
    before, after = registers(source), registers(patched)
    changed = [(key, before[key], after[key]) for key in before if before[key] != after[key]]
    assert len(changed) == 1
    assert changed[0][0][0] == 0 and changed[0][0][2] == 0x5000
    assert changed[0][1:] == ((0xDF,), (0x5F,))
    assert hashlib.sha256(patched).hexdigest() == PATCHED
    if args.reference:
        assert patched == args.reference.read_bytes()
    damaged = bytearray(source)
    damaged[300] ^= 1
    for invalid in (bytes(damaged), patched, b""):
        try:
            patch_module(invalid)
        except ValueError:
            pass
        else:
            raise AssertionError("Invalid input accepted")
    camx = patch_camx(args.camx.read_bytes()).decode("utf-8")
    assert camx.count("\nenableSensorRemosaic=0\n") == 1
    assert camx.count("\nnoRemosaicCaptureMode=1\n") == 1
    assert "autoImageDumpMCTFEoutputPortMask0=36028797018963968\n" in camx
    print("PASS", len(before), PATCHED)


if __name__ == "__main__":
    main()
