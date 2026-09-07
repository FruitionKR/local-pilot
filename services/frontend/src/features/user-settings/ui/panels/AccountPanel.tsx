"use client";

import styles from "../SettingsModal.module.css";
import panelStyles from "./AccountPanel.module.css";

/** 계정 설정 패널 (Figma 963:8660). */
export function AccountPanel({ name, email }: { name: string; email: string }) {
  return (
    <div className={styles.detail}>
      <div className={styles.title}>
        <div className={styles["title-row"]}>
          <h2>계정 설정</h2>
        </div>
        <p>개인 계정 설정을 관리합니다.</p>
      </div>

      <div className={styles.section}>
        <div className={styles["section-header"]}>
          <span>프로필</span>
          <span className={styles["section-line"]} />
        </div>
        <div className={styles.field}>
          <label htmlFor="account-nickname">닉네임</label>
          <input id="account-nickname" type="text" value={name} readOnly />
        </div>
      </div>

      <div className={styles.section}>
        <div className={styles["section-header"]}>
          <span>계정 정보</span>
          <span className={styles["section-line"]} />
        </div>
        <div className={styles.row}>
          <div className={styles["row-title"]}>
            <strong>이메일</strong>
            <small>{email || "이메일 정보를 불러오지 못했습니다."}</small>
          </div>
          {/* 이메일 변경은 백엔드 미지원이라 비활성 버튼으로만 노출한다. */}
          <button type="button" className={styles.btn} disabled>
            이메일 변경
          </button>
        </div>
        <div className={styles.row}>
          <div className={styles["row-title"]}>
            <strong>비밀번호</strong>
            <small>이메일 인증으로 비밀번호를 재설정합니다.</small>
          </div>
          {/* 비밀번호 재설정 흐름(/forgot-password)을 새 탭으로 연다. 이메일은 쿼리로 프리필한다. */}
          <button
            type="button"
            className={styles.btn}
            onClick={() => {
              const query = email ? `?email=${encodeURIComponent(email)}` : "";
              window.open(`/forgot-password${query}`, "_blank", "noopener,noreferrer");
            }}
          >
            비밀번호 변경
          </button>
        </div>
      </div>

      {/* 계정 보안: 백엔드 미지원이라 전부 비활성 UI로만 노출한다. */}
      <div className={styles.section}>
        <div className={styles["section-header"]}>
          <span>계정 보안</span>
          <span className={styles["section-line"]} />
        </div>
        <div className={styles.row}>
          <div className={styles["row-title"]}>
            <strong>다단계 인증</strong>
            <small>계정 보안을 위한 인증 단계에요. (준비 중)</small>
          </div>
          {/* 실제 상태가 없는 값을 켜짐으로 보여주지 않도록 OFF 상태로 둔다. */}
          <button
            type="button"
            role="switch"
            aria-checked={false}
            aria-label="다단계 인증"
            className={styles.switch}
            disabled
          >
            <span className={styles["switch-ball"]} />
          </button>
        </div>
        <div className={styles.row}>
          <div className={styles["row-title"]}>
            <strong>로그인된 기기</strong>
            <small>여러 기기에서 로그인 중인 기기 수에요.</small>
          </div>
          <button type="button" className={panelStyles.pill} disabled>
            준비 중
          </button>
        </div>
      </div>
    </div>
  );
}
