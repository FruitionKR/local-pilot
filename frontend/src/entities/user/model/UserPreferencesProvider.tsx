"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode
} from "react";
import { usePathname } from "next/navigation";
import { getAccessToken, getSelectedWorkspaceId } from "@/shared/lib/auth";
import { fetchMe } from "../api/auth";
import {
  DEFAULT_USER_PREFERENCES,
  normalizeUserPreferences,
  type UserPreferences
} from "./preferences";

const STORAGE_KEY_PREFIX = "fruition.preferences.v1";
const GRAPH_STORAGE_KEY_PREFIX = "fruition.preferences.graph.v1";

type UserPreferencesContextValue = {
  preferences: UserPreferences;
  preferencesReady: boolean;
  reduceMotion: boolean;
  updatePreferences: (update: (current: UserPreferences) => UserPreferences) => void;
  resetPreferences: () => void;
};

const UserPreferencesContext = createContext<UserPreferencesContextValue | null>(null);

function readStoredPreferences(key: string) {
  try {
    return normalizeUserPreferences(JSON.parse(window.localStorage.getItem(key) ?? "null"));
  } catch {
    return DEFAULT_USER_PREFERENCES;
  }
}

function readStoredGraphPreferences(key: string) {
  try {
    const graph = JSON.parse(window.localStorage.getItem(key) ?? "null");
    return normalizeUserPreferences({ graph }).graph;
  } catch {
    return DEFAULT_USER_PREFERENCES.graph;
  }
}

export function UserPreferencesProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const [preferences, setPreferences] = useState<UserPreferences>(DEFAULT_USER_PREFERENCES);
  const [storageKey, setStorageKey] = useState<string | null>(null);
  const [graphStorageKey, setGraphStorageKey] = useState<string | null>(null);
  const [preferencesReady, setPreferencesReady] = useState(false);
  const [systemReducesMotion, setSystemReducesMotion] = useState(false);

  useEffect(() => {
    if (!getAccessToken()) {
      setPreferences(DEFAULT_USER_PREFERENCES);
      setStorageKey(null);
      setGraphStorageKey(null);
      setPreferencesReady(true);
      return;
    }

    let ignore = false;
    setPreferences(DEFAULT_USER_PREFERENCES);
    setStorageKey(null);
    setGraphStorageKey(null);
    setPreferencesReady(false);
    fetchMe()
      .then((user) => {
        if (ignore) return;
        const key = `${STORAGE_KEY_PREFIX}.${user.id}`;
        const accountPreferences = readStoredPreferences(key);
        const workspaceId = getSelectedWorkspaceId();
        const nextGraphStorageKey = workspaceId
          ? `${GRAPH_STORAGE_KEY_PREFIX}.${user.id}.${workspaceId}`
          : null;
        setPreferences({
          ...accountPreferences,
          graph: nextGraphStorageKey
            ? readStoredGraphPreferences(nextGraphStorageKey)
            : DEFAULT_USER_PREFERENCES.graph
        });
        setStorageKey(key);
        setGraphStorageKey(nextGraphStorageKey);
        setPreferencesReady(true);
      })
      .catch(() => {
        // 계정을 식별하지 못하면 다른 사용자의 설정과 섞이지 않도록 저장하지 않는다.
        setPreferencesReady(true);
      });

    return () => {
      ignore = true;
    };
  }, [pathname]);

  useEffect(() => {
    if (!storageKey) return;
    window.localStorage.setItem(
      storageKey,
      JSON.stringify({ ...preferences, graph: DEFAULT_USER_PREFERENCES.graph })
    );
    if (graphStorageKey) {
      window.localStorage.setItem(graphStorageKey, JSON.stringify(preferences.graph));
    }
  }, [graphStorageKey, preferences, storageKey]);

  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    const syncSystemMotion = () => setSystemReducesMotion(mediaQuery.matches);
    syncSystemMotion();
    mediaQuery.addEventListener("change", syncSystemMotion);
    return () => mediaQuery.removeEventListener("change", syncSystemMotion);
  }, []);

  const reduceMotion = preferences.motion === "reduced"
    || (preferences.motion === "system" && systemReducesMotion);

  useEffect(() => {
    document.documentElement.dataset.reduceMotion = String(reduceMotion);
    document.documentElement.dataset.documentFont = preferences.documentFont;
  }, [preferences.documentFont, reduceMotion]);

  const updatePreferences = useCallback(
    (update: (current: UserPreferences) => UserPreferences) => {
      setPreferences((current) => normalizeUserPreferences(update(current)));
    },
    []
  );

  const resetPreferences = useCallback(() => {
    setPreferences(DEFAULT_USER_PREFERENCES);
  }, []);

  const value = useMemo<UserPreferencesContextValue>(
    () => ({
      preferences,
      preferencesReady,
      reduceMotion,
      updatePreferences,
      resetPreferences
    }),
    [preferences, preferencesReady, reduceMotion, resetPreferences, updatePreferences]
  );

  return (
    <UserPreferencesContext.Provider value={value}>
      {children}
    </UserPreferencesContext.Provider>
  );
}

export function useUserPreferences() {
  const context = useContext(UserPreferencesContext);
  if (!context) {
    throw new Error("useUserPreferences는 UserPreferencesProvider 안에서 사용해야 합니다.");
  }
  return context;
}
