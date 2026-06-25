package org.gigameter.jmeter.ai.service.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElementTypeAliasTest {

    @Test
    void mapsNaturalNamesToManagerKeys() {
        assertEquals("responseassert", ElementTypeAlias.normalize("responseassertion"));
        assertEquals("jsonpathextractor", ElementTypeAlias.normalize("jsonextractor"));
        assertEquals("jsonpathextractor", ElementTypeAlias.normalize("JSONPostProcessor"));
        assertEquals("httpsampler", ElementTypeAlias.normalize("HTTP Request"));
        assertEquals("httpdefaults", ElementTypeAlias.normalize("http_defaults"));
    }

    @Test
    void normalisesCaseAndSeparators() {
        assertEquals("threadgroup", ElementTypeAlias.normalize("Thread Group"));
        assertEquals("constanttimer", ElementTypeAlias.normalize("constant-timer"));
        assertEquals("jsr223sampler", ElementTypeAlias.normalize("JSR223_Sampler"));
    }

    @Test
    void unknownTokenReturnedNormalised() {
        assertEquals("somethingweird", ElementTypeAlias.normalize("Something Weird"));
    }

    @Test
    void nullSafe() {
        assertEquals("", ElementTypeAlias.normalize(null));
    }

    @Test
    void blazeMeterPluginNames() {
        assertEquals("concurrencythreadgroup", ElementTypeAlias.normalize("Concurrency Thread Group"));
        assertEquals("steppingthreadgroup", ElementTypeAlias.normalize("Stepping Thread Group"));
        assertEquals("throughputshapingtimer", ElementTypeAlias.normalize("Throughput Shaping Timer"));
        assertEquals("throughputshapingtimer", ElementTypeAlias.normalize("VariableThroughputTimer"));
        assertEquals("perfmoncollector", ElementTypeAlias.normalize("PerfMon Metrics Collector"));
        assertEquals("dummysampler", ElementTypeAlias.normalize("Dummy Sampler"));
    }
}
