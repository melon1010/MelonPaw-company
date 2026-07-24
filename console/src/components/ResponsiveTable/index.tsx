import {
  isValidElement,
  useEffect,
  useMemo,
  useState,
  type Key,
  type ReactNode,
} from "react";
import { Empty, Pagination, Spin, Table } from "antd";
import type {
  ColumnType,
  ColumnsType,
  TablePaginationConfig,
  TableProps,
} from "antd/es/table";
import styles from "./index.module.less";

export interface ResponsiveTableProps<RecordType extends object>
  extends TableProps<RecordType> {
  mobileTitleColumnKey?: Key;
  mobileActionColumnKey?: Key;
  mobileHiddenColumnKeys?: Key[];
  mobileDetails?: (record: RecordType) => ReactNode;
  mobileDetailsLabel?: ReactNode;
}

function getColumnKey<RecordType extends object>(
  column: ColumnType<RecordType>,
  index: number,
): Key {
  if (column.key != null) return column.key;
  if (Array.isArray(column.dataIndex)) return column.dataIndex.join(".");
  if (column.dataIndex != null) return String(column.dataIndex);
  return index;
}

function readValue<RecordType extends object>(
  record: RecordType,
  dataIndex: ColumnType<RecordType>["dataIndex"],
): unknown {
  if (dataIndex == null) return undefined;
  const path = (Array.isArray(dataIndex) ? dataIndex : [dataIndex]) as Array<
    string | number
  >;
  return path.reduce<unknown>((value, segment) => {
    if (value == null || typeof value !== "object") return undefined;
    return (value as Record<string | number, unknown>)[segment];
  }, record);
}

function renderColumn<RecordType extends object>(
  column: ColumnType<RecordType>,
  record: RecordType,
  index: number,
): ReactNode {
  const value = readValue(record, column.dataIndex);
  const rendered = column.render
    ? column.render(value, record, index)
    : (value as ReactNode);

  if (
    rendered &&
    typeof rendered === "object" &&
    !isValidElement(rendered) &&
    "children" in rendered
  ) {
    return rendered.children;
  }
  if (rendered == null || rendered === "") return "-";
  if (typeof rendered === "boolean") return rendered ? "Yes" : "No";
  return rendered as ReactNode;
}

function getRowKey<RecordType extends object>(
  record: RecordType,
  index: number,
  rowKey: TableProps<RecordType>["rowKey"],
): Key {
  if (typeof rowKey === "function") return rowKey(record);
  if (typeof rowKey === "string") {
    const value = (record as Record<string, unknown>)[rowKey];
    if (typeof value === "string" || typeof value === "number") return value;
  }
  const fallback = (record as Record<string, unknown>).key;
  return typeof fallback === "string" || typeof fallback === "number"
    ? fallback
    : index;
}

function paginationPageSize(
  pagination: false | TablePaginationConfig | undefined,
): number | null {
  if (pagination === false) return null;
  return pagination?.pageSize || pagination?.defaultPageSize || 10;
}

