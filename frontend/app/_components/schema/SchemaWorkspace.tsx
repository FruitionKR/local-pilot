"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { SchemaEditorForm } from "./SchemaEditorForm";
import { SchemaList } from "./SchemaList";
import { SchemaPreviewCard } from "./SchemaPreviewCard";
import {
  activateWikiSchema,
  createWikiSchemaDraft,
  listWikiSchemas,
  previewWikiSchema
} from "../../_lib/api/schema";
import { getErrorMessage } from "../../_lib/errors";
import type { WikiSchema, WikiSchemaPreview } from "../../_lib/types/schema";

// 스킬(스키마) 관리 임시 화면. rail "규칙" 뷰에 마운트된다.
// 데이터는 목업(_lib/api/schema.ts)으로 구동하며, 실제 배선은 상호참조 이슈로 정리한다.
export function SchemaWorkspace() {
  const [schemas, setSchemas] = useState<WikiSchema[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [rawMarkdown, setRawMarkdown] = useState("");
  const [preview, setPreview] = useState<WikiSchemaPreview | null>(null);
  const [isBusy, setIsBusy] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setSchemas(await listWikiSchemas());
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const selectedPreview = useMemo<WikiSchemaPreview | null>(() => {
    const selected = schemas.find((schema) => schema.id === selectedId);
    if (!selected) return null;
    return {
      fragments: selected.fragments,
      issues: selected.issues,
      previewMarkdown: selected.previewMarkdown,
      hasBlockedIssues: selected.hasBlockedIssues
    };
  }, [schemas, selectedId]);

  async function run(task: () => Promise<void>) {
    setIsBusy(true);
    setErrorMessage(null);
    try {
      await task();
    } catch (error) {
      setErrorMessage(getErrorMessage(error, "요청을 처리하지 못했습니다."));
    } finally {
      setIsBusy(false);
    }
  }

  function handlePreview() {
    void run(async () => {
      setSelectedId(null);
      setPreview(await previewWikiSchema(rawMarkdown));
    });
  }

  function handleSaveDraft() {
    void run(async () => {
      const draft = await createWikiSchemaDraft(rawMarkdown, name);
      await refresh();
      setSelectedId(draft.id);
      setPreview(null);
    });
  }

  function handleActivate(schema: WikiSchema) {
    void run(async () => {
      await activateWikiSchema(schema.id);
      await refresh();
      setSelectedId(schema.id);
    });
  }

  return (
    <section className="schema-workspace" aria-label="스킬 관리">
      <div className="schema-column schema-column-list">
        <header className="schema-header">
          <h2>스킬</h2>
          <p>원하는 형태로 스킬을 작성하면 AI 편집/생성에 반영됩니다. (현재 임시 화면)</p>
        </header>
        {errorMessage && <p className="schema-error" role="alert">{errorMessage}</p>}
        <SchemaList
          schemas={schemas}
          selectedId={selectedId}
          isBusy={isBusy}
          onSelect={(schema) => {
            setSelectedId(schema.id);
            setPreview(null);
          }}
          onActivate={handleActivate}
        />
      </div>

      <div className="schema-column schema-column-editor">
        <SchemaEditorForm
          name={name}
          rawMarkdown={rawMarkdown}
          isBusy={isBusy}
          onNameChange={setName}
          onMarkdownChange={setRawMarkdown}
          onPreview={handlePreview}
          onSaveDraft={handleSaveDraft}
        />
        {preview && <SchemaPreviewCard preview={preview} />}
        {!preview && selectedPreview && (
          <SchemaPreviewCard
            preview={selectedPreview}
            onActivate={selectedId ? () => {
              const selected = schemas.find((schema) => schema.id === selectedId);
              if (selected && selected.status !== "active") handleActivate(selected);
            } : undefined}
          />
        )}
      </div>
    </section>
  );
}
