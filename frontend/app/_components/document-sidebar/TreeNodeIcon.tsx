import type { TreeItem } from "../../_lib/types";
import { isFileItem } from "../../_lib/tree";
import {
  archiveIcon,
  arrowIcon,
  fileIcon,
  SvgIcon
} from "../SvgIcon";

export function TreeNodeIcon({
  item,
  hasChildren,
  isOpen
}: {
  item: TreeItem;
  hasChildren: boolean;
  isOpen: boolean;
}) {
  if (isFileItem(item)) return <SvgIcon src={fileIcon} className="tree-asset raw" />;
  if (item.wikiKind === "source") return <SvgIcon src={fileIcon} className="tree-asset source" />;
  if (item.wikiKind === "concept") return <SvgIcon src={fileIcon} className="tree-asset concept" />;
  if (hasChildren) return <SvgIcon src={arrowIcon} className={`tree-arrow ${isOpen ? "is-open" : ""}`} />;
  return <SvgIcon src={archiveIcon} className="tree-asset" />;
}
