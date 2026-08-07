export type MarkdownSegment = { kind: "frontmatter" | "markdown"; content: string };
export type MarkdownSegmentRange = MarkdownSegment & { startLine: number; endLine: number };

const LIST_ITEM_PATTERN = /^(?:[-+*]\s+|\d+[.)]\s+)/;
const LIST_ITEM_WITH_INDENT_PATTERN = /^(\s*)(?:([-+*])\s+|(\d+[.)])\s+)/;

type ListItem = {
  indent: number;
  kind: "bullet" | "ordered";
};

type CodeFence = {
  marker: "`" | "~";
  length: number;
};

function parseOpeningCodeFence(line: string): CodeFence | null {
  const match = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
  if (!match || (match[1][0] === "`" && match[2].includes("`"))) return null;
  return {
    marker: match[1][0] as CodeFence["marker"],
    length: match[1].length
  };
}

function isClosingCodeFence(line: string, openingFence: CodeFence) {
  const match = line.match(/^ {0,3}(`{3,}|~{3,})[\t ]*$/);
  return Boolean(
    match
    && match[1][0] === openingFence.marker
    && match[1].length >= openingFence.length
  );
}

function isAtxHeading(line: string) {
  return /^ {0,3}#{1,6}(?:[\t ]+|$)/.test(line);
}

function isListItem(line: string) {
  return LIST_ITEM_PATTERN.test(line.trim());
}

function parseListItem(line: string): ListItem | null {
  const match = line.match(LIST_ITEM_WITH_INDENT_PATTERN);
  if (!match) return null;
  return {
    indent: match[1].length,
    kind: match[2] ? "bullet" : "ordered"
  };
}

function leadingWhitespaceLength(line: string) {
  return line.match(/^[\t ]*/)?.[0].length ?? 0;
}

function collectListBlock(lines: string[], startIndex: number) {
  const firstItem = parseListItem(lines[startIndex]);
  if (!firstItem) return { content: lines[startIndex], nextIndex: startIndex + 1 };

  const collected: string[] = [];
  let index = startIndex;

  while (index < lines.length) {
    const line = lines[index];
    const item = parseListItem(line);
    if (item) {
      if (item.indent < firstItem.indent) break;
      if (item.indent === firstItem.indent && item.kind !== firstItem.kind) break;
      collected.push(line);
      index += 1;
      continue;
    }

    if (line.trim()) {
      if (leadingWhitespaceLength(line) <= firstItem.indent) break;
      collected.push(line);
      index += 1;
      continue;
    }

    let nextContentIndex = index + 1;
    while (nextContentIndex < lines.length && !lines[nextContentIndex].trim()) {
      nextContentIndex += 1;
    }
    if (nextContentIndex >= lines.length) break;

    const nextItem = parseListItem(lines[nextContentIndex]);
    let continuesList = leadingWhitespaceLength(lines[nextContentIndex]) > firstItem.indent;
    if (nextItem) {
      continuesList = nextItem.indent > firstItem.indent
        || (nextItem.indent === firstItem.indent && nextItem.kind === firstItem.kind);
    }
    if (!continuesList) break;

    collected.push(...lines.slice(index, nextContentIndex));
    index = nextContentIndex;
  }

  return { content: collected.join("\n"), nextIndex: index };
}

/**
 * markdown을 블록 단위 문자열로 분할한다.
 * 분할 순서가 백엔드 block ID(B0001, B0002, ...) 계약과 일치해야 하므로
 * 기존 파서와 동일한 경계 규칙을 유지한다.
 */
export function splitMarkdownBlockRanges(markdown: string): MarkdownSegmentRange[] {
  const segments: MarkdownSegmentRange[] = [];
  const lines = markdown.split("\n");
  let index = 0;

  while (index < lines.length) {
    const trimmed = lines[index].trim();

    if (!trimmed) {
      index += 1;
      continue;
    }

    if (trimmed === "---" && segments.length === 0) {
      const startLine = index + 1;
      const frontmatter = [];
      index += 1;
      while (index < lines.length && lines[index].trim() !== "---") {
        frontmatter.push(lines[index]);
        index += 1;
      }
      index += 1;
      segments.push({
        kind: "frontmatter",
        content: frontmatter.join("\n"),
        startLine,
        endLine: Math.min(index, lines.length)
      });
      continue;
    }

    const openingFence = parseOpeningCodeFence(lines[index]);
    if (openingFence) {
      const startLine = index + 1;
      const code = [lines[index]];
      index += 1;
      while (index < lines.length) {
        code.push(lines[index]);
        const reachedClosingFence = isClosingCodeFence(lines[index], openingFence);
        index += 1;
        if (reachedClosingFence) break;
      }
      segments.push({
        kind: "markdown",
        content: code.join("\n"),
        startLine,
        endLine: Math.min(index, lines.length)
      });
      continue;
    }

    if (isAtxHeading(lines[index]) || trimmed === "---" || trimmed === "***") {
      segments.push({
        kind: "markdown",
        content: trimmed,
        startLine: index + 1,
        endLine: index + 1
      });
      index += 1;
      continue;
    }

    if (isListItem(lines[index])) {
      const startLine = index + 1;
      const listBlock = collectListBlock(lines, index);
      segments.push({
        kind: "markdown",
        content: listBlock.content,
        startLine,
        endLine: listBlock.nextIndex
      });
      index = listBlock.nextIndex;
      continue;
    }

    const startLine = index + 1;
    const paragraph = [trimmed];
    index += 1;
    while (index < lines.length) {
      const nextLine = lines[index].trim();
      if (
        !nextLine
        || isAtxHeading(lines[index])
        || /^[-*]{3}$/.test(nextLine)
        || parseOpeningCodeFence(lines[index])
        || isListItem(lines[index])
      ) break;
      paragraph.push(nextLine);
      index += 1;
    }
    segments.push({
      kind: "markdown",
      content: paragraph.join("\n"),
      startLine,
      endLine: index
    });
  }

  return segments;
}

export function splitMarkdownBlocks(markdown: string): MarkdownSegment[] {
  return splitMarkdownBlockRanges(markdown).map(({ kind, content }) => ({ kind, content }));
}
