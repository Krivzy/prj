"""Read-only full coordinate differences for every closed Paper/Core/Purpur pair.

No JVM starts. Original live digests remain unchanged; fluid differences stay visible.
Full reports are new files in the explicitly selected private session directory.
"""
import argparse
import itertools
import json
from pathlib import Path
import benchmark as b
import terrain_diff as d


def diagnose(session,private):
    private=b.regular(private.absolute(),True);session=b.regular(session.absolute())
    if private.name!='.private' or session.parent.parent!=private or not session.parent.name.startswith('session-'):raise ValueError('Explicit owned private session required')
    raw=json.loads(session.read_bytes())
    if raw.get('scope')!='disposable-mcc-core-benchmark-session':raise ValueError('Unexpected session scope')
    pairs={}
    for entry in raw['runs']:
        root=b.regular(Path(entry['fixture']).absolute(),True)
        if root.parent!=private:raise ValueError('Fixture escaped the selected private root')
        marker=json.loads((root/'MCC-BENCHMARK-FIXTURE.json').read_bytes());closure=json.loads((root/'closure.json').read_bytes())
        if marker['scope']!='disposable-mcc-core-benchmark' or marker['runId']!=entry['runId'] or closure['runId']!=entry['runId'] or closure['sessionId']!=raw['sessionId']:raise ValueError('Fixture marker/closure identity mismatch')
        b.validate_closed(closure)
        if entry['core'] not in b.CORES or not isinstance(entry.get('repetition'),int) or not 0<=entry['repetition']<=9:raise ValueError('Unexpected core/repetition')
        pair=pairs.setdefault(entry['repetition'],{})
        if entry['core'] in pair:raise ValueError('Duplicate core in pair')
        pair[entry['core']]=(root,closure)
    reports=[]
    for repetition,pair in sorted(pairs.items()):
        # Paper first; Core/Purpur follows. Incomplete failed sessions can still
        # diagnose the completed pair, but do not become performance success.
        cores=[core for core in ['Paper','MCC-Core','Purpur'] if core in pair]
        for left,right in itertools.combinations(cores,2):
            lroot,lc=pair[left];rroot,rc=pair[right]
            result=d.compare(lroot,rroot)
            result.update({'repetition':repetition,'leftRunId':lc['runId'],'rightRunId':rc['runId'],
                           'leftCore':left,'rightCore':right,'leftCoreSha256':lc['coreSha256'],'rightCoreSha256':rc['coreSha256'],
                           'leftWorldSnapshot':lc['worldSnapshot'],'rightWorldSnapshot':rc['worldSnapshot'],
                           'productionTouched':False,'serverStarted':False,'snapshotStage':'after-normal-stop; not the earlier live sample'})
            target=session.parent/f'diff-{repetition}-{left}-{right}.json';b.write_new(target,result)
            reports.append({'file':target.name,'sha256':b.sha(target),'repetition':repetition,'leftCore':left,'rightCore':right,
                            'allBlocksCompared':result['allBlocksCompared'],'differentBlocks':result['differentBlocks'],
                            'originalDigestSampleDifferentBlocks':result['originalDigestSampleDifferentBlocks'],
                            'biomeNbtDifferentChunks':result['biomeNbtDifferentChunks'],'categories':result['categories'],
                            'hashGuardUnchanged':True})
    if not reports:raise ValueError('No completed comparable pair to diagnose')
    index={'schema':1,'scope':'full-poststop-coordinate-diagnostics','sourceSessionSha256':b.sha(session),
           'sourceSessionPassed':bool(raw.get('passed')),'reports':reports,'productionTouched':False,'serverStarted':False,
           'limits':['Full saved state after stop can differ from the earlier live sample; no fluids/properties are filtered.',
                     'Diagnostics do not override the original live digest or turn a failed performance session into success.']}
    output=session.parent/'coordinate-diagnostics.json';b.write_new(output,index)
    return {'output':str(output),'sha256':b.sha(output),'comparisons':len(reports),'productionTouched':False,'serverStarted':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--session',required=True);parser.add_argument('--source-private-root',required=True);args=parser.parse_args()
    print(json.dumps(diagnose(Path(args.session),Path(args.source_private_root))))


if __name__=='__main__':main()
