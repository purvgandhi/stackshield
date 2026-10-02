package com.stacktrial.workspace.change;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, formatting-preserving edits to a pom.xml. It replaces exact text inside well-known blocks instead of
 * re-serialising the XML, so the resulting git diff shows only the intended lines.
 */
final class PomEditor {

    private static final Pattern VERSION_VALUE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._\\-+]{0,63}$");
    private static final Pattern PROPERTY_REF = Pattern.compile("^\\$\\{([A-Za-z0-9._\\-]+)}$");

    private final Path pom;
    private String xml;

    PomEditor(Path workspace) {
        this.pom = workspace.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new ChangeApplicationException("No pom.xml at the repository root");
        }
        try {
            this.xml = Files.readString(pom, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void requireVersion(String version) {
        if (version == null || !VERSION_VALUE.matcher(version).matches()) {
            throw new ChangeApplicationException("Invalid version: '" + version + "'");
        }
    }

    /** @return the previous version, or null when the pom has no spring-boot-starter-parent */
    String setSpringBootParentVersion(String version) {
        Matcher parent = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL).matcher(xml);
        if (!parent.find() || !hasTag(parent.group(1), "artifactId", "spring-boot-starter-parent")) {
            return null;
        }
        String block = parent.group(1);
        Matcher v = tagPattern("version").matcher(block);
        if (!v.find()) {
            throw new ChangeApplicationException("spring-boot-starter-parent has no <version>");
        }
        String old = v.group(1).strip();
        String newBlock = block.substring(0, v.start(1)) + version + block.substring(v.end(1));
        xml = xml.substring(0, parent.start(1)) + newBlock + xml.substring(parent.end(1));
        return old;
    }

    /** @return the previous value, or null when the property is not declared */
    String setProperty(String name, String value) {
        Matcher props = Pattern.compile("<properties>(.*?)</properties>", Pattern.DOTALL).matcher(xml);
        if (!props.find()) {
            return null;
        }
        String block = props.group(1);
        Matcher p = tagPattern(name).matcher(block);
        if (!p.find()) {
            return null;
        }
        String old = p.group(1).strip();
        String newBlock = block.substring(0, p.start(1)) + value + block.substring(p.end(1));
        xml = xml.substring(0, props.start(1)) + newBlock + xml.substring(props.end(1));
        return old;
    }

    /**
     * Sets the version of a declared dependency. A {@code ${property}} version updates the property; a literal
     * version is replaced; a dependency without a version (managed by a BOM) gets an explicit version.
     *
     * @return a description of the edit
     */
    String setDependencyVersion(String groupId, String artifactId, String version) {
        Matcher m = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(xml);
        List<int[]> matches = new ArrayList<>();
        while (m.find()) {
            String block = m.group(1);
            if (hasTag(block, "groupId", groupId) && hasTag(block, "artifactId", artifactId)) {
                matches.add(new int[]{m.start(1), m.end(1)});
            }
        }
        if (matches.isEmpty()) {
            throw new ChangeApplicationException("Dependency " + groupId + ":" + artifactId
                    + " is not declared in pom.xml. Use a git diff change for transitive or BOM-only dependencies.");
        }

        // Pass 1 (backwards, so earlier offsets stay valid): replace literal versions, remember property refs.
        List<String> edits = new ArrayList<>();
        Set<String> propertiesToUpdate = new LinkedHashSet<>();
        boolean anyVersioned = false;
        for (int i = matches.size() - 1; i >= 0; i--) {
            int[] range = matches.get(i);
            String block = xml.substring(range[0], range[1]);
            Matcher v = tagPattern("version").matcher(block);
            if (!v.find()) {
                continue;
            }
            anyVersioned = true;
            String current = v.group(1).strip();
            Matcher ref = PROPERTY_REF.matcher(current);
            if (ref.matches()) {
                propertiesToUpdate.add(ref.group(1));
            } else {
                xml = xml.substring(0, range[0]) + block.substring(0, v.start(1)) + version
                        + block.substring(v.end(1)) + xml.substring(range[1]);
                edits.add("version " + current + " -> " + version);
            }
        }
        // Pass 2: update referenced properties (offsets of dependency blocks are no longer needed).
        for (String property : propertiesToUpdate) {
            String old = setProperty(property, version);
            if (old == null) {
                throw new ChangeApplicationException("Property " + property + " is not declared in <properties>");
            }
            edits.add("property " + property + ": " + old + " -> " + version);
        }
        if (!anyVersioned) {
            int[] range = matches.get(0);
            String block = xml.substring(range[0], range[1]);
            Matcher a = Pattern.compile("(\\s*)<artifactId>\\s*" + Pattern.quote(artifactId) + "\\s*</artifactId>").matcher(block);
            if (!a.find()) {
                throw new ChangeApplicationException("Could not locate <artifactId> of " + artifactId);
            }
            String indent = a.group(1);
            String inserted = block.substring(0, a.end()) + indent + "<version>" + version + "</version>" + block.substring(a.end());
            xml = xml.substring(0, range[0]) + inserted + xml.substring(range[1]);
            edits.add("explicit version " + version + " added (was managed)");
        }
        return String.join("; ", edits);
    }

    void save() {
        try {
            Files.writeString(pom, xml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean hasTag(String block, String tag, String value) {
        Matcher m = tagPattern(tag).matcher(block);
        return m.find() && m.group(1).strip().equals(value);
    }

    private static Pattern tagPattern(String tag) {
        return Pattern.compile("<" + Pattern.quote(tag) + ">([^<]*)</" + Pattern.quote(tag) + ">");
    }
}
