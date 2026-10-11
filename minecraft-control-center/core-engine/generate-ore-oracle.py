"""Generate a local, unchanged Paper OreFeature oracle for behavior tests.

The generated Minecraft source belongs only in the ignored dedicated upstream
checkout. This recovery package does not contain copied Minecraft sources.
"""
from pathlib import Path
import hashlib
import re
import subprocess
import sys

SOURCE = 'net/minecraft/world/level/levelgen/feature/OreFeature.java'
EXPECTED_SHA = '38f51ced82ccda2f763c381cf01d3196bb2ffe1f29d5d9da8b9ae62dbe0218d1'


def main():
    checkout = Path(sys.argv[1]).resolve(strict=True)
    generated = checkout / 'paper-server/src/minecraft/java'
    source = subprocess.run(['git', 'show', 'HEAD~1:' + SOURCE], cwd=generated,
                            capture_output=True, check=True, text=True,
                            encoding='utf-8').stdout.replace('\r\n', '\n')
    if hashlib.sha256(source.encode('utf-8')).hexdigest() != EXPECTED_SHA:
        raise RuntimeError('Unchanged upstream OreFeature source identity mismatch')
    if 'OreBitSetPool' in source:
        raise RuntimeError('The oracle must not contain MCC scratch pooling')
    # Rename only the class and its self references. The complete upstream
    # placement algorithm remains present, not a simplified reconstruction.
    renamed = re.sub(r'\bOreFeature\b', 'MccOriginalOreFeature', source)
    target = checkout / 'paper-server/src/test/java' / SOURCE.replace('OreFeature.java', 'MccOriginalOreFeature.java')
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.read_text(encoding='utf-8') != renamed:
        raise RuntimeError('Refusing to overwrite a changed local test oracle')
    target.write_text(renamed, encoding='utf-8')
    print('Generated unchanged local OreFeature oracle; source SHA-256=' + EXPECTED_SHA)


if __name__ == '__main__':
    main()
