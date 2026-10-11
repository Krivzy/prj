"""Controlled diagnostic planning guards; no world scan, process API or JVM."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import diagnose_session as d


class DiagnosticPlanTests(unittest.TestCase):
    def prepare(self,root):
        private=root/'.private';private.mkdir();session=private/'session-unit';session.mkdir();runs=[]
        for core in ['Paper','MCC-Core','Purpur']:
            fixture=private/(core.lower()+'-unit');fixture.mkdir();run=core+'-unit'
            closure={'scope':'disposable-mcc-core-benchmark','runId':run,'sessionId':'unit','core':core,
                     'coreSha256':'0'*64,'worldSnapshot':{'fixtureRunId':'template','snapshotManifestSha256':'1'*64}}
            (fixture/'MCC-BENCHMARK-FIXTURE.json').write_text(json.dumps(closure),encoding='utf-8')
            (fixture/'closure.json').write_text(json.dumps(closure),encoding='utf-8')
            runs.append({'fixture':str(fixture),'runId':run,'core':core,'repetition':0})
        file=session/'result.json';file.write_text(json.dumps({'scope':'disposable-mcc-core-benchmark-session','sessionId':'unit','runs':runs,'passed':True}),encoding='utf-8')
        return private,file

    def diff(self):
        return {'allBlocksCompared':12582912,'differentBlocks':6,'originalDigestSampleDifferentBlocks':0,
                'biomeNbtDifferentChunks':0,'categories':{'other -> fluid':6},'hashGuardUnchanged':True}

    def test_all_three_pairs_preserve_full_fluid_diagnostics(self):
        with tempfile.TemporaryDirectory() as folder:
            private,file=self.prepare(Path(folder))
            with patch.object(d.b,'validate_closed') as closure,patch.object(d.d,'compare',return_value=self.diff()) as compare:
                result=d.diagnose(file,private)
            self.assertEqual(closure.call_count,3);self.assertEqual(compare.call_count,3);self.assertEqual(result['comparisons'],3)
            index=json.loads(Path(result['output']).read_bytes())
            self.assertTrue(all(row['categories']=={'other -> fluid':6} for row in index['reports']))
            self.assertTrue(all(row['hashGuardUnchanged'] for row in index['reports']))

    def test_external_fixture_path_rejected_before_compare(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);private,file=self.prepare(root);external=root/'external';external.mkdir()
            raw=json.loads(file.read_bytes());raw['runs'][0]['fixture']=str(external);file.write_text(json.dumps(raw),encoding='utf-8')
            with patch.object(d.d,'compare',side_effect=AssertionError('Unsafe root must not be scanned')):
                with self.assertRaises(ValueError):d.diagnose(file,private)

    def test_failed_normal_closure_prevents_world_reads(self):
        with tempfile.TemporaryDirectory() as folder:
            private,file=self.prepare(Path(folder))
            with patch.object(d.b,'validate_closed',side_effect=RuntimeError('Not normally closed')),patch.object(d.d,'compare',side_effect=AssertionError('Open world must not be scanned')):
                with self.assertRaises(RuntimeError):d.diagnose(file,private)


if __name__=='__main__':unittest.main()
