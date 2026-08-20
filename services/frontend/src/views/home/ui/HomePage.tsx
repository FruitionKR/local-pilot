"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { HomeWorkspace } from "@/widgets/workspace";
import { useMe, useSignOut } from "@/entities/user";
import { getSelectedWorkspaceId } from "@/shared/lib/auth";
import { LoadingOverlay } from "@/shared/ui/LoadingOverlay";

export default function HomePage() {
  const router = useRouter();
  const { signOut } = useSignOut();
  // SSR에서는 null이지만 어차피 로딩 오버레이만 렌더링하므로 hydration 불일치가 없다.
  const hasWorkspace = Boolean(getSelectedWorkspaceId());

  // access token은 메모리에만 있으므로 서버 검증 과정에서 HttpOnly refresh 쿠키로 복구한다.
  const { isSuccess, isError } = useMe({ enabled: hasWorkspace });

  useEffect(() => {
    if (!hasWorkspace) router.replace("/workspaces");
  }, [hasWorkspace, router]);

  // 세션 만료 시 캐시·인증 상태를 정리하고 로그인 화면으로 보낸다.
  useEffect(() => {
    if (isError) void signOut();
  }, [isError, signOut]);

  if (!hasWorkspace || !isSuccess) return <LoadingOverlay message="워크스페이스 불러오는 중…" />;
  return <HomeWorkspace />;
}
