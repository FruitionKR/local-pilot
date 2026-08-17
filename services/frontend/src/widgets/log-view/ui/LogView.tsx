"use client";

import { useEffect, useRef, useState } from "react";
import {
  fetchOperationLogDetail,
  fetchRestorePreview,
  restoreOperation,
  OPERATION_TYPE_LABELS,
  type OperationChange,
  type OperationLogDetail
} from "@/entities/operation-log";
import retryIcon from "../../../../svg/log/retry.svg";
import { publishNotice } from "@/features/document-notifications";
import { cx } from "@/shared/lib/classNames";
import { getErrorMessage } from "@/shared/lib/errors";
import { formatRelativeTime } from "@/shared/lib/time";
import { SvgIcon } from "@/shared/ui/SvgIcon";
import styles from "./LogView.module.css";

const STATUS_LABELS: Record<string, string> = {
  processing: "처리 중",
  applying: "반영 중",
  notify_pending: "반영 중",
  rebuilding: "재조립 중",
  partially_succeeded: "일부 성공",
  failed: "실패",
  conflict: "충돌"
};

/** 변경 리소스 하나의 diff를 Figma 754:10436 형태로 렌더링한다. */
function ChangeDiff({ change }: { change: OperationChange }) {
  const heading = change.change_summary || `${change.resource_type} · ${change.change_type}`;

  return (
    <div className={styles["change"]}>
      <p className={styles["change-heading"]}>{heading}</p>
      {change.diff_too_large ? (
        <p className={styles["change-notice"]}>변경 폭이 커서 diff를 표시할 수 없습니다.</p>
      ) : !change.hunks?.length ? (
        <p className={styles["change-notice"]}>
          {change.additions != null || change.deletions != null
            ? `추가 ${change.additions ?? 0}줄 · 삭제 ${change.deletions ?? 0}줄`
            : "표시할 변경 내용이 없습니다."}
        </p>
      ) : (
        change.hunks.map((hunk, hunkIndex) => (
          <div key={hunkIndex} className={styles["hunk"]}>
            <div className={styles["hunk-header"]}>
              {`@@ -${hunk.old_start},${hunk.old_lines} +${hunk.new_start},${hunk.new_lines} @@`}
            </div>
            {hunk.lines.map((line, lineIndex) => (
              <div
                key={lineIndex}
                className={cx(
                  styles["diff-line"],
                  line.type === "ADD" && styles["is-add"],
                  line.type === "DELETE" && styles["is-delete"]
                )}
              >
                <span className={styles["diff-gutter"]}>{line.old_line ?? ""}</span>
                <span className={styles["diff-gutter"]}>{line.new_line ?? ""}</span>
                <span className={styles["diff-content"]}>
                  {/* CONTEXT 행에도 같은 폭의 부호 칸을 둬야 본문 시작 위치가 어긋나지 않는다. */}
                  <span className={styles["diff-sign"]} aria-hidden>
                    {line.type === "ADD" ? "+" : line.type === "DELETE" ? "−" : " "}
                  </span>
                  {line.content}
                </span>
              </div>
            ))}
          </div>
        ))
      )}
    </div>
  );
}

