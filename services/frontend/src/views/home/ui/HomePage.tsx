"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { HomeWorkspace } from "@/widgets/workspace";
import { fetchMe } from "@/entities/user";
import { clearAuth, getSelectedWorkspaceId } from "@/shared/lib/auth";
import { LoadingOverlay } from "@/shared/ui/LoadingOverlay";

export default function HomePage() {
  const router = useRouter();
  const [isReady, setIsReady] = useState(false);

  // access token은 메모리에만 있으므로 서버 검증 과정에서 HttpOnly refresh 쿠키로 복구한다.
  useEffect(() => {
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

  if (!isReady) return <LoadingOverlay message="워크스페이스 불러오는 중…" />;
  return <HomeWorkspace />;
}
