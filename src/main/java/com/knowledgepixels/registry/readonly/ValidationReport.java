package com.knowledgepixels.registry.readonly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@link Validate} found: counts of what was checked and every failure.
 */
public class ValidationReport {

    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    public void count(String what) {
        counts.merge(what, 1L, Long::sum);
    }

    public void fail(String message) {
        failures.add(message);
    }

    public boolean ok() {
        return failures.isEmpty();
    }

    public List<String> failures() {
        return failures;
    }

    public Map<String, Long> counts() {
        return counts;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        counts.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        if (failures.isEmpty()) {
            sb.append("OK: no problems found\n");
        } else {
            sb.append("FAILED: ").append(failures.size()).append(" problem(s)\n");
            for (String f : failures) {
                sb.append("  - ").append(f).append('\n');
            }
        }
        return sb.toString();
    }

}
