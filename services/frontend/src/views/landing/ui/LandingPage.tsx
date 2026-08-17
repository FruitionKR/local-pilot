import Link from "next/link";
import styles from "./LandingPage.module.css";

export default function LandingPage() {
  return (
    <main className={styles["landing-page"]}>
      <nav className={styles["landing-nav"]} aria-label="주요 메뉴">
        <span className={styles["landing-brand"]}>Fruition</span>
        <Link className={styles["landing-login-link"]} href="/login">
          로그인
        </Link>
      </nav>

      <section className={styles["landing-hero"]} aria-labelledby="landing-title">
        <p className={styles["landing-eyebrow"]}>AI RESEARCH WORKSPACE</p>
        <h1 id="landing-title">자료를 연결하고, 생각을 완성하세요.</h1>
        <p className={styles["landing-description"]}>
          문서와 지식을 한곳에 모아 탐색하고, AI와 함께 더 빠르게 인사이트를 발견하세요.
        </p>
        <div className={styles["landing-actions"]}>
          <Link className={styles["landing-primary-action"]} href="/login">
            시작하기
          </Link>
        </div>
      </section>
    </main>
  );
}
