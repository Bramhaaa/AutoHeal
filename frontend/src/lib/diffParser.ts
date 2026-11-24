export interface ParsedFileDiff {
  path: string;
  language: string;
  original: string;
  modified: string;
  additions: number;
  deletions: number;
}

function detectLanguage(filePath: string): string {
  const lower = filePath.toLowerCase();
  if (lower.endsWith(".java")) return "java";
  if (lower.endsWith(".xml")) return "xml";
  if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
  if (lower.endsWith(".json")) return "json";
  if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
  if (lower.endsWith(".sql")) return "sql";
  return "plaintext";
}

function stripDiffPrefix(rawPath: string): string {
  const trimmed = rawPath.trim().split("\t")[0] ?? rawPath.trim();
  if (trimmed.startsWith("a/") || trimmed.startsWith("b/")) {
    return trimmed.slice(2);
  }
  return trimmed;
}

/**
 * Parses a standard unified diff (single or multi-file) into side-by-side
 * original and modified text buffers suitable for Monaco DiffEditor.
 */
export function parseUnifiedDiff(unifiedDiff: string): ParsedFileDiff[] {
  if (!unifiedDiff || unifiedDiff.trim().length === 0) {
    return [];
  }

  const lines = unifiedDiff.replace(/\r\n/g, "\n").split("\n");
  const files: ParsedFileDiff[] = [];

  let currentPath: string | null = null;
  let originalLines: string[] = [];
  let modifiedLines: string[] = [];
  let additions = 0;
  let deletions = 0;
  let inHunk = false;

  const flush = () => {
    if (currentPath !== null && (originalLines.length > 0 || modifiedLines.length > 0)) {
      files.push({
        path: currentPath,
        language: detectLanguage(currentPath),
        original: originalLines.join("\n"),
        modified: modifiedLines.join("\n"),
        additions,
        deletions,
      });
    }
    currentPath = null;
    originalLines = [];
    modifiedLines = [];
    additions = 0;
    deletions = 0;
    inHunk = false;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i] ?? "";

    if (line.startsWith("diff --git ")) {
      flush();
      const parts = line.split(" ");
      const bPath = parts[3];
      if (bPath) {
        currentPath = stripDiffPrefix(bPath);
      }
      continue;
    }

    if (line.startsWith("--- ")) {
      if (inHunk) {
        flush();
      }
      const nextLine = lines[i + 1] ?? "";
      if (nextLine.startsWith("+++ ")) {
        const fromPath = stripDiffPrefix(line.slice(4));
        const toPath = stripDiffPrefix(nextLine.slice(4));
        currentPath = toPath !== "/dev/null" ? toPath : fromPath;
        i++;
      }
      continue;
    }

    if (line.startsWith("+++ ")) {
      const toPath = stripDiffPrefix(line.slice(4));
      if (toPath !== "/dev/null") {
        currentPath = toPath;
      }
      continue;
    }

    if (line.startsWith("@@ ")) {
      inHunk = true;
      if (currentPath === null) {
        currentPath = "patch.diff";
      }
      continue;
    }

    if (!inHunk) {
      continue;
    }

    if (line.startsWith("\\ No newline at end of file")) {
      continue;
    }

    if (line.startsWith("+")) {
      modifiedLines.push(line.slice(1));
      additions++;
    } else if (line.startsWith("-")) {
      originalLines.push(line.slice(1));
      deletions++;
    } else if (line.startsWith(" ")) {
      const content = line.slice(1);
      originalLines.push(content);
      modifiedLines.push(content);
    }
  }

  flush();
  return files;
}
