"use client";

/** 채팅 내용을 위키 문서로 내보내기 전 미리보기·수락/취소 팝업 (Figma 513:10878) */
export function WikiExportConfirmCard({
  title,
  previewContent,
  isSubmitting,
  onCancel,
  onAccept
}: {
  title: string;
  previewContent: string;
  isSubmitting: boolean;
  onCancel: () => void;
  onAccept: () => void;
}) {
  return (
    <section
      className="wiki-export-card"
      aria-labelledby="wiki-export-title"
      onClick={(event) => event.stopPropagation()}
    >
      <h2 id="wiki-export-title">{title}</h2>
      <p className="wiki-export-preview">{previewContent}</p>
      <div className="wiki-export-actions">
        <button type="button" className="wiki-export-cancel" disabled={isSubmitting} onClick={onCancel}>
          취소
        </button>
        <button type="button" className="wiki-export-accept" disabled={isSubmitting} onClick={onAccept}>
          수락
        </button>
      </div>
    </section>
  );
}
