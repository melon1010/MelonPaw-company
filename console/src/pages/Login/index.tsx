import { useState, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Button, Form, Input, Typography } from "antd";
import { useAppMessage } from "../../hooks/useAppMessage";
import { KeyOutlined, LockOutlined, UserOutlined } from "@ant-design/icons";
import { authApi } from "../../api/modules/auth";
import { setAuthToken } from "../../api/config";
import { useTheme } from "../../contexts/ThemeContext";
import { useAuth } from "../../contexts/AuthContext";
import styles from "./index.module.less";

export default function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { isDark } = useTheme();
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
          navigate("/workbench", { replace: true });
          return;
        }
        setIsBootstrap(Boolean(res.bootstrap_required || !res.has_users));
        setBootstrapTokenRequired(Boolean(res.bootstrap_token_required));
        if (res.authenticated && res.user) {
          setSession(res.user, res);
          navigate("/workbench", { replace: true });
        }
      })
      .catch(() => {});
  }, [navigate]);

  const onFinish = async (values: {
    username: string;
    password: string;
    displayName?: string;
    bootstrapToken?: string;
  }) => {
    setLoading(true);
    try {
      const raw = searchParams.get("redirect") || "/workbench";
      const redirect =
        raw.startsWith("/") && !raw.startsWith("//") ? raw : "/workbench";

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
      <section className={styles.loginPanel} aria-labelledby="login-title">
        <div className={styles.brand}>
          <span className={styles.brandMark}>MP</span>
          <div>
            <div className={styles.brandName}>MelonPaw-Company</div>
            <div className={styles.brandEdition}>
              {t("enterprise.auth.privateEdition")}
            </div>
          </div>
        </div>
        <div className={styles.heading}>
          <Typography.Title id="login-title" level={2}>
            {isBootstrap
              ? t("enterprise.auth.bootstrapTitle")
              : t("enterprise.auth.loginTitle")}
          </Typography.Title>
          <Typography.Text type="secondary">
            {isBootstrap
              ? t("enterprise.auth.bootstrapHint")
              : t("enterprise.auth.loginHint")}
          </Typography.Text>
        </div>

        <Form
          layout="vertical"
          onFinish={onFinish}
          autoComplete="off"
          size="large"
        >
          {isBootstrap && (
            <Form.Item name="displayName">
              <Input placeholder={t("enterprise.auth.displayName")} />
            </Form.Item>
          )}
          {isBootstrap && bootstrapTokenRequired && (
            <Form.Item
              name="bootstrapToken"
              rules={[{ required: true, message: t("enterprise.auth.bootstrapTokenRequired") }]}
            >
              <Input.Password
                prefix={<KeyOutlined />}
                placeholder={t("enterprise.auth.bootstrapToken")}
              />
            </Form.Item>
          )}
          <Form.Item
            name="username"
            rules={[{ required: true, message: t("login.usernameRequired") }]}
          >
            <Input
              prefix={
                <UserOutlined
                  style={{
                    color: isDark ? "rgba(255,255,255,0.45)" : undefined,
                  }}
                />
              }
              placeholder={t("login.usernamePlaceholder")}
              autoFocus
            />
          </Form.Item>

          <Form.Item
            name="password"
            rules={[{ required: true, message: t("login.passwordRequired") }]}
          >
            <Input.Password
              prefix={
                <LockOutlined
                  style={{
                    color: isDark ? "rgba(255,255,255,0.45)" : undefined,
                  }}
                />
              }
              placeholder={t("login.passwordPlaceholder")}
            />
          </Form.Item>

          <Form.Item style={{ marginBottom: 0, marginTop: 8 }}>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              block
              className={styles.submit}
            >
              {isBootstrap
                ? t("enterprise.auth.bootstrapAction")
                : t("enterprise.auth.loginAction")}
            </Button>
          </Form.Item>
        </Form>
      </section>
    </div>
  );
}
