---
name: skill-creator
description: Use this skill when the user wants to create, revise, audit, or package a RikkaHub skill with a SKILL.md file and optional bundled resources.
---

# Skill Creator

Help the user create practical RikkaHub skills: compact instruction packages that teach an assistant a specialized workflow, domain, or tool habit.

## Skill Shape

A skill is a directory with a required `SKILL.md`:

```text
skill-name/
  SKILL.md
  scripts/
  references/
  assets/
```

`SKILL.md` must start with YAML frontmatter:

```markdown
---
name: skill-name
description: Clear trigger condition for when the assistant should use this skill.
---
```

The body should contain only the instructions the assistant needs after the skill is selected.

## Writing Rules

- Make the `description` specific enough to trigger on the right user requests.
- Keep `SKILL.md` concise. Move long documentation into `references/`.
- Use `scripts/` for repeatable or fragile operations that should not be rewritten every time.
- Use `assets/` for templates, examples, images, or files that should be copied or transformed.
- Avoid extra files such as README, changelog, install notes, or broad background essays unless they are part of the actual workflow.
- Include validation steps when the workflow produces code, data, or files.

## Recommended Process

1. Clarify the task the skill should handle and the situations that should trigger it.
2. Draft the frontmatter `name` and `description`.
3. Write the shortest useful workflow in the body.
4. Move optional details into references and mention when to read them.
5. Add scripts or assets only when they reduce repeated work or prevent mistakes.
6. Review the skill against realistic user requests and remove instructions that are not needed.

## Quality Check

Before finishing a skill, verify:

- The name is stable, lowercase, and directory-safe.
- The description says when to use the skill, not just what it is.
- The body tells the assistant what to do, in order.
- Large context is progressively disclosed through files under `references/`.
- Any generated scripts are executable or clearly documented.
