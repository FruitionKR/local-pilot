"use client";

import { useEffect, useRef } from "react";
import { fetchOperationLogs } from "@/entities/operation-log";
import { useUserPreferences } from "@/entities/user";
import { collectTerminalNotices, nextKnownStatuses } from "./operationNotices";
import { publishNotice } from "./noticeBus";

const POLL_INTERVAL_MS = 15_000;

/**
 * 백엔드는 status를 생략한 목록에서 실패를 걷어낸다(되돌릴 대상이 없어 목록에서 할 일이 없다).
 * 알림은 실패를 놓치면 안 되므로 명시 조회로 따로 받아 합친다.
 */
const FAILURE_STATUSES = ["failed", "conflict"] as const;

/**
 * 오래 걸리는 AI 작업(lint·restore)의 종결을 감지해 알림을 발행한다.
 * ai-operation-logs 목록을 폴링하며, 진행 중 → 종결 전이와
 * 폴링 사이에 새로 나타난 종결 작업을 모두 잡는다. 유형별로 켜고 끌 수 있다.
 *
 * 성공·부분 성공은 기본 목록에, 실패는 status 명시 조회에 나온다. 셋을 합쳐야 종결을 빠짐없이 본다.
 * 조회를 나눠 하므로 하나가 실패해도 나머지로 알림을 발행한다. 전부 실패했을 때만 건너뛴다.
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
      const settled = await Promise.allSettled([
        fetchOperationLogs(),
        ...FAILURE_STATUSES.map((status) => fetchOperationLogs({ status }))
      ]);
      if (cancelled) return;

      const received = settled.flatMap((result) =>
        result.status === "fulfilled" ? [result.value] : []);
      // 워크스페이스 미선택·일시적 실패는 다음 폴링에서 재시도한다.
      if (received.length === 0) return;

      const logs = received.flatMap((response) => response.logs);
      const previous = knownStatusesRef.current;

      for (const notice of collectTerminalNotices(previous, logs, {
        lint: lintEnabled,
        restore: restoreEnabled
      })) {
        publishNotice(notice);
      }
      knownStatusesRef.current =
        nextKnownStatuses(previous, logs, received.length === settled.length);
    }

    void poll();
    const timer = window.setInterval(() => void poll(), POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [enabled, lintEnabled, restoreEnabled]);
}