export default function ResponsiveTable<RecordType extends object>({
  columns = [],
  dataSource = [],
  rowKey,
  loading,
  locale,
  pagination,
  mobileTitleColumnKey,
  mobileActionColumnKey = "actions",
  mobileHiddenColumnKeys = [],
  mobileDetails,
  mobileDetailsLabel = "Details",
  ...tableProps
}: ResponsiveTableProps<RecordType>) {
  const [mobilePage, setMobilePage] = useState(1);
  const flatColumns = useMemo(
    () =>
      (columns as ColumnsType<RecordType>).filter(
        (column): column is ColumnType<RecordType> => !("children" in column),
      ),
    [columns],
  );
  const keyedColumns = useMemo(
    () =>
      flatColumns.map((column, index) => ({
        column,
        key: getColumnKey(column, index),
      })),
    [flatColumns],
  );
  const hiddenKeys = useMemo(
    () => new Set(mobileHiddenColumnKeys.map(String)),
    [mobileHiddenColumnKeys],
  );
  const actionEntry = keyedColumns.find(
    (entry) => String(entry.key) === String(mobileActionColumnKey),
  );
  const titleEntry =
    keyedColumns.find(
      (entry) => String(entry.key) === String(mobileTitleColumnKey),
    ) ||
    keyedColumns.find(
      (entry) =>
        String(entry.key) !== String(mobileActionColumnKey) &&
        !hiddenKeys.has(String(entry.key)),
    );
  const detailEntries = keyedColumns.filter(
    (entry) =>
      entry !== titleEntry &&
      entry !== actionEntry &&
      !hiddenKeys.has(String(entry.key)),
  );
  const pageSize = paginationPageSize(pagination);
  const mobileData = pageSize
    ? dataSource.slice((mobilePage - 1) * pageSize, mobilePage * pageSize)
    : dataSource;

  useEffect(() => {
    const maxPage = pageSize
      ? Math.max(1, Math.ceil(dataSource.length / pageSize))
      : 1;
    if (mobilePage > maxPage) setMobilePage(maxPage);
  }, [dataSource.length, mobilePage, pageSize]);

  const isLoading =
    typeof loading === "boolean" ? loading : Boolean(loading?.spinning);
  const emptyText =
    typeof locale?.emptyText === "function"
      ? locale.emptyText()
      : locale?.emptyText;

  return (
    <>
      <div className={styles.desktopTable}>
        <Table<RecordType>
          {...tableProps}
          columns={columns}
          dataSource={dataSource}
          rowKey={rowKey}
          loading={loading}
          locale={locale}
          pagination={pagination}
        />
      </div>
      <div className={styles.mobileTable}>
        <Spin spinning={isLoading}>
          {mobileData.length > 0 ? (
            <div className={styles.cardList}>
              {mobileData.map((record, index) => {
                const absoluteIndex = pageSize
                  ? (mobilePage - 1) * pageSize + index
                  : index;
                return (
                  <article
                    key={getRowKey(record, absoluteIndex, rowKey)}
                    className={styles.dataCard}
                  >
                    {titleEntry ? (
                      <div className={styles.cardTitle}>
                        {renderColumn(titleEntry.column, record, absoluteIndex)}
                      </div>
                    ) : null}
                    {detailEntries.length > 0 ? (
                      <dl className={styles.cardFields}>
                        {detailEntries.map((entry) => (
                          <div key={entry.key} className={styles.cardField}>
                            <dt>
                              {typeof entry.column.title === "function"
                                ? String(entry.key)
                                : entry.column.title || String(entry.key)}
                            </dt>
                            <dd>
                              {renderColumn(
                                entry.column,
                                record,
                                absoluteIndex,
                              )}
                            </dd>
                          </div>
                        ))}
                      </dl>
                    ) : null}
                    {mobileDetails ? (
                      <details className={styles.cardDetails}>
                        <summary>{mobileDetailsLabel}</summary>
                        <div>{mobileDetails(record)}</div>
                      </details>
                    ) : null}
                    {actionEntry ? (
                      <div className={styles.cardActions}>
                        {renderColumn(
                          actionEntry.column,
                          record,
                          absoluteIndex,
                        )}
                      </div>
                    ) : null}
                  </article>
                );
              })}
            </div>
          ) : !isLoading ? (
            <div className={styles.emptyState}>
              {emptyText || <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />}
            </div>
          ) : (
            <div className={styles.loadingSpace} />
          )}
        </Spin>
        {pageSize && dataSource.length > pageSize ? (
          <div className={styles.mobilePagination}>
            <Pagination
              simple
              size="small"
              current={mobilePage}
              pageSize={pageSize}
              total={dataSource.length}
              onChange={setMobilePage}
            />
          </div>
        ) : null}
      </div>
    </>
  );
}
