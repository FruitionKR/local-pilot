import { apiFetch, parseJsonOrThrow } from "@/shared/api/client";
import type { WorkspaceRole } from "./members";

export type InvitationPreview = {
  workspace_id: string;
  workspace_name: string;
  email: string;
  role: WorkspaceRole;
  invited_by: string;
  expires_at: string;
};

export async function fetchInvitation(token: string): Promise<InvitationPreview> {
  const response = await fetch(`/api/invitations/${encodeURIComponent(token)}`, { cache: "no-store" });
  return parseJsonOrThrow<InvitationPreview>(response, "초대 정보를 불러오지 못했습니다.");
}

export async function acceptInvitation(token: string): Promise<{ workspace_id: string }> {
  const response = await apiFetch(`/api/invitations/${encodeURIComponent(token)}/accept`, { method: "POST" });
  return parseJsonOrThrow<{ workspace_id: string }>(response, "초대를 수락하지 못했습니다.");
}
