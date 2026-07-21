export type MarkdownSegment = { kind: "frontmatter" | "markdown"; content: string };

const LIST_ITEM_PATTERN = /^(?:[-+*]\s+|\d+[.)]\s+)/;

function isListItem(line: string) {
  return LIST_ITEM_PATTERN.test(line.trim());
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
      const items = [];
      while (index < lines.length && isListItem(lines[index])) {
        items.push(lines[index]);
        index += 1;
      }
      segments.push({ kind: "markdown", content: items.join("\n") });
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
