# Changelog

## [0.1.1]

### Added

- A searchable toolchain installer in New Project, Lean settings, and the Toolchains tab. It marks installed
  releases and offers the project's missing version first. The wizard can install and select a toolchain in one step.
- Toolchain details show what selected the version, including the exact toolchain file when available.
  Changing the selection in settings previews the result before applying it.

### Fixed

- The new project wizard, Lean settings, and Toolchains tab now list installed elan toolchains on opening.
  Automatic selection shows the resolved toolchain and continues to follow the project's `lean-toolchain` file.
- Inspect Toolchains and Template Help now show their results while the new project dialog is open.
- Ctrl+click on a Lean reference now opens its declaration in Rider.
- The installer recognizes project-pinned channels during offline inspection. Toolchain discovery also recognizes
  local Lean builds selected by absolute path.

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
