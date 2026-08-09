"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { HomeWorkspace } from "@/widgets/workspace";
import { fetchMe } from "@/entities/user";
import { clearAuth, getAccessToken, getSelectedWorkspaceId } from "@/shared/lib/auth";

export default function HomePage() {
  const router = useRouter();
  const [isReady, setIsReady] = useState(false);

  // 임시 가드: 토큰 없으면 로그인, 워크스페이스 미선택이면 선택 화면으로 보낸다.
  // 토큰이 있어도 서버 검증(fetchMe) 실패 시(만료·DB 초기화 등) 세션을 지우고 로그인으로 보낸다.
  useEffect(() => {
    if (!getAccessToken()) {
      router.replace("/login");
      return;
    }
    if (!getSelectedWorkspaceId()) {
      router.replace("/workspaces");
      return;
    }

    let cancelled = false;
    fetchMe()
      .then(() => {
        if (!cancelled) setIsReady(true);
      })
      .catch(() => {
        if (cancelled) return;
        clearAuth();
        router.replace("/login");
      });
    return () => {
      cancelled = true;
    };
  }, [router]);

  if (!isReady) return null;
  return <HomeWorkspace />;
}
