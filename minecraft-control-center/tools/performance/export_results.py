"""Explicit numeric allowlist export from closed owned fixtures; no logs/worlds/paths.

The raw source stays private. Output is new, create-only tools/performance/results/<name>.
"""
import argparse
import csv
import hashlib
import json
import math
import os
import re
from pathlib import Path
import benchmark as b

PHASES={'idle-control','hopper-redstone','mob-ai','saved-chunk-read','fresh-chunk-generation','world-save'}
COLUMNS=['phase','tick_index','mspt','main_thread_allocated_delta_bytes','heap_used_bytes','operation_nanos','chunks_completed','chunks_pending']
METRICS=['msptP50','msptP95','msptP99','meanMspt','sampledHeapUsedMedian','wallSeconds','mainThreadAllocatedBytes','mainThreadAllocatedBytesPerTick']
TIMED_PHASES={'idle-control','hopper-redstone','mob-ai','world-save'}


def workload_plan(text,core,run_id):
    if len(text)>4096:raise ValueError('Workload plan is too large')
    values={}
    for line in text.splitlines():
        if not line:continue
        key,separator,value=line.partition('=')
        if not separator or key in values:raise ValueError('Malformed/duplicate workload plan key')
        values[key]=value
    expected={'run.id','mode','candidate','phase.ticks','warmup.ticks','generation.mode'}
    if set(values)!=expected or values['run.id']!=run_id or values['candidate']!=core or values['mode']!='measure' or values['generation.mode'] not in {'controlled','parallel'}:raise ValueError('Workload plan identity or policy mismatch')
    if any(not re.fullmatch(r'\d+',values[key]) for key in ['phase.ticks','warmup.ticks']):raise ValueError('Workload tick plan must be numeric')
    phase=int(values['phase.ticks']);warmup=int(values['warmup.ticks'])
    if not 40<=phase<=6000 or not 20<=warmup<=2400:raise ValueError('Workload plan outside runner bounds')
    return {'phaseTicks':phase,'initialIdleWarmupTicks':warmup,'generationMode':values['generation.mode']}


def checked_versions(core,server,java):
    # Grammars of the pinned artifacts; arbitrary ':'/'/' strings are not metadata.
    match=re.fullmatch(r'26\.3-(169|2647|DEV)-([0-9a-f]{7,40}) \(MC: 26\.3\)',server or '')
    expected={'Paper':'169','Purpur':'2647','MCC-Core':'DEV'}
    if core not in expected or not match or match[1]!=expected[core]:raise ValueError('Unexpected pinned server version format')
    if core in {'Paper','MCC-Core'} and not b.UPSTREAM_COMMIT.startswith(match[2]):raise ValueError('Server source commit differs from pinned Paper')
    if not re.fullmatch(r'25(?:\.\d+){0,3}(?:\+\d+)?',java or ''):raise ValueError('Only a numeric JDK 25 runtime version may be exported')
    return server,java


