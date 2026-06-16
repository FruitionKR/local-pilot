import { sideboxIcon, sparkleIcon, SvgIcon } from "../SvgIcon";

export function AgentHeader({ onClose }: { onClose: () => void }) {
  return (
    <div className="agent-header">
      <div className="agent-mark"><SvgIcon src={sparkleIcon} /></div>
      <div>
        <h2>Fruition Agent</h2>
        <p>자료 검색 에이전트 · 부산대 워크스페이스</p>
      </div>
      <button className="panel-action" aria-label="Agent 패널 숨기기" onClick={onClose}>
        <SvgIcon src={sideboxIcon} />
      </button>
    </div>
  );
}
