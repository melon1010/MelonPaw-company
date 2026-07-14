import { useTranslation } from "react-i18next";
import { Button, Empty, Spin, Table } from "antd";
import { Package, Plus } from "lucide-react";
import { PageHeader } from "@/components/PageHeader";
import { usePluginManager } from "./hooks/usePluginManager";
import { usePluginColumns } from "./hooks/usePluginColumns";
import { useInstallModal } from "./hooks/useInstallModal";
import { InstallPluginModal } from "./components/InstallPluginModal";
import styles from "./index.module.less";

export default function PluginManagerPage() {
  const { t } = useTranslation();

  const { plugins, loading, refresh, uninstallingId, handleUninstall } =
    usePluginManager();

  const installModal = useInstallModal(refresh);

  const columns = usePluginColumns({
    uninstallingId,
    onUninstall: handleUninstall,
  });

  return (
    <div className={styles.page}>
      <PageHeader
        parent={t("nav.settings")}
        current={t("nav.pluginManager")}
        extra={
          <Button
            type="primary"
            icon={<Plus size={16} />}
            onClick={installModal.openModal}
          >
            {t("pluginManager.installBtn")}
          </Button>
        }
      />

      <div className={styles.content}>
        <Spin spinning={loading}>
          {!loading && (!plugins || plugins.length === 0) ? (
            <Empty
              image={<Package size={48} strokeWidth={1} />}
              description={t("pluginManager.noPlugins")}
              style={{ marginTop: 24 }}
            />
          ) : (
            <Table
              dataSource={plugins}
              columns={columns}
              rowKey="id"
              pagination={false}
              className={styles.table}
            />
          )}
        </Spin>
      </div>

      <InstallPluginModal {...installModal} />
    </div>
  );
}
