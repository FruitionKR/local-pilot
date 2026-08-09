import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { clearSessionCache } from "@/entities/chat";
import { fetchMe } from "@/entities/user";
import { NotificationsPanel } from "@/features/document-notifications";
import { SettingsModal } from "@/features/user-settings";
import { clearAuth } from "@/shared/lib/auth";
import { cx } from "@/shared/lib/classNames";
import { profileToggleIcon, SvgIcon, userCircleIcon } from "@/shared/ui/SvgIcon";
import styles from "./DocumentSidebar.module.css";
import type { Project } from "@/entities/tree";

/** 사이드바 하단 프로필 푸터 (Figma 747:6648): 화살표 클릭 시 Wiki 관리/설정/로그아웃 메뉴를 연다. */
export function SidebarProfile({ projects }: { projects: Project[] }) {
  const router = useRouter();
  const [displayName, setDisplayName] = useState<string | null>(null);
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isNotificationsOpen, setIsNotificationsOpen] = useState(false);
  const rootRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchMe()
      .then((me) => {
        if (!cancelled) setDisplayName(me.display_name || me.email);
      })
      .catch(() => {
        // 표시용 데이터라 실패 시 fallback 이름을 유지한다.
        if (!cancelled) setDisplayName(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!isMenuOpen) return;

    function handleOutsidePointerDown(event: PointerEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setIsMenuOpen(false);
      }
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setIsMenuOpen(false);
    }

    document.addEventListener("pointerdown", handleOutsidePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("pointerdown", handleOutsidePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [isMenuOpen]);

  function handleLogout() {
    clearSessionCache();
    clearAuth();
    router.push("/login");
  }

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
              setIsNotificationsOpen(true);
            }}
          >
            Wiki 관리
          </button>
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
          <button type="button" role="menuitem" onClick={handleLogout}>
            로그아웃
          </button>
        </div>
      )}

      {isSettingsOpen && <SettingsModal onClose={() => setIsSettingsOpen(false)} />}
      {isNotificationsOpen && (
        <NotificationsPanel projects={projects} onClose={() => setIsNotificationsOpen(false)} />
      )}
    </footer>
  );
}
