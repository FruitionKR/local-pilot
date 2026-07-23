"use client";

import { useMemo, useState, type MouseEvent as ReactMouseEvent } from "react";
import { searchIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import type { SelectableTreeItem } from "@/widgets/document-sidebar/model/types";
import type { Project, TreeItem } from "@/entities/tree/model/tree";

// 드롭다운에 최대로 노출할 결과 수
const MAX_RESULTS = 8;

type SearchHit = SelectableTreeItem & { projectTitle: string };

/** 트리를 순회하며 선택 가능한 문서/노트(문서ID 또는 그래프노드ID 보유)를 평탄화한다. */
function collectSelectable(items: TreeItem[], projectTitle: string, acc: SearchHit[]) {
  for (const item of items) {
    if (item.documentId || item.graphNodeId) {
      acc.push({
        id: item.id,
        label: item.label,
        documentId: item.documentId,
        graphNodeId: item.graphNodeId,
        projectTitle
      });
    }
    if (item.children?.length) collectSelectable(item.children, projectTitle, acc);
  }
}

/** 사이드바 문서명 검색. 입력값으로 트리를 클라이언트 필터링하고, 결과 클릭 시 해당 문서를 연다. */
export function DocumentSearch({
  projects,
  onSelectGraphNode
}: {
  projects: Project[];
  onSelectGraphNode: (item: SelectableTreeItem) => void;
}) {
  const [query, setQuery] = useState("");

  const allItems = useMemo(() => {
    const acc: SearchHit[] = [];
    for (const project of projects) collectSelectable(project.items, project.title, acc);
    return acc;
  }, [projects]);

  const normalizedQuery = query.trim().toLowerCase();
  const matched = useMemo(() => {
    if (!normalizedQuery) return [];
    return allItems.filter((item) => item.label.toLowerCase().includes(normalizedQuery));
  }, [allItems, normalizedQuery]);
  const results = matched.slice(0, MAX_RESULTS);
  const overflowCount = matched.length - results.length;

  function handleSelect(event: ReactMouseEvent<HTMLButtonElement>, item: SearchHit) {
    event.stopPropagation();
    onSelectGraphNode(item);
    setQuery("");
  }

  return (
    <div className="sidebar-search" onClick={(event) => event.stopPropagation()}>
      <label className="sidebar-search-box">
        <SvgIcon src={searchIcon} className="sidebar-search-icon" />
        <input
          type="search"
          placeholder="문서명 검색"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </label>
      {normalizedQuery && (
        <div className="sidebar-search-results" role="group" aria-label="문서 검색 결과">
          {results.length > 0 ? (
            <>
              {results.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className="sidebar-search-result"
                  onClick={(event) => handleSelect(event, item)}
                >
                  <span className="sidebar-search-result-label">{item.label}</span>
                  <span className="sidebar-search-result-project">{item.projectTitle}</span>
                </button>
              ))}
              {overflowCount > 0 ? (
                <p className="sidebar-search-more">외 {overflowCount}개 더 있습니다. 검색어를 좁혀 주세요.</p>
              ) : null}
            </>
          ) : (
            <p className="sidebar-search-empty">검색 결과가 없습니다.</p>
          )}
        </div>
      )}
    </div>
  );
}
