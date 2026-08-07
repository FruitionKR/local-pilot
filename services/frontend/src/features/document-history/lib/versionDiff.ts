// 서버 diff 응답(GET /documents/{id}/diff)의 hunk 구조를 화면 렌더링용 평탄한 행 목록으로 변환한다.
// 서버가 GitHub 스타일 hunk를 반환하므로 프론트에서 diff를 다시 계산하지 않는다.

export type ServerDiffLineType = "CONTEXT" | "DELETE" | "ADD";

export type ServerDiffLine = {
  type: ServerDiffLineType;
  old_line: number | null;
  new_line: number | null;
  content: string;
};

export type ServerDiffHunk = {
  old_start: number;
  old_lines: number;
  new_start: number;
  new_lines: number;
  lines: ServerDiffLine[];
};

export type VersionDiffRow = {
  type: "context" | "delete" | "insert" | "gap";
  text: string;
};

const ROW_TYPE_BY_SERVER_TYPE: Record<ServerDiffLineType, VersionDiffRow["type"]> = {
  CONTEXT: "context",
  DELETE: "delete",
  ADD: "insert"
};

// hunk 사이에는 생략 구간 표시(gap) 행을 끼워 넣는다.
export function flattenDiffHunks(hunks: ServerDiffHunk[]): VersionDiffRow[] {
  const rows: VersionDiffRow[] = [];
  hunks.forEach((hunk, index) => {
    if (index > 0) rows.push({ type: "gap", text: "⋯" });
    for (const line of hunk.lines) {
      rows.push({ type: ROW_TYPE_BY_SERVER_TYPE[line.type], text: line.content });
    }
  });
  return rows;
}
