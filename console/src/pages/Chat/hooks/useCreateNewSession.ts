import { useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { useChatAnywhereSessions } from "@agentscope-ai/chat";
import sessionApi from "../sessionApi";

/**
 * Returns a stable async function that creates a new blank chat session.
 *
 * Navigates to /chat BEFORE calling the library's
 * createSession so that ChatSessionInitializer sees chatId=undefined and does
 * not re-apply the previous session, which would race against the new session
 * creation.
 */
export function useCreateNewSession(): () => Promise<void> {
  const navigate = useNavigate();
  const { createSession } = useChatAnywhereSessions();

  return useCallback(async () => {
    navigate("/chat", { replace: true });
    sessionApi.userInitiatedCreate = true;
    await createSession();
  }, [navigate, createSession]);
}
