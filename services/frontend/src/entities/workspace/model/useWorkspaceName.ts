"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchWorkspaces } from "@/entities/workspace/api/workspace";
import { getSelectedWorkspaceId } from "@/shared/lib/auth";

// 여러 컴포넌트(AgentHeader·SidebarWorkspaceHeader)가 동시에 이름을 조회해도
// GET /api/workspaces가 react-query 캐시로 공유되어 중복 호출되지 않는다.
// 워크스페이스 전환·생성은 전체 페이지 리로드를 하므로 캐시는 자연히 초기화된다.
const WORKSPACES_QUERY_KEY = ["workspaces"] as const;

/** 선택된 워크스페이스의 이름을 가져온다. 로드 전/실패 시 null을 유지한다. */
export function useWorkspaceName(): string | null {
  const { data } = useQuery({
    queryKey: WORKSPACES_QUERY_KEY,
    queryFn: fetchWorkspaces,
    staleTime: Infinity
  });

  if (!data) return null;
  const workspaces = data.workspaces ?? [];
  const selected = workspaces.find((workspace) => workspace.id === getSelectedWorkspaceId()) ?? workspaces[0];
  return selected?.name ?? null;
}
