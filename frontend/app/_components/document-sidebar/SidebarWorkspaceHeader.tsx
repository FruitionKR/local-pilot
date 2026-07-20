import { SvgIcon, toggleIcon } from "../SvgIcon";
import { useWorkspaceName } from "../../_hooks/useWorkspaceName";

/** 사이드바 상단 워크스페이스 헤더: 노란 아바타 + 워크스페이스명 + 토글 버튼 */
export function SidebarWorkspaceHeader() {
  const workspaceName = useWorkspaceName();
  const name = workspaceName ?? "워크스페이스";

  return (
    <div className="sidebar-workspace">
      <span className="sidebar-workspace-mark" aria-hidden>{name.charAt(0)}</span>
      {/* 워크스페이스 전환 UI는 아직 없어 버튼만 둔다 */}
      <button type="button" className="sidebar-workspace-name" aria-label="워크스페이스 전환">
        <span>{name}</span>
        <SvgIcon src={toggleIcon} className="sidebar-workspace-toggle" />
      </button>
    </div>
  );
}
