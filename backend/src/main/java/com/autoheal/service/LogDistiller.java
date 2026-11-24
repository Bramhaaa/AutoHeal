package com.autoheal.service;

import com.autoheal.domain.DistilledLog;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LogDistiller {

  public static final int MAX_RAW_CHARS = 500 * 1024;

  private static final List<String> FRAMEWORK_FRAME_PREFIXES =
      List.of(
          "org.springframework.",
          "java.base/",
          "java.",
          "javax.",
          "jakarta.",
          "jdk.internal.",
          "org.junit.",
          "sun.",
          "com.sun.",
          "org.apache.maven.",
          "org.hibernate.",
          "org.mockito.");

  private static final Pattern STACK_FRAME = Pattern.compile("^\\s*at\\s+(.+)$");
  private static final Pattern JAVA_FILE_IN_FRAME =
      Pattern.compile("\\(([A-Za-z0-9_$]+\\.java):\\d+\\)");
  private static final Pattern COMPILER_ERROR_FILE =
      Pattern.compile("([A-Za-z0-9_$]+\\.java):\\[\\d+,\\d+]");
  private static final Pattern FAILING_TEST_HEADER =
      Pattern.compile(
          "^\\[ERROR]\\s+([A-Za-z0-9_$.]+)\\s+--\\s+Time elapsed:.*<<<\\s+(?:FAILURE|ERROR)!");

  public DistilledLog distill(String rawLog) {
    if (rawLog == null || rawLog.isEmpty()) {
      return new DistilledLog("", List.of(), List.of(), 0, 0);
    }

    String bounded = rawLog.length() > MAX_RAW_CHARS ? rawLog.substring(0, MAX_RAW_CHARS) : rawLog;

    List<String> lines = bounded.lines().toList();
    List<String> kept = new ArrayList<>();
    Set<String> failingTests = new LinkedHashSet<>();
    Set<String> referencedFiles = new LinkedHashSet<>();

    int omittedFrameworkFrames = 0;
    String lastKeptFrame = null;
    int repeatedFrameCount = 0;
    boolean inCompilerError = false;

    for (String line : lines) {
      String trimmed = line.trim();

      Matcher frameMatcher = STACK_FRAME.matcher(line);
      if (frameMatcher.matches()) {
        String frameBody = frameMatcher.group(1);
        if (isFrameworkFrame(frameBody)) {
          flushRepeatedFrame(kept, repeatedFrameCount);
          repeatedFrameCount = 0;
          lastKeptFrame = null;
          omittedFrameworkFrames++;
          continue;
        }
        omittedFrameworkFrames = flushOmittedFrames(kept, omittedFrameworkFrames);
        extractJavaFile(JAVA_FILE_IN_FRAME, frameBody, referencedFiles);

        if (trimmed.equals(lastKeptFrame)) {
          repeatedFrameCount++;
        } else {
          flushRepeatedFrame(kept, repeatedFrameCount);
          repeatedFrameCount = 0;
          lastKeptFrame = trimmed;
          kept.add(line);
        }
        continue;
      }

      omittedFrameworkFrames = flushOmittedFrames(kept, omittedFrameworkFrames);
      flushRepeatedFrame(kept, repeatedFrameCount);
      repeatedFrameCount = 0;
      lastKeptFrame = null;

      if (trimmed.isEmpty() || isNoiseLine(trimmed)) {
        if (trimmed.startsWith("[INFO]")) {
          inCompilerError = false;
        }
        continue;
      }

      Matcher failingHeader = FAILING_TEST_HEADER.matcher(trimmed);
      if (failingHeader.find()) {
        failingTests.add(failingHeader.group(1));
      }

      if (trimmed.contains("COMPILATION ERROR")) {
        inCompilerError = true;
      }
      if (extractJavaFile(COMPILER_ERROR_FILE, trimmed, referencedFiles)) {
        inCompilerError = true;
      }

      if (inCompilerError || isSignalLine(trimmed)) {
        kept.add(line);
      }
    }

    flushOmittedFrames(kept, omittedFrameworkFrames);
    flushRepeatedFrame(kept, repeatedFrameCount);

    String summary = String.join("\n", kept);
    return new DistilledLog(
        summary,
        List.copyOf(failingTests),
        List.copyOf(referencedFiles),
        bounded.length(),
        summary.length());
  }

  private boolean isFrameworkFrame(String frameBody) {
    for (String prefix : FRAMEWORK_FRAME_PREFIXES) {
      if (frameBody.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private boolean isNoiseLine(String trimmed) {
    if (trimmed.startsWith("[INFO]")) {
      return true;
    }
    if (trimmed.startsWith("Downloading")
        || trimmed.startsWith("Downloaded")
        || trimmed.startsWith("Progress (")) {
      return true;
    }
    return trimmed.startsWith(":: Spring Boot ::") || trimmed.startsWith("WARNING: A Java agent");
  }

  private boolean isSignalLine(String trimmed) {
    return trimmed.startsWith("[ERROR]")
        || trimmed.startsWith("Caused by:")
        || trimmed.startsWith("Suppressed:")
        || trimmed.contains("Exception")
        || trimmed.contains("Error")
        || trimmed.startsWith("Expecting")
        || trimmed.startsWith("expected:")
        || trimmed.startsWith("but was:")
        || trimmed.startsWith("symbol:")
        || trimmed.startsWith("location:");
  }

  private int flushOmittedFrames(List<String> kept, int omittedCount) {
    if (omittedCount > 0) {
      kept.add("\t... (" + omittedCount + " framework frames omitted)");
    }
    return 0;
  }

  private void flushRepeatedFrame(List<String> kept, int repeatedCount) {
    if (repeatedCount > 0) {
      kept.add("\t... (repeated " + repeatedCount + " more times)");
    }
  }

  private boolean extractJavaFile(Pattern pattern, String text, Set<String> out) {
    Matcher matcher = pattern.matcher(text);
    boolean found = false;
    while (matcher.find()) {
      out.add(matcher.group(1));
      found = true;
    }
    return found;
  }
}
