"""Strict saved-region integrity tests using new temporary files only.

No Minecraft JVM, existing world, normalization or performance verdict is used.
"""
import gzip
from pathlib import Path
import struct
import tempfile
import unittest
import zlib
import terrain_diff as d


def nbt_root():
    key=b'answer'
    return b'\x0a\x00\x00\x03'+struct.pack('>H',len(key))+key+struct.pack('>i',42)+b'\x00'


class RegionIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory(prefix='mcc-region-integrity-')
        self.root=Path(self.directory.name)
        self.region=self.root/'bench_mechanics/dimensions/minecraft/bench_terrain/region/r.16.16.mca'
        self.region.parent.mkdir(parents=True)

    def tearDown(self):
        self.directory.cleanup()

    def write_region(self,payload,codec=2,*,external=False,declared_length=None,padding=True):
        encoded=b'' if external else payload
        record=struct.pack('>I',len(encoded)+1 if declared_length is None else declared_length)+bytes([codec|(128 if external else 0)])+encoded
        sectors=(len(record)+4095)//4096
        header=bytearray(8192)
        header[:4]=(2).to_bytes(3,'big')+bytes([sectors])
        # Nonzero sector padding is legitimate and outside the region length.
        if padding:record+=b'\xab'*(-len(record)%4096)
        self.region.write_bytes(header+record)
        if external:(self.region.parent/'c.512.512.mcc').write_bytes(payload)

    def parse(self):
        return d.chunk(self.root,512,512)

    def test_complete_zlib_gzip_and_raw_nbt_accept_sector_padding(self):
        for codec,encode in [(1,gzip.compress),(2,zlib.compress),(3,lambda value:value)]:
            with self.subTest(codec=codec):
                self.write_region(encode(nbt_root()),codec)
                self.assertEqual(self.parse(),{'answer':42})

    def test_complete_external_chunk_accepts_single_member(self):
        self.write_region(zlib.compress(nbt_root()),external=True)
        self.assertEqual(self.parse(),{'answer':42})

    def test_truncated_compression_footer_rejected_after_full_nbt(self):
        for codec,encode,footer in [(1,gzip.compress,8),(2,zlib.compress,4)]:
            for missing in range(1,footer+1):
                with self.subTest(codec=codec,missing=missing):
                    self.write_region(encode(nbt_root())[:-missing],codec)
                    with self.assertRaises(ValueError):self.parse()

    def test_bad_checksum_and_invalid_stream_rejected(self):
        for codec,encode in [(1,gzip.compress),(2,zlib.compress)]:
            value=bytearray(encode(nbt_root()));value[-1]^=1
            with self.subTest(codec=codec):
                self.write_region(value,codec)
                with self.assertRaises(ValueError):self.parse()
        self.write_region(b'invalid compressed bytes')
        with self.assertRaises(ValueError):self.parse()

    def test_trailing_compressed_bytes_and_second_member_rejected(self):
        for codec,encode in [(1,gzip.compress),(2,zlib.compress)]:
            for suffix in [b'\x00',encode(nbt_root())]:
                with self.subTest(codec=codec,suffix_length=len(suffix)):
                    self.write_region(encode(nbt_root())+suffix,codec)
                    with self.assertRaises(ValueError):self.parse()

    def test_nbt_suffix_rejected_for_all_region_codecs(self):
        for codec,encode in [(1,gzip.compress),(2,zlib.compress),(3,lambda value:value)]:
            with self.subTest(codec=codec):
                self.write_region(encode(nbt_root()+b'\x00'),codec)
                with self.assertRaises(ValueError):self.parse()
        with self.assertRaises(ValueError):d.Nbt(nbt_root()+b'\x00').root()

    def test_declared_compressed_length_cannot_read_past_file(self):
        value=zlib.compress(nbt_root())
        self.write_region(value,declared_length=len(value)+2,padding=False)
        with self.assertRaises(ValueError):self.parse()

    def test_expansion_budget_preserved(self):
        self.write_region(zlib.compress(b'\x00'*(d.LIMIT+1)))
        with self.assertRaises(ValueError):self.parse()

    def test_external_payload_budget_is_bounded(self):
        self.write_region(b'\x00'*(d.LIMIT+1),external=True)
        with self.assertRaises(ValueError):self.parse()


if __name__=='__main__':unittest.main()
