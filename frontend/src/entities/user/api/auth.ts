import { apiFetch, parseErrorResponse, parseJsonOrThrow, ERROR_MESSAGES } from "@/shared/api/client";
import type { UserMeResponse } from "@/entities/user/model/auth";

export type AuthTokensResponse = {
  access_token: string;
  refresh_token: string;
};

export type EmailVerificationResponse = {
  verification_id: string;
  expires_in: number;
  retry_after: number;
};

export type VerificationConfirmResponse = {
  verification_token: string;
  expires_in: number;
};

export type OAuthProvider = "google" | "naver" | "kakao";

export async function loginWithEmail(email: string, password: string): Promise<AuthTokensResponse> {
  const response = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password })
  });

  return parseJsonOrThrow<AuthTokensResponse>(response, ERROR_MESSAGES.loginFailed);
}

export function getOAuthAuthorizationUrl(provider: OAuthProvider): string {
  // OAuth 시작은 access-svc(8081) 오리진으로 직접 이동한다.
  // redirect_uri가 서버 자신 오리진 기준이라 Next rewrite 경유 시 3000 오리진으로 계산되므로 절대 URL을 유지한다.
  const accessUrl = process.env.NEXT_PUBLIC_ACCESS_URL || "http://localhost:8081";
  return `${accessUrl}/oauth2/authorization/${provider}`;
}

export async function exchangeOAuthCode(code: string): Promise<AuthTokensResponse> {
  const response = await fetch("/api/auth/oauth/exchange", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code })
  });

  return parseJsonOrThrow<AuthTokensResponse>(response, ERROR_MESSAGES.loginFailed);
}

export async function requestEmailVerification(
  email: string,
  purpose: "signup" | "password_reset"
): Promise<EmailVerificationResponse> {
  const response = await fetch("/api/auth/email-verifications", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, purpose })
  });

  return parseJsonOrThrow<EmailVerificationResponse>(response, "인증번호 요청에 실패했습니다.");
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

  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, ERROR_MESSAGES.signupFailed));
  }
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

  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, "비밀번호 재설정에 실패했습니다."));
  }
}

/** 로그인한 사용자 정보를 가져온다. */
export async function fetchMe(): Promise<UserMeResponse> {
  const response = await apiFetch("/api/auth/me", { cache: "no-store" });
  return parseJsonOrThrow<UserMeResponse>(response, ERROR_MESSAGES.meLoadFailed);
}
