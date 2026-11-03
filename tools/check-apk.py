#!/usr/bin/env python3
"""Validate container CRCs and standard compiled APK components, not installability."""
import hashlib,pathlib,sys,zipfile
path=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(path) as apk:
    assert apk.testzip() is None, 'ZIP CRC failure'
    names=apk.namelist()
    assert len(names)==len(set(names)), 'Duplicate ZIP entries'
    for component in ['AndroidManifest.xml','resources.arsc','classes.dex']:
        assert component in names, 'Missing '+component
    assert apk.read('AndroidManifest.xml')[:4]==b'\x03\x00\x08\x00', 'Manifest is not binary Android XML'
    assert apk.read('classes.dex').startswith(b'dex\n'), 'Invalid DEX header'
print('APK ZIP/CRC/binary manifest/DEX checks passed')
print('SHA-256:',hashlib.sha256(path.read_bytes()).hexdigest())
print('Bytes:',path.stat().st_size)
