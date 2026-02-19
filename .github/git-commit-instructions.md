# Git Commit Instructions (AI)

These instructions are intended for AI-assisted tools (for example GitHub Copilot or other AI commit helpers).

Follow these rules strictly when generating commit messages.

---

## Commit Style

- Use **Conventional Commits** format
- Use lowercase for the commit type
- Keep the subject line **concise and descriptive**
- Do not exceed **72 characters** in the subject line
- Do not end the subject line with a period

Format:

```
<type>(<optional scope>): <short summary>
```

Examples:

- `feat: add server scaling logic`
- `fix(github): correct issue template validation`
- `docs: add contributing guidelines`

---

## Allowed Commit Types

- `feat` - new functionality
- `fix` - bug fixes
- `docs` - documentation only changes
- `chore` - maintenance, tooling, config
- `refactor` - code changes without behaviour change
- `test` - tests only
- `security` - security-related changes

Do not invent new commit types.

---

## Scopes

Use a scope **only when it adds clarity**.

Common scopes:

- `github`
- `infra`
- `docs`
- `security`
- `ci`

Avoid overly broad or meaningless scopes.

---

## What to Avoid

- Do not use vague messages ("update stuff", "fix things")
- Do not include ticket numbers unless explicitly requested
- Do not include emojis
- Do not mention AI, Copilot, or automation
- Do not add unnecessary body text unless requested
- Do not use em dashes

---

## Default Behaviour

If unsure:

- Omit the scope
- Keep the message minimal
