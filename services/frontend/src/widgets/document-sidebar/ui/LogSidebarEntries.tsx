import {
  formatOperationLogDescription,
  formatOperationLogTitle,
  groupOperationLogsByDate,
  type OperationLogItem
} from "@/entities/operation-log";
import { cx } from "@/shared/lib/classNames";
import { formatRelativeTime } from "@/shared/lib/time";
import styles from "./DocumentSidebar.module.css";

/** 로그 뷰에서 문서 트리 자리를 대신하는 최신순 작업 목록. 고른 1건만 메인에 상세로 뜬다. */
export function LogSidebarEntries({
  items,
  selectedOperationId,
  hasMore,
  errorMessage,
  loadMoreErrorMessage,
  isLoading,
  isLoadingMore,
  documentTitles,
  onSelect,
  onLoadMore
}: {
  items: OperationLogItem[];
  selectedOperationId: string | null;
  hasMore: boolean;
  errorMessage: string | null;
  loadMoreErrorMessage: string | null;
  isLoading: boolean;
  isLoadingMore: boolean;
  documentTitles: ReadonlyMap<string, string>;
  onSelect: (operationId: string) => void;
  onLoadMore: () => void;
}) {
  if (errorMessage) {
    return <p className={styles["log-entries-message"]} role="alert">{errorMessage}</p>;
  }
  if (isLoading) {
    return <p className={styles["log-entries-message"]}>로그 불러오는 중…</p>;
  }
  if (items.length === 0) {
    return <p className={styles["log-entries-message"]}>아직 기록된 AI 작업이 없습니다.</p>;
  }
  const groups = groupOperationLogsByDate(items);

  return (
    <div className={styles["log-entries"]} role="listbox" aria-label="작업 로그">
      {groups.map((group) => (
        <section key={group.dateKey} className={styles["log-date-group"]} aria-label={group.label}>
          <div className={styles["log-date-heading"]}>
            <span>{group.label}</span>
            <span className={styles["log-date-divider"]} aria-hidden />
          </div>
          {group.items.map((item) => (
            <button
              key={item.operation_id}
              type="button"
              role="option"
              aria-selected={item.operation_id === selectedOperationId}
              className={cx(
                styles["log-entry"],
                item.operation_id === selectedOperationId && styles["is-active"]
              )}
              onClick={(event) => {
                event.stopPropagation();
                onSelect(item.operation_id);
              }}
            >
              <span className={styles["log-entry-label"]}>
                {formatOperationLogTitle(item)}
              </span>
              <span className={styles["log-entry-time"]}>{formatRelativeTime(item.created_at)}</span>
              <span className={styles["log-entry-summary"]}>
                {formatOperationLogDescription(
                  item,
                  item.target_display_name ?? (item.target_document_id
                    ? documentTitles.get(item.target_document_id)
                    : undefined)
                )}
              </span>
            </button>
          ))}
        </section>
      ))}
      {hasMore && (
        <button
          type="button"
          className={styles["log-entries-more"]}
          onClick={(event) => {
            event.stopPropagation();
            onLoadMore();
          }}
          disabled={isLoadingMore}
        >
          {isLoadingMore ? "불러오는 중…" : loadMoreErrorMessage ? "다시 시도" : "더 보기"}
        </button>
      )}
      {loadMoreErrorMessage && (
        <p className={styles["log-entries-message"]} role="alert">{loadMoreErrorMessage}</p>
      )}
    </div>
  );
}
