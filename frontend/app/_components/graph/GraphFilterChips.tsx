import { conceptPageIcon, rawPageIcon, sideboxIcon, sourcePageIcon, SvgIcon } from "../SvgIcon";

export function GraphFilterChips({
  rawDocumentCount,
  sourceNodeCount,
  conceptNodeCount,
  onRestoreAgentPanel
}: {
  rawDocumentCount: number;
  sourceNodeCount: number;
  conceptNodeCount: number;
  onRestoreAgentPanel?: () => void;
}) {
  return (
    <div className="filter-chips">
      <span><SvgIcon src={rawPageIcon} className="chip-icon raw" />원본 raw {rawDocumentCount}</span>
      <span><SvgIcon src={sourcePageIcon} className="chip-icon source" />source page {sourceNodeCount}</span>
      <span><SvgIcon src={conceptPageIcon} className="chip-icon concept" />concept page {conceptNodeCount}</span>
      {onRestoreAgentPanel && (
        <button
          type="button"
          className="filter-chip-action"
          aria-label="Agent 패널 보이기"
          onClick={(event) => {
            event.stopPropagation();
            onRestoreAgentPanel();
          }}
        >
          <SvgIcon src={sideboxIcon} />
        </button>
      )}
    </div>
  );
}
