"use client";

import { menuSearchIcon, settingScrollIcon, SvgIcon, userCircleIcon } from "@/shared/ui/SvgIcon";
import modalStyles from "../SettingsModal.module.css";
import styles from "./MembersPanel.module.css";

/** 멤버 관리 패널 (Figma 987:10705). 백엔드 멤버 API 미지원이라 컨트롤은 전부 비활성 정적 UI이며, 현재 로그인 사용자 1명만 실데이터로 표시한다. */
export function MembersPanel({ name, email }: { name: string; email: string }) {
  return (
    <div className={modalStyles.detail}>
      <div className={modalStyles.title}>
        <div className={modalStyles["title-row"]}>
          <h2>멤버 관리</h2>
        </div>
        <p>워크스페이스에 있는 사람과 역할을 관리합니다.</p>
      </div>

      {/* 필터·검색·멤버 추가 툴바: 멤버 API 미배선이라 비활성 */}
      <div className={styles.toolbar}>
        <div className={styles["toolbar-group"]}>
          <button type="button" className={styles["filter-btn"]} disabled>
            권한 <SvgIcon src={settingScrollIcon} className={styles["chev-icon"]} />
          </button>
          <button type="button" className={styles["filter-btn"]} disabled>
            <span className={styles["filter-accent"]}>권한 : OWNER</span>
            <SvgIcon src={settingScrollIcon} className={styles["chev-icon"]} />
          </button>
        </div>
        <div className={styles["toolbar-group"]}>
          <button type="button" className={styles["search-btn"]} aria-label="멤버 검색" disabled>
            <SvgIcon src={menuSearchIcon} className={styles["search-icon"]} />
          </button>
          <button type="button" className={styles["invite-btn"]} disabled>
            멤버 추가하기 <SvgIcon src={settingScrollIcon} className={styles["chev-icon"]} />
          </button>
        </div>
      </div>

      {/* 멤버 테이블: 현재 로그인 사용자 1명만 표시 */}
      <div className={styles.table}>
        <div className={`${styles.row} ${styles["row-head"]}`}>
          <span className={styles.checkbox} aria-hidden />
          <div className={styles["row-main"]}>
            <span>사용자</span>
            <span className={styles["cell-role"]}>사용 권한</span>
          </div>
        </div>
        <div className={styles.row}>
          <span className={styles.checkbox} aria-hidden />
          <div className={styles["row-main"]}>
            <div className={styles.profile}>
              <SvgIcon src={userCircleIcon} className={styles["profile-icon"]} />
              <div className={styles["profile-text"]}>
                <span className={styles["profile-name"]}>{name}</span>
                <span className={styles["profile-email"]}>{email}</span>
              </div>
            </div>
            <span className={styles["cell-role"]}>
              <button type="button" className={styles["role-chip"]} disabled>
                OWNER <SvgIcon src={settingScrollIcon} className={styles["chev-icon"]} />
              </button>
            </span>
          </div>
          <button type="button" className={styles["more-btn"]} aria-label="멤버 더보기" disabled>
            ⋯
          </button>
        </div>
      </div>
    </div>
  );
}
