"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchOperationLogs, type OperationLogItem } from "@/entities/operation-log";

const LINT_POLL_INTERVAL_MS = 3000;
const ACTIVE_LINT_QUERY_KEY = ["activeLintOperation"] as const;

/**
 * 진행 중인 lint 작업을 ai-operation-logs에서 읽는다.
 * 목록을 백엔드에서 걸러 받는다. lint가 끝나지 않은 상태는 processing 하나뿐이고(끝나면
 * succeeded/partially_succeeded/failed), 최신 한 건이면 진행 여부를 판단하기 충분하다.
 * 진행 중이거나 방금 요청했을 때만 3초 폴링한다(useBackendData와 같은 방식).
 * 조회 실패는 진행 표시가 잠깐 비는 것뿐이라 react-query 재시도에 맡긴다.
 */
export function useActiveLintOperation(isLintRequested: boolean): OperationLogItem | null {
  const query = useQuery({
    queryKey: ACTIVE_LINT_QUERY_KEY,
    queryFn: () => fetchOperationLogs({ type: "lint", status: "processing", size: 1 }),
    refetchInterval: (activeQuery) =>
      isLintRequested || (activeQuery.state.data?.logs.length ?? 0) > 0
        ? LINT_POLL_INTERVAL_MS
        : false,
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: false
  });

  return query.data?.logs[0] ?? null;
}
