import { createGlobalStyle } from "antd-style";
import {
  ConfigProvider,
  bailianDarkTheme,
  bailianTheme,
} from "@agentscope-ai/design";
import { App as AntdApp, Button, Result, Spin } from "antd";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import zhCN from "antd/locale/zh_CN";
import enUS from "antd/locale/en_US";
import jaJP from "antd/locale/ja_JP";
import ruRU from "antd/locale/ru_RU";
import idID from "antd/locale/id_ID";
import ptBR from "antd/locale/pt_BR";
import viVN from "antd/locale/vi_VN";
import type { Locale } from "antd/es/locale";
import { theme as antdTheme } from "antd";
import dayjs from "dayjs";
import relativeTime from "dayjs/plugin/relativeTime";
import "dayjs/locale/zh-cn";
import "dayjs/locale/ja";
import "dayjs/locale/ru";
import "dayjs/locale/id";
import "dayjs/locale/pt-br";
import "dayjs/locale/vi";
dayjs.extend(relativeTime);
import MainLayout from "./layouts/MainLayout";
import { ThemeProvider, useTheme } from "./contexts/ThemeContext";
import { AuthProvider, useAuth } from "./contexts/AuthContext";
import { PluginProvider } from "./plugins/PluginContext";
import { ApprovalProvider } from "./contexts/ApprovalContext";
import { Suspense } from "react";
import { lazyWithRetry } from "./utils/lazyWithRetry";

const LoginPage = lazyWithRetry(() => import("./pages/Login"));
import { authApi } from "./api/modules/auth";
import { languageApi } from "./api/modules/language";
import { useUploadLimitStore } from "./stores/uploadLimitStore";
import { getApiToken, clearAuthToken } from "./api/config";
import "./styles/layout.css";
import "./styles/form-override.css";
import "./styles/enterprise-theme.css";
import "./styles/webui-shell.css";

const antdLocaleMap: Record<string, Locale> = {
  zh: zhCN,
  en: enUS,
  ja: jaJP,
  ru: ruRU,
  id: idID,
  pt: ptBR,
  "pt-BR": ptBR,
  vi: viVN,
};

const dayjsLocaleMap: Record<string, string> = {
  zh: "zh-cn",
  en: "en",
  ja: "ja",
  ru: "ru",
  id: "id",
  pt: "pt-br",
  "pt-BR": "pt-br",
  vi: "vi",
};

const GlobalStyle = createGlobalStyle`
* {
  margin: 0;
  box-sizing: border-box;
}
`;

function AuthGuard({ children }: { children: React.ReactNode }) {
  const { t } = useTranslation();
  const [status, setStatus] = useState<
    "loading" | "auth-required" | "setup-required" | "error" | "ok"
  >("loading");
  const { setSession } = useAuth();

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await authApi.getStatus();
        if (cancelled) return;
        if (res.bootstrap_required || !res.has_users) {
          setSession(null, res);
          setStatus("setup-required");
          return;
        }
        if (!res.enabled) {
          setSession(
            res.user ?? {
              id: "local-system",
              username: "local-system",
              display_name: "Local Administrator",
              enabled: true,
              roles: ["SYSTEM_ADMIN"],
              permissions: ["*"],
            },
            res,
          );
          setStatus("ok");
          return;
        }
        const token = getApiToken();
        if (!token) {
          setStatus("auth-required");
          return;
        }
        const user = await authApi.getCurrentUser();
        if (cancelled) return;
        if (!user.enabled) throw new Error("Account is disabled");
        setSession(user, res);
        setStatus("ok");
      } catch {
        if (!cancelled) {
          if (getApiToken()) {
            clearAuthToken();
            setStatus("auth-required");
          } else {
            setStatus("error");
          }
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [setSession]);

  if (status === "loading") {
    return <Spin fullscreen tip={t("common.loading")} />;
  }
  if (status === "error") {
    return (
      <Result
        status="error"
        title={t("chunkError.title")}
        subTitle={t("chunkError.subTitle")}
        extra={
          <Button onClick={() => window.location.reload()}>
            {t("chunkError.reload")}
          </Button>
        }
      />
    );
  }
  if (status === "setup-required") {
    return <Navigate to="/login?setup=1" replace />;
  }
  if (status === "auth-required")
    return (
      <Navigate
        to={`/login?redirect=${encodeURIComponent(window.location.pathname)}`}
        replace
      />
    );
  return <>{children}</>;
}

