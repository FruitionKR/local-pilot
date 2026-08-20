"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchMe } from "@/entities/user/api/auth";

const ME_QUERY_KEY = ["me"] as const;
const ME_STALE_TIME_MS = 60_000;

/** 로그인한 사용자 정보를 react-query 캐시로 공유한다. 여러 컴포넌트가 써도 중복 호출되지 않는다. */
export function useMe(options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ME_QUERY_KEY,
    queryFn: fetchMe,
    staleTime: ME_STALE_TIME_MS,
    enabled: options?.enabled ?? true
  });
}
