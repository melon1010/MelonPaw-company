export function getSessionIdFromPath(pathname: string): string | undefined {
  const match = pathname.match(/^\/chat\/(.+)$/);
  return match?.[1];
}

export function buildSessionPath(sessionId?: string | null): string {
  return sessionId ? `/chat/${sessionId}` : "/chat";
}
