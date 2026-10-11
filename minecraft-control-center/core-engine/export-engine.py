"""Export only our small engine delta; never include decompiled Minecraft source."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys

PIN = '46d4834e8e2ec4af9dcfa028076fb16dfb8a3911'
source = Path(sys.argv[1]).resolve(strict=True)
bundle = Path(__file__).resolve().parent
actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip()
if actual != PIN:
    raise SystemExit('Refusing an unpinned engine checkout')
files = [
    'net/minecraft/nbt/CompoundTag.java',
    'net/minecraft/network/FriendlyByteBuf.java',
    'net/minecraft/network/protocol/game/ClientboundLevelChunkPacketData.java',
    'net/minecraft/util/SimpleBitStorage.java',
    'net/minecraft/world/level/levelgen/feature/OreFeature.java',
]
delta = subprocess.check_output(['git', 'diff', '--no-ext-diff', '--binary', '--', *files],
                                cwd=source / 'paper-server/src/minecraft/java')
if delta and delta.count(b'diff --git ') != len(files):
    raise SystemExit('Expected exactly five nonempty engine file changes')
header = b'''From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001
From: MCC Core build <mcc-core-build@users.noreply.github.com>
Date: Sun, 11 Oct 2026 12:00:00 +0300
Subject: [PATCH] MCC Core bounded allocation and chunk encoding optimizations

Keep the standard Paper tick ownership, generation order, and game mechanics.
Pool bounded ore scratch with a nested-call fallback; iterate NBT entries
without a second hash lookup; encode contiguous long arrays in bulk with
scalar fallback; construct chunk snapshots without a stream/hash table;
remove redundant palette divider fields while preserving unsigned arithmetic.

Prior-art review: Leaf MC 0157/0174/0240/0316 and Gale CompoundTag iteration.
All new helper code is part of MCC Core, GPL-3.0-only. Upstream attribution
and license details are retained in THIRD-PARTY.md and licenses/.

'''
(bundle / 'patches').mkdir(exist_ok=True)
name = '0038-MCC-Core-bounded-allocation-and-chunk-encoding.patch'
if delta:
    patch = header + delta.replace(b'\r\n', b'\n')
    (bundle / 'patches' / name).write_bytes(patch)
elif not (bundle / 'patches' / name).is_file() or (bundle / 'patches' / name).read_bytes() != (source / 'paper-server/patches/features' / name).read_bytes():
    raise SystemExit('No new diff and no matching exported feature patch')
shutil.copyfile(bundle / 'patches' / name, source / 'paper-server/patches/features' / name)
for group in ('main', 'test'):
    candidates = list((source / f'paper-server/src/{group}/java/dev/mcc/core/engine').glob('*.java'))
    if group == 'test':
        candidates.append(source / 'paper-server/src/test/java/net/minecraft/nbt/MccCompoundTagWriteTest.java')
    for path in candidates:
        relative = path.relative_to(source)
        target = bundle / 'overlay' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(path.read_bytes().replace(b'\r\n', b'\n'))
manifest = {str(path.relative_to(bundle)).replace('\\', '/'): {
    'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'bytes': path.stat().st_size
} for path in sorted(bundle.rglob('*')) if path.is_file() and path.name != 'export-engine.py'
    and path.suffix in ('.java', '.patch')}
print(json.dumps({'upstreamCommit': PIN, 'files': manifest}, ensure_ascii=False, indent=2))
