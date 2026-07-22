import { useState, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Button, Form, Input } from "antd";
import {
  ArrowRight,
  KeyRound,
  LockKeyhole,
  Moon,
  Sun,
  UserRound,
} from "lucide-react";
import { useAppMessage } from "../../hooks/useAppMessage";
import { authApi } from "../../api/modules/auth";
import { setAuthToken } from "../../api/config";
import { useTheme } from "../../contexts/ThemeContext";
import { useAuth } from "../../contexts/AuthContext";
import styles from "./index.module.less";

export default function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { isDark, toggleTheme } = useTheme();
  const [loading, setLoading] = useState(false);
  const [isBootstrap, setIsBootstrap] = useState(
    searchParams.get("setup") === "1",
  );
  const [bootstrapTokenRequired, setBootstrapTokenRequired] = useState(false);
  const { message } = useAppMessage();
  const { setSession } = useAuth();

  useEffect(() => {
    authApi
      .getStatus()
      .then((res) => {
        if (!res.enabled) {
          navigate("/chat", { replace: true });
          return;
        }
        setIsBootstrap(Boolean(res.bootstrap_required || !res.has_users));
        setBootstrapTokenRequired(Boolean(res.bootstrap_token_required));
        if (res.authenticated && res.user) {
          setSession(res.user, res);
          navigate("/chat", { replace: true });
        }
      })
      .catch(() => {});
  }, [navigate, setSession]);

  const onFinish = async (values: {
    username: string;
    password: string;
    displayName?: string;
    bootstrapToken?: string;
  }) => {
    setLoading(true);
    try {
      const raw = searchParams.get("redirect") || "/chat";
      const redirect =
        raw.startsWith("/") && !raw.startsWith("//") ? raw : "/chat";

      const res = isBootstrap
        ? await authApi.bootstrap(
            values.username.trim(),
            values.password,
            values.displayName?.trim(),
            values.bootstrapToken?.trim(),
          )
        : await authApi.login(values.username.trim(), values.password);
      if (res.token) {
        setAuthToken(res.token);
        setSession(res.user);
        if (isBootstrap) message.success(t("enterprise.auth.bootstrapSuccess"));
        navigate(redirect, { replace: true });
      }
    } catch (err) {
      let errorMsg = t("login.failed");

      // Check if it's an Error object and use the backend message directly
      if (err instanceof Error) {
        // Use the backend message directly without complex parsing
        errorMsg = err.message;
      } else if (isBootstrap) {
        errorMsg = t("enterprise.auth.bootstrapFailed");
      }

      message.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className={`${styles.page} ${isDark ? styles.pageDark : ""}`}>
      <button
        type="button"
        className={styles.themeToggle}
        onClick={toggleTheme}
        aria-label={isDark ? t("theme.light") : t("theme.dark")}
        title={isDark ? t("theme.light") : t("theme.dark")}
      >
        {isDark ? <Sun size={17} /> : <Moon size={17} />}
      </button>

      <div className={styles.gridAxis} aria-hidden="true" />

      <main className={styles.loginShell}>
        <header className={styles.brandLockup}>
          <img className={styles.brandMark} src="/company-mark.svg" alt="" />
          <div className={styles.brandNameGroup}>
            <strong>MelonPaw</strong>
            <span>Company</span>
          </div>
        </header>

        <section className={styles.authPanel} aria-labelledby="login-title">
          <div className={styles.authEyebrow}>
            <span className={styles.statusDot} aria-hidden="true" />
            <span>{t("enterprise.auth.privateEdition")}</span>
          </div>
          <div className={styles.heading}>
            <h1 id="login-title">
              {isBootstrap
                ? t("enterprise.auth.bootstrapTitle")
                : t("enterprise.auth.loginTitle")}
            </h1>
            <p>
              {isBootstrap
                ? t("enterprise.auth.bootstrapHint")
                : t("enterprise.auth.loginHint")}
            </p>
          </div>

          <Form
            className={styles.form}
            layout="vertical"
            onFinish={onFinish}
            autoComplete="on"
            requiredMark={false}
            size="large"
          >
            {isBootstrap && (
              <Form.Item
                name="displayName"
                label={t("enterprise.auth.displayName")}
              >
                <Input
                  prefix={<UserRound size={17} />}
                  placeholder={t("enterprise.auth.displayName")}
                  autoComplete="name"
                />
              </Form.Item>
            )}
            {isBootstrap && bootstrapTokenRequired && (
              <Form.Item
                name="bootstrapToken"
                label={t("enterprise.auth.bootstrapToken")}
                rules={[
                  {
                    required: true,
                    message: t("enterprise.auth.bootstrapTokenRequired"),
                  },
                ]}
              >
                <Input.Password
                  prefix={<KeyRound size={17} />}
                  placeholder={t("enterprise.auth.bootstrapToken")}
                  autoComplete="one-time-code"
                />
              </Form.Item>
            )}
            <Form.Item
              name="username"
              label={t("login.usernameLabel")}
              rules={[{ required: true, message: t("login.usernameRequired") }]}
            >
              <Input
                prefix={<UserRound size={17} />}
                placeholder={t("login.usernamePlaceholder")}
                autoComplete="username"
                autoFocus
              />
            </Form.Item>

            <Form.Item
              name="password"
              label={t("login.passwordLabel")}
              rules={[{ required: true, message: t("login.passwordRequired") }]}
            >
              <Input.Password
                prefix={<LockKeyhole size={17} />}
                placeholder={t("login.passwordPlaceholder")}
                autoComplete={isBootstrap ? "new-password" : "current-password"}
              />
            </Form.Item>

            <Form.Item className={styles.submitItem}>
              <Button
                type="primary"
                htmlType="submit"
                loading={loading}
                block
                className={styles.submit}
              >
                <span>
                  {isBootstrap
                    ? t("enterprise.auth.bootstrapAction")
                    : t("enterprise.auth.loginAction")}
                </span>
                <ArrowRight size={17} aria-hidden="true" />
              </Button>
            </Form.Item>
          </Form>

          <div className={styles.panelFooter}>
            <span>MelonPaw Company Console</span>
            <span aria-hidden="true">MP / 01</span>
          </div>
        </section>
      </main>
    </div>
  );
}
