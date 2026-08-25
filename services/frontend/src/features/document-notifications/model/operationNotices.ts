import type { OperationLogItem } from "@/entities/operation-log";
import type { NoticePayload } from "./noticeBus";

const TERMINAL_STATUSES = new Set(["succeeded", "partially_succeeded", "failed", "conflict"]);

// ingest는 문서 처리 알림이 담당하고, document_edit은 채팅 화면에서 즉시 확인되므로 제외한다.
const WATCHED_TYPES: Record<string, string> = {
  lint: "Lint",
  restore: "복구"
};

export type NotificationToggles = { lint: boolean; restore: boolean };

export function isTerminalStatus(status: string): boolean {
  return TERMINAL_STATUSES.has(status);
}

export function noticeFor(item: OperationLogItem): NoticePayload {
  const label = WATCHED_TYPES[item.operation_type];
  const isFailed = item.status === "failed" || item.status === "conflict";
  return {
    kind: isFailed ? "failed" : "completed",
    title: `${label} ${isFailed ? "실패" : "완료"}`,
    message: item.summary || `${label} 작업이 ${isFailed ? "실패했습니다." : "완료되었습니다."}`
  };
}

/**
 * 이번 폴링에서 발행할 알림. 진행 중 → 종결 전이와, 폴링 사이에 새로 나타나 이미 종결된
 * 작업을 모두 잡는다. {@code previous}가 null인 첫 폴링은 기준선만 잡고 아무것도 발행하지 않는다.
 */
export function collectTerminalNotices(
  previous: ReadonlyMap<string, string> | null,
  logs: OperationLogItem[],
  toggles: NotificationToggles
): NoticePayload[] {
  if (!previous) return [];
  const notices: NoticePayload[] = [];
  for (const log of logs) {
    const typeEnabled = log.operation_type === "lint" ? toggles.lint
      : log.operation_type === "restore" ? toggles.restore
      : false;
    if (!typeEnabled || !isTerminalStatus(log.status)) continue;
    const previousStatus = previous.get(log.operation_id);
    if (!previousStatus || !isTerminalStatus(previousStatus)) {
      notices.push(noticeFor(log));
    }
  }
  return notices;
}

/**
 * 다음 폴링의 비교 기준.
 *
 * 조회를 여러 번 나눠 하므로 일부만 성공할 수 있다. 그때 받은 것만으로 기준을 덮으면
 * 못 본 작업이 사라진 것처럼 보이고, 다음 폴링에서 "새로 나타난 종결"로 잡혀 같은 알림이
 * 다시 뜬다. 부분 성공이면 이전 기준 위에 덮어쓴다.
 */
export function nextKnownStatuses(
  previous: ReadonlyMap<string, string> | null,
  logs: OperationLogItem[],
  complete: boolean
): Map<string, string> {
  const seen = new Map(logs.map((log) => [log.operation_id, log.status]));
  if (complete || !previous) return seen;
  return new Map([...previous, ...seen]);
}
