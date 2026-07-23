import { conceptPageIcon, rawPageIcon, sideboxIcon, sourcePageIcon, SvgIcon, type SvgAsset } from "@/shared/ui/SvgIcon";
import type { GraphFilterKind } from "../../_lib/types";

export function GraphFilterChips({
  rawDocumentCount,
  sourceNodeCount,
  conceptNodeCount,
  visibleKinds,
  onToggleKind,
  onRestoreAgentPanel
}: {
  rawDocumentCount: number;
  sourceNodeCount: number;
  conceptNodeCount: number;
  visibleKinds: Record<GraphFilterKind, boolean>;
  onToggleKind: (kind: GraphFilterKind) => void;
  onRestoreAgentPanel?: () => void;
}) {
  const chip = (kind: GraphFilterKind, icon: SvgAsset, iconClass: string, label: string, count: number) => (
    <button
      type="button"
      className={`filter-chip${visibleKinds[kind] ? "" : " is-inactive"}`}
      aria-pressed={visibleKinds[kind]}
      onClick={(event) => {
        event.stopPropagation();
        onToggleKind(kind);
      }}
    >
      <SvgIcon src={icon} className={`chip-icon ${iconClass}`} />{label} {count}
    </button>
  );

  return (
    <div className="filter-chips">
      {chip("raw", rawPageIcon, "raw", "원본 raw", rawDocumentCount)}
      {chip("source", sourcePageIcon, "source", "source page", sourceNodeCount)}
      {chip("concept", conceptPageIcon, "concept", "concept page", conceptNodeCount)}
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
