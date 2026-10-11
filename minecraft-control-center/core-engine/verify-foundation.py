"""Verify every original payload byte, including the manifest and license notices."""
from pathlib import Path
import hashlib
import json
import sys
import zipfile


def content_sha(archive, info):
    digest = hashlib.sha256()
    with archive.open(info) as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(part)
    return digest.hexdigest()


original = Path(sys.argv[1]).resolve(strict=True)
engine = Path(sys.argv[2]).resolve(strict=True)
with zipfile.ZipFile(original) as source, zipfile.ZipFile(engine) as result:
    source_files = source.namelist()
    if set(result.namelist()) != set(source_files) | {'META-INF/mcc-engine.properties'}:
        raise SystemExit('Unexpected missing or added foundation entries')
    if len(result.namelist()) != len(set(result.namelist())):
        raise SystemExit('Duplicate engine entries')
    for name in source_files:
        if content_sha(source, name) != content_sha(result, name):
            raise SystemExit('Modified original payload: ' + name)
    props = result.read('META-INF/mcc-engine.properties').decode('utf-8')
    if 'engine.version=0.2.0\n' not in props or 'upstream.commit=46d4834e8e2ec4af9dcfa028076fb16dfb8a3911\n' not in props:
        raise SystemExit('Engine provenance is missing or unpinned')
    licenses = [name for name in source_files if 'license' in name.lower()]
    print(json.dumps({'ok': True, 'unchangedEntries': len(source_files),
                      'unchangedLicenseEntries': len(licenses), 'manifestUnchanged': True,
                      'addedEntry': 'META-INF/mcc-engine.properties'}))
