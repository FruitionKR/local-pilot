import type { SvgAsset } from "./SvgIcon";
import {
  collectionIcon,
  homeIcon,
  lightningIcon,
  settingIcon,
  shareIcon,
  SvgIcon
} from "./SvgIcon";

export type RailView = "home" | "share" | "rules" | "logs" | "settings";

export const railItems: { id: RailView; label: string; icon: SvgAsset; isLarge?: boolean }[] = [
  { id: "home", label: "홈", icon: homeIcon },
  { id: "share", label: "공유", icon: shareIcon },
  { id: "rules", label: "규칙", icon: lightningIcon },
  { id: "logs", label: "로그", icon: collectionIcon, isLarge: true },
  { id: "settings", label: "설정", icon: settingIcon }
];

export function RailNavigation({ activeView, onViewChange }: { activeView: RailView; onViewChange: (view: RailView) => void }) {
  return (
    <aside className="rail">
      {railItems.map((item) => (
        <button
          key={item.id}
          className={`rail-item ${activeView === item.id ? "is-active" : ""}`}
          aria-label={item.label}
          aria-pressed={activeView === item.id}
          onClick={() => onViewChange(item.id)}
        >
          <span className={`rail-icon ${item.isLarge ? "is-large" : ""}`}>
            <SvgIcon src={item.icon} />
          </span>
          <span>{item.label}</span>
        </button>
      ))}
    </aside>
  );
}
