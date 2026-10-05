#!/usr/bin/env python3
import argparse
import hashlib
import re
import struct
from pathlib import Path

STOCK = "dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613"
PATCHED = "32a715889eb54d601ade4a58cd76ff640b9c89631a2ab6f33fc7c1495cbb200d"
CAMX = "27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b"
POLY = 0x42F0E1EBA9EA3693


def crc64(body):
    table = []
    for value in range(256):
        for _ in range(8):
            value = (value >> 1) ^ (POLY if value & 1 else 0)
        table.append(value)
    crc = (1 << 64) - 1
    for value in body + struct.pack("<I", len(body)):
        crc = table[(crc ^ value) & 255] ^ (crc >> 8)
    return crc ^ ((1 << 64) - 1)


def patch_module(original):
    if hashlib.sha256(original).hexdigest() != STOCK:
        raise ValueError("Unsupported sensor module")
    if crc64(original[:-8]) != struct.unpack_from("<Q", original, len(original) - 8)[0]:
        raise ValueError("Invalid module CRC")
    data = bytearray(original)
    if struct.unpack_from("<I", data, 0x10F77A)[0] != 920:
        raise ValueError("Unexpected mode0 register reference")
    struct.pack_into("<I", data, 0x10F77A, 13160)
    struct.pack_into("<Q", data, len(data) - 8, crc64(bytes(data[:-8])))
    if hashlib.sha256(data).hexdigest() != PATCHED:
        raise ValueError("Unexpected patched module")
    return bytes(data)


def patch_camx(original):
    if hashlib.sha256(original).hexdigest() != CAMX:
        raise ValueError("Unsupported CamX settings")
    values = {
        "enableSensorRemosaic": "0",
        "noRemosaicCaptureMode": "1",
        "autoImageDump": "1",
        "autoImageDumpMask": "8192",
        "autoImageDumpMCTFEInstanceMask": "1",
        "autoImageDumpMCTFEoutputPortMask0": "36028797018963968",
        "autoImageDumpMCTFEoutputPortMask1": "0",
        "autoImageDumpMCTFEoutputPortMask2": "0",
        "autoImageDumpMCTFEoutputBatchNum": "1",
        "enableDumpSensorI2CInfo": "TRUE",
    }
    text = original.decode("utf-8").replace("\r\n", "\n")
    for key, value in values.items():
        text = re.sub(r"(?m)^\s*" + re.escape(key) + r"\s*=.*(?:\n|$)", "", text)
        text = text.rstrip("\n") + "\n" + key + "=" + value + "\n"
    return text.encode("utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("module", type=Path)
    parser.add_argument("camx", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    module = patch_module(args.module.read_bytes())
    camx = patch_camx(args.camx.read_bytes())
    args.output.mkdir(parents=True, exist_ok=True)
    for name, data in (("sensormodule.bin", module), ("camx.txt", camx)):
        path = args.output / name
        with path.open("xb") as stream:
            stream.write(data)
        print(hashlib.sha256(data).hexdigest(), name)


if __name__ == "__main__":
    main()
