import { useState } from "react";
import {
  Alert,
  Card,
  Form,
  InputNumber,
  Select,
  Switch,
  Input,
  Button,
  Tabs,
  Tag,
} from "@agentscope-ai/design";
import {
  Plus,
  Trash2,
  ChevronDown,
  ChevronRight,
  Repeat,
  Shield,
  CheckCircle,
  Info,
  Target,
  Rocket,
  Gauge,
  Wallet,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import styles from "../index.module.less";

const ACTION_OPTIONS = [
  { value: "modify_prompt", label: "Send Reminder" },
  { value: "stop", label: "Pause & Ask for Help" },
];

function SectionHeader({
  icon,
  title,
}: {
  icon: React.ReactNode;
  title: string;
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 8,
        marginBottom: 16,
        paddingBottom: 8,
        borderBottom: "1px solid var(--border-color, #f0f0f0)",
      }}
    >
      {icon}
      <span style={{ fontWeight: 600, fontSize: 14 }}>{title}</span>
    </div>
  );
}

function SectionDivider() {
  return (
    <hr
      style={{
        border: "none",
        borderTop: "1px solid var(--border-color, #f0f0f0)",
        margin: "24px 0",
      }}
    />
  );
}

function RuntimeGateCard({
  icon,
  title,
  description,
}: {
  icon: React.ReactNode;
  title: string;
  description: string;
}) {
  const { t } = useTranslation();
  return (
    <div
      style={{
        border: "1px solid var(--border-color, #f0f0f0)",
        borderRadius: 8,
        padding: "16px 20px",
        marginBottom: 12,
        position: "relative",
      }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
          }}
        >
          {icon}
          <span style={{ fontWeight: 500, fontSize: 13 }}>{title}</span>
        </div>
        <Tag
          style={{
            fontSize: 11,
            borderRadius: 4,
            display: "flex",
            alignItems: "center",
            gap: 4,
          }}
        >
          <CheckCircle size={10} />
          {t("agentConfig.active", "Active")}
        </Tag>
      </div>
      <p
        style={{
          margin: "8px 0 0",
          fontSize: 12,
          color: "var(--text-secondary, rgba(0,0,0,0.45))",
        }}
      >
        {description}
      </p>
    </div>
  );
}

function IterationSection() {
  const { t } = useTranslation();
  const form = Form.useFormInstance();
  const enabled = Form.useWatch(["loop", "iteration", "enabled"], form);

  return (
    <div>
      <SectionHeader
        icon={<Repeat size={16} style={{ opacity: 0.7 }} />}
        title={t("agentConfig.iterationTitle", "Iteration Limit")}
      />
      <Form.Item
        name={["loop", "iteration", "enabled"]}
        label={t("agentConfig.iterationEnabled", "Enable Iteration Limit")}
        valuePropName="checked"
        tooltip={t(
          "agentConfig.iterationEnabledTooltip",
          "Stop the agent after a fixed number of loop turns",
        )}
      >
        <Switch />
      </Form.Item>
      {enabled && (
        <Form.Item
          name={["loop", "iteration", "max_iterations"]}
          label={t("agentConfig.iterationMaxIterations", "Maximum Iterations")}
          tooltip={t(
            "agentConfig.iterationMaxIterationsTooltip",
            "Maximum number of loop turns before stopping",
          )}
        >
          <InputNumber min={1} max={500} style={{ width: 200 }} />
        </Form.Item>
      )}
    </div>
  );
}

