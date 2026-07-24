"use client";

import { cx } from "@/shared/lib/classNames";
import styles from "./SettingsMockup.module.css";

// 설정 임시 목업. 실제 저장 배선은 없다. rail "설정" 뷰에 마운트된다.
export function SettingsMockup() {
  return (
    <section className={styles["settings"]} aria-label="설정">
      <div className={styles["settings-inner"]}>
        <header className={styles["settings-header"]}>
          <h2>
            설정
            <span className={styles["settings-badge"]}>미리보기</span>
          </h2>
          <p>워크스페이스 및 계정 설정입니다. (임시 목업 화면 — 실제 저장은 연동 예정)</p>
        </header>

        <div className={styles["settings-section"]}>
          <h3>프로필</h3>
          <div className={styles["settings-field"]}>
            <div className={styles["settings-label"]}>
              <p>이름</p>
              <span>계정에 표시되는 이름</span>
            </div>
            <span className={styles["settings-value"]}>dev</span>
          </div>
          <div className={styles["settings-field"]}>
            <div className={styles["settings-label"]}>
              <p>이메일</p>
              <span>로그인 계정</span>
            </div>
            <span className={styles["settings-value"]}>dev@fruition.local</span>
          </div>
        </div>

        <div className={styles["settings-section"]}>
          <h3>워크스페이스</h3>
          <div className={styles["settings-field"]}>
            <div className={styles["settings-label"]}>
              <p>이름</p>
              <span>현재 워크스페이스</span>
            </div>
            <span className={styles["settings-value"]}>dev의 워크스페이스</span>
          </div>
          <div className={styles["settings-field"]}>
            <div className={styles["settings-label"]}>
              <p>자동 저장</p>
              <span>편집 내용을 자동으로 저장</span>
            </div>
            <span className={cx(styles["settings-toggle"], styles["is-on"])} aria-hidden />
          </div>
        </div>

        <div className={styles["settings-section"]}>
          <h3>AI 모델</h3>
          <div className={styles["settings-field"]}>
            <div className={styles["settings-label"]}>
              <p>LLM Provider</p>
              <span>위키 생성·편집에 사용하는 모델</span>
            </div>
            <span className={styles["settings-pill"]}>Gemini · gemini-flash-latest</span>
          </div>
        </div>

        <div className={styles["settings-section"]}>
          <h3>화면</h3>
          <div className={styles["settings-field"]}>
            <div className={styles["settings-label"]}>
              <p>테마</p>
              <span>화면 색상 모드</span>
            </div>
            <span className={styles["settings-pill"]}>다크</span>
          </div>
          <div className={styles["settings-field"]}>
            <div className={styles["settings-label"]}>
              <p>애니메이션 줄이기</p>
              <span>모션 효과 최소화</span>
            </div>
            <span className={styles["settings-toggle"]} aria-hidden />
          </div>
        </div>
      </div>
    </section>
  );
}
