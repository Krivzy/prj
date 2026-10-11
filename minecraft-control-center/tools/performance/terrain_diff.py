"""Read-only coordinate diagnosis of normally closed OWN benchmark world snapshots."""
import argparse, collections, gzip, hashlib, io, json, math, struct, zlib
from pathlib import Path
import benchmark as b

LIMIT=16*1024*1024

class Nbt:
    def __init__(self,data):
        if len(data)>LIMIT:raise ValueError('NBT size bound exceeded')
        self.stream=io.BytesIO(data)
    def take(self,n):
        value=self.stream.read(n)
        if n<0 or len(value)!=n:raise ValueError('Truncated NBT')
        return value
    def number(self,kind):return struct.unpack('>'+kind,self.take(struct.calcsize('>'+kind)))[0]
    def name(self):return self.take(self.number('H')).decode('utf-8')
    def length(self):
        value=self.number('i')
        if not 0<=value<=1_000_000:raise ValueError('NBT collection bound exceeded')
        return value
    def value(self,tag,depth=0):
        if depth>64:raise ValueError('NBT depth bound exceeded')
        if tag in [1,2,3,4,5,6]:return self.number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[tag])
        if tag==7:return self.take(self.length())
        if tag==8:return self.name()
        if tag==9:
            element=self.number('B');length=self.length();return [self.value(element,depth+1) for _ in range(length)]
        if tag==10:
            result={}
            while True:
                kind=self.number('B')
                if kind==0:return result
                name=self.name()
                if name in result:raise ValueError('Duplicate compound key')
                result[name]=self.value(kind,depth+1)
        if tag in [11,12]:
            length=self.length();kind='i' if tag==11 else 'q';return [self.number(kind) for _ in range(length)]
        raise ValueError('Unsupported NBT tag '+str(tag))
    def root(self):
        kind=self.number('B')
        if kind!=10:raise ValueError('Compound NBT root required')
        self.name();result=self.value(kind)
        if self.stream.read(1):raise ValueError('Trailing NBT payload')
        return result

def chunk(root,x,z):
    region=root/'bench_mechanics/dimensions/minecraft/bench_terrain/region'/f'r.{x//32}.{z//32}.mca';b.regular(region)
    with region.open('rb') as stream:
        stream.seek(4*((x%32)+32*(z%32)));pointer=stream.read(4)
        if len(pointer)!=4 or pointer==b'\0\0\0\0':raise ValueError('Expected saved chunk absent')
        offset=int.from_bytes(pointer[:3],'big');stream.seek(offset*4096);length=int.from_bytes(stream.read(4),'big');kind=stream.read(1)[0]
        if not 1<=length<=LIMIT:raise ValueError('Compressed chunk size outside bounds')
        if kind&128:
            external=region.parent/f'c.{x}.{z}.mcc';b.regular(external)
            with external.open('rb') as source:data=source.read(LIMIT+1)
            if len(data)>LIMIT:raise ValueError('External compressed chunk size outside bounds')
        else:
            data=stream.read(length-1)
            if len(data)!=length-1:raise ValueError('Truncated compressed chunk')
    codec=kind&127
    if codec in [1,2]:
        decoder=zlib.decompressobj(31 if codec==1 else 15)
        try:data=decoder.decompress(data,LIMIT+1)
        except zlib.error as error:raise ValueError('Malformed chunk compression') from error
        if decoder.unconsumed_tail or len(data)>LIMIT:raise ValueError('NBT expansion bound exceeded')
        # Region length already excludes sector padding. Require one complete
        # compressed member, including its checksum/footer, before parsing NBT.
        if not decoder.eof or decoder.unused_data:raise ValueError('Incomplete or trailing compressed chunk')
    elif codec!=3:raise ValueError('Unsupported region compression '+str(codec))
    return Nbt(data).root()

def state(entry):
    if isinstance(entry,str):return entry
    name=entry.get('Name',entry.get('id',entry.get('')));properties=entry.get('Properties',entry.get('properties',{}))
    if not isinstance(name,str):raise ValueError('Unsupported palette entry')
    return name+('['+','.join(key+'='+value for key,value in sorted(properties.items()))+']' if properties else '')

