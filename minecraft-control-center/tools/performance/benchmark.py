"""Own new disposable server fixtures only. No Main path, credentials, force kill or hot reload."""
from __future__ import annotations
from pathlib import Path
import argparse, csv, hashlib, json, math, os, random, re, statistics, stat, subprocess, sys, time, uuid, zipfile
import fixture_safety as safety

BASE=Path(__file__).resolve().parent
JAVA_HOME=None
JAVA=None
CORES={
 'Paper':{'sha256':'185d9f4267afca48959d6a56150cf8803b6645aee10de3ed147df7ab1ff781fb','build':169,'ports':[18105,25605]},
 'Purpur':{'sha256':'e8003c8a9967d5111cf222fd9b83ced6f34eb24e90d7ba7a9da886dd71fb746f','build':2647,'ports':[18106,25606]},
 'MCC-Core':{'ports':[18107,25607]},
}
FLAGS=['-Xms1024M','-Xmx1024M','-XX:+UseG1GC','-XX:+UnlockDiagnosticVMOptions','-XX:-G1UseTimeBasedHeapSizing','-Djava.awt.headless=true','-Xlog:gc*:file=gc.log:time,uptime,level,tags']
API_PINS={
 'io/papermc/paper/paper-api/26.3.build.169-beta/paper-api-26.3.build.169-beta.jar':'94ab589248e2fac75a2e83fe1feb9af22bbe99bd9baaa1c801328e54538b4213',
 'net/kyori/adventure-key/5.2.0/adventure-key-5.2.0.jar':'0184d173200e2eef8fbc791f622d1d58fd459f8930c616b5a4fe79e83eda6c55',
 'net/kyori/adventure-api/5.2.0/adventure-api-5.2.0.jar':'7e52fe7190be3e87b3b3f71712cfa12315fcd27109f302a7b440c12f01fae827',
 'com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar':'dc573e1fca4fd5454f4a5fd3d7da2df03002876a4175bafc14a95980dd7713b3',
}
ANNOTATIONS=None
ANNOTATIONS_SHA='ebc7aec252ed0c7d2d04c039d7f00e69f7b86b1f493c741d67b3ef31b986b054'
SEED=6381247891023
UPSTREAM_COMMIT='46d4834e8e2ec4af9dcfa028076fb16dfb8a3911'
FOUNDATION_SHA='86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223'
PATCHSET_SHA='3d42ace4d8f3a14bb7765cb173da0bcd06aa7a6533bd33e4e8892a50d0ca3c6f'

def canonical(path):
    text=str(path)
    if text.startswith('\\\\?\\UNC\\'):text='\\\\'+text[8:]
    elif text.startswith('\\\\?\\'):text=text[4:]
    return Path(text)

def extended(path):
    value=str(canonical(path).absolute())
    return ('\\\\?\\UNC\\'+value[2:] if value.startswith('\\\\') else '\\\\?\\'+value) if os.name=='nt' else value

def regular(path,directory=False):
    path=canonical(path).absolute();s=os.lstat(extended(path))
    if stat.S_ISLNK(s.st_mode) or getattr(s,'st_file_attributes',0)&0x400:raise RuntimeError('Linked/reparse path rejected')
    if not (stat.S_ISDIR(s.st_mode) if directory else stat.S_ISREG(s.st_mode)):raise RuntimeError('Unexpected path kind')
    if os.path.normcase(str(canonical(os.path.realpath(extended(path)))))!=os.path.normcase(str(path)):raise RuntimeError('Unresolved path rejected')
    return path

def sha(path):
    regular(path);result=hashlib.sha256()
    with open(extended(path),'rb') as stream:
        for block in iter(lambda:stream.read(262144),b''):result.update(block)
    return result.hexdigest()

def walk(root):
    regular(root,True)
    def fail(error):raise error
    for folder,dirs,files in os.walk(extended(root),onerror=fail):
        folder=canonical(folder);regular(folder,True)
        for name in dirs+files:regular(folder/name,name in dirs)
        yield folder,dirs,files

def ps(script):
    return subprocess.check_output(['powershell.exe','-NoProfile','-NonInteractive','-Command',"$ErrorActionPreference='Stop';"+script],text=True,creationflags=subprocess.CREATE_NO_WINDOW).strip()

