package org.gigameter.jmeter.ai.service.cli;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArgsParserTest {

    @Test
    void emptyOrBlankYieldsNoTokens() {
        assertEquals(Collections.emptyList(), ArgsParser.parse(""));
        assertEquals(Collections.emptyList(), ArgsParser.parse("   "));
        assertEquals(Collections.emptyList(), ArgsParser.parse(null));
    }

    @Test
    void splitsOnWhitespace() {
        assertEquals(Arrays.asList("-a", "-b", "-c"), ArgsParser.parse("-a  -b\t-c"));
    }

    @Test
    void keepsDoubleQuotedValueWithSpaces() {
        assertEquals(Arrays.asList("--include-directories", "C:\\My Tests", "--flag"),
                ArgsParser.parse("--include-directories \"C:\\My Tests\" --flag"));
    }

    @Test
    void keepsSingleQuotedValueWithSpaces() {
        assertEquals(Arrays.asList("--name", "my agent"),
                ArgsParser.parse("--name 'my agent'"));
    }

    @Test
    void handlesAdjacentQuotedAndUnquoted() {
        assertEquals(Collections.singletonList("ab cd"),
                ArgsParser.parse("a\"b cd\""));
    }
}
