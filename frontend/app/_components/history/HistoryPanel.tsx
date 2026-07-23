"use client";

import { useMemo, useState } from "react";
import { diffMarkdownLines } from "./lineDiff";
import type { DocumentSnapshot } from "./snapshotStore";

const DIFF_MARKERS = {
  context: " ",
  delete: "−",
  insert: "+"
} as const;

function formatTimestamp(createdAt: number): string {
  const date = new Date(createdAt);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function HistoryPanel({
  snapshots,
  currentMarkdown,
  onRestore,
  onClose
}: {
  snapshots: DocumentSnapshot[];
  currentMarkdown: string;
  onRestore: (snapshot: DocumentSnapshot) => void;
  onClose: () => void;
}) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = useMemo(
    () => snapshots.find((snapshot) => snapshot.id === selectedId) ?? null,
    [snapshots, selectedId]
  );
  // 선택한 스냅샷(과거) → 현재 본문(after) 방향으로 변경 라인을 보여준다.
  const diffLines = useMemo(
    () => (selected ? diffMarkdownLines(selected.markdown, currentMarkdown) : []),
    [selected, currentMarkdown]
  );
  const hasChanges = diffLines.some((line) => line.type !== "context");

  return (
    <aside className="history-panel" aria-label="문서 변경 기록" onClick={(event) => event.stopPropagation()}>
      <header className="history-panel-header">
        <strong>변경 기록</strong>
        <button type="button" aria-label="기록 닫기" onClick={onClose}>✕</button>
      </header>

      {snapshots.length === 0 ? (
        <p className="history-empty">아직 저장된 스냅샷이 없습니다. AI 편집을 적용하면 편집 전 상태가 자동으로 기록됩니다.</p>
      ) : (
        <ol className="history-list">
          {snapshots.map((snapshot) => (
            <li key={snapshot.id}>
              <button
                type="button"
                className={`history-item${snapshot.id === selectedId ? " is-selected" : ""}`}
                onClick={() => setSelectedId(snapshot.id)}
              >
                <span className="history-item-label">{snapshot.label}</span>
                <span className="history-item-time">{formatTimestamp(snapshot.createdAt)}</span>
              </button>
            </li>
          ))}
        </ol>
      )}

      {selected && (
        <section className="history-detail" aria-label="스냅샷과 현재 문서 비교">
          <div className="history-diff">
            {hasChanges ? (
              diffLines.map((line, index) => (
                <code className={`is-${line.type}`} key={`${line.type}-${index}`}>
                  <span aria-hidden="true">{DIFF_MARKERS[line.type]}</span>
                  {line.text || " "}
                </code>
              ))
            ) : (
              <p className="history-nodiff">이 스냅샷과 현재 문서가 동일합니다.</p>
            )}
          </div>
          <button
            type="button"
            className="history-restore"
            disabled={!hasChanges}
            onClick={() => onRestore(selected)}
          >
            이 버전으로 롤백
          </button>
        </section>
      )}
    </aside>
  );
}
