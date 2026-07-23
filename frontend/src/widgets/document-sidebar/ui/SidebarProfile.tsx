import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { clearSessionCache } from "@/entities/chat";
import { fetchMe } from "@/entities/user";
import { clearAuth } from "@/shared/lib/auth";
import styles from "./DocumentSidebar.module.css";

/** 사이드바 하단 프로필 푸터: 아바타 + 이름/온라인 + 로그아웃 */
export function SidebarProfile() {
  const router = useRouter();
  const [displayName, setDisplayName] = useState<string | null>(null);

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

  function handleLogout() {
    clearSessionCache();
    clearAuth();
    router.push("/login");
  }

  const name = displayName ?? "사용자";

  return (
    <footer className={styles["sidebar-profile"]}>
      <span className={styles["sidebar-profile-avatar"]} aria-hidden>{name.charAt(0)}</span>
      <span className={styles["sidebar-profile-info"]}>
        <strong>{name}</strong>
        <small>온라인</small>
      </span>
      <button type="button" className={styles["sidebar-logout"]} onClick={handleLogout}>로그아웃</button>
    </footer>
  );
}
