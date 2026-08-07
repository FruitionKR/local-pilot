"use client";

import styles from "./SchemaWorkspace.module.css";
import type { SchemaFragments, WikiSchemaPreview } from "@/entities/schema/model/schema";

// 조각 키 → 사람이 읽는 라벨. 비어 있지 않은 조각만 표시한다.
const FRAGMENT_LABELS: { key: keyof SchemaFragments; label: string }[] = [
  { key: "globalMarkdown", label: "공통" },
  { key: "queryMarkdown", label: "질의" },
  { key: "ingestMarkdown", label: "수집" },
  { key: "editMarkdown", label: "편집" },
  { key: "conceptMarkdown", label: "개념" },
  { key: "templateMarkdown", label: "템플릿" }
];

export function SchemaPreviewCard({
  preview,
  onActivate
}: {
  preview: WikiSchemaPreview;
  onActivate?: () => void;
}) {
  const filledFragments = FRAGMENT_LABELS.filter(({ key }) => preview.fragments[key].trim().length > 0);
  return (
    <section className={styles["schema-preview"]} aria-label="스킬 정리 결과 미리보기">
      <header>
        <strong>정리 결과</strong>
        {preview.hasBlockedIssues && <span className={`${styles["schema-badge"]} ${styles["is-blocked"]}`}>차단 이슈 있음</span>}
      </header>

      <div className={styles["schema-fragments"]}>
        {filledFragments.length === 0 ? (
          <p className={styles["schema-muted"]}>정리된 조각이 없습니다.</p>
        ) : (
          filledFragments.map(({ key, label }) => (
            <div className={styles["schema-fragment"]} key={key}>
              <span className={styles["schema-fragment-label"]}>{label}</span>
              <pre>{preview.fragments[key]}</pre>
            </div>
          ))
        )}
      </div>

      {preview.issues.length > 0 && (
        <ul className={styles["schema-issues"]} aria-label="보안/모호성 이슈">
          {preview.issues.map((issue, index) => (
            <li className={`${styles["schema-issue"]} ${styles[`is-${issue.severity}`]}`} key={`${issue.category}-${index}`}>
              <span className={styles["schema-issue-tag"]}>{issue.severity === "blocked" ? "차단" : "확인 필요"}</span>
              <div>
                <p className={styles["schema-issue-text"]}>{issue.text}</p>
                <p className={styles["schema-issue-reason"]}>{issue.reason}</p>
              </div>
            </li>
          ))}
        </ul>
      )}

      {onActivate && (
        <button
          type="button"
          className={styles["schema-activate"]}
          disabled={preview.hasBlockedIssues}
          onClick={onActivate}
        >
          {preview.hasBlockedIssues ? "차단 이슈 해결 후 활성화" : "이 스킬 활성화"}
        </button>
      )}
    </section>
  );
}
