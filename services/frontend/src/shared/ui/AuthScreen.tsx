import type { ReactNode } from "react";

type AuthScreenProps = {
  title: string;
  shellModifier?: string;
  children: ReactNode;
  extra?: ReactNode;
};

// 인증 화면 공통 셸. extra는 auth-content 내부, auth-main-section 뒤에 렌더링된다(로그인의 간편 로그인 영역 등).
export function AuthScreen({ title, shellModifier, children, extra }: AuthScreenProps) {
  const shellClassName = shellModifier ? `auth-shell auth-shell--${shellModifier}` : "auth-shell";

  return (
    <main className="auth-screen auth-screen--login">
      <section className={shellClassName} aria-labelledby="auth-title">
        <div className="auth-content">
          <h1 className="auth-title" id="auth-title">{title}</h1>
          <div className="auth-main-section">{children}</div>
          {extra}
        </div>
      </section>
    </main>
  );
}

// 리다이렉트 대기 등 내용 없이 배경만 유지하는 빈 인증 화면
export function AuthScreenBlank() {
  return <main className="auth-screen auth-screen--login" />;
}
