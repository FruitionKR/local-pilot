export type MarkdownSegment = { kind: "frontmatter" | "markdown"; content: string };

const LIST_ITEM_PATTERN = /^(?:[-+*]\s+|\d+[.)]\s+)/;
const LIST_ITEM_WITH_INDENT_PATTERN = /^(\s*)(?:([-+*])\s+|(\d+[.)])\s+)/;

type ListItem = {
  indent: number;
  kind: "bullet" | "ordered";
};

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
export function splitMarkdownBlocks(markdown: string): MarkdownSegment[] {
  const segments: MarkdownSegment[] = [];
  const lines = markdown.split("\n");
  let index = 0;

  while (index < lines.length) {
    const trimmed = lines[index].trim();

    if (!trimmed) {
      index += 1;
      continue;
    }

    if (trimmed === "---" && segments.length === 0) {
      const frontmatter = [];
      index += 1;
      while (index < lines.length && lines[index].trim() !== "---") {
        frontmatter.push(lines[index]);
        index += 1;
      }
      index += 1;
      segments.push({ kind: "frontmatter", content: frontmatter.join("\n") });
      continue;
    }

    if (trimmed.startsWith("```")) {
      const code = [lines[index]];
      index += 1;
      while (index < lines.length && !lines[index].trim().startsWith("```")) {
        code.push(lines[index]);
        index += 1;
      }
      code.push("```");
      index += 1;
      segments.push({ kind: "markdown", content: code.join("\n") });
      continue;
    }

    if (/^#{1,3} /.test(trimmed) || trimmed === "---" || trimmed === "***") {
      segments.push({ kind: "markdown", content: trimmed });
      index += 1;
      continue;
    }

    if (isListItem(lines[index])) {
      const listBlock = collectListBlock(lines, index);
      segments.push({ kind: "markdown", content: listBlock.content });
      index = listBlock.nextIndex;
      continue;
    }

    const paragraph = [trimmed];
    index += 1;
    while (index < lines.length) {
      const nextLine = lines[index].trim();
      if (!nextLine || /^(#{1,3} |[-*]{3}$|```)/.test(nextLine) || isListItem(lines[index])) break;
      paragraph.push(nextLine);
      index += 1;
    }
    segments.push({ kind: "markdown", content: paragraph.join("\n") });
  }

  return segments;
}