function DoomLoopSection() {
  const { t } = useTranslation();
  const form = Form.useFormInstance();
  const [advanced, setAdvanced] = useState(false);
  const enabled = Form.useWatch(["loop", "doom_loop", "enabled"], form);
  const stages = Form.useWatch(["loop", "doom_loop", "stages"], form) || [];

  return (
    <div>
      <SectionHeader
        icon={<Shield size={16} style={{ opacity: 0.7 }} />}
        title={t("agentConfig.doomLoopEnabled", "Repetition Protection")}
      />
      <Form.Item
        name={["loop", "doom_loop", "enabled"]}
        label={t("agentConfig.doomLoopEnabled", "Repetition Protection")}
        valuePropName="checked"
        tooltip={t(
          "agentConfig.doomLoopEnabledTooltip",
          "Automatically intervene when the agent gets stuck repeating similar outputs",
        )}
      >
        <Switch />
      </Form.Item>

      {enabled && (
        <>
          {!advanced && (
            <div style={{ marginBottom: 16 }}>
              {stages.map(
                (
                  stage: {
                    after: number;
                    action: string;
                    prompt: string;
                  },
                  idx: number,
                ) => (
                  <div
                    key={idx}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 8,
                      marginBottom: 8,
                    }}
                  >
                    <span
                      style={{
                        color: "var(--text-secondary)",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {t("agentConfig.doomLoopAfter", "After")}{" "}
                      <strong>{stage.after}</strong>{" "}
                      {t(
                        "agentConfig.doomLoopRepetitions",
                        "identical actions",
                      )}{" "}
                      →
                    </span>
                    <span>
                      {stage.action === "stop"
                        ? t(
                            "agentConfig.doomLoopStopAction",
                            "Pause & Ask for Help",
                          )
                        : t("agentConfig.doomLoopWarnAction", "Send Reminder")}
                    </span>
                  </div>
                ),
              )}
            </div>
          )}

          <Button
            type="link"
            size="small"
            onClick={() => setAdvanced(!advanced)}
            style={{ padding: 0, marginBottom: 16 }}
          >
            <span
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 4,
              }}
            >
              {advanced ? (
                <ChevronDown size={14} />
              ) : (
                <ChevronRight size={14} />
              )}
              {advanced
                ? t("agentConfig.simpleMode", "Simple")
                : t("agentConfig.advancedMode", "Advanced")}
            </span>
          </Button>

          {advanced && (
            <>
              <div className={styles.reactAgentRow}>
                <Form.Item
                  name={["loop", "doom_loop", "window_size"]}
                  label={t("agentConfig.doomLoopWindowSize", "Detection Range")}
                  tooltip={t(
                    "agentConfig.doomLoopWindowSizeTooltip",
                    "How many recent outputs to check for repetition",
                  )}
                  className={styles.reactAgentField}
                >
                  <InputNumber min={2} max={20} style={{ width: "100%" }} />
                </Form.Item>

                <Form.Item
                  name={["loop", "doom_loop", "similarity_threshold"]}
                  label={t(
                    "agentConfig.doomLoopSimilarity",
                    "Match Sensitivity",
                  )}
                  tooltip={t(
                    "agentConfig.doomLoopSimilarityTooltip",
                    "How similar outputs must be to count as repetition (lower = stricter)",
                  )}
                  className={styles.reactAgentField}
                >
                  <InputNumber
                    min={0}
                    max={1}
                    step={0.05}
                    style={{ width: "100%" }}
                  />
                </Form.Item>
              </div>

              <hr
                style={{
                  border: "none",
                  borderTop: "1px solid var(--border-color)",
                  margin: "12px 0",
                }}
              />
              <strong style={{ display: "block", marginBottom: 12 }}>
                {t("agentConfig.doomLoopStages", "Intervention Rules")}
              </strong>

              <Form.List name={["loop", "doom_loop", "stages"]}>
                {(fields, { add, remove }) => (
                  <>
                    {fields.map(({ key, name, ...rest }) => (
                      <div
                        key={key}
                        style={{
                          display: "flex",
                          gap: 8,
                          marginBottom: 12,
                          alignItems: "flex-start",
                        }}
                      >
                        <Form.Item
                          {...rest}
                          name={[name, "after"]}
                          label={
                            name === 0
                              ? t("agentConfig.doomLoopAfter", "After")
                              : undefined
                          }
                          rules={[{ required: true }]}
                          style={{ flex: 1 }}
                        >
                          <InputNumber
                            min={1}
                            placeholder="N"
                            style={{ width: "100%" }}
                          />
                        </Form.Item>

                        <Form.Item
                          {...rest}
                          name={[name, "action"]}
                          label={
                            name === 0
                              ? t("agentConfig.doomLoopAction", "Action")
                              : undefined
                          }
                          rules={[{ required: true }]}
                          style={{ flex: 1.5 }}
                        >
                          <Select options={ACTION_OPTIONS} />
                        </Form.Item>

                        <Form.Item
                          {...rest}
                          name={[name, "prompt"]}
                          label={
                            name === 0
                              ? t("agentConfig.doomLoopPrompt", "Message")
                              : undefined
                          }
                          style={{ flex: 3 }}
                        >
                          <Input.TextArea
                            rows={1}
                            autoSize={{ minRows: 1, maxRows: 3 }}
                            placeholder={t(
                              "agentConfig.doomLoopPromptPlaceholder",
                              "Reminder message or pause reason...",
                            )}
                          />
                        </Form.Item>

                        <Button
                          type="text"
                          danger
                          icon={<Trash2 size={14} />}
                          onClick={() => remove(name)}
                          style={{ marginTop: name === 0 ? 30 : 0 }}
                        />
                      </div>
                    ))}
                    <Button
                      type="dashed"
                      onClick={() =>
                        add({
                          after: (stages.length + 1) * 3,
                          action: "modify_prompt",
                          prompt: "",
                        })
                      }
                      icon={<Plus size={14} />}
                      style={{ width: "100%" }}
                    >
                      {t("agentConfig.doomLoopAddStage", "Add Rule")}
                    </Button>
                  </>
                )}
              </Form.List>
            </>
          )}
        </>
      )}
    </div>
  );
}

