"use client";

import dynamic from "next/dynamic";

export const DynamicNoteEditor = dynamic(
  () => import("./NoteEditor").then((module) => module.NoteEditor),
  {
    ssr: false,
    loading: () => <p>노트 편집기를 불러오는 중입니다.</p>
  }
);
