import type { WelcomeRenderProps } from "../../../plugins/registry/types";
import styles from "../index.module.less";

export default function ChatHomeWelcome(props: Partial<WelcomeRenderProps>) {
  const greeting = props.greeting || "有什么可以帮忙的？";

  return (
    <div
      className={styles.homeWelcome}
      aria-label={typeof greeting === "string" ? greeting : undefined}
    >
      <div className={styles.homeWelcomePrompt}>{greeting}</div>
    </div>
  );
}
