import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import type { AuthStatusResponse, AuthUser } from "../api/modules/auth";
import { useAgentStore } from "../stores/agentStore";

interface AuthContextValue {
  user: AuthUser | null;
  status: AuthStatusResponse | null;
  setSession: (user: AuthUser | null, status?: AuthStatusResponse | null) => void;
  clearSession: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [status, setStatus] = useState<AuthStatusResponse | null>(null);

  const setSession = useCallback(
    (nextUser: AuthUser | null, nextStatus?: AuthStatusResponse | null) => {
      if (nextUser) {
        useAgentStore.getState().bindUser(nextUser.id);
      }
      setUser(nextUser);
      if (nextStatus !== undefined) setStatus(nextStatus);
    },
    [],
  );

  const clearSession = useCallback(() => {
    setUser(null);
    setStatus(null);
  }, []);

  const value = useMemo(
    () => ({ user, status, setSession, clearSession }),
    [user, status, setSession, clearSession],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
