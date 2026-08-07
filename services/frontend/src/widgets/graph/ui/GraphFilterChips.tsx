import { conceptPageIcon, rawPageIcon, sideboxIcon, sourcePageIcon, SvgIcon, type SvgAsset } from "@/shared/ui/SvgIcon";
import { cx } from "@/shared/lib/classNames";
import type { GraphFilterKind } from "@/entities/wiki";
import styles from "./Graph.module.css";

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
      className={cx(styles["filter-chip"], !visibleKinds[kind] && styles["is-inactive"])}
      aria-pressed={visibleKinds[kind]}
      onClick={(event) => {
        event.stopPropagation();
        onToggleKind(kind);
      }}
    >
      <SvgIcon src={icon} className={cx(styles["chip-icon"], styles[iconClass])} />{label} {count}
    </button>
  );

  return (
    <div className={styles["filter-chips"]}>
      {chip("raw", rawPageIcon, "raw", "원본 raw", rawDocumentCount)}
      {chip("source", sourcePageIcon, "source", "source page", sourceNodeCount)}
      {chip("concept", conceptPageIcon, "concept", "concept page", conceptNodeCount)}
      {onRestoreAgentPanel && (
        <button
          type="button"
          className={styles["filter-chip-action"]}
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
