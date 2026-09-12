"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchWorkspaceIcon } from "../api/workspace";
import type { WorkspaceResponse } from "../model/workspace";

export function WorkspaceIcon({ workspace, className }: { workspace: WorkspaceResponse | null; className?: string }) {
  const { data: blob, error } = useQuery({
    queryKey: ["workspace-icon", workspace?.id, workspace?.updated_at],
    queryFn: () => fetchWorkspaceIcon(workspace!.id),
    enabled: Boolean(workspace?.icon_url),
    staleTime: Infinity,
    retry: false
  });
  const [image, setImage] = useState<{ blob: Blob; url: string } | null>(null);
  useEffect(() => {
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    setImage({ blob, url });
    return () => URL.revokeObjectURL(url);
  }, [blob]);

  return <span className={className} aria-hidden title={error ? "아이콘 이미지를 불러오지 못했습니다." : undefined}>
    {workspace?.icon_url && image?.blob === blob && image ? (
      // 인증이 필요한 이미지는 Bearer 요청으로 받은 Blob URL을 사용한다.
      // eslint-disable-next-line @next/next/no-img-element
      <img src={image.url} alt="" style={{ width: "100%", height: "100%", objectFit: "cover", borderRadius: "inherit" }} />
    ) : workspace?.icon_emoji || workspace?.name.charAt(0) || "워"}
  </span>;
}
