package com.stacktrial.workspace;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only detection of Maven / Java / Spring Boot facts from the root pom.xml. */
@Component
public class PomInspector {

    public ProjectInfo inspect(Path workspace) {
        Path pom = workspace.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new UnsupportedProjectException(
                    "No pom.xml at the repository root. StackTrial V1 supports Maven projects only.");
        }
        Element project;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setNamespaceAware(false);
            Document doc = f.newDocumentBuilder().parse(pom.toFile());
            project = doc.getDocumentElement();
        } catch (Exception e) {
            throw new UnsupportedProjectException("pom.xml could not be parsed: " + e.getMessage());
        }

        Element properties = child(project, "properties");
        Element parent = child(project, "parent");

        String groupId = text(child(project, "groupId"));
        if (groupId == null && parent != null) {
            groupId = text(child(parent, "groupId"));
        }
        String artifactId = text(child(project, "artifactId"));

        String bootVersion = null;
        if (parent != null && "spring-boot-starter-parent".equals(text(child(parent, "artifactId")))) {
            bootVersion = text(child(parent, "version"));
        }
        if (bootVersion == null) {
            bootVersion = text(child(properties, "spring-boot.version"));
        }

        String java = firstNonNull(
                text(child(properties, "java.version")),
                text(child(properties, "maven.compiler.release")),
                text(child(properties, "maven.compiler.target")),
                text(child(properties, "maven.compiler.source")));

        return new ProjectInfo(groupId, artifactId, parseJavaMajor(java), bootVersion);
    }

    static Integer parseJavaMajor(String value) {
        if (value == null || value.isBlank() || value.contains("${")) {
            return null;
        }
        String v = value.strip();
        if (v.startsWith("1.")) {
            v = v.substring(2);
        }
        int dot = v.indexOf('.');
        if (dot > 0) {
            v = v.substring(0, dot);
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element e) {
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.strip();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
