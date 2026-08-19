import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { WikiIngestModal } from "@/features/wiki-ingest/ui/WikiIngestModal";
import { formatLintProgressLabel } from "@/features/wiki-ingest/model/activeLintOperation";
import { isLintActionEnabled, selectActiveIngestDocuments } from "@/features/wiki-ingest/model/wikiReflectState";
import { useActiveLintOperation } from "@/features/wiki-ingest/model/useActiveLintOperation";
import { fetchWikiMaintenanceStatus } from "@/features/document-notifications";
import type { DocumentItemResponse } from "@/entities/document";
import styles from "./DocumentSidebar.module.css";

/** 그래프 뷰에서 문서 트리 자리를 대신하는 위키 액션 패널(Ingest / Lint). */
export function GraphSidebarActions({
  documents,
  pending,
  onIngestDocuments,
  onLint
}: {
  documents: DocumentItemResponse[];
  pending: "ingest" | "lint" | null;
  /** 모달에서 고른 문서들을 한 번에 위키에 반영한다. */
  onIngestDocuments: (documents: DocumentItemResponse[]) => void;
  onLint: () => void;
}) {
  const [isPickerOpen, setIsPickerOpen] = useState(false);
  const activeIngestDocuments = selectActiveIngestDocuments(documents);
  const activeLintOperation = useActiveLintOperation(pending === "lint");
  const lintProgressLabel = formatLintProgressLabel(activeLintOperation, pending === "lint");
  const isIngestActive = pending === "ingest" || activeIngestDocuments.length > 0;
  const isLintActive = lintProgressLabel !== null;
  const {
    data: maintenanceStatus,
    isError: isMaintenanceStatusError,
    isFetching: isMaintenanceStatusFetching,
    refetch: refetchMaintenanceStatus
  } = useQuery({
    queryKey: ["wikiMaintenanceStatus"],
    queryFn: fetchWikiMaintenanceStatus,
    refetchInterval: isIngestActive ? 3000 : false,
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: false
  });
  const previousWorkRef = useRef({ isIngestActive, isLintActive });

  useEffect(() => {
    const previous = previousWorkRef.current;
    previousWorkRef.current = { isIngestActive, isLintActive };
    if ((previous.isIngestActive && !isIngestActive) || (previous.isLintActive && !isLintActive)) {
      void refetchMaintenanceStatus();
    }
  }, [isIngestActive, isLintActive, refetchMaintenanceStatus]);

  const isLintEnabled = isLintActionEnabled({
    needsLint: maintenanceStatus?.needs_lint === true,
    isIngestActive,
    isLintActive
  });

  return (
    <div className={styles["graph-actions"]}>
      <button
        type="button"
        className={styles["graph-action"]}
        disabled={pending !== null || isLintActive}
        onClick={(event) => {
          event.stopPropagation();
          setIsPickerOpen(true);
        }}
      >
        {pending === "ingest" ? "Ingest 중…" : "Ingest"}
      </button>
      <button
        type="button"
        className={styles["graph-action"]}
        disabled={!isLintEnabled && !isMaintenanceStatusError}
        title={isIngestActive
          ? "Ingest가 끝난 뒤 Lint할 수 있습니다."
          : isMaintenanceStatusError
            ? "Lint 가능 상태를 불러오지 못했습니다. 클릭해서 다시 확인하세요."
            : isMaintenanceStatusFetching
              ? "Lint 가능 상태를 확인하고 있습니다."
          : maintenanceStatus?.needs_lint === false
            ? "새로 반영된 Wiki 내용이 없습니다."
            : undefined}
        onClick={(event) => {
          event.stopPropagation();
          if (isMaintenanceStatusError) {
            void refetchMaintenanceStatus();
            return;
          }
          onLint();
        }}
      >
        {isMaintenanceStatusError
          ? "Lint 상태 재확인"
          : pending === "lint"
            ? "Lint 중…"
            : "Lint"}
      </button>

      {/* 진행 중인 위키 작업. 문서 목록·작업 로그에서 읽어 새로고침해도 유지된다. */}
      {(activeIngestDocuments.length > 0 || lintProgressLabel) && (
        <ul className={styles["graph-active-work"]} aria-label="진행 중인 위키 작업">
          {activeIngestDocuments.map((document) => (
            <li key={document.id} className={styles["graph-active-item"]}>
              <span className={styles["graph-active-kind"]}>Ingest</span>
              <span className={styles["graph-active-label"]} title={document.filename}>
                {document.filename}
              </span>
            </li>
          ))}
          {lintProgressLabel && (
            <li className={styles["graph-active-item"]}>
              <span className={styles["graph-active-kind"]}>Lint</span>
              <span className={styles["graph-active-label"]}>{lintProgressLabel}</span>
            </li>
          )}
        </ul>
      )}

      {isPickerOpen && (
        <WikiIngestModal
          documents={documents}
          onSubmit={onIngestDocuments}
          onClose={() => setIsPickerOpen(false)}
        />
      )}
    </div>
  );
}
