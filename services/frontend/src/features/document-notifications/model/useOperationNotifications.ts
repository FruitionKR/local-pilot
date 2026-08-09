"use client";

import { useEffect, useRef } from "react";
import { fetchOperationLogs, type OperationLogItem } from "@/entities/operation-log";
import { useUserPreferences } from "@/entities/user";
import { publishNotice } from "./noticeBus";

const POLL_INTERVAL_MS = 15_000;
const TERMINAL_STATUSES = new Set(["succeeded", "partially_succeeded", "failed", "conflict"]);

// ingest는 문서 처리 알림이 담당하고, document_edit은 채팅 화면에서 즉시 확인되므로 제외한다.
const WATCHED_TYPES: Record<string, string> = {
  lint: "위키 다듬기",
  restore: "복구"
};

function isTerminal(status: string) {
  return TERMINAL_STATUSES.has(status);
}

function noticeFor(item: OperationLogItem) {
  const label = WATCHED_TYPES[item.operation_type];
  const isFailed = item.status === "failed" || item.status === "conflict";
  return {
    kind: isFailed ? ("failed" as const) : ("completed" as const),
    title: `${label} ${isFailed ? "실패" : "완료"}`,
    message: item.summary || `${label} 작업이 ${isFailed ? "실패했습니다." : "완료되었습니다."}`
  };
}

/**
 * 오래 걸리는 AI 작업(lint·restore)의 종결을 감지해 알림을 발행한다.
 * ai-operation-logs 목록을 폴링하며, 진행 중 → 종결 전이와
 * 폴링 사이에 새로 나타난 종결 작업을 모두 잡는다. 유형별로 켜고 끌 수 있다.
 */
export function useOperationNotifications() {
  const { preferences } = useUserPreferences();
  const { lint: lintEnabled, restore: restoreEnabled } = preferences.notifications;
  const enabled = lintEnabled || restoreEnabled;
  const knownStatusesRef = useRef<Map<string, string> | null>(null);

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;

    async function poll() {
      let logs: OperationLogItem[];
      try {
        logs = (await fetchOperationLogs()).logs;
      } catch {
        // 워크스페이스 미선택·일시적 실패는 다음 폴링에서 재시도한다.
        return;
      }
      if (cancelled) return;

      const previous = knownStatusesRef.current;
      const next = new Map(logs.map((log) => [log.operation_id, log.status]));

      if (previous) {
        for (const log of logs) {
          const typeEnabled = log.operation_type === "lint" ? lintEnabled
            : log.operation_type === "restore" ? restoreEnabled
            : false;
          if (!typeEnabled || !isTerminal(log.status)) continue;
          const previousStatus = previous.get(log.operation_id);
          // 진행 중이었다가 종결됐거나, 폴링 사이에 새로 나타나 이미 종결된 작업
          if (!previousStatus || !isTerminal(previousStatus)) {
            publishNotice(noticeFor(log));
          }
        }
      }
      knownStatusesRef.current = next;
    }

    void poll();
    const timer = window.setInterval(() => void poll(), POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [enabled, lintEnabled, restoreEnabled]);
}
