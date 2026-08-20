"use client";

import { useCallback } from "react";
import { useRouter } from "next/navigation";
import { clearSessionCache } from "@/entities/chat";
import { logout } from "@/entities/user/api/auth";
import { clearAuth } from "@/shared/lib/auth";

/**
 * 세션 종료 공통 절차: (선택) 서버 로그아웃 → 채팅 캐시 정리 → 인증 상태 정리 → 로그인 화면 이동.
 * 로그인 화면으로 돌아가는 경로라 history를 남기지 않도록 replace를 사용한다.
 */
export function useSignOut() {
  const router = useRouter();

  const signOut = useCallback(
    async (options?: { callLogout?: boolean }) => {
      if (options?.callLogout) await logout().catch(() => undefined);
      clearSessionCache();
      clearAuth();
      router.replace("/login");
    },
    [router]
  );

  return { signOut };
}
