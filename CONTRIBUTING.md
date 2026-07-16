# Contributing to OctoLab

Thank you for your interest in contributing to OctoLab!

## Reporting Issues

- Use [GitHub Issues](https://github.com/secninjaz/octolab/issues) for bug reports and feature requests
- Search existing issues before opening a new one
- Include: Android version, OctoLab version, GitLab version (CE/EE), steps to reproduce, expected vs actual behaviour
- For self-hosted instances: indicate if the issue also occurs on gitlab.com

## Security Vulnerabilities

Please do **not** open a public issue for security vulnerabilities. See [SECURITY.md](SECURITY.md).

## Development Setup

1. Fork the repository on GitHub
2. Clone your fork:
   ```bash
   git clone https://github.com/YOUR_USERNAME/octolab.git
   ```
3. Follow the build instructions in [README.md](README.md)
4. Create a feature branch:
   ```bash
   git checkout -b fix/issue-description
   ```

## Pull Requests

- One PR per issue/feature
- Reference the issue: `Fixes #N` in the commit or PR description
- Follow [Conventional Commits](https://www.conventionalcommits.org/) format:
  - `fix(scope): description`
  - `feat(scope): description`
  - `chore(scope): description`
- Keep changes minimal and focused — avoid unrelated cleanup in the same PR
- Ensure the app builds: `./gradlew assembleDebug`
- Test on a real device or emulator with a GitLab account

## Code Style

- Java, Android conventions
- No hardcoded secrets, tokens, or instance URLs in source
- No `Log.d` / debug logging in production paths

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
