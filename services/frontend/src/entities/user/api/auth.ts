import { apiFetch, throwIfNotOk, parseJsonOrThrow, ERROR_MESSAGES } from "@/shared/api/client";
import type { UserMeResponse } from "@/entities/user/model/auth";

export type AuthTokensResponse = {
  access_token: string;
  mfa_required?: false;
};

export type LoginResponse = AuthTokensResponse | { mfa_required: true; mfa_token: string };

export async function logout(): Promise<void> {
  await fetch("/api/auth/logout", { method: "POST" });
}

export type EmailVerificationResponse = {
  verification_id: string;
  expires_in: number;
  retry_after: number;
};

export type EmailAvailabilityResponse = {
  available: boolean;
};

export type VerificationConfirmResponse = {
  verification_token: string;
  expires_in: number;
};

export type OAuthProvider = "google" | "naver" | "kakao";

export async function loginWithEmail(email: string, password: string): Promise<LoginResponse> {
  const response = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password })
  });

  return parseJsonOrThrow<LoginResponse>(response, ERROR_MESSAGES.loginFailed);
}

export function getOAuthAuthorizationUrl(provider: OAuthProvider): string {
  // OAuth 시작은 access-svc(8081) 오리진으로 직접 이동한다.
  // redirect_uri가 서버 자신 오리진 기준이라 Next rewrite 경유 시 3000 오리진으로 계산되므로 절대 URL을 유지한다.
  const accessUrl = process.env.NEXT_PUBLIC_ACCESS_URL || "http://localhost:8081";
  return `${accessUrl}/oauth2/authorization/${provider}`;
}

export async function exchangeOAuthCode(code: string): Promise<LoginResponse> {
  const response = await fetch("/api/auth/oauth/exchange", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code })
  });

  return parseJsonOrThrow<LoginResponse>(response, ERROR_MESSAGES.loginFailed);
}

export async function loginWithMfa(mfaToken: string, code: string): Promise<AuthTokensResponse> {
  const response = await fetch("/api/auth/login/mfa", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ mfa_token: mfaToken, code })
  });
  return parseJsonOrThrow<AuthTokensResponse>(response, "다단계 인증에 실패했습니다.");
}

export type MfaStatus = { enabled: boolean; activated_at: string | null; remaining_recovery_codes: number };
export type MfaRegistration = { secret: string; otpauth_uri: string; recovery_codes: string[] };
export const MFA_QUERY_KEY = ["mfa-status"] as const;

export async function fetchMfaStatus(): Promise<MfaStatus> {
  const response = await apiFetch("/api/auth/me/mfa", { cache: "no-store" });
  return parseJsonOrThrow<MfaStatus>(response, "다단계 인증 상태를 불러오지 못했습니다.");
}

export async function registerMfa(): Promise<MfaRegistration> {
  const response = await apiFetch("/api/auth/me/mfa", { method: "POST" });
  return parseJsonOrThrow<MfaRegistration>(response, "다단계 인증 등록을 시작하지 못했습니다.");
}

export async function activateMfa(code: string): Promise<void> {
  const response = await apiFetch("/api/auth/me/mfa/activate", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code })
  });
  await throwIfNotOk(response, "다단계 인증을 활성화하지 못했습니다.");
}

export async function disableMfa(code: string): Promise<void> {
  const response = await apiFetch("/api/auth/me/mfa", {
    method: "DELETE", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code })
  });
  await throwIfNotOk(response, "다단계 인증을 해제하지 못했습니다.");
}

export async function requestEmailVerification(
  email: string,
  purpose: "signup" | "password_reset" | "email_change"
): Promise<EmailVerificationResponse> {
  const response = await fetch("/api/auth/email-verifications", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, purpose })
  });

  return parseJsonOrThrow<EmailVerificationResponse>(response, "인증번호 요청에 실패했습니다.");
}

export async function checkEmailAvailability(email: string): Promise<EmailAvailabilityResponse> {
  const response = await fetch("/api/auth/email-availability", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email })
  });

  return parseJsonOrThrow<EmailAvailabilityResponse>(response, "이메일 중복 확인에 실패했습니다.");
}

export async function confirmEmailVerification(
  verificationId: string,
  code: string
): Promise<VerificationConfirmResponse> {
  const response = await fetch(
    `/api/auth/email-verifications/${encodeURIComponent(verificationId)}/confirm`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code })
    }
  );

  return parseJsonOrThrow<VerificationConfirmResponse>(response, "인증번호 확인에 실패했습니다.");
}

export async function signupWithEmail(
  email: string,
  password: string,
  displayName: string,
  verificationToken: string
): Promise<void> {
  const response = await fetch("/api/auth/signup", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      email,
      password,
      display_name: displayName,
      verification_token: verificationToken
    })
  });

  await throwIfNotOk(response, ERROR_MESSAGES.signupFailed);
}

export async function resetPasswordWithVerification(
  email: string,
  newPassword: string,
  verificationToken: string
): Promise<void> {
  const response = await fetch("/api/auth/password-reset", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      email,
      new_password: newPassword,
      verification_token: verificationToken
    })
  });

  await throwIfNotOk(response, "비밀번호 재설정에 실패했습니다.");
}

/** 로그인한 사용자 정보를 가져온다. */
export async function fetchMe(): Promise<UserMeResponse> {
  const response = await apiFetch("/api/auth/me", { cache: "no-store" });
  return parseJsonOrThrow<UserMeResponse>(response, ERROR_MESSAGES.meLoadFailed);
}

export async function updateDisplayName(displayName: string): Promise<UserMeResponse> {
  const response = await apiFetch("/api/auth/me", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ display_name: displayName })
  });
  return parseJsonOrThrow<UserMeResponse>(response, "닉네임을 변경하지 못했습니다.");
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  const response = await apiFetch("/api/auth/me/password", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ current_password: currentPassword, new_password: newPassword })
  });
  await throwIfNotOk(response, "비밀번호를 변경하지 못했습니다.");
}

export async function changeEmail(newEmail: string, verificationToken: string): Promise<UserMeResponse> {
  const response = await apiFetch("/api/auth/me/email", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ new_email: newEmail, verification_token: verificationToken })
  });
  return parseJsonOrThrow<UserMeResponse>(response, "이메일을 변경하지 못했습니다.");
}

export type LoginSession = {
  session_id: number;
  user_agent: string | null;
  current: boolean;
  created_at: string;
  expires_at: string;
};

export const SESSIONS_QUERY_KEY = ["login-sessions"] as const;

export async function fetchSessions(): Promise<LoginSession[]> {
  const response = await apiFetch("/api/auth/me/sessions", { cache: "no-store" });
  const data = await parseJsonOrThrow<{ sessions: LoginSession[] }>(response, "로그인된 기기를 불러오지 못했습니다.");
  return data.sessions;
}

export async function revokeSession(sessionId: number): Promise<void> {
  const response = await apiFetch(`/api/auth/me/sessions/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
  await throwIfNotOk(response, "기기를 로그아웃하지 못했습니다.");
}
