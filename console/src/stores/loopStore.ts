import { create } from "zustand";
import { request } from "@/api/request";

export interface LoopSkillInfo {
  name: string;
  description: string;
  icon?: string;
}

export interface AvailableCommandInfo {
  name: string;
  description: string;
  category: "system" | "loop" | "plugin" | "skill" | string;
  syntax?: string;
}

interface LoopState {
  selectedSkill: LoopSkillInfo | null;
  chipHighlighted: boolean;

  availableSkills: LoopSkillInfo[];
  availableCommands: AvailableCommandInfo[];

  setSelectedSkill: (skill: LoopSkillInfo | null) => void;
  setChipHighlighted: (highlighted: boolean) => void;
  setAvailableSkills: (skills: LoopSkillInfo[]) => void;
  setAvailableCommands: (commands: AvailableCommandInfo[]) => void;
}

export const useLoopStore = create<LoopState>((set) => ({
  selectedSkill: null,
  chipHighlighted: false,
  availableSkills: [],
  availableCommands: [],

  setSelectedSkill: (skill) =>
    set({ selectedSkill: skill, chipHighlighted: false }),
  setChipHighlighted: (highlighted) => set({ chipHighlighted: highlighted }),
  setAvailableSkills: (skills) => set({ availableSkills: skills }),
  setAvailableCommands: (commands) => set({ availableCommands: commands }),
}));

interface CommandsResponse {
  commands: Array<{
    name: string;
    description: string;
    category: string;
  }>;
}

export async function fetchAvailableLoopSkills(): Promise<void> {
  try {
    const res = await request<CommandsResponse>(
      "/workspace/commands/available",
    );
    const commands = res?.commands ?? [];
    const availableCommands = commands.filter((c) => c?.name && c?.category);
    const loopSkills: LoopSkillInfo[] = availableCommands
      .filter((c) => c.category === "plugin" || c.category === "loop")
      .map((c) => ({
        name: c.name,
        description: c.description || c.name,
      }));
    useLoopStore.getState().setAvailableCommands(availableCommands);
    useLoopStore.getState().setAvailableSkills(loopSkills);
  } catch {
    useLoopStore.getState().setAvailableCommands([]);
    useLoopStore.getState().setAvailableSkills([]);
  }
}
