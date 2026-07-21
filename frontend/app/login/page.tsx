"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { exchangeOAuthCode, loginWithEmail, signupWithEmail } from "../_lib/api";
import { saveTokens } from "../_lib/auth";
import { getErrorMessage } from "../_lib/errors";
import { AuthError, AuthField, AuthSubmitButton, SocialLoginButtons } from "./AuthControls";
import { useVerificationRequest } from "./useVerificationRequest";

type AuthView = "login" | "signup" | "signup-verification" | "forgot-password" | "reset-password";

const INVALID_CREDENTIALS_MESSAGE = "가입하지 않은 아이디거나, 잘못된 비밀번호입니다.";

function resolveAuthView(value: string | null): AuthView {
  if (
    value === "signup" ||
    value === "signup-verification" ||
    value === "forgot-password" ||
    value === "reset-password"
  ) {
    return value;
  }
  return "login";
}

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
  const isCompletingSignup = useRef(false);
  const isLoginRequestInFlight = useRef(false);
  const view = resolveAuthView(searchParams.get("view"));
  const isVerificationView = view === "signup-verification" || view === "forgot-password";
  const { buttonLabel, clearRequest, isTemporaryCode, isWaiting, startRequest } =
    useVerificationRequest(isVerificationView);
  const [nickname, setNickname] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirmation, setPasswordConfirmation] = useState("");
  const [verificationCode, setVerificationCode] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (hasHandledOAuth.current) return;

    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    const oauthError = params.get("error");
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
  }, [router]);

  const changeView = useCallback((nextView: AuthView) => {
    setErrorMessage(null);
    if (nextView !== "signup-verification") clearRequest();
    router.push(nextView === "login" ? "/login" : `/login?view=${nextView}`);
  }, [clearRequest, router]);

  useEffect(() => {
    if (!isWaiting || !isTemporaryCode(verificationCode)) return;

    if (view === "forgot-password") {
      setVerificationCode("");
      changeView("reset-password");
      return;
    }

    if (view !== "signup-verification" || isCompletingSignup.current) return;

    isCompletingSignup.current = true;
    setIsSubmitting(true);
    loginWithEmail(email, password)
      .then((tokens) => {
        saveTokens(tokens.access_token, tokens.refresh_token);
        router.replace("/workspaces");
      })
      .catch((error: unknown) => {
        setErrorMessage(getErrorMessage(error, "회원가입에 실패했습니다."));
        setIsSubmitting(false);
        isCompletingSignup.current = false;
      });
  }, [changeView, email, isTemporaryCode, isWaiting, password, router, verificationCode, view]);

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

  async function handleSignupRequest(event: React.FormEvent) {
    event.preventDefault();
    if (isSubmitting) return;

    if (password.length < 8) {
      setErrorMessage("비밀번호는 8자 이상 입력해주세요.");
      return;
    }

    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      await signupWithEmail(email, password);
      setVerificationCode("");
      startRequest();
      changeView("signup-verification");
    } catch (error: unknown) {
      setErrorMessage(getErrorMessage(error, "회원가입에 실패했습니다."));
    } finally {
      setIsSubmitting(false);
    }
  }

  function handleVerificationRequest(event: React.FormEvent) {
    event.preventDefault();
    if (isWaiting) return;

    setErrorMessage(null);
    startRequest();
  }

  function handlePasswordReset(event: React.FormEvent) {
    event.preventDefault();
    setErrorMessage(
      password === passwordConfirmation ? null : "비밀번호가 일치하지 않습니다."
    );
  }

  let content: React.ReactNode;

  if (view === "login") {
    content = (
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
              <button onClick={() => changeView("forgot-password")} type="button">비밀번호 찾기</button>
              <span />
              <button aria-disabled="true" className="is-disabled" type="button">아이디 찾기</button>
              <span />
              <button onClick={() => changeView("signup")} type="button">회원가입</button>
            </nav>
          </div>
          <SocialLoginButtons />
        </div>
      </section>
    );
  } else if (view === "signup") {
    content = (
      <section className="auth-shell" aria-labelledby="auth-title">
        <div className="auth-content">
          <h1 className="auth-title" id="auth-title">회원가입</h1>
          <div className="auth-main-section">
            <form className="auth-form" method="post" onSubmit={handleSignupRequest}>
              <div className="auth-field-with-error">
                <div className="auth-field-stack">
                  <AuthField label="닉네임" name="nickname" onChange={(event) => setNickname(event.target.value)} placeholder="name" value={nickname} />
                  <AuthField autoComplete="email" label="이메일" name="email" onChange={(event) => setEmail(event.target.value)} placeholder="example@email.com" type="email" value={email} />
                  <AuthField autoComplete="new-password" label="비밀번호" name="password" onChange={(event) => setPassword(event.target.value)} placeholder="password" type="password" value={password} />
                </div>
                {errorMessage ? <AuthError>{errorMessage}</AuthError> : null}
              </div>
              <AuthSubmitButton disabled={isSubmitting}>인증 요청</AuthSubmitButton>
            </form>
            <p className="auth-prompt">이미 아이디가 있으신가요?<button onClick={() => changeView("login")} type="button">로그인하기</button></p>
          </div>
        </div>
      </section>
    );
  } else if (view === "signup-verification") {
    content = (
      <section className="auth-shell auth-shell--verification" aria-labelledby="auth-title">
        <div className="auth-content">
          <h1 className="auth-title" id="auth-title">회원가입</h1>
          <div className="auth-main-section">
            <form className="auth-form" method="post" onSubmit={handleVerificationRequest}>
              <div className="auth-field-with-error">
                <AuthField label="인증번호" name="verificationCode" onChange={(event) => setVerificationCode(event.target.value)} placeholder="code" value={verificationCode} />
                {errorMessage ? <AuthError>{errorMessage}</AuthError> : null}
              </div>
              <AuthSubmitButton disabled={isWaiting || isSubmitting}>{buttonLabel}</AuthSubmitButton>
            </form>
          </div>
        </div>
      </section>
    );
  } else if (view === "forgot-password") {
    content = (
      <section className="auth-shell auth-shell--password" aria-labelledby="auth-title">
        <div className="auth-content">
          <h1 className="auth-title" id="auth-title">비밀번호 찾기</h1>
          <div className="auth-main-section">
            <form className="auth-form" method="post" onSubmit={handleVerificationRequest}>
              <div className="auth-field-with-error">
                <div className="auth-field-stack">
                  <AuthField autoComplete="email" label="이메일 인증" name="email" onChange={(event) => setEmail(event.target.value)} placeholder="example@email.com" type="email" value={email} />
                  <AuthField label="인증번호" name="verificationCode" onChange={(event) => setVerificationCode(event.target.value)} placeholder="code" required={false} value={verificationCode} />
                </div>
                {errorMessage ? <AuthError>{errorMessage}</AuthError> : null}
              </div>
              <AuthSubmitButton disabled={isWaiting}>{buttonLabel}</AuthSubmitButton>
            </form>
          </div>
        </div>
      </section>
    );
  } else {
    content = (
      <section className="auth-shell auth-shell--password" aria-labelledby="auth-title">
        <div className="auth-content">
          <h1 className="auth-title" id="auth-title">비밀번호 재설정</h1>
          <div className="auth-main-section">
            <form className="auth-form" method="post" onSubmit={handlePasswordReset}>
              <div className="auth-field-with-error">
                <div className="auth-field-stack">
                  <AuthField autoComplete="new-password" label="비밀번호" name="password" onChange={(event) => setPassword(event.target.value)} placeholder="password" type="password" value={password} />
                  <AuthField autoComplete="new-password" label="비밀번호 재확인" name="passwordConfirmation" onChange={(event) => setPasswordConfirmation(event.target.value)} placeholder="password" type="password" value={passwordConfirmation} />
                </div>
                {errorMessage ? <AuthError>{errorMessage}</AuthError> : null}
              </div>
              <AuthSubmitButton>확인</AuthSubmitButton>
            </form>
            <p className="auth-prompt">이미 아이디가 있으신가요?<button onClick={() => changeView("login")} type="button">로그인하기</button></p>
          </div>
        </div>
      </section>
    );
  }

  return <main className="auth-screen auth-screen--login">{content}</main>;
}
