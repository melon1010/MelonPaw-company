import { describe, expect, it } from "vitest";
import { groupSkillsByEnabled } from "./skillGroups";

describe("groupSkillsByEnabled", () => {
  it("keeps disabled skills outside the enabled progressive-render window", () => {
    const skills = [
      ...Array.from({ length: 25 }, (_, index) => ({
        name: `enabled-${String(index).padStart(2, "0")}`,
        enabled: true,
      })),
      { name: "AIS智能排程", enabled: false },
      { name: "not-explicitly-enabled" },
    ];

    const { enabledSkills, disabledSkills } = groupSkillsByEnabled(skills);

    expect(enabledSkills.slice(0, 20)).toHaveLength(20);
    expect(disabledSkills.map((skill) => skill.name)).toEqual([
      "AIS智能排程",
      "not-explicitly-enabled",
    ]);
  });

  it("sorts each group without changing the source order", () => {
    const skills = [
      { name: "zeta", enabled: false },
      { name: "beta", enabled: true },
      { name: "alpha", enabled: true },
    ];

    const { enabledSkills, disabledSkills } = groupSkillsByEnabled(skills);

    expect(enabledSkills.map((skill) => skill.name)).toEqual(["alpha", "beta"]);
    expect(disabledSkills.map((skill) => skill.name)).toEqual(["zeta"]);
    expect(skills.map((skill) => skill.name)).toEqual([
      "zeta",
      "beta",
      "alpha",
    ]);
  });
});
