"use client";

import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useMe } from "@/entities/user";
import { uploadWorkspaceIcon, useSelectedWorkspace, type WorkspaceListResponse, type WorkspaceResponse } from "@/entities/workspace";
import { fetchMembers } from "@/entities/workspace/api/members";
import { WorkspaceIcon } from "@/entities/workspace/ui/WorkspaceIcon";
import { getErrorMessage } from "@/shared/lib/errors";
import styles from "../SettingsModal.module.css";

export function WorkspaceIconSettings() {
  const workspace = useSelectedWorkspace();
  const { data: me } = useMe();
  const { data: members, error: membersError } = useQuery({
    queryKey: ["workspace-members", workspace?.id],
    queryFn: () => fetchMembers(workspace!.id),
    enabled: Boolean(workspace), retry: false
  });
  const isOwner = members?.some((member) => member.user_id === me?.id && member.role === "OWNER") ?? false;
  const queryClient = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  async function save(file: File) {
    if (!workspace || !isOwner || busy) return;
    setError(null);
    setSaved(false);
    if (file.size === 0 || file.size > 1024 * 1024) {
      setError("빈 파일은 사용할 수 없습니다. 1MB 이하 이미지를 선택해 주세요.");
      return;
    }
    setBusy(true);
    try {
      const updated: WorkspaceResponse = await uploadWorkspaceIcon(workspace.id, file);
      queryClient.setQueryData<WorkspaceListResponse>(["workspaces"], (current) => current && ({
        ...current, workspaces: current.workspaces.map((item) => item.id === updated.id ? updated : item)
      }));
      void queryClient.invalidateQueries({ queryKey: ["workspace-icon", workspace.id] });
      setSaved(true);
    } catch (cause: unknown) {
      setError(getErrorMessage(cause, "아이콘을 변경하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  }

  return <div className={styles.field}>
    <span className={styles["field-label"]}>워크스페이스 아이콘</span>
    <small>이미지를 업로드하거나 이모티콘을 선택하세요 (100*100 사이즈를 추천드립니다.)</small>
    <button type="button" className={styles["ws-icon-outline"]} aria-label="워크스페이스 아이콘 이미지 업로드"
      disabled={!isOwner || busy} onClick={() => fileInput.current?.click()}>
      <WorkspaceIcon workspace={workspace} className={styles["ws-icon"]} />
    </button>
    <input ref={fileInput} type="file" hidden accept="image/png,image/jpeg,image/webp,image/gif" disabled={!isOwner || busy}
      onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ""; if (file) void save(file); }} />
    {!isOwner && <small>아이콘 변경은 워크스페이스 OWNER만 할 수 있습니다.</small>}
    {busy && <small role="status">아이콘 저장 중…</small>}
    {saved && <small role="status">아이콘을 변경했습니다.</small>}
    {(error || membersError) && <small className={styles["model-error"]} role="alert">{error || getErrorMessage(membersError, "아이콘 변경 권한을 확인하지 못했습니다.")}</small>}
  </div>;
}
