import { ChevronDown, Folder, MoreHorizontal, MoreVertical, Search } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { fetchChatSessions, fetchCurrentChatSessionId } from "../../_lib/api";
import { getErrorMessage } from "../../_lib/errors";
import { useWorkspaceName } from "../../_hooks/useWorkspaceName";
import type { ChatSessionResponse } from "../../_lib/types";
import { sideboxIcon, SvgIcon } from "../SvgIcon";

/** 채팅 패널 헤더: 세션 제목 + 세션 목록 드롭다운 + 패널 닫기 버튼 */
export function AgentHeader({ sessionTitle, onClose }: { sessionTitle: string; onClose: () => void }) {
  const [isListOpen, setIsListOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState("");
  const [sessions, setSessions] = useState<ChatSessionResponse[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);
  const [loadErrorMessage, setLoadErrorMessage] = useState<string | null>(null);
  const workspaceName = useWorkspaceName();
  const rootRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!isListOpen) return;

    let cancelled = false;
    Promise.all([fetchChatSessions(), fetchCurrentChatSessionId()])
      .then(([response, sessionId]) => {
        if (cancelled) return;
        setSessions(response.sessions ?? []);
        setActiveSessionId(sessionId);
        setLoadErrorMessage(null);
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoadErrorMessage(getErrorMessage(error, "채팅 세션을 불러오지 못했습니다."));
      });

    function handleOutsidePointerDown(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setIsListOpen(false);
      }
    }
    document.addEventListener("mousedown", handleOutsidePointerDown);

    return () => {
      cancelled = true;
      document.removeEventListener("mousedown", handleOutsidePointerDown);
    };
  }, [isListOpen]);

  // 세션 title이 없으면 워크스페이스명 기반 문구로 표시한다
  const fallbackTitle = `${workspaceName ?? "워크스페이스"}의 채팅`;
  const normalizedSearch = searchTerm.trim().toLowerCase();
  const visibleSessions = sessions.filter((session) =>
    (session.title ?? fallbackTitle).toLowerCase().includes(normalizedSearch)
  );

  return (
    <div className="agent-header" ref={rootRef}>
      <button
        type="button"
        className="agent-session-title"
        aria-expanded={isListOpen}
        onClick={() => setIsListOpen((open) => !open)}
      >
        <span>{sessionTitle}</span>
        <ChevronDown size={12} />
      </button>
      <button className="panel-action" aria-label="Agent 패널 숨기기" onClick={onClose}>
        <SvgIcon src={sideboxIcon} />
      </button>
      {/* 시안의 ⋯ 메뉴 자리 — 동작은 아직 없음 */}
      <button type="button" className="panel-action" aria-label="채팅 옵션">
        <MoreHorizontal size={16} />
      </button>

      {isListOpen && (
        <div className="chat-session-dropdown">
          <label className="chat-session-search">
            <Search size={12} />
            <input
              aria-label="채팅 검색"
              value={searchTerm}
              placeholder="채팅 검색"
              onChange={(event) => setSearchTerm(event.target.value)}
            />
          </label>
          {loadErrorMessage ? (
            <p className="chat-session-error" role="alert">{loadErrorMessage}</p>
          ) : visibleSessions.length === 0 ? (
            <p className="chat-session-empty">채팅 세션이 없습니다.</p>
          ) : (
            <div className="chat-session-list">
              {visibleSessions.map((session) => {
                const isActive = session.id === activeSessionId;
                return (
                <button
                  key={session.id}
                  type="button"
                  className={`chat-session-item${isActive ? " is-active" : ""}`}
                  onClick={() => setIsListOpen(false)}
                >
                  <span>{session.title ?? fallbackTitle}</span>
                  {isActive
                    ? <MoreVertical className="chat-session-item-more" size={12} />
                    : <Folder className="chat-session-item-folder" size={12} />}
                </button>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
