import {
  createContext,
  useContext,
  useEffect,
  useState,
  useCallback,
  type ReactNode,
} from "react";

export type ThemeMode = "light" | "dark" | "system";
export type ResolvedTheme = "light" | "dark";

const STORAGE_KEY = "qwenpaw-theme";

interface ThemeContextValue {
  /** User selected preference: light / dark / system */
  themeMode: ThemeMode;
  /** Resolved final theme after applying system preference */
  isDark: boolean;
  setThemeMode: (mode: ThemeMode) => void;
  /** Convenience toggle: light ↔ dark (skips system) */
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  themeMode: "light",
  isDark: false,
  setThemeMode: () => {},
  toggleTheme: () => {},
});

function getInitialMode(): ThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === "light" || stored === "dark" || stored === "system") {
      return stored;
    }
  } catch {
    // ignore storage errors
  }
  // The authenticated workbench opens in the same focused dark mode as the
  // product's primary chat surface. Users can still choose system/light mode
  // from the avatar menu.
  return "dark";
}

function resolveIsDark(mode: ThemeMode): boolean {
  if (mode === "dark") return true;
  if (mode === "light") return false;
  // system
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [themeMode, setThemeModeState] = useState<ThemeMode>(getInitialMode);
  const [isDark, setIsDark] = useState<boolean>(() =>
    resolveIsDark(getInitialMode()),
  );

  // Apply dark/light class to <html> element for global CSS variable overrides
  useEffect(() => {
    const html = document.documentElement;
    const resolvedTheme: ResolvedTheme = isDark ? "dark" : "light";
    if (isDark) {
      html.classList.add("dark-mode");
    } else {
      html.classList.remove("dark-mode");
    }
    html.dataset.theme = resolvedTheme;
    html.style.colorScheme = resolvedTheme;
  }, [isDark]);

  // Listen to system theme changes when mode is "system"
  useEffect(() => {
    if (themeMode !== "system") return;

    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const handler = (e: MediaQueryListEvent) => {
      setIsDark(e.matches);
    };
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, [themeMode]);

  const setThemeMode = useCallback((mode: ThemeMode) => {
    setThemeModeState(mode);
    setIsDark(resolveIsDark(mode));
    try {
      localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // ignore
    }
  }, []);

  const toggleTheme = useCallback(() => {
    setThemeMode(isDark ? "light" : "dark");
  }, [isDark, setThemeMode]);

  return (
    <ThemeContext.Provider
      value={{ themeMode, isDark, setThemeMode, toggleTheme }}
    >
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme(): ThemeContextValue {
  return useContext(ThemeContext);
}
