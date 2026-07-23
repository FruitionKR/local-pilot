import { ChevronDown, Folder, MoreHorizontal, MoreVertical, Search } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { createChatSession, fetchChatSessions } from "../../_lib/api";
import { getErrorMessage } from "@/shared/lib/errors";
import { useWorkspaceName } from "@/entities/workspace/model/useWorkspaceName";
import type { ChatSessionResponse } from "../../_lib/types";
import { sideboxIcon, SvgIcon } from "@/shared/ui/SvgIcon";

/** 채팅 패널 헤더: 세션 제목 + 세션 목록 드롭다운 + 패널 닫기 버튼 */
export function AgentHeader({
  sessionTitle,
  onClose,
  activeSessionId,
  onSelectSession
}: {
  sessionTitle: string;
  onClose: () => void;
  activeSessionId: string | null;
  onSelectSession: (sessionId: string, title: string | null) => void;
}) {
  const [isListOpen, setIsListOpen] = useState(false);
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState("");
  const [sessions, setSessions] = useState<ChatSessionResponse[]>([]);
  const [loadErrorMessage, setLoadErrorMessage] = useState<string | null>(null);
  const workspaceName = useWorkspaceName();
  const rootRef = useRef<HTMLDivElement | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!isMenuOpen) return;
    function handleOutsidePointerDown(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setIsMenuOpen(false);
      }
    }
    document.addEventListener("mousedown", handleOutsidePointerDown);
    return () => document.removeEventListener("mousedown", handleOutsidePointerDown);
  }, [isMenuOpen]);

  async function startNewChat() {
    setIsMenuOpen(false);
    try {
      const created = await createChatSession();
      onSelectSession(created.id, created.title);
    } catch (error: unknown) {
      setLoadErrorMessage(getErrorMessage(error, "새 채팅을 만들지 못했습니다."));
    }
  }

  useEffect(() => {
    if (!isListOpen) return;

    let cancelled = false;
    fetchChatSessions()
      .then((response) => {
        if (cancelled) return;
        setSessions(response.sessions ?? []);
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
      <div className="agent-header-menu" ref={menuRef}>
        <button
          type="button"
          className="panel-action"
          aria-label="채팅 옵션"
          aria-expanded={isMenuOpen}
          onClick={() => setIsMenuOpen((open) => !open)}
        >
          <MoreHorizontal size={16} />
        </button>
        {isMenuOpen && (
          <div className="agent-header-menu-list" role="menu">
            <button type="button" role="menuitem" onClick={startNewChat}>새 채팅</button>
          </div>
        )}
      </div>

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
                  onClick={() => {
                    onSelectSession(session.id, session.title ?? fallbackTitle);
                    setIsListOpen(false);
                  }}
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
