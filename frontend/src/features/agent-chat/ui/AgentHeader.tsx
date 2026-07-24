import { ChevronDown, Folder, MoreHorizontal, MoreVertical, Search } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { createChatSession, deleteChatSession, fetchChatSessions, setActiveChatSession } from "@/entities/chat/api/chat";
import { exportChatWiki } from "@/features/wiki-export";
import { getErrorMessage } from "@/shared/lib/errors";
import type { ChatSessionResponse } from "@/entities/chat/model/chat";
import { fruitionLogo, sideboxIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import { cx } from "@/shared/lib/classNames";
import styles from "./AgentChat.module.css";

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
  const [rowMenuSessionId, setRowMenuSessionId] = useState<string | null>(null);
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

  // 세션 삭제 후 남은 세션 중 첫 번째로 전환하고, 없으면 새 채팅을 만든다.
  async function handleDeleteSession(sessionId: string) {
    setRowMenuSessionId(null);
    try {
      await deleteChatSession(sessionId);
      const response = await fetchChatSessions();
      const remaining = response.sessions ?? [];
      setSessions(remaining);
      if (remaining[0]) onSelectSession(remaining[0].id, remaining[0].title);
      else await startNewChat();
    } catch (error: unknown) {
      setLoadErrorMessage(getErrorMessage(error, "채팅을 삭제하지 못했습니다."));
    }
  }

  // 세션 전체를 원본 문서(위키)로 내보낸다.
  async function handleExportSession(sessionId: string) {
    setRowMenuSessionId(null);
    setIsListOpen(false);
    try {
      setActiveChatSession(sessionId);
      await exportChatWiki();
      setLoadErrorMessage(null);
    } catch (error: unknown) {
      setLoadErrorMessage(getErrorMessage(error, "원본 문서로 만들지 못했습니다."));
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

  // 세션 title이 없으면 헤더와 동일하게 "새 채팅"으로 표시한다
  const fallbackTitle = "새 채팅";
  const normalizedSearch = searchTerm.trim().toLowerCase();
  const visibleSessions = sessions.filter((session) =>
    (session.title ?? fallbackTitle).toLowerCase().includes(normalizedSearch)
  );

  return (
    <div className={styles["agent-header"]} ref={rootRef}>
      <button
        type="button"
        className={styles["agent-session-title"]}
        aria-expanded={isListOpen}
        onClick={() => setIsListOpen((open) => !open)}
      >
        <span>{sessionTitle}</span>
        <ChevronDown size={12} />
      </button>
      <button className={styles["panel-action"]} aria-label="Agent 패널 숨기기" onClick={onClose}>
        <SvgIcon src={sideboxIcon} />
      </button>
      <div className={styles["agent-header-menu"]} ref={menuRef}>
        <button
          type="button"
          className={styles["panel-action"]}
          aria-label="채팅 옵션"
          aria-expanded={isMenuOpen}
          onClick={() => setIsMenuOpen((open) => !open)}
        >
          <MoreHorizontal size={16} />
        </button>
        {isMenuOpen && (
          <div className={styles["agent-header-menu-list"]} role="menu">
            <button type="button" role="menuitem" onClick={startNewChat}>새 채팅</button>
          </div>
        )}
      </div>

      {isListOpen && (
        <div className={styles["chat-session-dropdown"]}>
          <label className={styles["chat-session-search"]}>
            <Search size={12} />
            <input
              aria-label="채팅 검색"
              value={searchTerm}
              placeholder="채팅 검색"
              onChange={(event) => setSearchTerm(event.target.value)}
            />
          </label>
          {loadErrorMessage ? (
            <p className={styles["chat-session-error"]} role="alert">{loadErrorMessage}</p>
          ) : visibleSessions.length === 0 ? (
            <p className={styles["chat-session-empty"]}>채팅 세션이 없습니다.</p>
          ) : (
            <div className={styles["chat-session-list"]}>
              {visibleSessions.map((session) => {
                const isActive = session.id === activeSessionId;
                return (
                <div
                  key={session.id}
                  className={cx(styles["chat-session-item"], isActive && styles["is-active"])}
                >
                  <button
                    type="button"
                    className={styles["chat-session-select"]}
                    onClick={() => {
                      setRowMenuSessionId(null);
                      onSelectSession(session.id, session.title ?? fallbackTitle);
                      setIsListOpen(false);
                    }}
                  >
                    {isActive
                      ? <SvgIcon src={fruitionLogo} className={styles["chat-session-logo"]} />
                      : <Folder size={12} />}
                    <span>{session.title ?? fallbackTitle}</span>
                  </button>
                  {isActive && (
                    <div className={styles["chat-session-menu"]}>
                      <button
                        type="button"
                        className={styles["chat-session-more"]}
                        aria-label="채팅 옵션"
                        aria-expanded={rowMenuSessionId === session.id}
                        onClick={() => setRowMenuSessionId((cur) => (cur === session.id ? null : session.id))}
                      >
                        <MoreVertical size={12} />
                      </button>
                      {rowMenuSessionId === session.id && (
                        <div className={styles["chat-session-menu-list"]} role="menu">
                          <button type="button" role="menuitem" onClick={() => handleExportSession(session.id)}>
                            원본 문서로 생성
                          </button>
                          <button
                            type="button"
                            role="menuitem"
                            className={styles["is-danger"]}
                            onClick={() => handleDeleteSession(session.id)}
                          >
                            삭제
                          </button>
                        </div>
                      )}
                    </div>
                  )}
                </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
