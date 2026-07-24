interface GroupableSkill {
  name: string;
  enabled?: boolean;
}

export function groupSkillsByEnabled<T extends GroupableSkill>(skills: T[]) {
  const enabledSkills: T[] = [];
  const disabledSkills: T[] = [];

  for (const skill of skills) {
    (skill.enabled ? enabledSkills : disabledSkills).push(skill);
  }

  const byName = (a: T, b: T) => a.name.localeCompare(b.name);
  enabledSkills.sort(byName);
  disabledSkills.sort(byName);

  return { enabledSkills, disabledSkills };
}
