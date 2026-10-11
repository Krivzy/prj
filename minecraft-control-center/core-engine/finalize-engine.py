"""Add source provenance while streaming every existing Paperclip payload unchanged."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

PIN = '46d4834e8e2ec4af9dcfa028076fb16dfb8a3911'
BUNDLE = Path(__file__).resolve().parent


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(part)
    return digest.hexdigest()


def patchset():
    paths = sorted([*BUNDLE.glob('patches/*.patch'), *BUNDLE.glob('overlay/paper-server/src/main/java/**/*.java')])
    entries = {str(path.relative_to(BUNDLE)).replace('\\', '/'): sha(path) for path in paths}
    return hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(',', ':')).encode()).hexdigest(), entries


def main():
    source = Path(sys.argv[1]).resolve(strict=True)
    output = Path(sys.argv[2]).resolve()
    if output.exists():
        raise SystemExit('Refusing to overwrite an existing engine artifact')
    libs = source / 'paper-server/build/libs'
    candidates = list(libs.glob('paper-paperclip-*.jar'))
    if len(candidates) != 1:
        raise SystemExit('Expected one newly built Paperclip foundation')
    reports = source / 'paper-server/build/test-results/test'
    names = {'dev.mcc.core.engine.LongArrayWriterTest', 'dev.mcc.core.engine.OreBitSetPoolTest',
             'dev.mcc.core.engine.PaletteStorageTest', 'net.minecraft.nbt.MccCompoundTagWriteTest',
             'dev.mcc.core.engine.OreFeatureEquivalenceTest'}
    tests = {}
    for report in reports.glob('TEST-*.xml'):
        root = ET.parse(report).getroot()
        if root.get('name') in names:
            tests[root.get('name')] = {key: int(root.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
            tests[root.get('name')]['reportSha256'] = sha(report)
    if set(tests) != names or any(row['failures'] or row['errors'] or row['skipped'] for row in tests.values()):
        raise SystemExit('Engine behavior suite has not passed completely')
    digest, entries = patchset()
    props = (f'engine.name=MCC Core\nengine.version=0.2.0\nupstream.name=Paper\n'
             f'upstream.minecraft=26.3\nupstream.commit={PIN}\npatchset.sha256={digest}\n'
             'tick.owner=paper-main-thread\n'
             'features=bounded-ore-scratch,nbt-fast-entries,contiguous-long-encoding,chunk-snapshot-loops,palette-layout\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    original = candidates[0]
    temporary = output.with_name(output.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with zipfile.ZipFile(original) as reader, zipfile.ZipFile(temporary, 'x', compression=zipfile.ZIP_DEFLATED) as writer:
            for info in reader.infolist():
                if info.filename == 'META-INF/mcc-engine.properties':
                    raise SystemExit('Foundation already has engine provenance')
                with reader.open(info) as incoming, writer.open(info, mode='w') as outgoing:
                    shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
            info = zipfile.ZipInfo('META-INF/mcc-engine.properties', (2026, 10, 11, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            writer.writestr(info, props)
        # Publish a new filename atomically. link() refuses an existing target.
        os.link(temporary, output)
    finally:
        if temporary.exists():
            temporary.unlink()
    receipt = {'engineVersion': '0.2.0', 'minecraft': '26.3', 'upstreamCommit': PIN,
               'patchsetSha256': digest, 'patchsetFiles': entries, 'engineArtifact': output.name,
               'engineSha256': sha(output), 'engineBytes': output.stat().st_size,
               'originalPaperclipSha256': sha(original), 'tests': tests,
               'nativeVerified': False, 'performanceVerified': False, 'mainTouched': False}
    output.with_suffix('.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(receipt, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