def grouped_pairs(runs,pilot):
    if type(pilot) is not bool:raise ValueError('Session pilot flag must be a boolean')
    if len({run['runId'] for run in runs})!=len(runs):raise ValueError('Paired repeats must use distinct independent boot identities')
    cores={run['core'] for run in runs};pairs={}
    if not {'Paper','Purpur'}<=cores or not cores<=set(b.CORES):raise ValueError('A comparable baseline pair is required')
    for run in runs:
        repetition=run['repetition']
        if type(repetition) is not int or not 0<=repetition<=9:raise ValueError('Unexpected repetition index')
        pair=pairs.setdefault(repetition,{})
        if run['core'] in pair:raise ValueError('Duplicate kernel in pair')
        pair[run['core']]=run
        if not pilot:
            if run['plan']['phaseTicks']<1200 or run['plan']['initialIdleWarmupTicks']<600:raise ValueError('Nonpilot requires phase >=60 s and initial idle warmup >=30 s')
            if any(run['phases'][phase]['ticks']<1200 for phase in TIMED_PHASES):raise ValueError('Nonpilot measured time phases are shorter than 60 s nominal ticks')
    if set(pairs)!=set(range(len(pairs))):raise ValueError('Repetitions must be complete contiguous paired boots')
    if not pilot and len(pairs)<3:raise ValueError('Nonpilot requires at least 3 independent complete paired boots')
    if any(set(pair)!=cores or len({run['generatedBlockStateSampleSha256'] for run in pair.values()})!=1 for pair in pairs.values()):raise ValueError('Kernel pairs incomplete or original terrain hash differs')
    if not pilot and len({run['generatedBlockStateSampleSha256'] for run in runs})!=1:raise ValueError('Original generated sample differs across independent paired boots')
    # Comparing unlike inputs is refused even if the generated sample happens to agree.
    comparable=['plugins','javaVersion','generationMode','worldSnapshot','plan']
    first=runs[0]
    if any(any(run[key]!=first[key] for key in comparable) for run in runs[1:]):raise ValueError('Mixed plugin/JVM/generation/template/workload inputs cannot be compared')
    for core in cores:
        same=[run for run in runs if run['core']==core]
        if len({run['coreSha256'] for run in same})!=1:raise ValueError('Kernel artifact changed across paired boots')
    return {index:pairs[index] for index in sorted(pairs)}


def paired_comparisons(pairs):
    cores=set(next(iter(pairs.values())))
    contrasts=[(core,'Paper') for core in sorted(cores-{'Paper'})]
    if {'MCC-Core','Purpur'}<=cores:contrasts.append(('MCC-Core','Purpur'))
    output={}
    for left,right in contrasts:
        output[left+'-minus-'+right]={phase:{metric:b.paired_bootstrap([pair[left]['phases'][phase][metric]-pair[right]['phases'][phase][metric] for pair in pairs.values()]) for metric in METRICS if all(pair[left]['phases'][phase][metric] is not None and pair[right]['phases'][phase][metric] is not None for pair in pairs.values())} for phase in sorted(PHASES)}
    return output


def checked_csv(text):
    import io
    rows=csv.DictReader(io.StringIO(text))
    if rows.fieldnames!=COLUMNS:raise ValueError('Unexpected raw columns; refusing generic publication')
    output=[]
    for row in rows:
        if set(row)!=set(COLUMNS) or row['phase'] not in PHASES:raise ValueError('Unexpected tick row')
        if len(output)>=120_000:raise ValueError('Tick export size bound exceeded')
        clean={'phase':row['phase']}
        for column in COLUMNS[1:]:
            value=row[column]
            if not re.fullmatch(r'-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?',value or '') or not math.isfinite(float(value)):raise ValueError('Only finite numeric tick fields may be exported')
            clean[column]=value
        output.append(clean)
    if not output:raise ValueError('Empty tick data')
    return output


def scalar_tree(value):
    """Analysis outputs have no strings except known bootstrap scope/reason literals."""
    if value is None or isinstance(value,(bool,int)):return value
    if isinstance(value,float) and math.isfinite(value):return value
    if isinstance(value,dict):return {key:scalar_tree(item) for key,item in value.items()}
    raise ValueError('Unexpected nonnumeric summary value')


