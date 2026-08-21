import { useMemo, useState } from "react";
import type { SelectableTreeItem } from "@/widgets/document-sidebar/model/types";
import type { Project, TreeItem } from "@/entities/tree/model/tree";

// 모달에 최대로 노출할 결과 수
const MAX_RESULTS = 12;

// 한글 음절(가-힣) 분해용 초성 테이블
const CHOSEONG = ["ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"];

/** 한글 음절을 초성으로 치환한다. 그 외 문자는 그대로 둔다. ("새 노트" → "ㅅ ㄴㅌ") */
function toChoseong(text: string): string {
  let result = "";
  for (const char of text) {
    const code = char.charCodeAt(0);
    result += code >= 0xac00 && code <= 0xd7a3
      ? CHOSEONG[Math.floor((code - 0xac00) / 588)]
      : char;
  }
  return result;
}

/** 자음(초성)으로만 이뤄진 검색어인지. "ㅅ" 한 글자로도 초성 검색이 동작한다. */
function isChoseongQuery(query: string): boolean {
  return /^[ㄱ-ㅎ\s]+$/.test(query);
}

export type SearchHit = SelectableTreeItem & { projectTitle: string; updatedAt?: string };

/** 트리를 순회하며 선택 가능한 문서/노트(문서ID 또는 그래프노드ID 보유)를 평탄화한다. */
function collectSelectable(items: TreeItem[], projectTitle: string, acc: SearchHit[]) {
  for (const item of items) {
    if (item.documentId || item.graphNodeId) {
      acc.push({
        id: item.id,
        label: item.label,
        documentId: item.documentId,
        graphNodeId: item.graphNodeId,
        projectTitle,
        updatedAt: item.updatedAt ?? item.uploadedAt
      });
    }
    if (item.children?.length) collectSelectable(item.children, projectTitle, acc);
  }
}

/** 문서명 검색 로직: 트리 평탄화 + 클라이언트 필터링. UI와 분리된 feature model 계층. */
export function useDocumentSearch(projects: Project[]) {
  const [query, setQuery] = useState("");

  const allItems = useMemo(() => {
    const acc: SearchHit[] = [];
    for (const project of projects) collectSelectable(project.items, project.title, acc);
    return acc;
  }, [projects]);

  const normalizedQuery = query.trim().toLowerCase();
  const matched = useMemo(() => {
    // 검색어가 없으면 현재 있는 문서를 전부 보여준다.
    if (!normalizedQuery) return allItems;
    const isChoseongMode = isChoseongQuery(normalizedQuery);
    return allItems.filter((item) => {
      const label = item.label.toLowerCase();
      if (label.includes(normalizedQuery)) return true;
      return isChoseongMode && toChoseong(label).includes(normalizedQuery);
    });
  }, [allItems, normalizedQuery]);

  const results = matched.slice(0, MAX_RESULTS);
  const overflowCount = matched.length - results.length;

  return { query, setQuery, normalizedQuery, results, overflowCount };
}
