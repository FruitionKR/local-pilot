import { cx } from "@/shared/lib/classNames";
import { railItems, type RailView } from "../RailNavigation";
import { folderPlusIcon, SvgIcon } from "@/shared/ui/SvgIcon";

/** 사이드바 가로 아이콘 메뉴 줄. 활성 항목만 라벨이 있는 pill로 표시한다. */
export function SidebarMenuRow({
  activeView,
  onViewChange,
  onAddProject
}: {
  activeView: RailView;
  onViewChange: (view: RailView) => void;
  onAddProject: () => void;
}) {
  return (
    <nav className="sidebar-menu" aria-label="워크스페이스 메뉴">
      {railItems.map((item) => (
        <button
          key={item.id}
          type="button"
          className={cx("sidebar-menu-item", activeView === item.id && "is-active")}
          aria-label={item.label}
          aria-pressed={activeView === item.id}
          onClick={(event) => {
            event.stopPropagation();
            onViewChange(item.id);
          }}
        >
          <SvgIcon src={item.icon} className="sidebar-menu-icon" />
          {activeView === item.id && <span>{item.label}</span>}
        </button>
      ))}
      <button
        type="button"
        className="sidebar-menu-item sidebar-menu-add"
        aria-label="새 폴더 생성"
        onClick={(event) => {
          event.stopPropagation();
          onAddProject();
        }}
      >
        <SvgIcon src={folderPlusIcon} className="sidebar-menu-icon" />
      </button>
    </nav>
  );
}