/** 로그 화면 (Figma 747:6105). 사이드바에서 고른 작업 1건의 상세만 그린다. */
export function LogView({
  operationId,
  restoredOperationIds,
  onRestoreComplete
}: {
  operationId: string | null;
  restoredOperationIds: ReadonlySet<string>;
  onRestoreComplete: (operationId: string) => Promise<void>;
}) {
  const [detail, setDetail] = useState<OperationLogDetail | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isRestoring, setIsRestoring] = useState(false);
  const [locallyRestoredOperationIds, setLocallyRestoredOperationIds] = useState<ReadonlySet<string>>(new Set());
  // 선택이 바뀌면 이전 작업의 응답을 버린다. 늦게 온 응답이 다른 작업을 덮어쓰지 않게 한다.
  const requestIdRef = useRef(0);

  useEffect(() => {
    const requestId = ++requestIdRef.current;
    setDetail(null);
    setErrorMessage(null);
    if (!operationId) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    fetchOperationLogDetail(operationId)
      .then((response) => {
        if (requestIdRef.current === requestId) setDetail(response);
      })
      .catch((error: unknown) => {
        if (requestIdRef.current === requestId) {
          setErrorMessage(getErrorMessage(error, "로그 상세를 불러오지 못했습니다."));
        }
      })
      .finally(() => {
        if (requestIdRef.current === requestId) setIsLoading(false);
      });
  }, [operationId]);

  const statusLabel = detail ? STATUS_LABELS[detail.status] : undefined;
  const canRestore = detail != null
    && detail.operation_type !== "restore"
    && (detail.status === "succeeded" || detail.status === "partially_succeeded")
    && !restoredOperationIds.has(detail.operation_id)
    && !locallyRestoredOperationIds.has(detail.operation_id);

  async function handleRestore() {
    if (!detail || !canRestore || isRestoring) return;
    setIsRestoring(true);
    try {
      const preview = await fetchRestorePreview(detail.operation_id);
      const affectedCount = preview.delete_count + preview.restore_count + preview.rebuild_count;
      const confirmed = window.confirm(
        affectedCount > 0
          ? `${affectedCount}개 Wiki 페이지에 영향을 줍니다. 이 작업을 롤백할까요?`
          : "이 작업을 롤백할까요?"
      );
      if (!confirmed) return;
      const result = await restoreOperation(detail.operation_id, preview.preview_token);
      setLocallyRestoredOperationIds((current) => new Set(current).add(detail.operation_id));
      publishNotice({
        kind: "completed",
        title: "롤백 요청 완료",
        message: result.status === "succeeded" ? "작업을 롤백했습니다." : "롤백 작업을 시작했습니다."
      });
      await onRestoreComplete(result.operation_id);
    } catch (error: unknown) {
      publishNotice({
        kind: "failed",
        title: "롤백 실패",
        message: getErrorMessage(error, "롤백 요청에 실패했습니다.")
      });
    } finally {
      setIsRestoring(false);
    }
  }

  return (
    <section className={styles["logs"]} aria-label="로그">
      <div className={styles["logs-inner"]}>
        <header className={styles["logs-header"]}>
          <div>
            <h2>
              로그
            </h2>
            <p>워크스페이스 활동 기록입니다.</p>
          </div>
        </header>

        {errorMessage ? (
          <p className={styles["logs-message"]} role="alert">{errorMessage}</p>
        ) : !operationId ? (
          <p className={styles["logs-message"]}>왼쪽 목록에서 작업을 선택하세요.</p>
        ) : isLoading || !detail ? (
          <p className={styles["logs-message"]}>로그 불러오는 중…</p>
        ) : (
          <article className={styles["card"]}>
            <header className={styles["card-header"]}>
              <h3># {OPERATION_TYPE_LABELS[detail.operation_type]}</h3>
              <p className={styles["card-description"]}>
                {detail.summary && <span>{detail.summary}</span>}
                {statusLabel && (
                  <span className={cx(styles["card-status"], detail.status === "failed" && styles["is-failed"])}>
                    {statusLabel}
                  </span>
                )}
              </p>
              <span className={styles["card-time"]}>{formatRelativeTime(detail.created_at)}</span>
              {canRestore && (
                <button
                  type="button"
                  className={styles["rollback-button"]}
                  onClick={() => void handleRestore()}
                  disabled={isRestoring}
                >
                  <SvgIcon src={retryIcon} className={styles["rollback-icon"]} />
                  {isRestoring ? "처리 중…" : "Rollback"}
                </button>
              )}
            </header>
            {detail.changes.length === 0 ? (
              <p className={styles["card-notice"]}>변경된 리소스가 없습니다.</p>
            ) : (
              <div className={styles["card-body"]}>
                {detail.changes.map((change) => (
                  <ChangeDiff key={change.id} change={change} />
                ))}
              </div>
            )}
          </article>
        )}
      </div>
    </section>
  );
}