function RubricSection() {
  const { t } = useTranslation();
  const form = Form.useFormInstance();
  const [advanced, setAdvanced] = useState(false);
  const enabled = Form.useWatch(["loop", "rubric", "enabled"], form);

  return (
    <div>
      <SectionHeader
        icon={<CheckCircle size={16} style={{ opacity: 0.7 }} />}
        title={t("agentConfig.rubricTitle", "Completion Check")}
      />
      <p
        style={{
          fontSize: 12,
          color: "var(--text-secondary, rgba(0,0,0,0.45))",
          marginBottom: 12,
          lineHeight: 1.6,
        }}
      >
        {t(
          "agentConfig.rubricDesc",
          "Some LLMs may stop with a text-only response without calling any tool, causing the agent to end prematurely. Enable this to re-prompt the agent and improve task completion.",
        )}
      </p>
      <Form.Item
        name={["loop", "rubric", "enabled"]}
        label={t("agentConfig.rubricEnabled", "Enable Completion Check")}
        valuePropName="checked"
        tooltip={t(
          "agentConfig.rubricEnabledTooltip",
          "Re-prompt the agent when it produces a text-only response without tool calls",
        )}
      >
        <Switch />
      </Form.Item>
      {enabled && (
        <>
          <Form.Item
            name={["loop", "rubric", "prompt"]}
            label={t("agentConfig.rubricPrompt", "Re-prompt Message")}
            tooltip={t(
              "agentConfig.rubricPromptTooltip",
              "The prompt injected when the agent outputs text without tool calls",
            )}
          >
            <Input.TextArea
              autoSize={{ minRows: 2, maxRows: 5 }}
              placeholder={t(
                "agentConfig.rubricPromptPlaceholder",
                "You did not call any tool. If the task is complete, confirm. Otherwise, continue with tool calls.",
              )}
            />
          </Form.Item>

          <Button
            type="link"
            size="small"
            onClick={() => setAdvanced(!advanced)}
            style={{ padding: 0, marginBottom: 12 }}
          >
            <span
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 4,
              }}
            >
              {advanced ? (
                <ChevronDown size={14} />
              ) : (
                <ChevronRight size={14} />
              )}
              {advanced
                ? t("agentConfig.simpleMode", "Simple")
                : t("agentConfig.advancedMode", "Advanced")}
            </span>
          </Button>

          {advanced && (
            <Form.Item
              name={["loop", "rubric", "max_interventions"]}
              label={t(
                "agentConfig.rubricMaxInterventions",
                "Max Continuation Rounds",
              )}
              tooltip={t(
                "agentConfig.rubricMaxInterventionsTooltip",
                "Maximum number of non-complete rubric continuations before the loop stops.",
              )}
            >
              <InputNumber min={1} max={10} style={{ width: 200 }} />
            </Form.Item>
          )}
        </>
      )}
    </div>
  );
}

function ReactTab() {
  return (
    <>
      <IterationSection />
      <SectionDivider />
      <DoomLoopSection />
      <SectionDivider />
      <RubricSection />
    </>
  );
}

