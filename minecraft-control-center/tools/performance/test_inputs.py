"""Public runner input guards; no JVM starts, no production paths are read."""
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import benchmark as b


class PublicInputTests(unittest.TestCase):
    def args(self,root,**overrides):
        value=dict(java_home=str(root/'jdk'),paper_jar=str(root/'paper.jar'),paper_cache=str(root/'paper-cache'),
                   purpur_jar=None,purpur_cache=None,annotations_jar=str(root/'annotations.jar'),
                   offline_caches_confirmed=True,execute_disposable_benchmarks=False,
                   mcc_jar=None,mcc_sha256=None,accept_minecraft_eula=False,web_port_base=18105,game_port_base=25605)
        return SimpleNamespace(**(value|overrides))

    def prepare(self,root,version='25.0.1'):
        (root/'jdk/bin').mkdir(parents=True)
        for name in ['java.exe','javac.exe']:(root/'jdk/bin'/name).write_bytes(b'guard-test-placeholder-not-executable')
        (root/'jdk/release').write_text('JAVA_VERSION="'+version+'"\n',encoding='utf-8')
        (root/'paper-cache').mkdir();(root/'paper.jar').write_bytes(b'paper-pin-test')
        (root/'annotations.jar').write_bytes(b'annotations-pin-test')

    def invoke(self,args):
        with patch.object(b,'sha',return_value=b.ANNOTATIONS_SHA),patch.object(b,'source',return_value=Path(args.paper_cache)):
            b.configure(args)

    def test_confirmation_required_before_any_input_is_read(self):
        with patch.object(b,'regular',side_effect=AssertionError('Must not read without confirmation')):
            with self.assertRaises(ValueError):b.configure(self.args(Path('unused'),offline_caches_confirmed=False))

    def test_jdk25_release_guard_rejects_other_jdk(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.prepare(root,'21.0.1')
            with self.assertRaises(ValueError):self.invoke(self.args(root))

    def test_jdk25_and_explicit_paper_inputs(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.prepare(root);self.invoke(self.args(root))
            self.assertEqual(b.JAVA,root/'jdk/bin/java.exe')
            self.assertEqual(b.CORES['Paper']['cache'],root/'paper-cache')

    def test_execute_requires_purpur_pair(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.prepare(root)
            with self.assertRaises(ValueError):self.invoke(self.args(root,execute_disposable_benchmarks=True))

    def test_overlapping_or_privileged_ports_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.prepare(root)
            for port in [80,18106,65534]:
                with self.assertRaises(ValueError):self.invoke(self.args(root,game_port_base=port))

    def test_frozen_mcc_pin_rejects_wrong_or_missing_hash(self):
        with tempfile.TemporaryDirectory() as folder:
            jar=Path(folder)/'plugin.jar';jar.write_bytes(b'explicit-freeze')
            with self.assertRaises(RuntimeError):b.frozen_mcc(SimpleNamespace(mcc_jar=str(jar),mcc_sha256='0'*64))
            result=b.frozen_mcc(SimpleNamespace(mcc_jar=str(jar),mcc_sha256=b.sha(jar)))
            self.assertEqual(result['jarSha256'],b.sha(jar))

    def test_cli_requires_explicit_mode_and_paths(self):
        with self.assertRaises(SystemExit),patch('sys.stderr'):
            b.parser_for_cli().parse_args([])

    def test_documented_pins_match_actual_runner_guards(self):
        pins=json.loads((b.BASE/'pins.json').read_bytes())
        self.assertEqual(pins['paper']['sha256'],b.CORES['Paper']['sha256'])
        self.assertEqual(pins['purpur']['sha256'],b.CORES['Purpur']['sha256'])
        self.assertEqual(pins['compileDependencies'],b.API_PINS)
        self.assertEqual(pins['annotations']['sha256'],b.ANNOTATIONS_SHA)
        self.assertEqual(pins['foundation']['sha256'],b.FOUNDATION_SHA)
        self.assertEqual(pins['foundation']['patchsetSha256'],b.PATCHSET_SHA)
        self.assertEqual(pins['paper']['upstreamCommit'],b.UPSTREAM_COMMIT)

    def test_fixture_lease_is_exclusive_and_idempotently_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'owned.lock';first=b.safety.OfflineLease(path)
            try:
                with self.assertRaises(OSError):b.safety.OfflineLease(path)
            finally:first.close();first.close()
            second=b.safety.OfflineLease(path);second.close()


if __name__=='__main__':unittest.main()
