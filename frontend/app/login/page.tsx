"use client";

// 임시 로그인/회원가입 화면. 정식 디자인(Figma) 도입 시 교체 대상.
import { useState } from "react";
import { useRouter } from "next/navigation";
import { loginWithEmail, signupWithEmail } from "../_lib/api";
import { saveTokens } from "../_lib/auth";
import { getErrorMessage } from "../_lib/errors";

type AuthMode = "login" | "signup";

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<AuthMode>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (isSubmitting) return;

    setErrorMessage(null);
    setIsSubmitting(true);

    // 회원가입과 로그인 실패 메시지를 분리해 회원가입 성공 후 로그인 실패를 오표시하지 않는다.
    if (mode === "signup") {
      try {
        await signupWithEmail(email, password);
      } catch (error) {
        setErrorMessage(getErrorMessage(error, "회원가입에 실패했습니다."));
        setIsSubmitting(false);
        return;
      }
    }

    try {
      const tokens = await loginWithEmail(email, password);
      saveTokens(tokens.access_token, tokens.refresh_token);
      router.replace("/workspaces");
    } catch (error) {
      setErrorMessage(getErrorMessage(error, "로그인에 실패했습니다."));
      setIsSubmitting(false);
    }
  }

  return (
    <main className="auth-screen">
      <section className="auth-card">
        <h1 className="auth-title">Fruition</h1>
        <p className="auth-subtitle">{mode === "login" ? "계정으로 로그인하세요." : "새 계정을 만드세요."}</p>

        <div className="auth-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={mode === "login"}
            className={mode === "login" ? "auth-tab active" : "auth-tab"}
            onClick={() => setMode("login")}
          >
            로그인
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === "signup"}
            className={mode === "signup" ? "auth-tab active" : "auth-tab"}
            onClick={() => setMode("signup")}
          >
            회원가입
          </button>
        </div>

        <form className="auth-form" onSubmit={handleSubmit}>
          <label className="auth-field">
            <span>이메일</span>
            <input
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="user@example.com"
              autoComplete="email"
              required
            />
          </label>
          <label className="auth-field">
            <span>비밀번호</span>
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="8~72자"
              autoComplete={mode === "signup" ? "new-password" : "current-password"}
              minLength={8}
              maxLength={72}
              required
            />
          </label>

          {errorMessage ? <p className="auth-error" role="alert">{errorMessage}</p> : null}

          <button type="submit" className="auth-submit" disabled={isSubmitting}>
            {isSubmitting ? "처리 중..." : mode === "login" ? "로그인" : "회원가입"}
          </button>
        </form>
      </section>
    </main>
  );
}
