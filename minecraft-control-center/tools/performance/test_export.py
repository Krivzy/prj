"""Publication allowlist regression tests; no native processes or existing data."""
import copy
import csv
import json
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
import export_results as e


class ExportTests(unittest.TestCase):
    def csv(self,**changes):
        row={'phase':'idle-control','tick_index':'0','mspt':'1.2','main_thread_allocated_delta_bytes':'128',
             'heap_used_bytes':'1000','operation_nanos':'0','chunks_completed':'0','chunks_pending':'0'}|changes
        return ','.join(e.COLUMNS)+'\n'+','.join(row[key] for key in e.COLUMNS)+'\n'

    def test_numeric_rows_preserved(self):
        self.assertEqual(e.checked_csv(self.csv())[0]['mspt'],'1.2')

    def test_credentials_or_nonfinite_text_rejected(self):
        for value in ['secret-value','NaN','inf','1e999','user/password']:
            with self.assertRaises(ValueError):e.checked_csv(self.csv(mspt=value))

    def test_unknown_phase_rejected(self):
        with self.assertRaises(ValueError):e.checked_csv(self.csv(phase='unexpected'))

    def test_unknown_csv_column_rejected(self):
        with self.assertRaises(ValueError):e.checked_csv('phase,password\nidle-control,secret\n')

    def test_unavailable_metrics_preserved_without_zero(self):
        self.assertEqual(e.scalar_tree({'allocated':None,'mspt':1.2}),{'allocated':None,'mspt':1.2})
        with self.assertRaises(ValueError):e.scalar_tree({'password':'text'})

    def runs(self,repeats=3):
        values=[]
        for repetition in range(repeats):
            for core,base in [('Paper',100),('Purpur',20),('MCC-Core',18)]:
                if core=='Purpur':value=[20,60,100][repetition]
                elif core=='MCC-Core':value=[18,66,92][repetition]
                else:value=base*(repetition+1)
                phase={metric:float(value) for metric in e.METRICS}|{'ticks':1200}
                values.append({'core':core,'repetition':repetition,'runId':core+'-boot-'+str(repetition),'coreSha256':core+'-pin',
                               'plugins':{'MinecraftControlCenter':'mcc-pin','MCCBenchmark':'benchmark-pin'},
                               'javaVersion':'25.0.1','generationMode':'controlled',
                               'worldSnapshot':{'fixtureRunId':'template','snapshotManifestSha256':'1'*64},
                               'plan':{'phaseTicks':1200,'initialIdleWarmupTicks':600,'generationMode':'controlled'},
                               'generatedBlockStateSampleSha256':'0'*64,
                               'phases':{name:dict(phase) for name in e.PHASES}})
        return values

    def test_direct_core_purpur_difference_is_paired_not_subtracted_ci(self):
        comparisons=e.paired_comparisons(e.grouped_pairs(self.runs(),False))
        self.assertEqual(set(comparisons),{'MCC-Core-minus-Paper','Purpur-minus-Paper','MCC-Core-minus-Purpur'})
        actual=comparisons['MCC-Core-minus-Purpur']['idle-control']['meanMspt']
        self.assertEqual(actual,e.b.paired_bootstrap([-2,6,-8]))
        self.assertEqual(actual['pairedRuns'],3)

    def test_nonpilot_requires_three_complete_pairs(self):
        for repeats in [1,2]:
            with self.assertRaises(ValueError):e.grouped_pairs(self.runs(repeats),False)
        self.assertEqual(len(e.grouped_pairs(self.runs(3),False)),3)

    def test_nonpilot_rejects_short_plan_or_actual_timed_phase(self):
        for key,value in [('phaseTicks',1199),('initialIdleWarmupTicks',599)]:
            runs=self.runs();runs[0]['plan'][key]=value
            with self.assertRaises(ValueError):e.grouped_pairs(runs,False)
        runs=self.runs();runs[0]['phases']['world-save']['ticks']=1199
        with self.assertRaises(ValueError):e.grouped_pairs(runs,False)

    def test_chunk_phases_need_not_run_sixty_seconds(self):
        runs=self.runs()
        for run in runs:
            for phase in ['saved-chunk-read','fresh-chunk-generation']:run['phases'][phase]['ticks']=129
        self.assertEqual(len(e.grouped_pairs(runs,False)),3)

    def test_mixed_plugins_jvm_generation_world_or_plan_rejected(self):
        for key,value in [('plugins',{'MinecraftControlCenter':'changed','MCCBenchmark':'benchmark-pin'}),
                          ('javaVersion','25.0.2'),('generationMode','parallel'),
                          ('worldSnapshot',{'fixtureRunId':'different','snapshotManifestSha256':'1'*64}),
                          ('plan',{'phaseTicks':1300,'initialIdleWarmupTicks':600,'generationMode':'controlled'})]:
            runs=self.runs();runs[-1][key]=value
            with self.assertRaises(ValueError):e.grouped_pairs(runs,False)

    def test_missing_core_duplicate_pair_or_changed_release_rejected(self):
        runs=self.runs();runs.pop()
        with self.assertRaises(ValueError):e.grouped_pairs(runs,False)
        runs=self.runs();runs.append(copy.deepcopy(runs[-1]))
        with self.assertRaises(ValueError):e.grouped_pairs(runs,False)
        runs=self.runs();runs[-1]['coreSha256']='different'
        with self.assertRaises(ValueError):e.grouped_pairs(runs,False)

    def test_same_boot_cannot_be_relabelled_as_an_independent_repetition(self):
        runs=self.runs()
        for run in runs:
            if run['repetition']>0:run['runId']=run['core']+'-boot-0'
        with self.assertRaises(ValueError):e.grouped_pairs(runs,False)
    def test_unmodified_hash_guard_covers_all_nonpilot_repeats(self):
        runs=self.runs()
        for run in runs:
            if run['repetition']==2:run['generatedBlockStateSampleSha256']='1'*64
        with self.assertRaises(ValueError):e.grouped_pairs(runs,False)

    def test_direct_unavailable_allocation_not_claimed_zero(self):
        runs=self.runs();runs[0]['phases']['idle-control']['mainThreadAllocatedBytes']=None
        runs[2]['phases']['idle-control']['mainThreadAllocatedBytes']=None
        comparisons=e.paired_comparisons(e.grouped_pairs(runs,False))
        self.assertNotIn('mainThreadAllocatedBytes',comparisons['MCC-Core-minus-Purpur']['idle-control'])
        self.assertIn('meanMspt',comparisons['MCC-Core-minus-Purpur']['idle-control'])

    def test_pinned_version_grammar_rejects_arbitrary_paths(self):
        for server,java in [('C:/MCC-QA-Test/private','25.0.1'),('26.3-169-46d4834 (MC: 26.3)','C:/jdk/private')]:
            with self.assertRaises(ValueError):e.checked_versions('Paper',server,java)
        e.checked_versions('Paper','26.3-169-46d4834 (MC: 26.3)','25.0.1')
        e.checked_versions('MCC-Core','26.3-DEV-46d4834 (MC: 26.3)','25.0.1')
        e.checked_versions('Purpur','26.3-2647-4cd2cf6 (MC: 26.3)','25.0.1')

    def test_plan_identity_duplicate_unknown_keys_and_numeric_bounds(self):
        text='run.id=run\nmode=measure\ncandidate=Paper\nphase.ticks=1200\nwarmup.ticks=600\ngeneration.mode=controlled\n'
        self.assertEqual(e.workload_plan(text,'Paper','run')['initialIdleWarmupTicks'],600)
        for bad in [text+'phase.ticks=1200\n',text+'secret=value\n',text.replace('warmup.ticks=600','warmup.ticks=0'),text.replace('candidate=Paper','candidate=Purpur')]:
            with self.assertRaises(ValueError):e.workload_plan(bad,'Paper','run')

    def files(self,root):
        private=root/'.private';private.mkdir();session_id=str(uuid.uuid4());session=private/('session-'+session_id);session.mkdir()
        plugins={'MinecraftControlCenter':'a'*64,'MCCBenchmark':'b'*64}
        snapshot={'fixtureRunId':str(uuid.uuid4()),'snapshotManifestSha256':'c'*64};entries=[]
        versions={'Paper':'26.3-169-46d4834 (MC: 26.3)','MCC-Core':'26.3-DEV-46d4834 (MC: 26.3)','Purpur':'26.3-2647-4cd2cf6 (MC: 26.3)'}
        for repetition in range(3):
            for index,core in enumerate(['Paper','Purpur','MCC-Core']):
                run_id=str(uuid.uuid4());fixture=private/(core.lower()+'-'+run_id);output=fixture/'plugins/MCCBenchmark';output.mkdir(parents=True)
                csv_file=output/'ticks.csv';phases=[]
                with csv_file.open('x',encoding='utf-8',newline='') as stream:
                    writer=csv.DictWriter(stream,fieldnames=e.COLUMNS,lineterminator='\n');writer.writeheader()
                    for phase in sorted(e.PHASES):
                        count=1200 if phase in e.TIMED_PHASES else 129
                        phases.append({'name':phase,'ticks':count,'wallNanos':count*50_000_000,'gcCollections':0,'gcMillis':0,'completedChunks':128 if phase not in e.TIMED_PHASES else 0})
                        for tick in range(count):writer.writerow({'phase':phase,'tick_index':tick,'mspt':1+index+repetition,'main_thread_allocated_delta_bytes':64,'heap_used_bytes':1000,'operation_nanos':0,'chunks_completed':0,'chunks_pending':0})
                measurement=output/'result.json';measurement.write_text(json.dumps({'passed':True,'errors':[],'scope':'disposable-mcc-core-benchmark','mode':'measure','runId':run_id,'candidate':core,'minecraftVersion':'26.3','serverVersion':versions[core],'javaVersion':'25.0.1','phases':phases,'generatedBlockStateSampleSha256':'d'*64,'unexportedPrivateField':'unit-only-ignore'}),encoding='utf-8')
                (output/'plan.properties').write_text(f'run.id={run_id}\nmode=measure\ncandidate={core}\nphase.ticks=1200\nwarmup.ticks=600\ngeneration.mode=controlled\n',encoding='utf-8')
                entry={'scope':'disposable-mcc-core-benchmark','sessionId':session_id,'mode':'measure','fixture':str(fixture),'runId':run_id,'core':core,'repetition':repetition,'coreSha256':e.b.FOUNDATION_SHA if core=='MCC-Core' else e.b.CORES[core]['sha256'],'plugins':plugins,'generationMode':'controlled-worker1-io1-synchronous-row-order','worldSnapshot':snapshot,'jvmFlags':e.b.FLAGS,
                       'measurement':{'file':str(measurement),'sha256':e.b.sha(measurement)},'ticks':{'file':str(csv_file),'sha256':e.b.sha(csv_file)}}
                (fixture/'MCC-BENCHMARK-FIXTURE.json').write_text(json.dumps(entry),encoding='utf-8')
                (fixture/'closure.json').write_text(json.dumps(entry|{'passed':True}),encoding='utf-8');entries.append(entry)
        result=session/'result.json';result.write_text(json.dumps({'scope':'disposable-mcc-core-benchmark-session','passed':True,'allOwnedFixturesNormallyClosed':True,'pilot':False,'comparativeVerdict':'bounded-workload-measurements-only','sessionId':session_id,'jvmFlags':e.b.FLAGS,'MccJarSha256':plugins['MinecraftControlCenter'],'plugin':{'pluginSha256':plugins['MCCBenchmark']},'runs':entries}),encoding='utf-8')
        return private,result,entries

    def test_full_export_recomputes_three_contrasts_and_omits_private_paths(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);private,file,entries=self.files(root);before=e.b.sha(file)
            with patch.object(e.b,'BASE',root),patch.object(e.b,'validate_closed'):
                receipt=e.export(file,private,'unit-export')
            summary=json.loads((Path(receipt['output'])/'summary.json').read_bytes())
            self.assertEqual(len(summary['runs']),9);self.assertEqual(len(summary['rawCsvFiles']),9)
            self.assertEqual(set(summary['comparisons']),{'MCC-Core-minus-Paper','Purpur-minus-Paper','MCC-Core-minus-Purpur'})
            self.assertEqual(e.b.sha(file),before)
            text=json.dumps(summary);self.assertNotIn(str(root),text);self.assertNotIn('unit-only-ignore',text)
            for item in summary['rawCsvFiles']:self.assertEqual(e.b.sha(Path(receipt['output'])/item['file']),item['sha256'])

    def test_export_rejects_modified_csv_before_public_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);private,file,entries=self.files(root);csv_file=Path(entries[0]['ticks']['file']);csv_file.write_bytes(csv_file.read_bytes()+b'changed')
            with patch.object(e.b,'BASE',root),patch.object(e.b,'validate_closed'):
                with self.assertRaises(RuntimeError):e.export(file,private,'unit-export')
            self.assertFalse((root/'results/unit-export').exists())

    def test_export_rejects_modified_measurement_json_before_public_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);private,file,entries=self.files(root);measurement=Path(entries[0]['measurement']['file'])
            measurement.write_bytes(measurement.read_bytes()+b' ')
            with patch.object(e.b,'BASE',root),patch.object(e.b,'validate_closed'):
                with self.assertRaises(RuntimeError):e.export(file,private,'unit-export')
            self.assertFalse((root/'results/unit-export').exists())

    def test_export_rejects_mismatched_closure_artifact_provenance(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);private,file,entries=self.files(root);closure_file=Path(entries[0]['fixture'])/'closure.json'
            closure=json.loads(closure_file.read_bytes());closure['plugins']['MinecraftControlCenter']='e'*64;closure_file.write_text(json.dumps(closure),encoding='utf-8')
            with patch.object(e.b,'BASE',root),patch.object(e.b,'validate_closed'):
                with self.assertRaises(ValueError):e.export(file,private,'unit-export')
            self.assertFalse((root/'results/unit-export').exists())

    def test_export_rejects_reusing_the_same_closed_three_boots_across_repeats(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);private,file,entries=self.files(root);raw=json.loads(file.read_bytes());raw['runs']=[]
            for repetition in range(3):
                for entry in entries[:3]:raw['runs'].append(copy.deepcopy(entry)|{'repetition':repetition})
            file.write_text(json.dumps(raw),encoding='utf-8')
            with patch.object(e.b,'BASE',root),patch.object(e.b,'validate_closed'):
                with self.assertRaises(ValueError):e.export(file,private,'unit-export')
            self.assertFalse((root/'results/unit-export').exists())

    def test_export_rejects_same_fixture_even_with_a_new_claimed_run_id(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);private,file,entries=self.files(root);raw=json.loads(file.read_bytes())
            raw['runs'][3]['fixture']=raw['runs'][0]['fixture']
            # Distinct runId here ensures rejection is driven by resolved path.
            self.assertNotEqual(raw['runs'][3]['runId'],raw['runs'][0]['runId'])
            file.write_text(json.dumps(raw),encoding='utf-8')
            with patch.object(e.b,'BASE',root),patch.object(e.b,'validate_closed'):
                with self.assertRaisesRegex(ValueError,'Reused run identity or resolved fixture'):e.export(file,private,'unit-export')
            self.assertFalse((root/'results/unit-export').exists())


if __name__=='__main__':unittest.main()
