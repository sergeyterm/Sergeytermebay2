#!/usr/bin/env python3
"""Verify Android APK 16KiB native-library alignment without extracting secrets.
Used by GitHub Actions before publishing installation packages.
"""
import pathlib
import struct
import sys
from zipfile import ZipFile, ZIP_STORED


def verify(path: pathlib.Path) -> None:
    count_native=0
    count_stored=0
    errors=[]
    with path.open("rb") as fp, ZipFile(path) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or entry.compress_type != ZIP_STORED:
                continue
            count_stored += 1
            fp.seek(entry.header_offset)
            header=fp.read(30)
            if len(header)!=30 or header[:4]!=b"PK\x03\x04":
                errors.append(f"{entry.filename}: invalid ZIP local header")
                continue
            name_length, extra_length=struct.unpack_from("<HH",header,26)
            start=entry.header_offset+30+name_length+extra_length
            alignment=16*1024 if entry.filename.endswith(".so") else 4
            if entry.filename.endswith(".so"):
                count_native += 1
            if start % alignment != 0:
                errors.append(f"{entry.filename}: data offset not aligned to {alignment} bytes (remainder {start % alignment})")
    if errors:
        raise SystemExit("APK alignment check FAILED:\n" + "\n".join(errors))
    print(f"PASS: {path.name} - {count_native} native libs 16KiB-aligned; {count_stored} stored ZIP entries aligned.")


if __name__ == "__main__":
    if len(sys.argv)<2: raise SystemExit("Usage: python check_apk_alignment.py file.apk [file2.apk...]")
    for arg in sys.argv[1:]: verify(pathlib.Path(arg))
