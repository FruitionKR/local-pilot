import type { TreeItem } from "../../_lib/types";
import { isFileItem } from "../../_lib/tree";
import {
  arrowIcon,
  fileIcon,
  sourceIcon,
  SvgIcon
} from "@/shared/ui/SvgIcon";

export function TreeNodeIcon({
  item,
  hasChildren,
  isOpen
}: {
  item: TreeItem;
  hasChildren: boolean;
  isOpen: boolean;
}) {
  if (isFileItem(item)) {
    return <span className="tree-folder-slot" aria-hidden />;
  }
  if (item.wikiKind === "source") return <SvgIcon src={sourceIcon} className="tree-asset source" />;
  if (item.wikiKind === "concept") return <SvgIcon src={fileIcon} className="tree-asset concept" />;
  return (
    <span className="tree-folder-slot" aria-hidden>
      {hasChildren && <SvgIcon src={arrowIcon} className={`tree-arrow ${isOpen ? "is-open" : ""}`} />}
    </span>
  );
}
