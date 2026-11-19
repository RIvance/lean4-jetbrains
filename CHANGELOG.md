# Changelog

## [0.1.1]

### Fixed

- The new project wizard, Lean settings, and Toolchains tab now list installed elan toolchains on opening.
  Automatic selection shows the resolved toolchain and continues to follow the project's `lean-toolchain` file.
- Inspect Toolchains and Template Help now show their results while the new project dialog is open.

## [0.1.0]

### Added

- Lean 4 editor support with code completion, diagnostics, semantic highlighting, quick documentation,
  code actions, import suggestions, rename, references, and navigation.
- Automatic richer semantic highlighting on supported Lean releases, with fallback to standard highlighting.
- Interactive InfoView with goals, expected types, messages, traces, pinning, and project-defined widgets.
- Unicode abbreviation input, custom translations, and a searchable symbol picker.
- Proof-status and processing markers, interactive inlay hints, and proof-folding commands.
- Elan discovery, toolchain inspection, installation, and project-specific selection in Lean settings.
- Lean project creation through Lake templates.
- Lake run configurations and project actions for builds, tests, dependency updates, and Mathlib cache downloads.
- Dependency source browsing, module import hierarchies, and module-name copying.
- Loogle search for Lean and Mathlib definitions and theorems.
- Configurable indentation, syntax colors, server arguments, environment variables, and keyboard shortcuts.