def sections(data):
    result={}
    for section in data['sections']:
        if 'block_states' not in section:continue
        y=section['Y'];states=section['block_states'];palette=[state(item) for item in states['palette']];values=states.get('data',[])
        if len(palette)==1:indices=[0]*4096
        else:
            bits=max(4,(len(palette)-1).bit_length());per=64//bits;mask=(1<<bits)-1
            indices=[((values[index//per]&((1<<64)-1))>>((index%per)*bits))&mask for index in range(4096)]
        if any(index>=len(palette) for index in indices):raise ValueError('Palette index outside bounds')
        result[y]=[palette[index] for index in indices]
    return result

def category(name):
    bare=name.split('[',1)[0]
    if bare.endswith('_ore') or bare in ['minecraft:ancient_debris','minecraft:raw_copper_block','minecraft:raw_iron_block']:return 'ore'
    if bare in ['minecraft:water','minecraft:lava'] or 'waterlogged=true' in name:return 'fluid'
    if 'leaves' in bare:return 'leaves'
    if bare in ['minecraft:stone','minecraft:deepslate','minecraft:granite','minecraft:andesite','minecraft:diorite','minecraft:tuff','minecraft:calcite','minecraft:bedrock']:return 'stone'
    return 'other'

def own_closed(path):
    root=b.regular(path,True)
    if root.parent!=b.BASE/'.private' or not (root/'MCC-BENCHMARK-FIXTURE.json').exists():raise ValueError('Only owned disposable fixture roots are allowed')
    receipt=json.loads((root/'closure.json').read_bytes());b.validate_closed(receipt);return root,receipt

def compare(left,right,limit=2000):
    counts=collections.Counter();pairs=collections.Counter();sample=[];chunks=[];checked=0;sampled=0;biome=0
    for index in range(128):
        cx=512+index%16;cz=512+index//16;a=chunk(left,cx,cz);c=chunk(right,cx,cz);aa=sections(a);cc=sections(c);different=0
        for sy in sorted(set(aa)|set(cc)):
            av=aa.get(sy,['minecraft:air']*4096);cv=cc.get(sy,['minecraft:air']*4096)
            for pos,(before,after) in enumerate(zip(av,cv)):
                checked+=1
                if before==after:continue
                x=pos%16;y=sy*16+(pos//256);z=(pos//16)%16;different+=1;in_sample=x%4==0 and z%4==0;sampled+=int(in_sample)
                label=category(before)+' -> '+category(after);counts[label]+=1;pairs[(before,after)]+=1
                if len(sample)<limit:sample.append({'x':cx*16+x,'y':y,'z':cz*16+z,'left':before,'right':after,'includedInOriginalDigest':in_sample})
        ap={row['Y']:row.get('biomes') for row in a['sections']};cp={row['Y']:row.get('biomes') for row in c['sections']}
        if ap!=cp:biome+=1
        if different:chunks.append({'chunkX':cx,'chunkZ':cz,'changedBlocks':different})
    return {'schema':1,'scope':'coordinate-diagnosis-only-no-normalized-verdict','allBlocksCompared':checked,'differentBlocks':sum(counts.values()),'originalDigestSampleDifferentBlocks':sampled,'changedChunks':chunks,'biomeNbtDifferentChunks':biome,'categories':dict(counts),'statePairs':[{'left':key[0],'right':key[1],'count':value} for key,value in pairs.most_common()],'coordinateDifferences':sample,'coordinateSampleLimit':limit,'hashGuardUnchanged':True}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--left',required=True);parser.add_argument('--right',required=True);parser.add_argument('--output',required=True);args=parser.parse_args()
    left,lc=own_closed(Path(args.left).absolute());right,rc=own_closed(Path(args.right).absolute());output=Path(args.output).absolute()
    if output.parent!=b.BASE/'.private':raise ValueError('Output must be a new file in performance/.private')
    result=compare(left,right);result.update({'leftRunId':lc['runId'],'rightRunId':rc['runId'],'leftCore':lc['core'],'rightCore':rc['core'],'leftCoreSha256':lc['coreSha256'],'rightCoreSha256':rc['coreSha256'],'leftWorldSnapshot':lc['worldSnapshot'],'rightWorldSnapshot':rc['worldSnapshot'],'productionTouched':False,'serverStarted':False})
    b.write_new(output,result);print(json.dumps({'output':str(output),'sha256':b.sha(output),'differentBlocks':result['differentBlocks'],'sampleDifferentBlocks':result['originalDigestSampleDifferentBlocks'],'categories':result['categories'],'biomeNbtDifferentChunks':result['biomeNbtDifferentChunks']}))

if __name__=='__main__':main()
