package org.gigameter.jmeter.ai.intellisense;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CommandIntellisenseProvider. The advertised set is the CLI-first commands:
 * @plan, @lint, @optimize, @rollback (legacy @this/@wrap/@usage/@code are no longer surfaced).
 */
public class CommandIntellisenseProviderTest {

    @Test
    public void testGetSuggestionsWithExactMatch() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("@plan");

        assertEquals(1, suggestions.size());
        assertEquals("@plan", suggestions.get(0));
    }

    @Test
    public void testGetSuggestionsWithPartialMatch() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("@l");

        assertTrue(suggestions.contains("@lint"));
        // Should not contain commands that don't start with @l
        assertFalse(suggestions.contains("@plan"));
    }

    @Test
    public void testGetSuggestionsWithNoMatch() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("@xyz");

        assertTrue(suggestions.isEmpty());
    }

    @Test
    public void testGetSuggestionsWithAtSymbolOnly() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("@");

        // Should return all advertised CLI-first commands
        assertTrue(suggestions.contains("@plan"));
        assertTrue(suggestions.contains("@lint"));
        assertTrue(suggestions.contains("@optimize"));
        assertTrue(suggestions.contains("@rollback"));
        // Legacy commands are no longer surfaced
        assertFalse(suggestions.contains("@usage"));
        assertFalse(suggestions.contains("@code"));
    }
}
