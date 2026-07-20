import { useEffect, useState } from "react";
import { fetchWorkspaces } from "../_lib/api";
import { getSelectedWorkspaceId } from "../_lib/auth";

/** 선택된 워크스페이스의 이름을 가져온다. 로드 전/실패 시 null을 유지한다. */
export function useWorkspaceName(): string | null {
  const [name, setName] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const selectedId = getSelectedWorkspaceId();

    fetchWorkspaces()
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
