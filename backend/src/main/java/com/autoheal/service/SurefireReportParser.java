package com.autoheal.service;

import com.autoheal.domain.TestCaseId;
import com.autoheal.domain.TestSuiteReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@Service
public class SurefireReportParser {

  private static final String SUREFIRE_RELATIVE_PATH = "target/surefire-reports";

  public TestSuiteReport parse(Path workspaceDir) {
    Path reportsDir = workspaceDir.resolve(SUREFIRE_RELATIVE_PATH);
    if (!Files.isDirectory(reportsDir)) {
      return TestSuiteReport.empty();
    }

    List<Path> reportFiles;
    try (Stream<Path> files = Files.list(reportsDir)) {
      reportFiles =
          files
              .filter(Files::isRegularFile)
              .filter(
                  p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("TEST-") && name.endsWith(".xml");
                  })
              .sorted()
              .toList();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to list Surefire reports in " + reportsDir, e);
    }

    int totalTests = 0;
    int failures = 0;
    int errors = 0;
    int skipped = 0;
    Set<TestCaseId> passing = new HashSet<>();
    Set<TestCaseId> failing = new HashSet<>();

    DocumentBuilder builder = createSecureDocumentBuilder();
    for (Path reportFile : reportFiles) {
      Document doc = parseFile(builder, reportFile);
      Element suite = doc.getDocumentElement();
      totalTests += parseIntAttr(suite, "tests");
      failures += parseIntAttr(suite, "failures");
      errors += parseIntAttr(suite, "errors");
      skipped += parseIntAttr(suite, "skipped");

      NodeList testCases = suite.getElementsByTagName("testcase");
      for (int i = 0; i < testCases.getLength(); i++) {
        Element tc = (Element) testCases.item(i);
        TestCaseId id = new TestCaseId(tc.getAttribute("classname"), tc.getAttribute("name"));
        boolean hasFailure = tc.getElementsByTagName("failure").getLength() > 0;
        boolean hasError = tc.getElementsByTagName("error").getLength() > 0;
        boolean isSkipped = tc.getElementsByTagName("skipped").getLength() > 0;

        if (hasFailure || hasError) {
          failing.add(id);
        } else if (!isSkipped) {
          passing.add(id);
        }
      }
    }

    return new TestSuiteReport(totalTests, failures, errors, skipped, passing, failing);
  }

  private static DocumentBuilder createSecureDocumentBuilder() {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("Failed to configure secure XML parser", e);
    }
  }

  private static Document parseFile(DocumentBuilder builder, Path file) {
    try {
      return builder.parse(file.toFile());
    } catch (SAXException | IOException e) {
      throw new IllegalStateException("Failed to parse Surefire report: " + file.getFileName(), e);
    }
  }

  private static int parseIntAttr(Element element, String attrName) {
    String raw = element.getAttribute(attrName);
    return raw.isEmpty() ? 0 : Integer.parseInt(raw);
  }
}
