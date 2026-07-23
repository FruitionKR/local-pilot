import { useEffect, useState } from "react";
import { fetchWorkspaces } from "../_lib/api";
import { getSelectedWorkspaceId } from "@/shared/lib/auth";
import type { WorkspaceListResponse } from "../_lib/types";

// 여러 컴포넌트(AgentHeader·SidebarWorkspaceHeader)가 동시에 이름을 조회해도
// GET /api/workspaces가 중복 호출되지 않도록 진행 중인 요청을 공유한다.
// 워크스페이스 전환·생성은 전체 페이지 리로드를 하므로 캐시는 자연히 초기화된다.
let workspacesPromise: Promise<WorkspaceListResponse> | null = null;

function loadWorkspaces(): Promise<WorkspaceListResponse> {
  if (!workspacesPromise) {
    workspacesPromise = fetchWorkspaces().catch((error: unknown) => {
      workspacesPromise = null; // 실패 시 다음 호출에서 재시도할 수 있도록 캐시 해제
      throw error;
    });
  }
  return workspacesPromise;
}

/** 선택된 워크스페이스의 이름을 가져온다. 로드 전/실패 시 null을 유지한다. */
export function useWorkspaceName(): string | null {
  const [name, setName] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const selectedId = getSelectedWorkspaceId();

    loadWorkspaces()
      .then((response) => {
        if (cancelled) return;
        const workspaces = response.workspaces ?? [];
        const selected = workspaces.find((workspace) => workspace.id === selectedId) ?? workspaces[0];
        setName(selected?.name ?? null);
      })
      .catch(() => {
        // 이름 표시용 데이터라 실패해도 화면은 fallback 문구로 유지한다.
        if (!cancelled) setName(null);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return name;
}
