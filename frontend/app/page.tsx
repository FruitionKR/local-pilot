import Link from "next/link";

export default function LandingPage() {
  return (
    <main className="landing-page">
      <nav className="landing-nav" aria-label="주요 메뉴">
        <span className="landing-brand">Fruition</span>
        <Link className="landing-login-link" href="/login">
          로그인
        </Link>
      </nav>

      <section className="landing-hero" aria-labelledby="landing-title">
        <p className="landing-eyebrow">AI RESEARCH WORKSPACE</p>
        <h1 id="landing-title">자료를 연결하고, 생각을 완성하세요.</h1>
        <p className="landing-description">
          문서와 지식을 한곳에 모아 탐색하고, AI와 함께 더 빠르게 인사이트를 발견하세요.
        </p>
        <div className="landing-actions">
          <Link className="landing-primary-action" href="/login">
            시작하기
          </Link>
          <Link className="landing-secondary-action" href="/login?view=signup">
            회원가입
          </Link>
        </div>
      </section>
    </main>
  );
}
