package org.gigameter.jmeter.ai.service.ops;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the friendly {@code elementType} tokens used in the {@code jmeter-ops} protocol onto the
 * exact keys understood by {@code JMeterElementManager.addElement}. Lets agents say
 * {@code responseassertion} / {@code jsonextractor} (natural names) while the engine resolves them
 * to the manager's {@code responseassert} / {@code jsonpathextractor}.
 */
public final class ElementTypeAlias {

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        // Aliases where the natural name differs from the manager key.
        ALIASES.put("responseassertion", "responseassert");
        ALIASES.put("responseassert", "responseassert");
        ALIASES.put("jsonassertion", "jsonassertion");
        ALIASES.put("jsonpathassertion", "jsonassertion");
        ALIASES.put("jsonextractor", "jsonpathextractor");
        ALIASES.put("jsonpostprocessor", "jsonpathextractor");
        ALIASES.put("jsonpathextractor", "jsonpathextractor");
        ALIASES.put("regexextractor", "regexextractor");
        ALIASES.put("httprequest", "httpsampler");
        ALIASES.put("httpsampler", "httpsampler");
        ALIASES.put("csvdataconfig", "csvdataset");
        ALIASES.put("csvdataset", "csvdataset");
        ALIASES.put("httprequestdefaults", "httpdefaults");
        ALIASES.put("httpdefaults", "httpdefaults");
        // BlazeMeter / jmeter-plugins natural-name aliases (normalized forms already match the map
        // keys for most; these cover the remaining variants).
        ALIASES.put("variablethroughputtimer", "throughputshapingtimer");
        ALIASES.put("throughputtimer", "throughputshapingtimer");
        ALIASES.put("perfmonmetricscollector", "perfmoncollector");
        ALIASES.put("perfmon", "perfmoncollector");
        ALIASES.put("concurrencytg", "concurrencythreadgroup");
    }

    private ElementTypeAlias() {
    }

    /**
     * Normalises a raw token: lowercases, strips spaces/dashes/underscores, then applies known
     * aliases. Unknown tokens are returned in normalised form (the manager will reject them, which
     * surfaces as an op error the agent can correct).
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String key = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
        return ALIASES.getOrDefault(key, key);
    }
}
