package com.stacktrial.evidence;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Parses Maven Surefire/Failsafe XML reports ({@code TEST-*.xml}).
 *
 * <pre>
 * &lt;testsuite&gt;
 *   &lt;testcase classname="com.acme.FooTest" name="works" time="0.012"/&gt;
 *   &lt;testcase ...&gt;&lt;failure message="expected 200" type="AssertionError"&gt;stack&lt;/failure&gt;&lt;/testcase&gt;
 *   &lt;testcase ...&gt;&lt;error .../&gt;&lt;/testcase&gt;      (exception thrown, not an assertion)
 *   &lt;testcase ...&gt;&lt;skipped/&gt;&lt;/testcase&gt;
 * &lt;/testsuite&gt;
 * </pre>
 *
 * The XML comes from untrusted builds, so external entities and DTDs are disabled (XXE protection).
 */
@Component
public class SurefireReportParser {

    private static final int MAX_STACK_TRACE_CHARS = 4000;

    public List<TestCaseResult> parseDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<TestCaseResult> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> reports = files
                    .filter(p -> p.getFileName().toString().startsWith("TEST-"))
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
            for (Path report : reports) {
                try (InputStream in = Files.newInputStream(report)) {
                    results.addAll(parse(in));
                }
            }
        } catch (IOException e) {
            throw new EvidenceParsingException("Could not read Surefire reports in " + dir, e);
        }
        results.sort(Comparator.comparing(TestCaseResult::key));
        return results;
    }

    public List<TestCaseResult> parse(InputStream xml) {
        try {
            Document doc = newSecureBuilder().parse(xml);
            NodeList cases = doc.getElementsByTagName("testcase");
            List<TestCaseResult> results = new ArrayList<>(cases.getLength());
            for (int i = 0; i < cases.getLength(); i++) {
                results.add(toResult((Element) cases.item(i)));
            }
            return results;
        } catch (Exception e) {
            throw new EvidenceParsingException("Malformed Surefire XML report", e);
        }
    }

    private TestCaseResult toResult(Element testCase) {
        String className = testCase.getAttribute("classname");
        String name = testCase.getAttribute("name");
        long durationMs = parseSeconds(testCase.getAttribute("time"));

        Element failure = firstChild(testCase, "failure");
        Element error = firstChild(testCase, "error");
        Element skipped = firstChild(testCase, "skipped");

        if (failure != null) {
            return failed(className, name, TestOutcome.FAILED, failure, durationMs);
        }
        if (error != null) {
            return failed(className, name, TestOutcome.ERROR, error, durationMs);
        }
        if (skipped != null) {
            return new TestCaseResult(className, name, TestOutcome.SKIPPED, null, null, null, durationMs);
        }
        return new TestCaseResult(className, name, TestOutcome.PASSED, null, null, null, durationMs);
    }

    private TestCaseResult failed(String className, String name, TestOutcome outcome, Element el, long durationMs) {
        String message = emptyToNull(el.getAttribute("message"));
        String type = emptyToNull(el.getAttribute("type"));
        String stack = emptyToNull(el.getTextContent() == null ? null : el.getTextContent().strip());
        if (stack != null && stack.length() > MAX_STACK_TRACE_CHARS) {
            stack = stack.substring(0, MAX_STACK_TRACE_CHARS) + "\n... (truncated)";
        }
        return new TestCaseResult(className, name, outcome, message, type, stack, durationMs);
    }

    private static Element firstChild(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                return e;
            }
        }
        return null;
    }

    private static long parseSeconds(String time) {
        if (time == null || time.isBlank()) {
            return 0;
        }
        try {
            return Math.round(Double.parseDouble(time.replace(",", "")) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static DocumentBuilder newSecureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }
}
