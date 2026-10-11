"""Run only the bounded hotpath microbenchmark; never start a Minecraft server."""
from pathlib import Path
import argparse
import os
import subprocess

BUNDLE = Path(__file__).resolve().parent
PIN = '46d4834e8e2ec4af9dcfa028076fb16dfb8a3911'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkout', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, default=Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    parser.add_argument('--gradle-home', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    if options.java_home is None:
        parser.error('Set JAVA_HOME or supply --java-home')
    checkout = options.checkout.resolve(strict=True)
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout, text=True).strip()
    if actual != PIN:
        parser.error('Only the exact pinned Paper checkout is accepted')
    output = options.output.resolve()
    if output.exists():
        parser.error('Choose a new output path; existing reports are preserved')
    java_home = options.java_home.resolve(strict=True)
    java = java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    environment = os.environ.copy()
    environment['JAVA_HOME'] = str(java_home)
    if options.gradle_home:
        environment['GRADLE_USER_HOME'] = str(options.gradle_home.resolve())
    args = [str(java), '--enable-native-access=ALL-UNNAMED', '-Xms64m', '-Xmx64m', '-cp', str(checkout / 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain',
            ':paper-server:mccHotpaths', '--init-script', str(BUNDLE / 'hotpaths.gradle'),
            '-Dmcc.hotpaths.bundle=' + str(BUNDLE), '-Dmcc.hotpaths.output=' + str(output),
            '--no-daemon', '--console=plain', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx2g']
    subprocess.run(args, cwd=checkout, env=environment, check=True)


if __name__ == '__main__':
    main()
