package com.autoheal.service;

import com.autoheal.domain.SearchMatch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceTools {

  public static final int MAX_READ_LINES = 400;
  public static final int MAX_LIST_FILES = 200;
  public static final int MAX_SEARCH_MATCHES = 50;
  private static final long MAX_SEARCHABLE_FILE_BYTES = 1_048_576L;
  private static final Set<String> IGNORED_DIRS = Set.of(".git", "target", "node_modules");

  private final WorkspacePathResolver pathResolver;

  public WorkspaceTools(WorkspacePathResolver pathResolver) {
    this.pathResolver = Objects.requireNonNull(pathResolver, "pathResolver must not be null");
  }

  public List<String> listFiles(Path workspaceRoot, String dir, String glob) {
    Path baseDir = pathResolver.resolveSafeDirectory(workspaceRoot, dir);
    if (!Files.isDirectory(baseDir)) {
      throw new IllegalArgumentException("Not a directory: " + dir);
    }
    PathMatcher matcher = buildMatcher(glob);
    List<String> results = new ArrayList<>();

    try {
      Files.walkFileTree(
          baseDir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
              if (!d.equals(baseDir)) {
                Path name = d.getFileName();
                if (name != null && IGNORED_DIRS.contains(name.toString())) {
                  return FileVisitResult.SKIP_SUBTREE;
                }
                if (!pathResolver.isWithinWorkspace(workspaceRoot, d)) {
                  return FileVisitResult.SKIP_SUBTREE;
                }
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
              }
              if (!pathResolver.isWithinWorkspace(workspaceRoot, file)) {
                return FileVisitResult.CONTINUE;
              }
              String relPath = pathResolver.toRelativePosixPath(workspaceRoot, file);
              if (matchesGlob(matcher, file, relPath)) {
                results.add(relPath);
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to list files in " + dir, e);
    }

    Collections.sort(results);
    if (results.size() > MAX_LIST_FILES) {
      return List.copyOf(results.subList(0, MAX_LIST_FILES));
    }
    return List.copyOf(results);
  }

  public String readFile(Path workspaceRoot, String path, int startLine, int endLine) {
    if (startLine < 1) {
      throw new IllegalArgumentException("startLine must be >= 1");
    }
    if (endLine < startLine) {
      throw new IllegalArgumentException("endLine must be >= startLine");
    }
    Path target = pathResolver.resolveSafe(workspaceRoot, path);
    if (!Files.isRegularFile(target)) {
      throw new IllegalArgumentException("File does not exist: " + path);
    }

    List<String> allLines;
    try {
      allLines = Files.readAllLines(target, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read file: " + path, e);
    }

    if (allLines.isEmpty() || startLine > allLines.size()) {
      return "";
    }

    int cappedEnd = Math.min(endLine, startLine + MAX_READ_LINES - 1);
    int actualEnd = Math.min(cappedEnd, allLines.size());

    StringBuilder sb = new StringBuilder();
    for (int lineNo = startLine; lineNo <= actualEnd; lineNo++) {
      if (lineNo > startLine) {
        sb.append('\n');
      }
      sb.append(lineNo).append(": ").append(allLines.get(lineNo - 1));
    }
    return sb.toString();
  }

  public List<SearchMatch> searchCode(Path workspaceRoot, String query, String glob) {
    if (query == null || query.isEmpty()) {
      throw new IllegalArgumentException("query must not be null or empty");
    }
    List<String> files = listFiles(workspaceRoot, "", glob);
    List<SearchMatch> matches = new ArrayList<>();

    for (String relPath : files) {
      if (matches.size() >= MAX_SEARCH_MATCHES) {
        break;
      }
      Path file = pathResolver.resolveSafe(workspaceRoot, relPath);
      try {
        if (Files.size(file) > MAX_SEARCHABLE_FILE_BYTES) {
          continue;
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.indexOf('\0') >= 0) {
          continue;
        }
        String[] lines = content.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
          if (lines[i].contains(query)) {
            matches.add(new SearchMatch(relPath, i + 1, lines[i]));
            if (matches.size() >= MAX_SEARCH_MATCHES) {
              break;
            }
          }
        }
      } catch (MalformedInputException e) {
        // Binary or non-UTF-8 file; skip during text code search.
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to search file: " + relPath, e);
      }
    }

    return List.copyOf(matches);
  }

  private PathMatcher buildMatcher(String glob) {
    if (glob == null || glob.isBlank()) {
      return null;
    }
    return FileSystems.getDefault().getPathMatcher("glob:" + glob.trim());
  }

  private boolean matchesGlob(PathMatcher matcher, Path file, String relPath) {
    if (matcher == null) {
      return true;
    }
    Path fileName = file.getFileName();
    return matcher.matches(Path.of(relPath)) || (fileName != null && matcher.matches(fileName));
  }
}
