package org.gigameter.jmeter.ai.service;

import java.util.List;

public interface AiService {
    String generateResponse(List<String> conversation);
    String generateResponse(List<String> conversation, String model);
    String getName();
}
