import { Plus } from "lucide-react";
import { useEffect, useState } from "react";
import { SvgIcon, toggleIcon } from "../SvgIcon";
import { useWorkspaceName } from "../../_hooks/useWorkspaceName";
import { createWorkspace, fetchWorkspaces } from "../../_lib/api";
import { setSelectedWorkspaceId } from "../../_lib/auth";
import { getErrorMessage } from "../../_lib/errors";
import type { WorkspaceResponse } from "../../_lib/types";

/** 선택한 워크스페이스로 전환하고 화면을 새로 그린다 */
function switchWorkspace(workspaceId: string) {
  setSelectedWorkspaceId(workspaceId);
  window.location.reload();
}

/** 사이드바 상단 워크스페이스 헤더: 노란 아바타 + 워크스페이스명 + hover 시 전환 드롭다운 */
export function SidebarWorkspaceHeader() {
  const workspaceName = useWorkspaceName();
  const name = workspaceName ?? "워크스페이스";
  const [isOpen, setIsOpen] = useState(false);
  const [workspaces, setWorkspaces] = useState<WorkspaceResponse[]>([]);
  const [loadErrorMessage, setLoadErrorMessage] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);

  useEffect(() => {
    if (!isOpen) return;

    let cancelled = false;
    fetchWorkspaces()
      .then((response) => {
        if (cancelled) return;
        setWorkspaces(response.workspaces ?? []);
        setLoadErrorMessage(null);
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoadErrorMessage(getErrorMessage(error, "워크스페이스를 불러오지 못했습니다."));
      });

    return () => {
      cancelled = true;
    };
  }, [isOpen]);

  async function handleCreateWorkspace() {
    if (isCreating) return;
    setIsCreating(true);
    try {
      const workspace = await createWorkspace("새 워크스페이스");
      switchWorkspace(workspace.id);
    } catch (error: unknown) {
      setLoadErrorMessage(getErrorMessage(error, "워크스페이스 생성에 실패했습니다."));
      setIsCreating(false);
    }
  }

  return (
    <div
      className="sidebar-workspace"
      onMouseEnter={() => setIsOpen(true)}
      onMouseLeave={() => setIsOpen(false)}
    >
      <span className="sidebar-workspace-mark" aria-hidden>{name.charAt(0)}</span>
      <button
        type="button"
        className="sidebar-workspace-name"
        aria-label="워크스페이스 전환"
        aria-expanded={isOpen}
        onClick={() => setIsOpen((open) => !open)}
      >
        <span>{name}</span>
        <SvgIcon src={toggleIcon} className="sidebar-workspace-toggle" />
      </button>

      {isOpen && (
        <div className="workspace-dropdown">
          {loadErrorMessage ? (
            <p className="workspace-dropdown-error" role="alert">{loadErrorMessage}</p>
          ) : (
            <div className="workspace-dropdown-list">
              {workspaces.map((workspace) => (
                <button
                  key={workspace.id}
                  type="button"
                  className="workspace-dropdown-item"
                  onClick={() => switchWorkspace(workspace.id)}
                >
                  <span className="workspace-dropdown-avatar" aria-hidden>{workspace.name.charAt(0)}</span>
                  <span className="workspace-dropdown-label">{workspace.name}</span>
                </button>
              ))}
            </div>
          )}
          <button
            type="button"
            className="workspace-dropdown-new"
            onClick={handleCreateWorkspace}
            disabled={isCreating}
          >
            <Plus size={12} />새 워크스페이스
          </button>
        </div>
      )}
    </div>
  );
}