function getRouterBasename(pathname: string): string | undefined {
  return /^\/console(?:\/|$)/.test(pathname) ? "/console" : undefined;
}

function AppInner() {
  const basename = getRouterBasename(window.location.pathname);
  const { i18n } = useTranslation();
  const { user } = useAuth();
  const { isDark } = useTheme();
  const selectedTheme = isDark ? bailianDarkTheme : bailianTheme;
  const companyTokens = isDark
    ? {
        colorPrimary: "#f4f4f5",
        colorPrimaryHover: "#ffffff",
        colorPrimaryActive: "#e4e4e7",
        colorPrimaryBg: "#2a2a2a",
        colorInfo: "#a1a1aa",
        colorSuccess: "#86efac",
        colorWarning: "#fbbf24",
        colorError: "#f87171",
        colorLink: "#e4e4e7",
        colorBgBase: "#171717",
        colorBgLayout: "#171717",
        colorBgContainer: "#1f1f1f",
        colorBgElevated: "#242424",
        colorFillAlter: "#262626",
        colorText: "#f4f4f5",
        colorTextSecondary: "#b4b4b8",
        colorTextTertiary: "#a1a1aa",
        colorTextQuaternary: "#8b8b92",
        colorTextDisabled: "#a1a1aa",
        colorTextPlaceholder: "#a1a1aa",
        colorBorder: "#3a3a3a",
        colorBorderSecondary: "#2a2a2a",
        colorBgContainerDisabled: "#2f2f2f",
        colorFillQuaternary: "#3f3f46",
        colorTextLightSolid: "#171717",
      }
    : {
        colorPrimary: "#18181b",
        colorPrimaryHover: "#27272a",
        colorPrimaryActive: "#09090b",
        colorPrimaryBg: "#f4f4f5",
        colorInfo: "#52525b",
        colorSuccess: "#15803d",
        colorWarning: "#a16207",
        colorError: "#b91c1c",
        colorLink: "#27272a",
        colorBgBase: "#ffffff",
        colorBgLayout: "#fafafa",
        colorBgContainer: "#ffffff",
        colorBgElevated: "#ffffff",
        colorFillAlter: "#f4f4f5",
        colorText: "#18181b",
        colorTextSecondary: "#52525b",
        colorTextTertiary: "#71717a",
        colorTextQuaternary: "#71717a",
        colorTextDisabled: "#71717a",
        colorTextPlaceholder: "#71717a",
        colorBorder: "#d4d4d8",
        colorBorderSecondary: "#e4e4e7",
        colorBgContainerDisabled: "#f4f4f5",
        colorFillQuaternary: "#d4d4d8",
        colorTextLightSolid: "#ffffff",
      };
  const lang = i18n.resolvedLanguage || i18n.language || "en";
  const [antdLocale, setAntdLocale] = useState<Locale>(
    antdLocaleMap[lang] ?? enUS,
  );

  useEffect(() => {
    if (user) {
      languageApi
        .getLanguage()
        .then(async ({ language, configured }) => {
          const localLanguage = localStorage.getItem("language");
          if (
            !configured &&
            localLanguage &&
            Object.prototype.hasOwnProperty.call(antdLocaleMap, localLanguage)
          ) {
            const saved = await languageApi.updateLanguage(localLanguage);
            language = saved.language;
          }
          if (language && language !== i18n.language) {
            await i18n.changeLanguage(language);
            localStorage.setItem("language", language);
          }
        })
        .catch((err) =>
          console.error("Failed to fetch language preference:", err),
        );
    }
    useUploadLimitStore.getState().fetch();
  }, [i18n, user]);

  useEffect(() => {
    const handleLanguageChanged = (lng: string) => {
      const shortLng = lng.split("-")[0];
      setAntdLocale(antdLocaleMap[shortLng] ?? enUS);
      dayjs.locale(dayjsLocaleMap[shortLng] ?? "en");
      document.documentElement.lang = lng;
    };

    // Set initial dayjs locale
    dayjs.locale(dayjsLocaleMap[lang.split("-")[0]] ?? "en");
    document.documentElement.lang = lang;

    i18n.on("languageChanged", handleLanguageChanged);
    return () => {
      i18n.off("languageChanged", handleLanguageChanged);
    };
  }, [i18n, lang]);

  return (
    <BrowserRouter basename={basename}>
      <GlobalStyle />
      <ConfigProvider
        {...selectedTheme}
        // Keep the legacy CSS namespace while the design-system dependency is
        // shared with the upstream console. This is not a product identifier.
        prefix="qwenpaw"
        prefixCls="qwenpaw"
        locale={antdLocale}
        theme={{
          ...selectedTheme.theme,
          algorithm: isDark
            ? antdTheme.darkAlgorithm
            : antdTheme.defaultAlgorithm,
          token: {
            ...selectedTheme.theme?.token,
            ...companyTokens,
            borderRadius: 4,
            borderRadiusLG: 6,
            controlHeight: 34,
            controlHeightSM: 28,
            fontSize: 14,
            boxShadow: "0 8px 24px rgba(28, 41, 38, 0.10)",
            boxShadowSecondary: "0 4px 14px rgba(28, 41, 38, 0.08)",
            fontFamily:
              "Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
          },
          components: {
            ...selectedTheme.theme?.components,
            Button: {
              borderRadius: 4,
              controlHeight: 34,
              primaryShadow: "none",
              defaultShadow: "none",
            },
            Card: { borderRadiusLG: 6 },
            Input: {
              activeBorderColor: companyTokens.colorPrimary,
              hoverBorderColor: companyTokens.colorPrimary,
              activeShadow: `0 0 0 2px ${
                isDark ? "rgba(255, 255, 255, 0.14)" : "rgba(24, 24, 27, 0.12)"
              }`,
            },
            Select: {
              activeBorderColor: companyTokens.colorPrimary,
              hoverBorderColor: companyTokens.colorPrimary,
              optionSelectedBg: companyTokens.colorPrimaryBg,
            },
            Table: {
              headerBg: isDark ? "#262626" : "#f4f4f5",
              headerColor: isDark ? "#b4b4b8" : "#52525b",
              rowHoverBg: isDark ? "#262626" : "#fafafa",
              borderColor: isDark ? "#2a2a2a" : "#e4e4e7",
              cellPaddingBlock: 10,
              cellPaddingInline: 14,
            },
            Tabs: {
              inkBarColor: companyTokens.colorPrimary,
              itemActiveColor: companyTokens.colorPrimary,
              itemHoverColor: companyTokens.colorPrimaryHover,
              itemSelectedColor: companyTokens.colorPrimary,
              titleFontSize: 13,
            },
            Menu: {
              itemBorderRadius: 4,
              itemHeight: 38,
              itemMarginInline: 6,
            },
            Modal: { borderRadiusLG: 6 },
          },
        }}
      >
        <AntdApp>
          <ApprovalProvider>
            <Routes>
              <Route
                path="/login"
                element={
                  <Suspense fallback={null}>
                    <LoginPage />
                  </Suspense>
                }
              />
              <Route
                path="/*"
                element={
                  <AuthGuard>
                    <MainLayout />
                  </AuthGuard>
                }
              />
            </Routes>
          </ApprovalProvider>
        </AntdApp>
      </ConfigProvider>
    </BrowserRouter>
  );
}

function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <PluginProvider>
          <AppInner />
        </PluginProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}

export default App;