function GoalModeTab() {
  const { t } = useTranslation();
  return (
    <div>
      <Alert
        type="info"
        showIcon
        icon={<Info size={14} />}
        message={t("agentConfig.goalModeInfoTitle", "Goal Mode vs Default")}
        description={t(
          "agentConfig.goalModeInfo",
          "Default mode stops after a single reply. Goal mode keeps the agent looping toward a goal, using Rubric evaluation to determine completion. All operations run within the current agent context.",
        )}
        style={{ marginBottom: 16 }}
      />
      <RuntimeGateCard
        icon={<Repeat size={14} style={{ opacity: 0.5 }} />}
        title={t("agentConfig.goalIterationGate", "Goal Iteration Gate")}
        description={t(
          "agentConfig.goalIterationGateDesc",
          "Limits the number of agent turns within a goal session. Tracks iteration count and token usage.",
        )}
      />
      <RuntimeGateCard
        icon={<Wallet size={14} style={{ opacity: 0.5 }} />}
        title={t("agentConfig.goalBoundedExecution", "Bounded Execution")}
        description={t(
          "agentConfig.goalBoundedExecutionDesc",
          "Caps outer goal rounds using the configured iteration limit, with a hard runtime ceiling.",
        )}
      />
      <RuntimeGateCard
        icon={<CheckCircle size={14} style={{ opacity: 0.5 }} />}
        title={t("agentConfig.goalRubricGate", "Goal Completion Rubric")}
        description={t(
          "agentConfig.goalRubricGateDesc",
          "Evaluates whether the goal has been achieved by checking the session status.",
        )}
      />
      <RuntimeGateCard
        icon={<Shield size={14} style={{ opacity: 0.5 }} />}
        title={t("agentConfig.goalTerminationGuard", "Termination Guard")}
        description={t(
          "agentConfig.goalTerminationGuardDesc",
          "Stops when the completion rubric passes, output is empty, evaluation is unavailable, or the round limit is reached.",
        )}
      />
    </div>
  );
}

function MissionModeTab() {
  const { t } = useTranslation();
  return (
    <div>
      <Alert
        type="info"
        showIcon
        icon={<Info size={14} />}
        message={t(
          "agentConfig.missionModeInfoTitle",
          "Mission Mode vs Goal Mode",
        )}
        description={t(
          "agentConfig.missionModeInfo",
          "Mission mode decomposes complex tasks and delegates sub-tasks to independent sub-agents. Each sub-task runs in its own context, preventing context pollution of the main session. Best for complex engineering tasks.",
        )}
        style={{ marginBottom: 16 }}
      />
      <RuntimeGateCard
        icon={<Gauge size={14} style={{ opacity: 0.5 }} />}
        title={t("agentConfig.missionOrchestration", "Mission Orchestration")}
        description={t(
          "agentConfig.missionOrchestrationDesc",
          "Uses native isolated subagents for delegated work, then requires the coordinator to reconcile their results.",
        )}
      />
      <RuntimeGateCard
        icon={<Repeat size={14} style={{ opacity: 0.5 }} />}
        title={t("agentConfig.missionCompletionLoop", "Completion Loop")}
        description={t(
          "agentConfig.missionCompletionLoopDesc",
          "Runs coordinator rounds until the mission rubric confirms a reconciled result or the configured limit is reached.",
        )}
      />
    </div>
  );
}

export function AgentLoopCard() {
  const { t } = useTranslation();

  const tabItems = [
    {
      key: "react",
      label: (
        <span
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 6,
          }}
        >
          <Repeat size={13} />
          {t("agentConfig.reactModeTab", "Loop Template - Default")}
        </span>
      ),
      children: <ReactTab />,
    },
    {
      key: "goal",
      label: (
        <span
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 6,
          }}
        >
          <Target size={13} />
          {t("agentConfig.goalModeTab", "Loop Template - Goal")}
        </span>
      ),
      children: <GoalModeTab />,
    },
    {
      key: "mission",
      label: (
        <span
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 6,
          }}
        >
          <Rocket size={13} />
          {t("agentConfig.missionModeTab", "Loop Template - Mission")}
        </span>
      ),
      children: <MissionModeTab />,
    },
  ];

  return (
    <Card
      className={styles.formCard}
      title={t("agentConfig.agentLoopTitle", "Agent Loop Settings")}
    >
      <Tabs defaultActiveKey="react" items={tabItems} size="small" />
    </Card>
  );
}