def export(session_file,private_root,name):
    private=b.regular(private_root.absolute(),True);session_file=b.regular(session_file.absolute())
    if private.name!='.private' or session_file.parent.parent!=private or not session_file.parent.name.startswith('session-'):raise ValueError('Only an explicit owned session under .private is accepted')
    raw=json.loads(session_file.read_bytes())
    if raw.get('scope')!='disposable-mcc-core-benchmark-session' or raw.get('passed') is not True or raw.get('allOwnedFixturesNormallyClosed') is not True:raise ValueError('Failed/unfinished sessions cannot become comparative reports')
    if type(raw.get('pilot')) is not bool or raw.get('comparativeVerdict')!=('pilot-only' if raw['pilot'] else 'bounded-workload-measurements-only'):raise ValueError('Session measurement mode mismatch')
    if raw.get('jvmFlags')!=b.FLAGS:raise ValueError('Session JVM flags differ from the reviewed protocol')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}',name):raise ValueError('Use a simple new result name')
    target=b.BASE/'results'/name
    if target.exists():raise FileExistsError('Results are create-only')
    runs=[];csv_rows=[];boot_ids=set();fixture_roots=set()
    for entry in raw['runs']:
        root=b.regular(Path(entry['fixture']).absolute(),True)
        if root.parent!=private:raise ValueError('Run escaped explicitly selected private root')
        path_identity=os.path.normcase(str(root))
        if entry['runId'] in boot_ids or path_identity in fixture_roots:raise ValueError('Reused run identity or resolved fixture cannot count as another independent boot')
        boot_ids.add(entry['runId']);fixture_roots.add(path_identity)
        marker_file=b.regular(root/'MCC-BENCHMARK-FIXTURE.json');closure_file=b.regular(root/'closure.json')
        marker=json.loads(marker_file.read_bytes());closure=json.loads(closure_file.read_bytes())
        if marker.get('scope')!='disposable-mcc-core-benchmark' or marker['runId']!=entry['runId'] or closure['runId']!=entry['runId'] or closure['sessionId']!=raw['sessionId'] or not closure.get('passed'):raise ValueError('Run marker/closure mismatch')
        for key in ['scope','sessionId','core','coreSha256','plugins','generationMode','worldSnapshot','jvmFlags','mode']:
            if marker.get(key)!=entry.get(key) or closure.get(key)!=entry.get(key):raise ValueError('Marker/closure/entry provenance differs')
        if entry.get('scope')!='disposable-mcc-core-benchmark' or entry.get('mode')!='measure' or entry.get('jvmFlags')!=b.FLAGS:raise ValueError('Unexpected fixture execution scope/flags')
        if closure.get('measurement')!=entry.get('measurement') or closure.get('ticks')!=entry.get('ticks'):raise ValueError('Closure/result/CSV receipt differs from session')
        b.validate_closed(closure)
        measurement=root/'plugins/MCCBenchmark/result.json';ticks=root/'plugins/MCCBenchmark/ticks.csv'
        row={**entry,'measurement':{'file':str(measurement),'sha256':entry['measurement']['sha256']},'ticks':{'file':str(ticks),'sha256':entry['ticks']['sha256']}}
        summary=b.summarize(row);data=json.loads(measurement.read_bytes())
        if data.get('passed') is not True or data.get('errors')!=[]:raise ValueError('Raw workload assertions failed')
        if summary['core'] not in b.CORES or not isinstance(entry.get('repetition'),int) or not 0<=entry['repetition']<=9 or set(summary['phases'])!=PHASES:raise ValueError('Unexpected kernel/repetition/phase set')
        if data['runId']!=entry['runId'] or data['candidate']!=entry['core'] or data['minecraftVersion']!='26.3' or data.get('mode')!='measure' or data.get('scope')!='disposable-mcc-core-benchmark':raise ValueError('Raw measurement identity/version mismatch')
        if not re.fullmatch(r'[0-9a-f]{64}',summary['generatedBlockStateSampleSha256'] or ''):raise ValueError('Missing unmodified terrain digest')
        server,java=checked_versions(entry['core'],data['serverVersion'],data['javaVersion'])
        plan=workload_plan(b.regular(root/'plugins/MCCBenchmark/plan.properties').read_text(encoding='utf-8'),entry['core'],entry['runId'])
        expected_generation='controlled-worker1-io1-synchronous-row-order' if plan['generationMode']=='controlled' else 'parallel-default-worker-policy-async-queue4'
        if entry['generationMode']!=expected_generation:raise ValueError('Fixture generation mode and workload plan differ')
        clean={'core':summary['core'],'repetition':entry['repetition'],'runId':entry['runId'],
               'coreSha256':entry['coreSha256'],'plugins':entry['plugins'],
               'serverVersion':server,'javaVersion':java,'plan':plan,'worldSnapshot':entry['worldSnapshot'],
               'generatedBlockStateSampleSha256':summary['generatedBlockStateSampleSha256'],
               'generationMode':entry['generationMode'],'behaviorAssertionsPassed':True,
               'normalClosureExitCode':0,'phases':scalar_tree(summary['phases'])}
        for digest in [clean['coreSha256'],*clean['plugins'].values()]:
            if not re.fullmatch(r'[0-9a-f]{64}',digest):raise ValueError('Artifact hash malformed')
        if set(clean['plugins'])!={'MinecraftControlCenter','MCCBenchmark'}:raise ValueError('Unexpected plugin set')
        if clean['plugins']['MinecraftControlCenter']!=raw.get('MccJarSha256') or clean['plugins']['MCCBenchmark']!=raw.get('plugin',{}).get('pluginSha256'):raise ValueError('Session frozen plugin pins differ from copied fixtures')
        expected_pin=b.FOUNDATION_SHA if entry['core']=='MCC-Core' else b.CORES[entry['core']]['sha256']
        if clean['coreSha256']!=expected_pin:raise ValueError('Kernel release pin differs from the reviewed candidate')
        if clean['generationMode'] not in ['controlled-worker1-io1-synchronous-row-order','parallel-default-worker-policy-async-queue4']:raise ValueError('Unexpected generation policy')
        uuid_pattern=r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}'
        if not re.fullmatch(uuid_pattern,clean['runId']):raise ValueError('Unexpected run identifier')
        snapshot=clean['worldSnapshot']
        if not isinstance(snapshot,dict) or set(snapshot)!={'fixtureRunId','snapshotManifestSha256'} or not re.fullmatch(uuid_pattern,snapshot['fixtureRunId']) or not re.fullmatch(r'[0-9a-f]{64}',snapshot['snapshotManifestSha256']):raise ValueError('Unexpected template provenance')
        runs.append(clean);csv_rows.append(checked_csv(ticks.read_text(encoding='utf-8')))
    if not 1<=len(runs)<=30:raise ValueError('Unexpected run count')
    pairs=grouped_pairs(runs,raw['pilot']);comparisons=paired_comparisons(pairs)
    result={'schema':1,'scope':'sanitized-bounded-workload-measurements','sourceSessionSha256':b.sha(session_file),
            'pilot':bool(raw['pilot']),'minecraftVersion':'26.3','jvmFlags':b.FLAGS,
            'runs':runs,'comparisons':comparisons,'rawCsvFiles':[],
            'limits':['Whole-run paired exploratory bootstrap; short runs and multiple metrics do not establish overall superiority.',
                      'Allocation covers Minecraft main thread; heap covers the JVM, not just MCC.',
                      'Terrain equality is an original blockstate sample, not proof of complete world or vanilla equivalence.',
                      'Chunk workloads contain 128 bounded admissions; no real player sending or production saturation is measured.',
                      'Background machine activity, thermal state and OS cache may affect results.']}
    target.parent.mkdir(exist_ok=True);b.regular(target.parent,True);target.mkdir()
    for run,rows in zip(runs,csv_rows):
        file=target/(f"run-{run['repetition']}-{run['core']}.csv")
        with file.open('x',encoding='utf-8',newline='') as stream:
            writer=csv.DictWriter(stream,fieldnames=COLUMNS,lineterminator='\n');writer.writeheader();writer.writerows(rows)
        result['rawCsvFiles'].append({'file':file.name,'sha256':b.sha(file)})
    b.write_new(target/'summary.json',result)
    return {'output':str(target),'summarySha256':b.sha(target/'summary.json'),'runs':len(runs),'sourceUnchanged':True}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--session',required=True);parser.add_argument('--source-private-root',required=True);parser.add_argument('--name',required=True);args=parser.parse_args()
    print(json.dumps(export(Path(args.session),Path(args.source_private_root),args.name)))


if __name__=='__main__':main()
