# Changelog

All notable changes to OctoLab are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [1.1.3] - 2026-07-20

### Fixed
- Multi-account switching: community (release) build was incorrectly logging out both accounts when switching between self-hosted and gitlab.com. Root cause: R8/ProGuard was optimising methods in Gl4Application that manage per-account instance URL state. Added explicit ProGuard keep rules.

