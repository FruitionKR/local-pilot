"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { exchangeOAuthCode, loginWithEmail } from "@/entities/user";
import { saveTokens } from "@/shared/lib/auth";
import { AuthError, AuthField, AuthSubmitButton, SocialLoginButtons } from "@/shared/ui/AuthControls";

const INVALID_CREDENTIALS_MESSAGE = "가입하지 않은 아이디거나, 잘못된 비밀번호입니다.";

const LEGACY_AUTH_ROUTES: Record<string, string> = {
  signup: "/signup",
  "signup-verification": "/signup/verify",
  "forgot-password": "/forgot-password",
  "reset-password": "/reset-password"
};

export default function LoginPage() {
  return (
    <Suspense fallback={<main className="auth-screen auth-screen--login" />}>
      <LoginPageContent />
    </Suspense>
  );
}

function LoginPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const hasHandledOAuth = useRef(false);
  const isLoginRequestInFlight = useRef(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    const legacyRoute = LEGACY_AUTH_ROUTES[searchParams.get("view") ?? ""];
    if (legacyRoute) {
      router.replace(legacyRoute);
      return;
    }

    if (hasHandledOAuth.current) return;

    const code = searchParams.get("code");
    const oauthError = searchParams.get("error");
    if (!code && !oauthError) return;

    hasHandledOAuth.current = true;
    window.history.replaceState({}, "", window.location.pathname);

    if (oauthError) {
      setErrorMessage("간편 로그인에 실패했습니다.");
      return;
    }

    setIsSubmitting(true);
    exchangeOAuthCode(code as string)
      .then((tokens) => {
        saveTokens(tokens.access_token, tokens.refresh_token);
        router.replace("/workspaces");
      })
      .catch(() => {
        setErrorMessage("간편 로그인에 실패했습니다.");
        setIsSubmitting(false);
      });
  }, [router, searchParams]);

  async function handleLogin(event: React.FormEvent) {
    event.preventDefault();
    if (isSubmitting || isLoginRequestInFlight.current) return;

    isLoginRequestInFlight.current = true;
    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      const tokens = await loginWithEmail(email, password);
      saveTokens(tokens.access_token, tokens.refresh_token);
      router.replace("/workspaces");
    } catch {
      isLoginRequestInFlight.current = false;
      setErrorMessage(INVALID_CREDENTIALS_MESSAGE);
      setIsSubmitting(false);
    }
  }

  return (
    <main className="auth-screen auth-screen--login">
      <section className="auth-shell auth-shell--login" aria-labelledby="auth-title">
        <div className="auth-content">
          <h1 className="auth-title" id="auth-title">로그인</h1>
          <div className="auth-main-section">
            <form className="auth-form" method="post" onSubmit={handleLogin}>
              <div className="auth-field-stack">
                <AuthField
                  autoComplete="email"
                  label="이메일"
                  name="email"
                  onChange={(event) => setEmail(event.target.value)}
                  placeholder="example@email.com"
                  type="email"
                  value={email}
                />
                <div className="auth-field-with-error">
                  <AuthField
                    autoComplete="current-password"
                    label="비밀번호"
                    name="password"
                    onChange={(event) => setPassword(event.target.value)}
                    placeholder="password"
                    type="password"
                    value={password}
                  />
                  {errorMessage ? <AuthError>{errorMessage}</AuthError> : null}
                </div>
              </div>
              <AuthSubmitButton disabled={isSubmitting}>로그인</AuthSubmitButton>
            </form>
            <nav aria-label="계정 도움말" className="auth-login-links">
              <button onClick={() => router.push("/forgot-password")} type="button">비밀번호 찾기</button>
              <span />
              {/*
                아이디 찾기: 이 서비스는 이메일 = 아이디이며 로그인·가입·인증이 모두 이메일 기반이다.
                별도 아이디가 없고, 이메일 외 조회에 쓸 고유 2차 식별자(전화번호 등)도 수집하지 않으므로
                "아이디/이메일 찾기" 조회 기능이 성립하지 않는다. 그래서 버튼을 비활성으로 유지한다.
                추후 전화번호 등 2차 식별자를 가입에 추가하면 해당 시점에 조회 화면/API를 구현한다.
              */}
              <button aria-disabled="true" className="is-disabled" type="button">아이디 찾기</button>
              <span />
              <button onClick={() => router.push("/signup")} type="button">회원가입</button>
            </nav>
          </div>
          <SocialLoginButtons />
        </div>
      </section>
    </main>
  );
}
