package org.gigameter.jmeter.ai.service.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonRepairTest {

    @Test
    void balancedJsonUnchanged() {
        String j = "[{\"op\":\"remove_element\",\"id\":2}]";
        assertEquals(j, JsonRepair.closeTruncated(j));
    }

    @Test
    void appendsMissingArrayClose() {
        // The real truncation observed: missing the final outer ']'
        String truncated = "[{\"op\":\"add_element\",\"parentId\":1,\"children\":[{\"elementType\":\"httpsampler\"}]}";
        assertEquals(truncated + "]", JsonRepair.closeTruncated(truncated));
    }

    @Test
    void appendsMultipleClosers() {
        assertEquals("{\"a\":[1,2]}", JsonRepair.closeTruncated("{\"a\":[1,2"));
    }

    @Test
    void closesUnterminatedStringThenBrackets() {
        assertEquals("[{\"name\":\"abc\"}]", JsonRepair.closeTruncated("[{\"name\":\"abc"));
    }

    @Test
    void dropsDanglingComma() {
        assertEquals("[{\"a\":1}]", JsonRepair.closeTruncated("[{\"a\":1},"));
    }

    @Test
    void ignoresBracketsInsideStrings() {
        String j = "[{\"body\":\"{[not real]}\"}]";
        assertEquals(j, JsonRepair.closeTruncated(j));
    }
}
