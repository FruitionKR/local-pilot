"use client";

import { useCallback } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { clearSessionCache } from "@/entities/chat";
import { logout } from "@/entities/user/api/auth";
import { clearAuth } from "@/shared/lib/auth";

/**
 * 세션 종료 공통 절차: (선택) 서버 로그아웃 → 채팅 캐시 정리 → 인증 상태 정리 → 로그인 화면 이동.
 * 로그인 화면으로 돌아가는 경로라 history를 남기지 않도록 replace를 사용한다.
 */
export function useSignOut() {
  const router = useRouter();
  const queryClient = useQueryClient();

  const signOut = useCallback(
    async (options?: { callLogout?: boolean }) => {
      if (options?.callLogout) await logout().catch(() => undefined);
      clearSessionCache();
      // 사용자 범위 react-query 캐시(me·workspaces 등)를 비워 다음 로그인에 이전 사용자 데이터가 남지 않게 한다.
      queryClient.clear();
      clearAuth();
      router.replace("/login");
    },
    [queryClient, router]
  );

  return { signOut };
}
