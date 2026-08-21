"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  appendLogPage,
  collectRestoredOperationIds,
  fetchOperationLogs,
  filterVisibleOperationLogs,
  mergeRefreshedLogPage,
  pickSelectedOperationId,
  type OperationLogItem
} from "@/entities/operation-log";
import { getErrorMessage } from "@/shared/lib/errors";

/** 사이드바 목록 한 페이지 크기. 백엔드 기본은 20, 최대는 100이다. */
const PAGE_SIZE = 30;
const LOG_POLL_INTERVAL_MS = 5_000;

/**
 * 로그 뷰의 작업 목록. 유형 조건 없이 최신순 한 페이지를 받고 가장 최근 작업을 자동으로 고른다.
 * 항목 선택은 목록을 다시 받지 않으며, 더 보기만 커서로 다음 페이지를 이어붙인다.
 */
export function useOperationLogFeed(isActive: boolean) {
  const [items, setItems] = useState<OperationLogItem[]>([]);
  const [selectedOperationId, setSelectedOperationId] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [loadMoreErrorMessage, setLoadMoreErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  // 로그 뷰를 다시 열면 이전 요청의 응답을 버린다.
  const requestIdRef = useRef(0);
  const silentPollInFlightRef = useRef(false);

  const refresh = useCallback(async (
    preferredOperationId?: string,
    options?: { silent?: boolean }
  ) => {
    if (!isActive) return;
    const silent = options?.silent === true;
    if (silent && silentPollInFlightRef.current) return;
    const requestId = silent ? requestIdRef.current : ++requestIdRef.current;
    if (silent) {
      silentPollInFlightRef.current = true;
    } else {
      setIsLoading(true);
      setErrorMessage(null);
      setLoadMoreErrorMessage(null);
    }
    try {
      const response = await fetchOperationLogs({ size: PAGE_SIZE });
      if (requestIdRef.current !== requestId) return;
      const visibleLogs = filterVisibleOperationLogs(response.logs);
      if (silent) {
        setItems((previous) => {
          const merged = mergeRefreshedLogPage(previous, visibleLogs);
          setSelectedOperationId((current) => pickSelectedOperationId(
            merged,
            preferredOperationId ?? current
          ));
          return merged;
        });
      } else {
        setItems(visibleLogs);
        setNextCursor(response.next_cursor);
        setSelectedOperationId((current) => pickSelectedOperationId(
          visibleLogs,
          preferredOperationId ?? current
        ));
      }
    } catch (error: unknown) {
      if (requestIdRef.current === requestId && !silent) {
        setErrorMessage(getErrorMessage(error, "로그를 불러오지 못했습니다."));
      }
    } finally {
      if (silent) silentPollInFlightRef.current = false;
      if (requestIdRef.current === requestId && !silent) setIsLoading(false);
    }
  }, [isActive]);

  useEffect(() => {
    if (!isActive) {
      // 화면을 떠난 뒤 도착한 목록 응답이 현재 상태를 덮지 못하게 한다.
      requestIdRef.current += 1;
      silentPollInFlightRef.current = false;
      setIsLoading(false);
      setIsLoadingMore(false);
      return;
    }
    void refresh();
  }, [isActive, refresh]);

  useEffect(() => {
    if (!isActive) return;
    const intervalId = window.setInterval(
      () => void refresh(undefined, { silent: true }),
      LOG_POLL_INTERVAL_MS
    );
    return () => window.clearInterval(intervalId);
  }, [isActive, refresh]);

  const selectOperation = useCallback((operationId: string) => {
    setSelectedOperationId(operationId);
  }, []);

  const loadMore = useCallback(async () => {
    if (!nextCursor || isLoadingMore) return;
    const requestId = requestIdRef.current;
    setIsLoadingMore(true);
    setLoadMoreErrorMessage(null);
    try {
      const response = await fetchOperationLogs({ cursor: nextCursor, size: PAGE_SIZE });
      if (requestIdRef.current !== requestId) return;
      setItems((previous) => appendLogPage(previous, filterVisibleOperationLogs(response.logs)));
      setNextCursor(response.next_cursor);
    } catch (error: unknown) {
      if (requestIdRef.current === requestId) {
        setLoadMoreErrorMessage(getErrorMessage(error, "로그를 더 불러오지 못했습니다."));
      }
    } finally {
      if (requestIdRef.current === requestId) setIsLoadingMore(false);
    }
  }, [isLoadingMore, nextCursor]);

  const restoredOperationIds = useMemo(() => collectRestoredOperationIds(items), [items]);

  return {
    items,
    selectedOperationId,
    hasMore: Boolean(nextCursor),
    errorMessage,
    loadMoreErrorMessage,
    isLoading,
    isLoadingMore,
    restoredOperationIds,
    refresh,
    onSelect: selectOperation,
    onLoadMore: () => void loadMore()
  };
}
