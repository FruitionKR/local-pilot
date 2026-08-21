import { ChevronDown, MoreHorizontal, MoreVertical, Plus, Search } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { createChatSession, deleteChatSession, fetchChatSessions } from "@/entities/chat/api/chat";
import { getErrorMessage } from "@/shared/lib/errors";
import { useDismissOnOutside } from "@/shared/lib/useDismissOnOutside";
import type { ChatSessionResponse } from "@/entities/chat/model/chat";
import { chatBubbleIcon, fruitionLogo, sideboxIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import { cx } from "@/shared/lib/classNames";
import styles from "./AgentChat.module.css";

/** 채팅 패널 헤더: 세션 제목 + 세션 목록 드롭다운 + 패널 닫기 버튼 */
export function AgentHeader({
  sessionTitle,
  onClose,
  activeSessionId,
  isInteractionLocked,
  onSelectSession,
  canStartWikiExport,
  onStartWikiExport
}: {
  sessionTitle: string;
  onClose: () => void;
  activeSessionId: string | null;
  isInteractionLocked: boolean;
  onSelectSession: (sessionId: string, title: string | null) => void;
  canStartWikiExport: boolean;
  onStartWikiExport: () => void;
}) {
  const [isListOpen, setIsListOpen] = useState(false);
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState("");
  const [sessions, setSessions] = useState<ChatSessionResponse[]>([]);
  const [loadErrorMessage, setLoadErrorMessage] = useState<string | null>(null);
  // 세션 생성 요청 진행 중 여부. 연타로 인한 중복 POST를 막는다.
  const [isCreatingChat, setIsCreatingChat] = useState(false);
  // 행 옵션 메뉴는 스크롤 컨테이너에 클리핑되지 않도록 portal(fixed)로 띄운다.
  const [rowMenu, setRowMenu] = useState<{ id: string; top: number; left: number } | null>(null);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!isInteractionLocked) return;
    setIsListOpen(false);
    setIsMenuOpen(false);
    setRowMenu(null);
  }, [isInteractionLocked]);

  useDismissOnOutside(menuRef, isMenuOpen, () => setIsMenuOpen(false));

  // 성공 여부를 반환해 호출부가 실패 시 에러 표시 상태(목록 열림)를 유지할 수 있게 한다.
  async function startNewChat() {
    if (isCreatingChat) return false;
    setIsMenuOpen(false);
    setIsCreatingChat(true);
    try {
      const created = await createChatSession();
      onSelectSession(created.id, created.title);
      return true;
    } catch (error: unknown) {
      setLoadErrorMessage(getErrorMessage(error, "새 채팅을 만들지 못했습니다."));
      return false;
    } finally {
      setIsCreatingChat(false);
    }
  }

  // 세션 삭제 후 남은 세션 중 첫 번째로 전환하고, 없으면 새 채팅을 만든다.
  async function handleDeleteSession(sessionId: string) {
    setRowMenu(null);
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

  useEffect(() => {
    if (!isListOpen) setRowMenu(null);
  }, [isListOpen]);

  useDismissOnOutside(rootRef, isListOpen, () => setIsListOpen(false));

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

    return () => {
      cancelled = true;
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
        disabled={isInteractionLocked}
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
          disabled={isInteractionLocked}
          onClick={() => setIsMenuOpen((open) => !open)}
        >
          <MoreHorizontal size={16} />
        </button>
        {isMenuOpen && (
          <div className={styles["agent-header-menu-list"]} role="menu">
            <button type="button" role="menuitem" onClick={startNewChat}>새 채팅</button>
            <button
              type="button"
              role="menuitem"
              disabled={!canStartWikiExport}
              onClick={() => {
                setIsMenuOpen(false);
                onStartWikiExport();
              }}
            >
              채팅을 문서로 편입
            </button>
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
          <button
            type="button"
            className={styles["chat-session-new"]}
            disabled={isInteractionLocked || isCreatingChat}
            onClick={() => {
              // 실패 시 목록을 열어 둔 채 에러 문구를 보여주기 위해 성공했을 때만 닫는다.
              void startNewChat().then((created) => {
                if (created) setIsListOpen(false);
              });
            }}
          >
            <Plus size={12} />
            <span>새 채팅</span>
          </button>
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
                    disabled={isInteractionLocked}
                    onClick={() => {
                      setRowMenu(null);
                      onSelectSession(session.id, session.title ?? fallbackTitle);
                      setIsListOpen(false);
                    }}
                  >
                    {isActive
                      ? <SvgIcon src={fruitionLogo} className={styles["chat-session-logo"]} />
                      : <SvgIcon src={chatBubbleIcon} className={styles["chat-session-icon"]} />}
                    <span>{session.title ?? fallbackTitle}</span>
                  </button>
                  {isActive && (
                    <div className={styles["chat-session-menu"]}>
                      <button
                        type="button"
                        className={styles["chat-session-more"]}
                        aria-label="채팅 옵션"
                        aria-expanded={rowMenu?.id === session.id}
                        disabled={isInteractionLocked}
                        onClick={(event) => {
                          if (rowMenu?.id === session.id) {
                            setRowMenu(null);
                            return;
                          }
                          const rect = event.currentTarget.getBoundingClientRect();
                          setRowMenu({ id: session.id, top: rect.bottom + 4, left: rect.right - 132 });
                        }}
                      >
                        <MoreVertical size={12} />
                      </button>
                    </div>
                  )}
                </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {rowMenu && createPortal(
        <div
          className={styles["chat-session-menu-list"]}
          role="menu"
          style={{ top: rowMenu.top, left: rowMenu.left }}
          onPointerDown={(event) => event.stopPropagation()}
        >
          <button
            type="button"
            role="menuitem"
            className={styles["is-danger"]}
            onClick={() => handleDeleteSession(rowMenu.id)}
          >
            삭제
          </button>
        </div>,
        document.body
      )}
    </div>
  );
}
