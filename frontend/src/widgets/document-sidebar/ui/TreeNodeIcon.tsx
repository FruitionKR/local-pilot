import type { TreeItem } from "@/entities/tree";
import { isFileItem } from "@/entities/tree";
import {
  arrowIcon,
  fileIcon,
  sourceIcon,
  SvgIcon
} from "@/shared/ui/SvgIcon";
import { cx } from "@/shared/lib/classNames";
import styles from "./DocumentSidebar.module.css";

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
    return <span className={styles["tree-folder-slot"]} aria-hidden />;
  }
  if (item.wikiKind === "source") return <SvgIcon src={sourceIcon} className={cx(styles["tree-asset"], styles.source)} />;
  if (item.wikiKind === "concept") return <SvgIcon src={fileIcon} className={cx(styles["tree-asset"], styles.concept)} />;
  return (
    <span className={styles["tree-folder-slot"]} aria-hidden>
      {hasChildren && <SvgIcon src={arrowIcon} className={cx(styles["tree-arrow"], isOpen && styles["is-open"])} />}
    </span>
  );
}
