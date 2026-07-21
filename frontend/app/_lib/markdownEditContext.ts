export type MarkdownEditTarget = {
  type: "selection" | "current_section" | "whole_document";
  startLine: number;
  endLine: number;
};

export type MarkdownEditorSnapshot = {
  markdown: string;
  target: MarkdownEditTarget;
};

export type ActiveMarkdownEditContext = {
  documentId: string;
  editorSnapshot: MarkdownEditorSnapshot;
};

type MarkdownHeading = {
  line: number;
  level: number;
};

function lineCount(markdown: string) {
  return markdown.split("\n").length;
}

function lineAtOffset(markdown: string, offset: number) {
  const safeOffset = Math.max(0, Math.min(markdown.length, Math.trunc(offset)));
  let line = 1;
  for (let index = 0; index < safeOffset; index += 1) {
    if (markdown[index] === "\n") line += 1;
  }
  return line;
}

function collectMarkdownHeadings(markdown: string): MarkdownHeading[] {
  const lines = markdown.split("\n");
  const headings: MarkdownHeading[] = [];
  let startIndex = 0;
  let fenceCharacter: "`" | "~" | null = null;
  let fenceLength = 0;

  if (lines[0]?.trim() === "---") {
    const closingIndex = lines.findIndex((line, index) => index > 0 && line.trim() === "---");
    startIndex = closingIndex >= 0 ? closingIndex + 1 : lines.length;
  }

  for (let index = startIndex; index < lines.length; index += 1) {
    const line = lines[index];
    if (fenceCharacter !== null) {
      const closingFence = line.match(/^ {0,3}(`{3,}|~{3,})[\t ]*$/);
      if (closingFence && closingFence[1][0] === fenceCharacter && closingFence[1].length >= fenceLength) {
        fenceCharacter = null;
        fenceLength = 0;
      }
      continue;
    }

    const openingFence = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if (openingFence) {
      const marker = openingFence[1];
      if (marker[0] !== "`" || !openingFence[2].includes("`")) {
        fenceCharacter = marker[0] as "`" | "~";
        fenceLength = marker.length;
      }
      continue;
    }

    const heading = line.match(/^ {0,3}(#{1,6})(?:[\t ]+|$)/);
    if (heading) headings.push({ line: index + 1, level: heading[1].length });
  }

  return headings;
}

function wholeDocumentTarget(markdown: string): MarkdownEditTarget {
  return { type: "whole_document", startLine: 1, endLine: lineCount(markdown) };
}

function currentSectionTarget(markdown: string, cursorLine: number): MarkdownEditTarget {
  const headings = collectMarkdownHeadings(markdown);
  const currentHeadingIndex = headings.findLastIndex((heading) => heading.line <= cursorLine);
  if (currentHeadingIndex < 0) return wholeDocumentTarget(markdown);

  const currentHeading = headings[currentHeadingIndex];
  const nextBoundary = headings
    .slice(currentHeadingIndex + 1)
    .find((heading) => heading.level <= currentHeading.level);

  return {
    type: "current_section",
    startLine: currentHeading.line,
    endLine: nextBoundary ? nextBoundary.line - 1 : lineCount(markdown)
  };
}

export function buildMarkdownEditorSnapshot(
  markdown: string,
  selectionFrom: number,
  selectionTo: number
): MarkdownEditorSnapshot {
  const from = Math.max(0, Math.min(markdown.length, Math.min(selectionFrom, selectionTo)));
  const to = Math.max(0, Math.min(markdown.length, Math.max(selectionFrom, selectionTo)));

  if (from !== to) {
    return {
      markdown,
      target: {
        type: "selection",
        startLine: lineAtOffset(markdown, from),
        endLine: lineAtOffset(markdown, to - 1)
      }
    };
  }

  return {
    markdown,
    target: currentSectionTarget(markdown, lineAtOffset(markdown, from))
  };
}
