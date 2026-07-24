import { Fragment, type ReactNode } from "react";
import styles from "./index.module.less";

export type PageHeaderBreadcrumbItem = {
  title: ReactNode;
};

export interface PageHeaderProps {
  /**
   * Title convention:
   * - App top-level pages use `current` only.
   * - Settings top-level pages use Settings as `parent` and the menu label as `current`.
   * - Detail pages use `items` to preserve their business hierarchy.
   * When omitted, `parent` and `current` build the trail.
   */
  items?: PageHeaderBreadcrumbItem[];
  parent?: ReactNode;
  current?: ReactNode;
  center?: ReactNode;
  extra?: ReactNode;
  /** Same row as the breadcrumb (e.g. workspace path chip). */
  afterBreadcrumb?: ReactNode;
  subRow?: ReactNode;
  className?: string;
}

export interface SectionHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  icon?: ReactNode;
  extra?: ReactNode;
  className?: string;
}

function buildItemsFromParentCurrent(
  parent: ReactNode | undefined,
  current: ReactNode | undefined,
): PageHeaderBreadcrumbItem[] {
  const out: PageHeaderBreadcrumbItem[] = [];
  if (parent != null && parent !== "") out.push({ title: parent });
  if (current != null && current !== "") out.push({ title: current });
  return out;
}

export function PageHeader({
  items: itemsProp,
  parent,
  current,
  center,
  extra,
  afterBreadcrumb,
  subRow,
  className,
}: PageHeaderProps) {
  const items =
    itemsProp !== undefined
      ? itemsProp
      : buildItemsFromParentCurrent(parent, current);

  return (
    <div className={`${styles.pageHeader} ${className ?? ""}`.trim()}>
      <div className={styles.leading}>
        <div className={styles.leadingTop}>
          <div className={styles.breadcrumbHeader}>
            {items.map((item, index) => (
              <Fragment key={index}>
                {index > 0 ? (
                  <span className={styles.breadcrumbSeparator}>/</span>
                ) : null}
                <span
                  className={
                    index === items.length - 1
                      ? styles.breadcrumbCurrent
                      : styles.breadcrumbParent
                  }
                >
                  {item.title}
                </span>
              </Fragment>
            ))}
            {afterBreadcrumb}
          </div>
        </div>
        {subRow}
      </div>
      {center ? <div className={styles.center}>{center}</div> : null}
      {extra ? <div className={styles.extra}>{extra}</div> : null}
    </div>
  );
}

export function SectionHeader({
  title,
  description,
  icon,
  extra,
  className,
}: SectionHeaderProps) {
  return (
    <div className={`${styles.sectionHeader} ${className ?? ""}`.trim()}>
      <div className={styles.sectionHeading}>
        {icon ? <span className={styles.sectionIcon}>{icon}</span> : null}
        <div className={styles.sectionCopy}>
          <h2>{title}</h2>
          {description ? <p>{description}</p> : null}
        </div>
      </div>
      {extra ? <div className={styles.sectionExtra}>{extra}</div> : null}
    </div>
  );
}
