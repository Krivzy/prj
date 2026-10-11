package dev.mcc.core.engine;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SuiteDisplayName("MCC Core engine behavior equivalence")
@SelectClasses({OreBitSetPoolTest.class, LongArrayWriterTest.class, PaletteStorageTest.class,
    net.minecraft.nbt.MccCompoundTagWriteTest.class, OreFeatureEquivalenceTest.class})
public class MccEngineTestSuite {
}
