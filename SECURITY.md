# Security

Testlab holds GitHub, Jira and model API tokens and drives a phone, so we take reports seriously.

Please **do not open a public issue** for a vulnerability. Report it privately through GitHub's
«Report a vulnerability» (Security tab of the repository). Include the version (`testlab --version`),
the OS and the steps; a diagnostics zip (`testlab diag`) has secrets removed and helps.

What is in scope, for example: a way to read vault values, the MCP server or control API
answering without the token or to another origin, a tool result or log that leaks a stored secret,
a Jira or uninstall write that skips the approval, path traversal in imports, attachments or reports.
