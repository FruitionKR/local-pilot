"use client";

import { useCallback, useEffect, useState } from "react";
import { appendSnapshot, readSnapshots, type DocumentSnapshot } from "./snapshotStore";

// 현재 열려 있는 문서의 스냅샷 목록을 관리한다.
// documentId가 바뀌면 해당 문서의 저장분을 다시 읽는다.
export function useSnapshots(documentId: string | null): {
  snapshots: DocumentSnapshot[];
  capture: (markdown: string, label: string) => void;
} {
  const [snapshots, setSnapshots] = useState<DocumentSnapshot[]>([]);

  useEffect(() => {
    setSnapshots(documentId ? readSnapshots(documentId) : []);
  }, [documentId]);

  const capture = useCallback((markdown: string, label: string) => {
    if (!documentId) return;
    setSnapshots((current) => appendSnapshot(current, documentId, markdown, label));
  }, [documentId]);

  return { snapshots, capture };
}
