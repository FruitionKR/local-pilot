"use client";

import { cx } from "@/shared/lib/classNames";
import styles from "./LogsMockup.module.css";

type LogStatus = "success" | "error" | "info";

interface LogEntry {
  action: string;
  target: string;
  time: string;
  status: LogStatus;
}

// 로그 임시 목업. 실제 데이터 배선은 없다. rail "로그" 뷰에 마운트된다.
const MOCK_LOGS: LogEntry[] = [
  { action: "위키 페이지 생성", target: "학습지원 사례집 · concept page", time: "방금 전", status: "success" },
  { action: "문서 업로드", target: "2026_통합교육_효과분석.pdf", time: "3분 전", status: "success" },
  { action: "AI 편집 반영", target: "통합교육 효과분석 · 3개 문단", time: "12분 전", status: "info" },
  { action: "채팅 세션 생성", target: "새 채팅", time: "28분 전", status: "info" },
  { action: "파이프라인 처리 실패", target: "손상된_스캔본.pdf", time: "1시간 전", status: "error" },
  { action: "워크스페이스 전환", target: "dev의 워크스페이스", time: "2시간 전", status: "info" },
  { action: "원본 문서로 생성", target: "상담 기록 요약 · full session", time: "어제", status: "success" }
];

export function LogsMockup() {
  return (
    <section className={styles["logs"]} aria-label="로그">
      <div className={styles["logs-inner"]}>
        <header className={styles["logs-header"]}>
          <h2>
            로그
            <span className={styles["logs-badge"]}>미리보기</span>
          </h2>
          <p>워크스페이스 활동 기록입니다. (임시 목업 화면 — 실제 데이터는 연동 예정)</p>
        </header>

        <div className={styles["logs-list"]}>
          {MOCK_LOGS.map((log, index) => (
            <div key={index} className={styles["logs-row"]}>
              <span className={cx(styles["logs-dot"], styles[`is-${log.status}`])} aria-hidden />
              <div className={styles["logs-main"]}>
                <p className={styles["logs-action"]}>{log.action}</p>
                <p className={styles["logs-target"]}>{log.target}</p>
              </div>
              <span className={styles["logs-time"]}>{log.time}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
