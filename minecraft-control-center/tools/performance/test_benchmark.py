"""Analysis and guard regression tests. Never starts a JVM or touches existing fixtures."""
import csv, hashlib, json, os, unittest, uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import benchmark as b


class AnalysisTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root=b.BASE/'.private'/('unit-'+uuid.uuid4().hex)
        cls.root.mkdir(parents=True)

    def test_percentile_interpolates_small_sample(self):
        self.assertEqual(b.percentile([2,0,1,3],.5),1.5)
        self.assertAlmostEqual(b.percentile([0,10],.95),9.5)

    def test_percentile_rejects_empty_nonfinite_or_bad_quantile(self):
        for values,q in [([],0),([float('nan')],.5),([1],1.1),([1],-.1)]:
            with self.assertRaises(ValueError):b.percentile(values,q)

    def test_bootstrap_requires_independent_boots(self):
        self.assertFalse(b.paired_bootstrap([1,2])['available'])

    def test_bootstrap_is_reproducible_and_preserves_difference_direction(self):
        left=b.paired_bootstrap([-1,-2,-3],iterations=100)
        self.assertEqual(left,b.paired_bootstrap([-1,-2,-3],iterations=100))
        self.assertEqual(left['meanDifference'],-2)
        self.assertLess(left['bootstrap95High'],0)

    def test_bootstrap_no_improvement_for_zero_differences(self):
        value=b.paired_bootstrap([0,0,0],iterations=50)
        self.assertEqual(value['bootstrap95Low'],0)
        self.assertEqual(value['bootstrap95High'],0)

    def test_bootstrap_rejects_nonfinite_and_zero_iterations(self):
        with self.assertRaises(ValueError):b.paired_bootstrap([float('inf')],iterations=10)
        with self.assertRaises(ValueError):b.paired_bootstrap([1,2,3],iterations=0)

    def raw(self,allocation=10,ticks=20,passed=True):
        root=self.root/uuid.uuid4().hex;root.mkdir()
        raw={'passed':passed,'phases':[{'name':'idle-control','ticks':ticks,'wallNanos':1_000_000_000,'gcCollections':0,'gcMillis':0,'completedChunks':0}]}
        result=root/'result.json';result.write_text(json.dumps(raw),encoding='utf-8')
        rows=root/'ticks.csv'
        with rows.open('x',encoding='utf-8',newline='') as out:
            writer=csv.DictWriter(out,fieldnames=['phase','mspt','main_thread_allocated_delta_bytes','heap_used_bytes'])
            writer.writeheader()
            for index in range(20):writer.writerow({'phase':'idle-control','mspt':index+1,'main_thread_allocated_delta_bytes':allocation,'heap_used_bytes':1000})
        return {'core':'Paper','runId':'unit','measurement':{'file':str(result),'sha256':b.sha(result)},'ticks':{'file':str(rows),'sha256':b.sha(rows)}}

    def test_summary_uses_raw_tick_durations(self):
        result=b.summarize(self.raw())['phases']['idle-control']
        self.assertEqual(result['msptP50'],10.5)
        self.assertEqual(result['mainThreadAllocatedBytes'],200)
        self.assertEqual(result['wallSeconds'],1)
        self.assertIsNone(result['chunkThroughputPerSecond'])

    def test_unavailable_allocation_is_never_claimed_zero(self):
        self.assertIsNone(b.summarize(self.raw(allocation=-1))['phases']['idle-control']['mainThreadAllocatedBytes'])

    def test_summary_refuses_mismatched_tick_count(self):
        with self.assertRaises(RuntimeError):b.summarize(self.raw(ticks=21))

    def test_summary_refuses_failed_workload(self):
        with self.assertRaises(RuntimeError):b.summarize(self.raw(passed=False))

    def test_summary_refuses_modified_raw_csv(self):
        row=self.raw();Path(row['ticks']['file']).write_text('changed',encoding='utf-8')
        with self.assertRaises(RuntimeError):b.summarize(row)

    def test_copy_is_create_only_and_hash_verified(self):
        root=self.root/uuid.uuid4().hex;root.mkdir();source=root/'input';source.write_bytes(b'bounded test data');inventory=[]
        b.copy_file(source,root/'output',inventory,root)
        self.assertEqual(inventory[0]['sha256'],hashlib.sha256(b'bounded test data').hexdigest())
        with self.assertRaises(FileExistsError):b.copy_file(source,root/'output',inventory,root)

    def test_reparse_path_is_rejected(self):
        value=SimpleNamespace(st_mode=0o100644,st_file_attributes=0x400)
        with patch.object(b.os,'lstat',return_value=value):
            with self.assertRaises(RuntimeError):b.regular(self.root/'anything')

    def test_closure_refuses_nonzero_or_unrequested_exit(self):
        for row in [{'closed':True,'exitCode':1,'normalStopRequested':True},{'closed':True,'exitCode':0,'normalStopRequested':False}]:
            with self.assertRaises(RuntimeError):b.validate_closed(row)

    def test_closure_does_not_confuse_reused_pid_with_old_owned_jvm(self):
        row={'closed':True,'exitCode':0,'normalStopRequested':True,'javaPid':123,'processCreationDate':'old','ports':[18105,25605]}
        with patch.object(b,'processes',return_value=[{'CreationDate':'new'}]),patch.object(b,'listeners',return_value=[]):b.validate_closed(row)
        with patch.object(b,'processes',return_value=[{'CreationDate':'old'}]),patch.object(b,'listeners',return_value=[]):
            with self.assertRaises(RuntimeError):b.validate_closed(row)

    def test_attestation_rejects_wrong_parent_executable_or_creation_date(self):
        fixture={'fixture':str(self.root),'runId':'run'};child=SimpleNamespace(pid=123,poll=lambda:None)
        row={'CommandLine':str(self.root)+' -Dmcc.benchmark.run-id=run -Dmcc.benchmark.disposable=true','ParentProcessId':os.getpid(),'ExecutablePath':str(b.JAVA),'CreationDate':'now'}
        with patch.object(b,'processes',return_value=[row]):self.assertEqual(b.attest(child,fixture),row)
        for field,value in [('ParentProcessId',0),('ExecutablePath','different.exe'),('CreationDate','reused')]:
            with patch.object(b,'processes',return_value=[{**row,field:value}]):
                with self.assertRaises(RuntimeError):b.attest(child,fixture,{'CreationDate':'now'})


if __name__=='__main__':unittest.main()
