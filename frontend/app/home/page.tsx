"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { HomeWorkspace } from "../_components/home-workspace/HomeWorkspace";
import { getAccessToken, getSelectedWorkspaceId } from "@/shared/lib/auth";

export default function HomePage() {
  const router = useRouter();
  const [isReady, setIsReady] = useState(false);

  // 임시 가드: 토큰 없으면 로그인, 워크스페이스 미선택이면 선택 화면으로 보낸다.
  useEffect(() => {
    if (!getAccessToken()) {
      router.replace("/login");
      return;
    }
    if (!getSelectedWorkspaceId()) {
      router.replace("/workspaces");
      return;
    }
    setIsReady(true);
  }, [router]);

  if (!isReady) return null;
  return <HomeWorkspace />;
}
