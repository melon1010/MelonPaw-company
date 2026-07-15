import { Suspense, useMemo, useState } from "react";
import { Layout, Result, Spin } from "antd";
import { Routes, Route, useLocation, matchPath } from "react-router-dom";
import { useTranslation } from "react-i18next";
import Sidebar from "../Sidebar";
import Header from "../Header";
import ConsolePollService from "../../components/ConsolePollService";
import { ChunkErrorBoundary } from "../../components/ChunkErrorBoundary";
import styles from "../index.module.less";
import { useRoutes } from "../../plugins/registry/hooks";
import { Slot } from "../../plugins/registry/Slot";
import { useAuth } from "../../contexts/AuthContext";
import { canAccessRoute } from "../../auth/authorization";
import MobileNavigation from "../MobileNavigation";

const { Content } = Layout;

/**
 * Find the registered route whose path pattern matches the current URL.
 * Falls back to "core.chat" so the sidebar always has a sensible
 * highlight, mirroring the old `pathToKey` default.
 */
function pickSelectedKey(
  currentPath: string,
  routes: ReturnType<typeof useRoutes>,
): string {
  for (const r of routes) {
    if (matchPath({ path: r.path, end: r.path === "/" }, currentPath)) {
      return r.id;
    }
  }
  return "core.chat";
}

export default function MainLayout() {
  const { t } = useTranslation();
  const location = useLocation();
  const currentPath = location.pathname;
  const routes = useRoutes();
  const { user } = useAuth();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  const selectedKey = useMemo(
    () => pickSelectedKey(currentPath, routes),
    [currentPath, routes],
  );
  const canPollConsole = canAccessRoute("core.inbox", user);

  return (
    <Layout className={styles.mainLayout}>
      <Header
        sidebarCollapsed={sidebarCollapsed}
        onToggleSidebar={() => setSidebarCollapsed((value) => !value)}
      />
      <Layout>
        <Sidebar selectedKey={selectedKey} collapsed={sidebarCollapsed} />
        <Content className="page-container">
          {canPollConsole && <ConsolePollService />}
          <Slot name="content.statusBar" kind="fill" />
          <div className="page-content">
            <ChunkErrorBoundary resetKey={currentPath}>
              <Suspense
                fallback={
                  <Spin
                    tip={t("common.loading")}
                    style={{ display: "block", margin: "20vh auto" }}
                  />
                }
              >
                <Routes>
                  {routes.map((r) => (
                    <Route
                      key={r.id}
                      path={r.path}
                      element={
                        canAccessRoute(r.id, user) ? (
                          <r.Component />
                        ) : (
                          <Result
                            status="403"
                            title="403"
                            subTitle={t("enterprise.auth.forbidden")}
                          />
                        )
                      }
                    />
                  ))}
                </Routes>
              </Suspense>
            </ChunkErrorBoundary>
          </div>
        </Content>
      </Layout>
      <MobileNavigation />
      <Slot name="overlay.global" kind="fill" />
    </Layout>
  );
}
