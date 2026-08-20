import { useRef, useState } from "react";
import { useMe, useSignOut } from "@/entities/user";
import { SettingsModal } from "@/features/user-settings";
import { cx } from "@/shared/lib/classNames";
import { useDismissOnOutside } from "@/shared/lib/useDismissOnOutside";
import { profileToggleIcon, SvgIcon, userCircleIcon } from "@/shared/ui/SvgIcon";
import styles from "./DocumentSidebar.module.css";

/** 사이드바 하단 프로필 푸터 (Figma 747:6648): 화살표 클릭 시 설정/로그아웃 메뉴를 연다. */
export function SidebarProfile() {
  const { signOut } = useSignOut();
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const rootRef = useRef<HTMLElement | null>(null);

  // 표시용 데이터라 실패 시 fallback 이름을 유지한다.
  const { data: me } = useMe();
  const displayName = me ? me.display_name || me.email : null;

  useDismissOnOutside(rootRef, isMenuOpen, () => setIsMenuOpen(false));

  const name = displayName ?? "사용자";

  return (
    <footer className={styles["sidebar-profile"]} ref={rootRef}>
      <div className={styles["sidebar-profile-row"]}>
        <span className={styles["sidebar-profile-user"]}>
          <SvgIcon src={userCircleIcon} className={styles["sidebar-profile-avatar"]} />
          <span className={styles["sidebar-profile-info"]}>
            <strong>{name}</strong>
            <small>온라인</small>
          </span>
        </span>
        <button
          type="button"
          className={styles["sidebar-profile-toggle"]}
          aria-label="프로필 메뉴"
          aria-expanded={isMenuOpen}
          onClick={() => setIsMenuOpen((open) => !open)}
        >
          <SvgIcon
            src={profileToggleIcon}
            className={cx(styles["sidebar-profile-toggle-icon"], isMenuOpen && styles["is-open"])}
          />
        </button>
      </div>

      {isMenuOpen && (
        <div className={styles["profile-menu"]} role="menu" aria-label="프로필 메뉴">
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setIsMenuOpen(false);
              setIsSettingsOpen(true);
            }}
          >
            설정
          </button>
          <button type="button" role="menuitem" onClick={() => void signOut({ callLogout: true })}>
            로그아웃
          </button>
        </div>
      )}

      {isSettingsOpen && <SettingsModal onClose={() => setIsSettingsOpen(false)} />}
    </footer>
  );
}