def listeners():
    ports=','.join(str(port) for row in CORES.values() for port in row['ports'])
    return json.loads(ps('$r=@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object {$_.LocalPort -in @('+ports+')}|Select-Object LocalAddress,LocalPort,OwningProcess);ConvertTo-Json -InputObject $r -Compress') or '[]')

def processes(pid):
    assert isinstance(pid,int) and pid>0
    return json.loads(ps('$r=@(Get-CimInstance Win32_Process -Filter "ProcessId='+str(pid)+'" -ErrorAction Stop|Select-Object ProcessId,ParentProcessId,CreationDate,ExecutablePath,CommandLine);ConvertTo-Json -InputObject $r -Compress') or '[]')

def write_new(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    with open(extended(path),'x',encoding='utf-8',newline='\n') as stream:json.dump(value,stream,ensure_ascii=False,indent=2);stream.write('\n')

def copy_file(source,destination,inventory,root):
    before=sha(source);destination.parent.mkdir(parents=True,exist_ok=True)
    with open(extended(source),'rb') as inp,open(extended(destination),'xb') as out:
        for block in iter(lambda:inp.read(262144),b''):out.write(block)
    if sha(source)!=before or sha(destination)!=before:raise RuntimeError('Copy changed during transfer')
    inventory.append({'file':destination.relative_to(root).as_posix(),'sha256':before,'bytes':os.stat(extended(destination)).st_size})

def supervisor_module():
    # These helpers are self-contained; no production supervisor or credentials are loaded.
    return safety

def cache_lease(core,lease):
    # Serializes this harness's own readers, not external servers. Only explicitly
    # confirmed offline cache snapshots may be supplied; files are hash-checked.
    private=BASE/'.private';private.mkdir(exist_ok=True);lease.restrict(private,True)
    pin=hashlib.sha256(str(CORES[core]['cache']).encode('utf-8')).hexdigest()
    return lease.OfflineLease(private/('cache-read-'+pin+'.lock'))

def source(core):
    row=CORES[core]
    if not row.get('offlineConfirmed'):raise RuntimeError('Explicit offline cache confirmation is required')
    root=regular(row['cache'],True)
    if sha(row['jar'])!=row['sha256']:raise RuntimeError('Trusted core source pin failed')
    for folder in ['libraries','versions','cache']:regular(root/folder,True)
    return root

def build_plugin(lease):
    paper=source('Paper');private=BASE/'.private';private.mkdir(exist_ok=True);lease.restrict(private,True)
    output=private/('plugin-build-'+uuid.uuid4().hex);output.mkdir();lease.restrict(output,True)
    with_source=cache_lease('Paper',lease)
    try:
        source('Paper');classpath=[]
        for relative,pinned in API_PINS.items():
            jar=paper/'libraries'/relative
            if sha(jar)!=pinned:raise RuntimeError('Paper API compile dependency changed')
            classpath.append(str(jar))
        if sha(ANNOTATIONS)!=ANNOTATIONS_SHA:raise RuntimeError('Annotations compile dependency changed')
        classpath.append(str(ANNOTATIONS))
        compiled=subprocess.run([str(JAVA_HOME/'bin/javac.exe'),'-encoding','UTF-8','-cp',os.pathsep.join(classpath),'-d',str(output/'classes'),str(BASE/'src/MccBenchmark.java')],capture_output=True,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
        (output/'compile.log').write_text(compiled.stdout+compiled.stderr,encoding='utf-8')
        if compiled.returncode:raise RuntimeError('Benchmark compilation failed; see compile.log')
        plugin=output/'MCCBenchmark.jar'
        with zipfile.ZipFile(plugin,'x',compression=zipfile.ZIP_DEFLATED) as archive:
            def put(name,data):
                entry=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(entry,data)
            for item in sorted((output/'classes').rglob('*.class')):put(item.relative_to(output/'classes').as_posix(),item.read_bytes())
            put('plugin.yml','name: MCCBenchmark\nversion: 0.1.0\nmain: fixture.benchmark.MccBenchmark\napi-version: "26.3"\nsoftdepend: [MinecraftControlCenter]\n')
        receipt={'passed':True,'plugin':str(plugin),'pluginSha256':sha(plugin),'javaSourceSha256':sha(BASE/'src/MccBenchmark.java'),'compileDependencyPins':API_PINS,'annotationsSha256':ANNOTATIONS_SHA,'javaExecutableSha256':sha(JAVA),'productionTouched':False,'serverStarted':False}
        write_new(output/'build.json',receipt);return receipt
    finally:with_source.close()

def frozen_mcc(args):
    jar=regular(Path(args.mcc_jar).absolute())
    if not re.fullmatch(r'[0-9a-f]{64}',args.mcc_sha256 or '') or sha(jar)!=args.mcc_sha256:raise RuntimeError('Explicit frozen MCC JAR pin failed')
    return {'jar':str(jar),'jarSha256':args.mcc_sha256,'source':'explicit-reviewed-frozen-artifact'}

def properties(port):
    return '\n'.join(['server-ip=127.0.0.1','server-port='+str(port),'online-mode=false','white-list=true','enforce-whitelist=true','enable-rcon=false','enable-query=false','motd=MCC disposable benchmark','level-name=bench_mechanics','level-seed='+str(SEED),'level-type=minecraft:flat','view-distance=8','simulation-distance=8','max-players=0','max-tick-time=60000','pause-when-empty-seconds=-1','sync-chunk-writes=true','allow-nether=false','spawn-protection=0'])+'\n'

COMMON_SPIGOT='''config-version: 12
settings:
  restart-on-crash: false
world-settings:
  default:
    entity-activation-range:
      animals: 0
      monsters: 0
      raiders: 0
      misc: 0
      water: 0
      villagers: 0
      flying-monsters: 0
      villagers-work-immunity-after: 100
      villagers-work-immunity-for: 20
      villagers-active-for-panic: true
    ticks-per:
      hopper-transfer: 8
      hopper-check: 1
    hopper-amount: 1
    hopper-can-load-chunks: false
'''

def create_fixture(core,plugin,build,lease,session,mode='measure',template=None,phase_ticks=2400,warmup_ticks=1200,candidate=None,controlled_generation=False):
    private=BASE/'.private';root=private/(core.lower()+'-'+uuid.uuid4().hex[:12]);root.mkdir();lease.restrict(root,True)
    info=CORES[core];upstream=source('Paper' if core=='MCC-Core' else core);inventory=[]
    retain=cache_lease('Paper' if core=='MCC-Core' else core,lease)
    try:
        source('Paper' if core=='MCC-Core' else core)
        artifact=Path(candidate['jar']) if core=='MCC-Core' else CORES[core]['jar']
        pinned=candidate['sha256'] if core=='MCC-Core' else info['sha256']
        if sha(artifact)!=pinned:raise RuntimeError('Core artifact changed')
        copy_file(artifact,root/'core.jar',inventory,root)
        for folder in ['libraries','versions','cache']:
            for parent,_,files in walk(upstream/folder):
                for name in files:copy_file(parent/name,root/folder/parent.relative_to(upstream/folder)/name,inventory,root)
        copy_file(Path(plugin['plugin']),root/'plugins/MCCBenchmark.jar',inventory,root)
        copy_file(Path(build['jar']),root/'plugins/MinecraftControlCenter.jar',inventory,root)
        if template:
            validate_closed(template)
            for folder in ['bench_mechanics']:
                for parent,dirs,files in walk(Path(template['fixture'])/folder):
                    dirs[:]=[name for name in dirs if name not in {'players','playerdata','advancements','stats'}]
                    for name in files:
                        if name!='session.lock':copy_file(parent/name,root/folder/parent.relative_to(Path(template['fixture'])/folder)/name,inventory,root)
        if sha(artifact)!=pinned:raise RuntimeError('Core source changed during copy')
    finally:retain.close()
    for name,text in [('eula.txt','eula=true\n'),('server.properties',properties(info['ports'][1])),('spigot.yml',COMMON_SPIGOT),('bukkit.yml','settings:\n  allow-end: false\n'),('plugins/MinecraftControlCenter/config.yml','web:\n  host: 127.0.0.1\n  port: '+str(info['ports'][0])+'\n  allow-lan: false\n  public-origin: ""\nperformance:\n  profile: eco\nmodules:\n'+''.join('  '+key+': '+('true' if key=='metrics' else 'false')+'\n' for key in ['chat','events','automations','map','oreMonitor','integrations','metrics','playerSnapshots','entitySnapshots','timeline','catalog','console']))]:
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8',newline='\n')
    if controlled_generation:
        (root/'config').mkdir(exist_ok=True);(root/'config/paper-global.yml').write_text('chunk-system:\n  worker-threads: 1\n  io-threads: 1\n',encoding='utf-8',newline='\n')
    run=str(uuid.uuid4());plan=root/'plugins/MCCBenchmark/plan.properties';plan.parent.mkdir();plan.write_text(f'run.id={run}\nmode={mode}\ncandidate={core}\nphase.ticks={phase_ticks}\nwarmup.ticks={warmup_ticks}\ngeneration.mode='+('controlled' if controlled_generation else 'parallel')+'\n',encoding='utf-8')
    receipt={'schema':1,'scope':'disposable-mcc-core-benchmark','runId':run,'fixture':str(root),'core':core,'coreSha256':pinned,'minecraftVersion':'26.3','ports':info['ports'],'plugins':{'MCCBenchmark':plugin['pluginSha256'],'MinecraftControlCenter':build['jarSha256']},'mode':mode,'copiedFiles':inventory,'worldSnapshot':None if not template else {'fixtureRunId':template['runId'],'snapshotManifestSha256':template['snapshotManifestSha256']},'productionTouched':False,'serverStarted':False,'sessionId':session,'jvmFlags':FLAGS,'sourceOfflineLeaseRetainedDuringCopy':False,'offlineCacheSnapshotExplicitlyConfirmed':True,'ownHarnessCacheReaderLeaseRetained':True}
    receipt['generationMode']='controlled-worker1-io1-synchronous-row-order' if controlled_generation else 'parallel-default-worker-policy-async-queue4'
    write_new(root/'MCC-BENCHMARK-FIXTURE.json',receipt);return receipt

def attest(child,fixture,identity=None):
    root=Path(fixture['fixture']);rows=processes(child.pid)
    if child.poll() is not None:raise RuntimeError('Owned JVM exited early')
    if len(rows)!=1:raise RuntimeError('Owned JVM identity is unavailable')
    row=rows[0];argv=row['CommandLine']
    if not argv or str(root) not in argv or '-Dmcc.benchmark.run-id='+fixture['runId'] not in argv or 'mcc.benchmark.disposable=true' not in argv or row['ParentProcessId']!=os.getpid() or os.path.normcase(str(row['ExecutablePath']))!=os.path.normcase(str(JAVA)):raise RuntimeError('Owned JVM identity mismatch')
    if identity and row['CreationDate']!=identity['CreationDate']:raise RuntimeError('Owned JVM PID was reused')
    return row

def validate_closed(row):
    if not row.get('closed') or row.get('exitCode')!=0 or not row.get('normalStopRequested'):raise RuntimeError('Fixture is not normally closed')
    if any(value['CreationDate']==row['processCreationDate'] for value in processes(row['javaPid'])):raise RuntimeError('Fixture JVM still exists; do not copy its world')
    if any(value['LocalPort'] in row['ports'] for value in listeners()):raise RuntimeError('Fixture ports remain open')

def snapshot_manifest(root):
    rows=[]
    for folder in ['bench_mechanics']:
        for parent,dirs,files in walk(root/folder):
            dirs[:]=[name for name in dirs if name not in {'players','playerdata','advancements','stats'}]
            for name in files:
                if name!='session.lock':rows.append({'file':(parent/name).relative_to(root).as_posix(),'sha256':sha(parent/name)})
    data=json.dumps(rows,sort_keys=True,separators=(',',':')).encode();return rows,hashlib.sha256(data).hexdigest()

def run_fixture(fixture,lease,timeout=1800):
    root=Path(fixture['fixture']);regular(root,True)
    if root.parent!=BASE/'.private' or not root.name.startswith(fixture['core'].lower()+'-'):raise RuntimeError('Fixture scope escaped')
    if listeners():raise RuntimeError('Benchmark ports already in use; no process was started')
    if sha(root/'core.jar')!=fixture['coreSha256']:raise RuntimeError('Fixture core changed')
    retained=lease.OfflineLease(root/'benchmark.lock');child=None;identity=None;normal=False;stdout=None;failure=None
    row={**fixture,'serverStarted':False,'normalStopRequested':False,'closed':False,'passed':False,'forceKilled':False,'processCreationDate':None}
    try:
        command=[str(JAVA),*FLAGS,'-Dmcc.benchmark.disposable=true','-Dmcc.benchmark.fixture='+str(root),'-Dmcc.benchmark.run-id='+fixture['runId'],'-jar','core.jar','--nogui']
        stdout=open(extended(root/'process.log'),'xb');child=subprocess.Popen(command,cwd=extended(root),stdin=subprocess.PIPE,stdout=stdout,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        row.update({'javaPid':child.pid,'serverStarted':True,'command':command});started=time.monotonic();result=root/'plugins/MCCBenchmark/result.json'
        identity=attest(child,fixture);row['processCreationDate']=identity['CreationDate']
        while time.monotonic()-started<timeout:
            if child.poll() is not None:raise RuntimeError('Owned fixture exited before a complete benchmark result')
            if result.exists():
                regular(result);value=json.loads(result.read_bytes());
                if value['runId']!=fixture['runId'] or value['candidate']!=fixture['core'] or value['scope']!=fixture['scope'] or value['minecraftVersion']!='26.3':raise RuntimeError('Benchmark result identity/version mismatch')
                open_ports=[value for value in listeners() if value['LocalPort'] in fixture['ports']]
                if len(open_ports)!=2 or any(value['OwningProcess']!=child.pid or value['LocalAddress']!='127.0.0.1' for value in open_ports):raise RuntimeError('Benchmark listener ownership mismatch')
                row.update({'measurementPassed':value['passed'],'measurement':{'file':str(result),'sha256':sha(result)},'ticks':{'file':str(result.with_name('ticks.csv')),'sha256':sha(result.with_name('ticks.csv'))},'elapsedSeconds':time.monotonic()-started});break
            # The loop only polls the retained child handle and one output file. CIM/network discovery stays outside measurement.
            time.sleep(1)
        else:raise RuntimeError('Benchmark deadline reached; only normal stop will be attempted')
    except BaseException as error:failure=error
    finally:
        try:
            if child is not None and child.poll() is None:
                # Retained object identity and creation date are checked before sending the sole allowed command.
                attest(child,fixture,identity);child.stdin.write(b'stop\n');child.stdin.flush();normal=True;child.wait(timeout=180)
        except BaseException as error:
            failure=failure or error;row['closureError']=type(error).__name__
        finally:
            if stdout:stdout.close()
            retained.close()
    row.update({'normalStopRequested':normal,'exitCode':None if child is None else child.returncode,'closed':child is not None and child.returncode==0})
    if failure is None:
        try:
            validate_closed(row)
            if row['mode']=='template':manifest,pin=snapshot_manifest(root);write_new(root/'world-snapshot.json',manifest);row['snapshotManifestSha256']=pin
            row['passed']=row['measurementPassed'] and row['closed']
        except BaseException as error:failure=error
    if failure is not None:row['error']={'type':type(failure).__name__,'message':str(failure)}
    write_new(root/('closure.json' if row['closed'] else 'closure-pending.json'),row)
    if failure is not None:raise RuntimeError('Benchmark failed; inspected closure receipt is '+str(root/('closure.json' if row['closed'] else 'closure-pending.json'))) from failure
    return row

def percentile(values,q):
    values=sorted(values)
    if not values or not 0<=q<=1 or any(not math.isfinite(value) for value in values):raise ValueError('Invalid percentile input')
    index=(len(values)-1)*q;low=int(index);return values[low]+(values[min(low+1,len(values)-1)]-values[low])*(index-low)

def summarize(row):
    if sha(Path(row['measurement']['file']))!=row['measurement']['sha256'] or sha(Path(row['ticks']['file']))!=row['ticks']['sha256']:raise RuntimeError('Raw measurement changed')
    raw=json.loads(Path(row['measurement']['file']).read_bytes());samples={}
    if not raw.get('passed') or not raw.get('phases'):raise RuntimeError('A failed or empty workload cannot produce a performance summary')
    with open(row['ticks']['file'],encoding='utf-8',newline='') as stream:
        for item in csv.DictReader(stream):samples.setdefault(item['phase'],[]).append(item)
    output={}
    for phase in raw['phases']:
        records=samples[phase['name']];durations=[float(value['mspt']) for value in records]
        if len(records)!=phase['ticks'] or len(records)<20:raise RuntimeError('Insufficient or mismatched phase samples')
        allocation=[int(value['main_thread_allocated_delta_bytes']) for value in records];memory=[int(value['heap_used_bytes']) for value in records]
        total=None if min(allocation)<0 else sum(allocation)
        output[phase['name']]={'ticks':len(records),'msptP50':percentile(durations,.5),'msptP95':percentile(durations,.95),'msptP99':percentile(durations,.99),'maxMspt':max(durations),'meanMspt':statistics.fmean(durations),'mainThreadAllocatedBytes':total,'mainThreadAllocatedBytesPerTick':None if total is None else total/len(records),'sampledHeapUsedMedian':percentile(memory,.5),'sampledHeapUsedMax':max(memory),'wallSeconds':phase['wallNanos']/1e9,'gcCollections':phase['gcCollections'],'gcMillis':phase['gcMillis'],'chunkThroughputPerSecond':phase['completedChunks']/(phase['wallNanos']/1e9) if phase['completedChunks'] else None}
    return {'core':row['core'],'runId':row['runId'],'phases':output,'generatedBlockStateSampleSha256':raw.get('generatedBlockStateSampleSha256')}

def paired_bootstrap(differences,seed=20261011,iterations=10_000):
    if iterations<1 or any(not math.isfinite(value) for value in differences):raise ValueError('Invalid bootstrap input')
    if len(differences)<3:return {'available':False,'reason':'At least 3 independent paired server boots are required; ticks are not independent replicates.'}
    rng=random.Random(seed);means=sorted(statistics.fmean(rng.choices(differences,k=len(differences))) for _ in range(iterations));return {'available':True,'pairedRuns':len(differences),'meanDifference':statistics.fmean(differences),'bootstrap95Low':percentile(means,.025),'bootstrap95High':percentile(means,.975),'iterations':iterations,'scope':'Exploratory paired bootstrap over whole-run summaries; no multiple-comparison correction and not proof of overall performance.'}

def measure_session(args,plugin,build,lease,session,output,result,candidate):
    fixture=create_fixture('Paper',plugin,build,lease,session,mode='template',phase_ticks=args.phase_seconds*20,warmup_ticks=args.warmup_seconds*20,controlled_generation=args.controlled_generation);template=run_fixture(fixture,lease);result['template']=template
    if not template['passed']:raise RuntimeError('Fresh snapshot preparation failed')
    rng=random.Random(20261011);cores=['Paper','Purpur']+(['MCC-Core'] if candidate else []);pairs=[]
    for repetition in range(args.runs):
        order=cores.copy();rng.shuffle(order);pair={}
        for core in order:
            fixture=create_fixture(core,plugin,build,lease,session,template=template,phase_ticks=args.phase_seconds*20,warmup_ticks=args.warmup_seconds*20,candidate=candidate,controlled_generation=args.controlled_generation);row=run_fixture(fixture,lease);row['repetition']=repetition
            if row['passed']:row['summary']=summarize(row)
            result['runs'].append(row);write_new(output/f'run-{repetition}-{core}.json',row)
            if not row['passed']:raise RuntimeError('A workload behavior/closure check failed; no comparison will be made')
            pair[core]=row['summary']
        hashes={row['generatedBlockStateSampleSha256'] for row in pair.values()}
        if len(hashes)!=1:raise RuntimeError('Generated terrain state sample differs between kernels; no performance verdict allowed')
        pairs.append(pair)
    comparisons={}
    for other in cores[1:]:
        per_phase={}
        for phase in pairs[0]['Paper']['phases']:
            per_phase[phase]={metric:paired_bootstrap([pair[other]['phases'][phase][metric]-pair['Paper']['phases'][phase][metric] for pair in pairs]) for metric in ['msptP50','msptP95','msptP99','meanMspt','sampledHeapUsedMedian','wallSeconds','mainThreadAllocatedBytes','mainThreadAllocatedBytesPerTick'] if all(pair[other]['phases'][phase][metric] is not None and pair['Paper']['phases'][phase][metric] is not None for pair in pairs)}
        comparisons[other+'-minus-Paper']=per_phase
    result['comparisons']=comparisons;result['passed']=True;result['allOwnedFixturesNormallyClosed']=True;result['comparativeVerdict']='pilot-only' if args.pilot else 'bounded-workload-measurements-only'

def parser_for_cli():
    parser=argparse.ArgumentParser(description='Windows-only bounded Minecraft 26.3 benchmark; new private fixtures only.')
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--compile-only',action='store_true');mode.add_argument('--execute-disposable-benchmarks',action='store_true')
    parser.add_argument('--java-home',required=True,help='Reviewed JDK 25 directory, containing bin/java.exe and bin/javac.exe')
    parser.add_argument('--paper-jar',required=True,help='Exact official Paper 26.3 build 169 JAR')
    parser.add_argument('--paper-cache',required=True,help='Offline cache snapshot root with libraries/, versions/ and cache/ only read')
    parser.add_argument('--purpur-jar',help='Exact official Purpur 26.3 build 2647 JAR (required for execution)')
    parser.add_argument('--purpur-cache',help='Offline Purpur runtime cache snapshot root (required for execution)')
    parser.add_argument('--annotations-jar',required=True,help='JetBrains annotations 26.1.0, exact pin checked')
    parser.add_argument('--offline-caches-confirmed',action='store_true',help='Inputs are dedicated quiescent snapshots, not live production folders')
    parser.add_argument('--mcc-jar',help='Reviewed frozen MCC 0.10 plugin, same artifact for every kernel')
    parser.add_argument('--mcc-sha256',help='Exact SHA-256 of the frozen MCC artifact')
    parser.add_argument('--candidate-jar');parser.add_argument('--candidate-sha256');parser.add_argument('--candidate-source-commit')
    parser.add_argument('--accept-minecraft-eula',action='store_true',help='Explicitly accept the Minecraft EULA for newly created fixtures')
    parser.add_argument('--web-port-base',type=int,default=18105);parser.add_argument('--game-port-base',type=int,default=25605)
    parser.add_argument('--runs',type=int,default=5);parser.add_argument('--phase-seconds',type=int,default=120);parser.add_argument('--warmup-seconds',type=int,default=60)
    parser.add_argument('--pilot',action='store_true');parser.add_argument('--controlled-generation',action='store_true')
    return parser

def configure(args):
    global JAVA_HOME,JAVA,ANNOTATIONS
    if not args.offline_caches_confirmed:raise ValueError('Confirm dedicated offline cache snapshots explicitly')
    JAVA_HOME=regular(Path(args.java_home).absolute(),True);JAVA=regular(JAVA_HOME/'bin/java.exe');regular(JAVA_HOME/'bin/javac.exe')
    release=regular(JAVA_HOME/'release').read_text(encoding='utf-8')
    if not re.search(r'^JAVA_VERSION="25(?:[.\-+][^"]*)?"$',release,re.MULTILINE):raise ValueError('A reviewed JDK 25 is required')
    ANNOTATIONS=regular(Path(args.annotations_jar).absolute())
    if sha(ANNOTATIONS)!=ANNOTATIONS_SHA:raise ValueError('Annotations pin failed')
    for core,prefix in [('Paper','paper'),('Purpur','purpur')]:
        jar=getattr(args,prefix+'_jar');cache=getattr(args,prefix+'_cache')
        if jar and cache:
            CORES[core].update({'jar':regular(Path(jar).absolute()),'cache':regular(Path(cache).absolute(),True),'offlineConfirmed':True})
            source(core)
        elif core=='Paper' or args.execute_disposable_benchmarks:raise ValueError(core+' JAR and offline cache are required')
    web=args.web_port_base;game=args.game_port_base
    if not 1024<=web<=65533 or not 1024<=game<=65533 or set(range(web,web+3))&set(range(game,game+3)):raise ValueError('Ports must be distinct non-privileged bounded triples')
    for index,core in enumerate(CORES):CORES[core]['ports']=[web+index,game+index]
    if args.execute_disposable_benchmarks and (not args.accept_minecraft_eula or not args.mcc_jar or not args.mcc_sha256):raise ValueError('Execution requires EULA acceptance and an exact reviewed MCC JAR pin')

def main():
    parser=parser_for_cli();args=parser.parse_args()
    if os.name!='nt':raise RuntimeError('This reviewed fixture runner is Windows-only')
    if not 1<=args.runs<=10 or not 2<=args.phase_seconds<=300 or not 1<=args.warmup_seconds<=120:parser.error('Run bounds exceeded')
    if not args.pilot and (args.runs<3 or args.phase_seconds<60 or args.warmup_seconds<30):parser.error('Short runs must explicitly use --pilot; they cannot support a comparative verdict')
    try:configure(args)
    except ValueError as error:parser.error(str(error))
    lease=supervisor_module();plugin=build_plugin(lease)
    if args.compile_only:print(json.dumps(plugin));return
    build=frozen_mcc(args);candidate=None
    if args.candidate_jar:
        if not re.fullmatch(r'[0-9a-f]{64}',args.candidate_sha256 or '') or not re.fullmatch(r'[0-9a-f]{40}',args.candidate_source_commit or ''):parser.error('Candidate requires exact SHA-256 and full source commit')
        artifact=Path(args.candidate_jar).absolute()
        if args.candidate_sha256!=FOUNDATION_SHA or sha(artifact)!=args.candidate_sha256:raise RuntimeError('Candidate release pin failed')
        with zipfile.ZipFile(artifact) as archive:
            metadata=dict(line.split('=',1) for line in archive.read('META-INF/mcc-engine.properties').decode('utf-8').splitlines() if '=' in line)
        if metadata.get('upstream.minecraft')!='26.3' or metadata.get('upstream.commit')!=UPSTREAM_COMMIT or metadata.get('upstream.commit')!=args.candidate_source_commit or metadata.get('engine.version')!='0.2.0' or metadata.get('patchset.sha256')!=PATCHSET_SHA:raise RuntimeError('Candidate is not the pinned actual engine foundation')
        candidate={'jar':str(artifact),'sha256':args.candidate_sha256,'sourceCommit':args.candidate_source_commit,'notWrapperCandidate':True,'artifactScope':'Actual patched engine foundation; production CoreBridge pairing is outside this benchmark','engineMetadata':metadata}
    session=str(uuid.uuid4());output=BASE/'.private'/('session-'+session);output.mkdir();lease.restrict(output,True)
    result={'schema':1,'scope':'disposable-mcc-core-benchmark-session','sessionId':session,'passed':False,'productionTouched':False,'nativeFixturesTouched':False,'pilot':args.pilot,'comparativeVerdict':'not-established','plugin':plugin,'MccJarSha256':build['jarSha256'],'candidate':candidate,'jvmFlags':FLAGS,'runs':[],'exclusions':[{'core':'Pufferfish','reason':'Pinned Pufferfish 1.21.10 is a different Minecraft version; no fair 26.3 comparison is claimed.'}],'limitations':['Concurrent background servers/jobs, unrelated CPU, IO, thermal and OS cache interference must be reviewed before interpreting results.','Fixed artificial no-player workloads do not establish overall production TPS, real player chunk sending, all farms or superiority to another kernel.','Matched entity-activation-range=0 is deliberate for an artificial no-player AI profile; it is not a production optimization or an assertion about default vanilla configuration.','Heap sampling is every 20 server ticks. Allocation is exact Minecraft main-thread deltas, not complete async worker allocation.','Tick rows are autocorrelated. Statistical replicates are independent fresh server boots.']}
    try:measure_session(args,plugin,build,lease,session,output,result,candidate)
    except BaseException as error:
        result['error']={'type':type(error).__name__,'message':str(error)};write_new(output/'failed.json',result);raise
    write_new(output/'result.json',result)
    print(json.dumps({'passed':True,'result':str(output/'result.json'),'sha256':sha(output/'result.json'),'runs':len(result['runs']),'pilot':args.pilot,'comparativeVerdict':result['comparativeVerdict'],'productionTouched':False}))

if __name__=='__main__':main()
