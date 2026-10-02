package com.stacktrial.evidence;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the text output of {@code mvn dependency:tree -DoutputFile=...}.
 *
 * <pre>
 * com.example:demo:jar:0.0.1-SNAPSHOT                       (root project, skipped)
 * +- org.springframework.boot:spring-boot-starter-web:jar:3.2.12:compile
 * |  \- com.fasterxml.jackson.core:jackson-databind:jar:2.15.4:compile
 * \- org.junit.jupiter:junit-jupiter:jar:tests:5.10.5:test   (with classifier)
 * </pre>
 */
@Component
public class DependencyTreeParser {

    public List<Dependency> parse(String treeText) {
        if (treeText == null || treeText.isBlank()) {
            return List.of();
        }
        Map<String, Dependency> byKey = new LinkedHashMap<>();
        String[] lines = treeText.split("\\R");
        boolean first = true;
        for (String raw : lines) {
            String line = stripTreeDecoration(raw);
            if (line.isEmpty()) {
                continue;
            }
            if (first) { // the root line is the project itself
                first = false;
                continue;
            }
            Dependency dep = parseCoordinates(line);
            if (dep != null) {
                byKey.putIfAbsent(dep.key(), dep);
            }
        }
        List<Dependency> result = new ArrayList<>(byKey.values());
        result.sort(Comparator.comparing(Dependency::key));
        return result;
    }

    static String stripTreeDecoration(String raw) {
        int i = 0;
        while (i < raw.length() && " |+-\\".indexOf(raw.charAt(i)) >= 0) {
            i++;
        }
        String rest = raw.substring(i).trim();
        int annotation = rest.indexOf(" (");   // e.g. "(version managed from ...)" or "(optional)"
        return annotation > 0 ? rest.substring(0, annotation) : rest;
    }

    /** groupId:artifactId:type[:classifier]:version[:scope] */
    static Dependency parseCoordinates(String coords) {
        String[] p = coords.split(":");
        return switch (p.length) {
            case 4 -> new Dependency(p[0], p[1], p[3], null);
            case 5 -> new Dependency(p[0], p[1], p[3], p[4]);
            case 6 -> new Dependency(p[0], p[1], p[4], p[5]);
            default -> null;
        };
    }
}
