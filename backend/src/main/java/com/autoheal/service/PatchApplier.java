package com.autoheal.service;

import com.autoheal.domain.AppliedPatch;
import com.autoheal.domain.NewTestFile;
import com.autoheal.domain.PatchApplyException;
import com.autoheal.domain.PatchProposal;
import com.autoheal.domain.SearchReplaceEdit;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PatchApplier {

  private static final int DIFF_CONTEXT_LINES = 3;

  private final WorkspacePathResolver pathResolver;

  public PatchApplier(WorkspacePathResolver pathResolver) {
    this.pathResolver = Objects.requireNonNull(pathResolver, "pathResolver must not be null");
  }

  public AppliedPatch apply(Path workspaceRoot, PatchProposal proposal) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    Objects.requireNonNull(proposal, "proposal must not be null");

    Map<Path, String> originalContents = new LinkedHashMap<>();
    Map<Path, String> workingContents = new LinkedHashMap<>();
    Map<Path, String> newFiles = new LinkedHashMap<>();

    // Phase 1: validate and stage every edit in memory so partial failures never touch disk.
    for (SearchReplaceEdit edit : proposal.edits()) {
      if (edit.search().isEmpty()) {
        throw new PatchApplyException("Search block must not be empty for file: " + edit.file());
      }
      Path target = pathResolver.resolveSafe(workspaceRoot, edit.file());
      String relPath = pathResolver.toRelativePosixPath(workspaceRoot, target);
      if (!Files.isRegularFile(target)) {
        throw new PatchApplyException("Target file does not exist: " + relPath);
      }

      String current =
          workingContents.computeIfAbsent(
              target,
              path -> {
                String content = readUtf8(path, relPath);
                originalContents.put(path, content);
                return content;
              });

      int firstIndex = current.indexOf(edit.search());
      if (firstIndex < 0) {
        throw new PatchApplyException("Search block not found in " + relPath);
      }
      int secondIndex = current.indexOf(edit.search(), firstIndex + 1);
      if (secondIndex >= 0) {
        int matches = countOccurrences(current, edit.search());
        throw new PatchApplyException(
            "Search block is ambiguous (matched " + matches + " times) in " + relPath);
      }

      String updated =
          current.substring(0, firstIndex)
              + edit.replace()
              + current.substring(firstIndex + edit.search().length());
      workingContents.put(target, updated);
    }

    for (NewTestFile newTestFile : proposal.newTestFiles()) {
      Path target = pathResolver.resolveSafe(workspaceRoot, newTestFile.path());
      String relPath = pathResolver.toRelativePosixPath(workspaceRoot, target);
      if (Files.exists(target) || newFiles.containsKey(target)) {
        throw new PatchApplyException("New test file already exists: " + relPath);
      }
      newFiles.put(target, newTestFile.content());
    }

    // Phase 2: write validated changes to disk and generate canonical unified diff.
    List<String> modifiedRelPaths = new ArrayList<>();
    List<String> createdRelPaths = new ArrayList<>();
    StringBuilder unifiedDiff = new StringBuilder();

    for (Map.Entry<Path, String> entry : workingContents.entrySet()) {
      Path path = entry.getKey();
      String relPath = pathResolver.toRelativePosixPath(workspaceRoot, path);
      String updated = entry.getValue();
      writeUtf8(path, updated, relPath);
      modifiedRelPaths.add(relPath);

      appendUnifiedDiff(
          unifiedDiff, "a/" + relPath, "b/" + relPath, originalContents.get(path), updated);
    }

    for (Map.Entry<Path, String> entry : newFiles.entrySet()) {
      Path path = entry.getKey();
      String relPath = pathResolver.toRelativePosixPath(workspaceRoot, path);
      String content = entry.getValue();
      createParentDirs(path, relPath);
      writeUtf8(path, content, relPath);
      createdRelPaths.add(relPath);

      appendUnifiedDiff(unifiedDiff, "/dev/null", "b/" + relPath, "", content);
    }

    return new AppliedPatch(modifiedRelPaths, createdRelPaths, unifiedDiff.toString());
  }

  private void appendUnifiedDiff(
      StringBuilder out,
      String originalLabel,
      String revisedLabel,
      String originalContent,
      String revisedContent) {
    List<String> originalLines = originalContent.lines().toList();
    List<String> revisedLines = revisedContent.lines().toList();
    Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);
    List<String> diffLines =
        UnifiedDiffUtils.generateUnifiedDiff(
            originalLabel, revisedLabel, originalLines, patch, DIFF_CONTEXT_LINES);
    for (String line : diffLines) {
      out.append(line).append('\n');
    }
  }

  private int countOccurrences(String text, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = text.indexOf(needle, idx)) >= 0) {
      count++;
      idx += 1;
    }
    return count;
  }

  private String readUtf8(Path path, String relPath) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read file: " + relPath, e);
    }
  }

  private void writeUtf8(Path path, String content, String relPath) {
    try {
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write file: " + relPath, e);
    }
  }

  private void createParentDirs(Path path, String relPath) {
    Path parent = path.getParent();
    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to create parent directories for: " + relPath, e);
      }
    }
  }
}
