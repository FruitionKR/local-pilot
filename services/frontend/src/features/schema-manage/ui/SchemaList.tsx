"use client";

import styles from "./SchemaWorkspace.module.css";
import type { WikiSchema } from "@/entities/schema/model/schema";

const STATUS_LABELS: Record<WikiSchema["status"], string> = {
  active: "활성",
  draft: "초안",
  rejected: "거부됨"
};

export function SchemaList({
  schemas,
  selectedId,
  isBusy,
  onSelect,
  onActivate
}: {
  schemas: WikiSchema[];
  selectedId: string | null;
  isBusy: boolean;
  onSelect: (schema: WikiSchema) => void;
  onActivate: (schema: WikiSchema) => void;
}) {
  if (schemas.length === 0) {
    return <p className={styles["schema-muted"]}>아직 만든 스킬이 없습니다. 오른쪽에서 새 스킬을 작성해보세요.</p>;
  }
  return (
    <ol className={styles["schema-list"]}>
      {schemas.map((schema) => (
        <li key={schema.id}>
          <button
            type="button"
            className={`${styles["schema-list-item"]}${schema.id === selectedId ? ` ${styles["is-selected"]}` : ""}`}
            onClick={() => onSelect(schema)}
          >
            <span className={styles["schema-list-name"]}>{schema.name}</span>
            <span className={`${styles["schema-badge"]} ${styles[`is-${schema.status}`]}`}>{STATUS_LABELS[schema.status]}</span>
          </button>
          {schema.status !== "active" && (
            <button
              type="button"
              className={styles["schema-list-activate"]}
              disabled={isBusy || schema.hasBlockedIssues}
              onClick={() => onActivate(schema)}
            >
              활성화
            </button>
          )}
        </li>
      ))}
    </ol>
  );
}
