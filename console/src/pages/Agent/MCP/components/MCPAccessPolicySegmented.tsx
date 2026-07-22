import React from "react";
import { Segmented } from "antd";
import type { MCPAccessEffect } from "../../../../api/types";
import styles from "../index.module.less";

interface MCPAccessPolicySegmentedProps {
  value: MCPAccessEffect;
  onChange: (effect: MCPAccessEffect) => void;
  effectLabel: (effect: MCPAccessEffect) => string;
}

export const MCPAccessPolicySegmented: React.FC<
  MCPAccessPolicySegmentedProps
> = ({ value, onChange, effectLabel }) => (
  <Segmented
    className={styles.accessPolicySegmented}
    data-effect={value}
    value={value}
    onChange={(nextValue) => onChange(nextValue as MCPAccessEffect)}
    options={[
      { label: effectLabel("ask"), value: "ask" },
      { label: effectLabel("allow"), value: "allow" },
      { label: effectLabel("deny"), value: "deny" },
    ]}
  />
);
