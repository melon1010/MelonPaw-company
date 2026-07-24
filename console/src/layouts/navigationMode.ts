import { matchPath } from "react-router-dom";

export const APP_PRIMARY_NAV_IDS = [
  "company.projects",
  "core.cron-jobs",
] as const;

export const APP_PRIMARY_NAV_ID_SET = new Set<string>(APP_PRIMARY_NAV_IDS);

interface NavigationPath {
  path: string;
}

export function shouldUseSettingsNavigation(
  currentPath: string,
  appPrimaryEntries: NavigationPath[],
) {
  if (
    currentPath === "/" ||
    currentPath === "/chat" ||
    currentPath.startsWith("/chat/")
  ) {
    return false;
  }

  const isAppPrimaryRoute = appPrimaryEntries.some((entry) =>
    matchPath({ path: entry.path, end: false }, currentPath),
  );

  return !isAppPrimaryRoute;
}
