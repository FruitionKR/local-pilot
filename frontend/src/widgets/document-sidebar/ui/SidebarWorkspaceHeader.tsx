import { Plus } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { SvgIcon, toggleIcon } from "@/shared/ui/SvgIcon";
import { useWorkspaceName } from "@/entities/workspace/model/useWorkspaceName";
import { createWorkspace, fetchWorkspaces } from "@/entities/workspace";
import { setSelectedWorkspaceId } from "@/shared/lib/auth";
import { getErrorMessage } from "@/shared/lib/errors";
import type { WorkspaceResponse } from "@/entities/workspace";

/** 선택한 워크스페이스로 전환하고 화면을 새로 그린다 */
function switchWorkspace(workspaceId: string) {
  setSelectedWorkspaceId(workspaceId);
  window.location.reload();
}

/** 사이드바 상단 워크스페이스 헤더: 클릭하면 워크스페이스 전환 메뉴를 연다. */
export function SidebarWorkspaceHeader() {
  const workspaceName = useWorkspaceName();
  const name = workspaceName ?? "워크스페이스";
  const [isOpen, setIsOpen] = useState(false);
  const [workspaces, setWorkspaces] = useState<WorkspaceResponse[]>([]);
  const [loadErrorMessage, setLoadErrorMessage] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!isOpen) return;

    let cancelled = false;
    function handleOutsidePointerDown(event: PointerEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setIsOpen(false);
    }

    document.addEventListener("pointerdown", handleOutsidePointerDown);
    document.addEventListener("keydown", handleKeyDown);
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
      document.removeEventListener("pointerdown", handleOutsidePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
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
    <div className="sidebar-workspace" ref={rootRef}>
      <button
        type="button"
        className="sidebar-workspace-trigger"
        aria-label="워크스페이스 전환"
        aria-expanded={isOpen}
        onClick={() => setIsOpen((open) => !open)}
      >
        <span className="sidebar-workspace-mark" aria-hidden>{name.charAt(0)}</span>
        <span className="sidebar-workspace-name">
          <span>{name}</span>
          <SvgIcon src={toggleIcon} className={`sidebar-workspace-toggle${isOpen ? " is-open" : ""}`} />
        </span>
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
                  onClick={() => {
                    setIsOpen(false);
                    switchWorkspace(workspace.id);
                  }}
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
