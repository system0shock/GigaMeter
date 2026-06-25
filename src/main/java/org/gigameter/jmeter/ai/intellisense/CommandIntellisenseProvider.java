package org.gigameter.jmeter.ai.intellisense;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides intellisense/autocomplete for AI chat commands. Only the CLI-first commands are
 * advertised; legacy ones (@this/@wrap/@usage/@code) remain in code but are no longer surfaced.
 */
public class CommandIntellisenseProvider {
    private final List<String> commands;

    public CommandIntellisenseProvider() {
        commands = new ArrayList<>();
        commands.add("@plan");
        commands.add("@lint");
        commands.add("@optimize");
        commands.add("@rollback");

        // Add more commands here as needed
    }

    public List<String> getSuggestions(String prefix) {
        List<String> suggestions = new ArrayList<>();
        for (String cmd : commands) {
            if (cmd.startsWith(prefix)) {
                suggestions.add(cmd);
            }
        }
        return suggestions;
    }
}

