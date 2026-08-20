import type { OperationLogItem } from "./types";

export type OperationLogDateGroup = {
  dateKey: string;
  label: string;
  items: OperationLogItem[];
};

/** Figma 로그 사이드바처럼 최신순 작업을 사용자의 날짜 기준으로 묶는다. */
export function groupOperationLogsByDate(
  items: OperationLogItem[],
  timeZone?: string
): OperationLogDateGroup[] {
  const formatter = new Intl.DateTimeFormat("ko-KR", {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    timeZone
  });
  const groups: OperationLogDateGroup[] = [];

  for (const item of items) {
    const parts = formatter.formatToParts(new Date(item.created_at));
    const year = parts.find((part) => part.type === "year")?.value ?? "";
    const month = parts.find((part) => part.type === "month")?.value ?? "";
    const day = parts.find((part) => part.type === "day")?.value ?? "";
    const dateKey = `${year}-${month}-${day}`;
    const current = groups.at(-1);
    if (current?.dateKey === dateKey) {
      current.items.push(item);
    } else {
      groups.push({ dateKey, label: `${month}월 ${day}일`, items: [item] });
    }
  }

  return groups;
}

/** 목록과 상세 헤더가 같은 문서·작업 설명을 사용한다. */
export function formatOperationLogDescription(
  item: Pick<OperationLogItem, "summary">,
  documentTitle?: string
): string {
  const summary = item.summary?.trim();
  if (documentTitle && summary && summary !== documentTitle) {
    return `${documentTitle} / ${summary}`;
  }
  return documentTitle ?? summary ?? "상세 정보 없음";
}

/**
 * 최신순 로그 목록의 다음 페이지를 이어붙인다.
 * 커서 경계에서 같은 operation_id가 다시 오면 한 번만 남긴다.
 */
export function appendLogPage(
  previous: OperationLogItem[],
  incoming: OperationLogItem[]
): OperationLogItem[] {
  const seen = new Set(previous.map((item) => item.operation_id));
  const added = incoming.filter((item) => !seen.has(item.operation_id));
  return added.length ? [...previous, ...added] : previous;
}

/** 최신 첫 페이지를 갱신하되 사용자가 이미 불러온 이전 페이지는 뒤에 보존한다. */
export function mergeRefreshedLogPage(
  previous: OperationLogItem[],
  refreshed: OperationLogItem[]
): OperationLogItem[] {
  const refreshedIds = new Set(refreshed.map((item) => item.operation_id));
  const retained = previous.filter((item) => !refreshedIds.has(item.operation_id));
  return [...refreshed, ...retained];
}

/**
 * 목록에서 상세로 띄울 작업 하나를 고른다.
 * 이미 고른 작업이 목록에 남아 있으면 유지하고, 없으면 가장 최근 작업을 고른다.
 */
export function pickSelectedOperationId(
  items: OperationLogItem[],
  current: string | null
): string | null {
  if (current && items.some((item) => item.operation_id === current)) return current;
  return items[0]?.operation_id ?? null;
}

export function collectRestoredOperationIds(items: OperationLogItem[]): ReadonlySet<string> {
  return new Set(items.flatMap((item) => item.restored_from ? [item.restored_from] : []));
}
