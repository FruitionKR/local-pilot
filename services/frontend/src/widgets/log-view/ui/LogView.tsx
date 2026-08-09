"use client";

import { useEffect, useRef, useState } from "react";
import {
  fetchOperationLogDetail,
  fetchOperationLogs,
  type OperationChange,
  type OperationLogDetail,
  type OperationLogItem,
  type OperationType
} from "@/entities/operation-log";
import { publishNotice } from "@/features/document-notifications";
import { requestWikiLint } from "@/features/document-notifications/api/wikiLint";
import { cx } from "@/shared/lib/classNames";
import { getErrorMessage } from "@/shared/lib/errors";
import { formatRelativeTime } from "@/shared/lib/time";
import styles from "./LogView.module.css";

const OPERATION_TYPE_LABELS: Record<OperationType, string> = {
  ingest: "위키 페이지 생성",
  document_edit: "문서 AI 편집",
  lint: "위키 다듬기",
  restore: "복구"
};

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
            {hunk.lines.map((line, lineIndex) => (
              <div
                key={lineIndex}
                className={cx(
                  styles["diff-line"],
                  line.type === "ADD" && styles["is-add"],
                  line.type === "DELETE" && styles["is-delete"]
                )}
              >
                <span className={styles["diff-gutter"]}>
                  {line.type === "DELETE" ? line.old_line : line.new_line ?? line.old_line}
                </span>
                <span className={styles["diff-content"]}>
                  {line.type !== "CONTEXT" && (
                    <span className={styles["diff-sign"]} aria-hidden>
                      {line.type === "ADD" ? "+" : "−"}
                    </span>
                  )}
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

/** 작업 1건 카드. 뷰포트에 들어오면 상세(diff)를 lazy 로드한다. */
function LogCard({ item }: { item: OperationLogItem }) {
  const [detail, setDetail] = useState<OperationLogDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [isVisible, setIsVisible] = useState(false);
  const cardRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    const element = cardRef.current;
    if (!element) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setIsVisible(true);
          observer.disconnect();
        }
      },
      { rootMargin: "120px" }
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!isVisible) return;
    let cancelled = false;
    fetchOperationLogDetail(item.operation_id)
      .then((response) => {
        if (!cancelled) setDetail(response);
      })
      .catch((error: unknown) => {
        if (!cancelled) setDetailError(getErrorMessage(error, "로그 상세를 불러오지 못했습니다."));
      });
    return () => {
      cancelled = true;
    };
  }, [isVisible, item.operation_id]);

  const statusLabel = STATUS_LABELS[item.status];

  return (
    <article className={styles["card"]} ref={cardRef}>
      <header className={styles["card-header"]}>
        <h3># {OPERATION_TYPE_LABELS[item.operation_type] ?? item.operation_type}</h3>
        <p className={styles["card-description"]}>
          {item.summary && <span>{item.summary}</span>}
          {statusLabel && (
            <span className={cx(styles["card-status"], item.status === "failed" && styles["is-failed"])}>
              {statusLabel}
            </span>
          )}
        </p>
        <span className={styles["card-time"]}>{formatRelativeTime(item.created_at)}</span>
      </header>
      {detailError ? (
        <p className={styles["card-notice"]}>{detailError}</p>
      ) : !detail ? (
        <p className={styles["card-notice"]}>변경 내용 불러오는 중…</p>
      ) : detail.changes.length === 0 ? (
        <p className={styles["card-notice"]}>변경된 리소스가 없습니다.</p>
      ) : (
        <div className={styles["card-body"]}>
          {detail.changes.map((change) => (
            <ChangeDiff key={change.id} change={change} />
          ))}
        </div>
      )}
    </article>
  );
}

/** 로그 화면 (Figma 747:6105). 작업 목록을 먼저 그리고, 카드가 보일 때 diff 상세를 채운다. */
export function LogView() {
  const [items, setItems] = useState<OperationLogItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [isLintChecking, setIsLintChecking] = useState(false);

  // 위키 다듬기 상시 진입점: dry-run으로 검사한 뒤 대상이 있으면 실행 카드를 띄운다.
  async function handleLintCheck() {
    if (isLintChecking) return;
    setIsLintChecking(true);
    try {
      const { changedPageCount } = await requestWikiLint(true);
      if (changedPageCount === 0) {
        publishNotice({
          kind: "info",
          title: "위키 다듬기",
          message: "지금은 다듬을 페이지가 없습니다."
        });
        return;
      }
      publishNotice({
        kind: "info",
        title: "위키 다듬기",
        message: `다듬을 페이지가 ${changedPageCount}개 있습니다.`,
        action: {
          label: "다듬기",
          onAction: () => {
            requestWikiLint(false).catch((error: unknown) => {
              publishNotice({
                kind: "failed",
                title: "위키 다듬기 실패",
                message: getErrorMessage(error, "위키 다듬기 요청에 실패했습니다.")
              });
            });
          }
        }
      });
    } catch (error: unknown) {
      publishNotice({
        kind: "failed",
        title: "위키 다듬기 검사 실패",
        message: getErrorMessage(error, "위키 다듬기 검사에 실패했습니다.")
      });
    } finally {
      setIsLintChecking(false);
    }
  }

  useEffect(() => {
    let cancelled = false;
    fetchOperationLogs()
      .then((response) => {
        if (cancelled) return;
        setItems(response.logs);
        setNextCursor(response.next_cursor);
      })
      .catch((error: unknown) => {
        if (!cancelled) setErrorMessage(getErrorMessage(error, "로그를 불러오지 못했습니다."));
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function handleLoadMore() {
    if (!nextCursor || isLoadingMore) return;
    setIsLoadingMore(true);
    try {
      const response = await fetchOperationLogs(nextCursor);
      setItems((prev) => [...prev, ...response.logs]);
      setNextCursor(response.next_cursor);
    } catch (error: unknown) {
      setErrorMessage(getErrorMessage(error, "로그를 불러오지 못했습니다."));
    } finally {
      setIsLoadingMore(false);
    }
  }

  return (
    <section className={styles["logs"]} aria-label="로그">
      <div className={styles["logs-inner"]}>
        <header className={styles["logs-header"]}>
          <div>
            <h2>
              로그
              <span className={styles["logs-badge"]}>미리보기</span>
            </h2>
            <p>워크스페이스 활동 기록입니다.</p>
          </div>
          <button
            type="button"
            className={styles["logs-lint-button"]}
            onClick={() => void handleLintCheck()}
            disabled={isLintChecking}
          >
            {isLintChecking ? "검사 중…" : "위키 다듬기"}
          </button>
        </header>

        {errorMessage ? (
          <p className={styles["logs-message"]} role="alert">{errorMessage}</p>
        ) : isLoading ? (
          <p className={styles["logs-message"]}>로그 불러오는 중…</p>
        ) : items.length === 0 ? (
          <p className={styles["logs-message"]}>아직 기록된 AI 작업이 없습니다.</p>
        ) : (
          <>
            {items.map((item) => (
              <LogCard key={item.operation_id} item={item} />
            ))}
            {nextCursor && (
              <button
                type="button"
                className={styles["logs-more"]}
                onClick={handleLoadMore}
                disabled={isLoadingMore}
              >
                {isLoadingMore ? "불러오는 중…" : "더 보기"}
              </button>
            )}
          </>
        )}
      </div>
    </section>
  );
}
