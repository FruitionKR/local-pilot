import { cx } from "@/shared/lib/classNames";
import type { RailView } from "@/widgets/rail-navigation/ui/RailNavigation";
import { homeIcon, menuArchiveIcon, menuSearchIcon, plusIcon, shareIcon, SvgIcon, type SvgAsset } from "@/shared/ui/SvgIcon";
import styles from "./DocumentSidebar.module.css";

// Figma 747:5861 — 좌측 홈/그래프/로그(Archive)/검색, 우측 +
const menuItems: { id: RailView; label: string; icon: SvgAsset }[] = [
  { id: "home", label: "홈", icon: homeIcon },
  { id: "graph", label: "그래프", icon: shareIcon },
  { id: "logs", label: "로그", icon: menuArchiveIcon }
];

/** 사이드바 가로 아이콘 메뉴 줄. 활성 항목만 라벨이 있는 pill로 표시한다. */
export function SidebarMenuRow({
  activeView,
  isSearchOpen,
  onViewChange,
  onToggleSearch,
  onAddProject
}: {
  activeView: RailView;
  isSearchOpen: boolean;
  onViewChange: (view: RailView) => void;
  onToggleSearch: () => void;
  onAddProject: () => void;
}) {
  return (
    <nav className={styles["sidebar-menu"]} aria-label="워크스페이스 메뉴">
      {menuItems.map((item) => (
        <button
          key={item.id}
          type="button"
          className={cx(styles["sidebar-menu-item"], activeView === item.id && styles["is-active"])}
          aria-label={item.label}
          aria-pressed={activeView === item.id}
          onClick={(event) => {
            event.stopPropagation();
            onViewChange(item.id);
          }}
        >
          <SvgIcon src={item.icon} className={styles["sidebar-menu-icon"]} />
          {activeView === item.id && <span>{item.label}</span>}
        </button>
      ))}
      <button
        type="button"
        className={cx(styles["sidebar-menu-item"], isSearchOpen && styles["is-open"])}
        aria-label="문서 검색"
        aria-expanded={isSearchOpen}
        onClick={(event) => {
          event.stopPropagation();
          onToggleSearch();
        }}
      >
        <SvgIcon src={menuSearchIcon} className={styles["sidebar-menu-icon"]} />
      </button>
      <button
        type="button"
        className={cx(styles["sidebar-menu-item"], styles["sidebar-menu-add"])}
        aria-label="새 폴더 생성"
        onClick={(event) => {
          event.stopPropagation();
          onAddProject();
        }}
      >
        <SvgIcon src={plusIcon} className={styles["sidebar-menu-icon"]} />
      </button>
    </nav>
  );
}
