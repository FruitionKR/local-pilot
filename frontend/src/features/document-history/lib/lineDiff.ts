import type { MarkdownDiffLine } from "@/features/agent-chat/lib/markdownAgent";

// 스냅샷(before)과 현재 본문(after)을 line 단위로 비교한다.
// markdownAgent.ts의 lineDiff는 export되지 않고 수정이 금지되어(스트림3 공유) 있으므로,
// 동일한 LCS 알고리즘을 히스토리 전용으로 재구현하고 타입만 재사용한다.
const MAX_LCS_CELLS = 250_000;

export function diffMarkdownLines(before: string, after: string): MarkdownDiffLine[] {
  const beforeLines = before.split("\n");
  const afterLines = after.split("\n");
  // 문서가 매우 크면 LCS 비용이 폭증하므로 전체 삭제+삽입으로 안전하게 대체한다.
  if (beforeLines.length * afterLines.length > MAX_LCS_CELLS) {
    return [
      ...beforeLines.map((text) => ({ type: "delete" as const, text })),
      ...afterLines.map((text) => ({ type: "insert" as const, text }))
    ];
  }

  const lengths = Array.from(
    { length: beforeLines.length + 1 },
    () => Array<number>(afterLines.length + 1).fill(0)
  );

  for (let beforeIndex = beforeLines.length - 1; beforeIndex >= 0; beforeIndex -= 1) {
    for (let afterIndex = afterLines.length - 1; afterIndex >= 0; afterIndex -= 1) {
      lengths[beforeIndex][afterIndex] = beforeLines[beforeIndex] === afterLines[afterIndex]
        ? lengths[beforeIndex + 1][afterIndex + 1] + 1
        : Math.max(lengths[beforeIndex + 1][afterIndex], lengths[beforeIndex][afterIndex + 1]);
    }
  }

  const diff: MarkdownDiffLine[] = [];
  let beforeIndex = 0;
  let afterIndex = 0;
  while (beforeIndex < beforeLines.length && afterIndex < afterLines.length) {
    if (beforeLines[beforeIndex] === afterLines[afterIndex]) {
      diff.push({ type: "context", text: beforeLines[beforeIndex] });
      beforeIndex += 1;
      afterIndex += 1;
    } else if (lengths[beforeIndex + 1][afterIndex] >= lengths[beforeIndex][afterIndex + 1]) {
      diff.push({ type: "delete", text: beforeLines[beforeIndex] });
      beforeIndex += 1;
    } else {
      diff.push({ type: "insert", text: afterLines[afterIndex] });
      afterIndex += 1;
    }
  }
  while (beforeIndex < beforeLines.length) {
    diff.push({ type: "delete", text: beforeLines[beforeIndex] });
    beforeIndex += 1;
  }
  while (afterIndex < afterLines.length) {
    diff.push({ type: "insert", text: afterLines[afterIndex] });
    afterIndex += 1;
  }
  return diff;
}
