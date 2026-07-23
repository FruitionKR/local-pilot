"use client";

import styles from "./SchemaWorkspace.module.css";

export function SchemaEditorForm({
  name,
  rawMarkdown,
  isBusy,
  onNameChange,
  onMarkdownChange,
  onPreview,
  onSaveDraft
}: {
  name: string;
  rawMarkdown: string;
  isBusy: boolean;
  onNameChange: (value: string) => void;
  onMarkdownChange: (value: string) => void;
  onPreview: () => void;
  onSaveDraft: () => void;
}) {
  const canSubmit = rawMarkdown.trim().length > 0 && !isBusy;
  return (
    <form className={styles["schema-form"]} onSubmit={(event) => event.preventDefault()}>
      <label className={styles["schema-field"]}>
        <span>스킬 이름</span>
        <input
          type="text"
          value={name}
          placeholder="예: 사내 위키 작성 규칙"
          onChange={(event) => onNameChange(event.target.value)}
        />
      </label>
      <label className={styles["schema-field"]}>
        <span>스킬 내용 (Markdown)</span>
        <textarea
          value={rawMarkdown}
          rows={12}
          placeholder={"## 편집\n- 문서는 항상 개요 → 본문 → 요약 순으로 정리한다.\n\n## 개념\n- 사내 용어는 정식 명칭을 우선 사용한다."}
          onChange={(event) => onMarkdownChange(event.target.value)}
        />
      </label>
      <div className={styles["schema-form-actions"]}>
        <button type="button" disabled={!canSubmit} onClick={onPreview}>미리보기</button>
        <button type="button" className={styles["is-primary"]} disabled={!canSubmit} onClick={onSaveDraft}>초안 저장</button>
      </div>
    </form>
  );
}
