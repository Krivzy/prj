"""Pure parser tests; no native processes and no world file mutation."""
import struct, unittest
import terrain_diff as d

def name(text):
    value=text.encode('utf-8');return struct.pack('>H',len(value))+value

class TerrainParserTests(unittest.TestCase):
    def test_minimal_compound(self):
        raw=b'\x0a'+name('')+b'\x08'+name('label')+name('fixture')+b'\x00'
        self.assertEqual(d.Nbt(raw).root(),{'label':'fixture'})

    def test_duplicate_compound_key_rejected(self):
        raw=b'\x0a'+name('')+(b'\x08'+name('label')+name('fixture'))*2+b'\x00'
        with self.assertRaises(ValueError):d.Nbt(raw).root()

    def test_truncated_nbt_and_large_collection_rejected(self):
        with self.assertRaises(ValueError):d.Nbt(b'\x0a').root()
        with self.assertRaises(ValueError):d.Nbt(struct.pack('>i',1_000_001)).length()

    def test_legacy_and_26_3_mixed_palette_encodings(self):
        self.assertEqual(d.state('minecraft:stone'),'minecraft:stone')
        self.assertEqual(d.state({'':'minecraft:stone'}),'minecraft:stone')
        self.assertEqual(d.state({'Name':'minecraft:stone'}),'minecraft:stone')
        self.assertEqual(d.state({'id':'minecraft:oak_leaves','properties':{'persistent':'false','distance':'1'}}),'minecraft:oak_leaves[distance=1,persistent=false]')

    def test_single_palette_section_and_light_only_section(self):
        result=d.sections({'sections':[{'Y':-5},{'Y':-4,'block_states':{'palette':['minecraft:air']}}]})
        self.assertEqual(result,{-4:['minecraft:air']*4096})

    def test_padded_long_array_five_bit_boundary(self):
        palette=['minecraft:test_'+str(number) for number in range(32)];words=[0]*342
        for index in range(4096):words[index//12]|=(index%32)<<((index%12)*5)
        result=d.sections({'sections':[{'Y':0,'block_states':{'palette':palette,'data':words}}]})[0]
        for index in [0,11,12,31,32,1000,4095]:self.assertEqual(result[index],palette[index%32])

    def test_category_uses_actual_ore_suffix(self):
        self.assertEqual(d.category('minecraft:diamond_ore'),'ore')
        self.assertEqual(d.category('minecraft:spore_blossom'),'other')
        self.assertEqual(d.category('minecraft:water[level=2]'),'fluid')
        self.assertEqual(d.category('minecraft:oak_leaves[distance=1]'),'leaves')

if __name__=='__main__':unittest.main()
