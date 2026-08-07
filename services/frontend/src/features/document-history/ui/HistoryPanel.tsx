"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { getErrorMessage } from "@/shared/lib/errors";
import {
  fetchDocumentVersionDiff,
  fetchDocumentVersions,
  restoreDocumentVersion,
  VersionRestoreConflictError,
  type DocumentVersionListResponse
} from "../api/versions";
import { flattenDiffHunks, type VersionDiffRow } from "../lib/versionDiff";
import styles from "./HistoryPanel.module.css";

const DIFF_MARKERS: Record<VersionDiffRow["type"], string> = {
  context: " ",
  delete: "−",
  insert: "+",
  gap: " "
};

function formatTimestamp(createdAt: string): string {
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function HistoryPanel({
  documentId,
  onRestored,
  onClose
}: {
  documentId: string;
  /** 복원 성공 후 복원된 문서 id와 함께 호출된다. 호출 측은 해당 문서 본문을 다시 불러와야 한다. */
  onRestored: (restoredDocumentId: string) => void;
  onClose: () => void;
}) {
  const [versionData, setVersionData] = useState<DocumentVersionListResponse | null>(null);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [diffRows, setDiffRows] = useState<VersionDiffRow[] | null>(null);
  const [isDiffLoading, setIsDiffLoading] = useState(false);
  const [isRestoring, setIsRestoring] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const loadVersions = useCallback(async () => {
    const data = await fetchDocumentVersions(documentId);
    setVersionData(data);
    return data;
  }, [documentId]);

  useEffect(() => {
    let ignore = false;
    setVersionData(null);
    setSelectedVersion(null);
    setDiffRows(null);
    setErrorMessage(null);
    loadVersions().catch((error: unknown) => {
      if (!ignore) setErrorMessage(getErrorMessage(error, "버전 이력을 불러오지 못했습니다."));
    });
    return () => {
      ignore = true;
    };
  }, [loadVersions]);

  const currentVersion = versionData?.current_version ?? null;
  const selected = useMemo(
    () => versionData?.versions.find((item) => item.version === selectedVersion) ?? null,
    [selectedVersion, versionData]
  );
  const isSelectedCurrent = selectedVersion !== null && selectedVersion === currentVersion;

  useEffect(() => {
    if (selectedVersion === null || currentVersion === null || selectedVersion === currentVersion) {
      setDiffRows(null);
      return;
    }
    let ignore = false;
    setIsDiffLoading(true);
    setDiffRows(null);
    fetchDocumentVersionDiff(documentId, selectedVersion, currentVersion)
      .then((diff) => {
        if (!ignore) setDiffRows(flattenDiffHunks(diff.hunks));
      })
      .catch((error: unknown) => {
        if (!ignore) setErrorMessage(getErrorMessage(error, "버전 비교 결과를 불러오지 못했습니다."));
      })
      .finally(() => {
        if (!ignore) setIsDiffLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, [currentVersion, documentId, selectedVersion]);

  async function handleRestore() {
    if (selectedVersion === null || currentVersion === null || isRestoring) return;
    setIsRestoring(true);
    setErrorMessage(null);
    try {
      await restoreDocumentVersion(documentId, selectedVersion, currentVersion);
      setSelectedVersion(null);
      setDiffRows(null);
      await loadVersions();
      onRestored(documentId);
    } catch (error) {
      if (error instanceof VersionRestoreConflictError) {
        // 다른 저장이 먼저 반영됐다. 최신 목록으로 갱신해 사용자가 다시 비교하게 한다.
        setErrorMessage(error.message);
        void loadVersions().catch(() => {});
      } else {
        setErrorMessage(getErrorMessage(error, "버전 복원에 실패했습니다."));
      }
    } finally {
      setIsRestoring(false);
    }
  }

  const hasChanges = (diffRows?.some((row) => row.type === "delete" || row.type === "insert")) ?? false;

  return (
    <aside className={styles["history-panel"]} aria-label="문서 버전 기록" onClick={(event) => event.stopPropagation()}>
      <header className={styles["history-panel-header"]}>
        <strong>버전 기록</strong>
        <button type="button" aria-label="기록 닫기" onClick={onClose}>✕</button>
      </header>

      {errorMessage && <p className={styles["history-error"]} role="alert">{errorMessage}</p>}

      {versionData === null && !errorMessage ? (
        <p className={styles["history-empty"]}>버전 이력을 불러오는 중입니다.</p>
      ) : versionData !== null && versionData.versions.length === 0 ? (
        <p className={styles["history-empty"]}>아직 저장된 버전이 없습니다. 문서를 저장하면 버전이 기록됩니다.</p>
      ) : versionData !== null ? (
        <ol className={styles["history-list"]}>
          {versionData.versions.map((item) => (
            <li key={item.version}>
              <button
                type="button"
                className={`${styles["history-item"]}${item.version === selectedVersion ? ` ${styles["is-selected"]}` : ""}`}
                onClick={() => setSelectedVersion(item.version)}
              >
                <span className={styles["history-item-label"]}>
                  v{item.version}
                  {item.version === currentVersion && <em className={styles["history-item-badge"]}>현재</em>}
                </span>
                <span className={styles["history-item-time"]}>{formatTimestamp(item.created_at)}</span>
              </button>
            </li>
          ))}
        </ol>
      ) : null}

      {selected && (
        <section className={styles["history-detail"]} aria-label="선택한 버전과 현재 버전 비교">
          {isSelectedCurrent ? (
            <p className={styles["history-nodiff"]}>현재 버전입니다.</p>
          ) : isDiffLoading ? (
            <p className={styles["history-nodiff"]}>비교 결과를 불러오는 중입니다.</p>
          ) : diffRows !== null ? (
            <div className={styles["history-diff"]}>
              {hasChanges ? (
                diffRows.map((row, index) => (
                  <code className={styles[`is-${row.type}`]} key={`${row.type}-${index}`}>
                    <span aria-hidden="true">{DIFF_MARKERS[row.type]}</span>
                    {row.text || " "}
                  </code>
                ))
              ) : (
                <p className={styles["history-nodiff"]}>이 버전과 현재 문서가 동일합니다.</p>
              )}
            </div>
          ) : null}
          <button
            type="button"
            className={styles["history-restore"]}
            disabled={isSelectedCurrent || isDiffLoading || isRestoring || !hasChanges}
            onClick={() => void handleRestore()}
          >
            {isRestoring ? "복원 중…" : "이 버전으로 복원"}
          </button>
        </section>
      )}
    </aside>
  );
}
