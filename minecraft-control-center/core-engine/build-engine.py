"""Build MCC Core's small patchset against a pinned official Paper checkout.

Requires Git, Python 3.10+, and a Java 25 JDK. Does not start or replace a server.
The checkout and output must be dedicated build directories.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys

PIN = '46d4834e8e2ec4af9dcfa028076fb16dfb8a3911'
ORIGIN = 'https://github.com/PaperMC/Paper.git'
BUNDLE = Path(__file__).resolve().parent


def run(args, *, cwd, env=None, capture=False):
    result = subprocess.run(args, cwd=cwd, env=env, check=True,
                            text=True, encoding='utf-8', errors='replace',
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkout', type=Path, default=BUNDLE / '.work/paper')
    parser.add_argument('--java-home', type=Path, default=Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    parser.add_argument('--gradle-home', type=Path)
    parser.add_argument('--output', type=Path, default=BUNDLE / 'build/MCC-Core-engine-0.2.0-26.3.jar')
    options = parser.parse_args()
    if options.java_home is None:
        parser.error('Set JAVA_HOME or pass --java-home for a Java25 JDK')
    java_home = options.java_home.resolve(strict=True)
    java_bin = java_home / 'bin'
    java_suffix = '.exe' if os.name == 'nt' else ''
    if not (java_bin / ('javac' + java_suffix)).is_file():
        parser.error('A JDK with javac is required')
    environment = os.environ.copy()
    environment['JAVA_HOME'] = str(java_home)
    environment['PATH'] = str(java_bin) + os.pathsep + environment.get('PATH', '')
    if options.gradle_home:
        environment['GRADLE_USER_HOME'] = str(options.gradle_home.resolve())
    # Only this build process receives Windows long-path handling and an
    # internal generated-patch committer. User/global Git config is untouched.
    git_count = int(environment.get('GIT_CONFIG_COUNT', '0'))
    environment['GIT_CONFIG_COUNT'] = str(git_count + 1)
    environment[f'GIT_CONFIG_KEY_{git_count}'] = 'core.longpaths'
    environment[f'GIT_CONFIG_VALUE_{git_count}'] = 'true'
    environment['GIT_COMMITTER_NAME'] = 'MCC Core build'
    environment['GIT_COMMITTER_EMAIL'] = 'mcc-core-build@users.noreply.github.com'
    checkout = options.checkout.resolve()
    output = options.output.resolve()
    if output.exists():
        parser.error('Output already exists; choose a new filename')
    if not checkout.exists():
        checkout.mkdir(parents=True)
    if not (checkout / '.git').exists():
        if any(checkout.iterdir()):
            parser.error('Refusing to use a nonempty directory without a Git checkout')
        run(['git', 'init', '-q'], cwd=checkout, env=environment)
        run(['git', 'remote', 'add', 'origin', ORIGIN], cwd=checkout, env=environment)
        run(['git', 'fetch', '--depth', '1', 'origin', PIN], cwd=checkout, env=environment)
        run(['git', 'checkout', '--detach', PIN], cwd=checkout, env=environment)
    if run(['git', 'remote', 'get-url', 'origin'], cwd=checkout, capture=True) != ORIGIN:
        parser.error('Refusing an engine checkout with an unexpected upstream remote')
    if run(['git', 'rev-parse', 'HEAD'], cwd=checkout, capture=True) != PIN:
        parser.error('Refusing to reset a checkout at another commit')
    overlay = BUNDLE / 'overlay'
    transfers = [(path, checkout / path.relative_to(overlay)) for path in sorted(overlay.rglob('*')) if path.is_file()]
    transfers += [(path, checkout / 'paper-server/patches/features' / path.name) for path in sorted((BUNDLE / 'patches').glob('*.patch'))]
    allowed = {str(target.relative_to(checkout)).replace('\\', '/') for _, target in transfers}
    allowed.add('paper-server/src/test/java/net/minecraft/world/level/levelgen/feature/MccOriginalOreFeature.java')
    status = run(['git', 'status', '--porcelain', '--untracked-files=all'], cwd=checkout, capture=True)
    if any(line[3:] not in allowed for line in status.splitlines()):
        parser.error('Engine checkout has unrelated changes; preserving them')
    for source, target in transfers:
        if target.exists() and target.read_bytes().replace(b'\r\n', b'\n') != source.read_bytes().replace(b'\r\n', b'\n'):
            parser.error('Refusing to overwrite a modified overlay: ' + str(target.relative_to(checkout)))
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    # Invoke the wrapper via Java directly; no platform shell or batch-file
    # parsing of user-selected filesystem paths is involved.
    gradle = [str(java_bin / ('java' + java_suffix)), '-Xmx64m', '-Xms64m', '-cp',
              str(checkout / 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain']
    common = ['--no-daemon', '--console=plain', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx2g']
    run([*gradle, 'applyPatches', *common], cwd=checkout, env=environment)
    run([sys.executable, str(BUNDLE / 'generate-ore-oracle.py'), str(checkout)], cwd=BUNDLE, env=environment)
    run([*gradle, ':paper-server:test', '--tests', 'dev.mcc.core.engine.MccEngineTestSuite',
         ':paper-server:createPaperclipJar', *common], cwd=checkout, env=environment)
    run([sys.executable, str(BUNDLE / 'finalize-engine.py'), str(checkout), str(output)], cwd=BUNDLE, env=environment)


if __name__ == '__main__':
    main()
