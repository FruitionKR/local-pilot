import { conceptPageIcon, rawPageIcon, sourceIcon, SvgIcon } from "../SvgIcon";

export function GraphFilterChips({
  rawDocumentCount,
  sourceNodeCount,
  conceptNodeCount
}: {
  rawDocumentCount: number;
  sourceNodeCount: number;
  conceptNodeCount: number;
}) {
  return (
    <div className="filter-chips">
      <span><SvgIcon src={rawPageIcon} className="chip-icon raw" />원본 raw {rawDocumentCount}</span>
      <span><SvgIcon src={sourceIcon} className="chip-icon source" />source page {sourceNodeCount}</span>
      <span><SvgIcon src={conceptPageIcon} className="chip-icon concept" />concept page {conceptNodeCount}</span>
    </div>
  );
}
