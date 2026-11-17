# Lean 4 for JetBrains

Lean 4 support for JetBrains IDEs, with interactive proof goals, native code completion and navigation,
Unicode input, and tools for managing Lean projects.

## Features

- **Interactive InfoView:** inspect goals and expected types, explore messages and traces, pin proof states,
  and use project-defined widgets.
- **Editor integration:** completion, diagnostics, semantic highlighting, quick documentation, code actions,
  rename, references, call hierarchy, and navigation to definitions and types.
- **Proof editing:** processing and proof status markers, interactive inlay hints, and proof-folding commands.
- **Unicode input:** Lean abbreviations, custom translations, and a searchable symbol picker.
- **Project tools:** elan toolchain selection, a project wizard, Lake run configurations, and Mathlib cache commands.
- **Library discovery:** dependency sources, module import hierarchies, and Loogle theorem search.

Available language features depend on the selected Lean toolchain. In particular, proof folding, module hierarchy,
and import optimization require support from its language server.

## Getting started

### Requirements

- IntelliJ IDEA, PyCharm, WebStorm, PhpStorm, RubyMine, GoLand, CLion, Rider, RustRover, DataGrip, or DataSpell.
  Supported platform builds are `261.26222` through `263.*`: generally **2026.1.4 through 2026.3**;
  DataSpell **2026.1.3.1** is also supported. Use the bundled JetBrains Runtime for InfoView.
- [Elan](https://github.com/leanprover/elan) with `dump-state` support and an installed Lean 4 toolchain.
  For remote development, install these on the project host.
- Git for Git-based Lake dependencies and project templates. Fetching dependencies also requires internet access.

### Installation

1. [Build the plugin](#building-the-plugin) to produce its ZIP archive.
2. In the IDE, open **Settings → Plugins**, choose **Install Plugin from Disk** from the gear menu, and select the ZIP.
3. Open your project directory. For Lake projects, choose the folder containing `lakefile.lean` or `lakefile.toml`.
4. Open **Settings → Languages & Frameworks → Lean** and use **Inspect** to check the active toolchain.
5. Open a `.lean` file to start language services. InfoView opens automatically for the first selected Lean file.

To create a project, choose **New Project → Lean 4**, select an installed toolchain, and choose a Lake template.
**Template Help** lists the options provided by that toolchain, including the supported configuration formats.

## Working with Lean

### InfoView

The **Lean → InfoView** tab follows your cursor and displays the current proof goals, expected type, and messages.
Pin a position to keep its goals visible while editing elsewhere, or pause updates while reading a proof state.
Interactive messages and traces let you inspect terms, search trace output, and navigate to source definitions.
Project widgets can offer visualizations and edits directly in the view.

Use **Tools → Lean InfoView** to show the view, toggle pins or expected types, pause updates, and copy a goal into
a Lean comment. InfoView follows your editor font and theme. Its automatic opening can be disabled in Lean settings.

### Editing and navigation

Use the IDE's completion, Quick Documentation, Rename, Find Usages, and navigation actions in Lean files.
The structure view and symbol search help you move through declarations; call hierarchy shows callers and callees.
Lean and dependency sources are available under **External Libraries**.

Semantic highlighting uses the token classifications supplied by Lean's language server. Colors for types,
parameters, constructors, functions, fields, documentation markup, and other semantic tokens can be customized
under **Settings → Editor → Color Scheme → Lean4**.

**Richer semantic highlighting** is enabled automatically for Lean **4.24.0, 4.25.0, 4.25.1, 4.25.2,
4.26.0, 4.28.0, 4.34.1, and 4.35.0**. It distinguishes type families, structures, typeclasses, constructors,
fields, local binders, and proof hypotheses using Lean's elaboration information. Other releases and release
candidates use their standard server highlighting.

The first launch builds a small bundled library with the selected toolchain's Lean and Lake, then caches it
on the project host. It does not change your Lean sources, Lake configuration, or toolchain selection.
If building or loading fails, Lean starts with standard highlighting. If the augmented server fails later,
the IDE restarts Lean without the library and disables that build for the rest of the project session.
Reopen the project to retry after fixing the problem; details are available in the IDE log.
You can turn this feature off in **Settings → Languages & Frameworks → Lean**.

**Code → Optimize Imports** adds imports for unambiguous unknown identifiers using Lean's suggestions.
Inlay hints can show implicit parameters and offer source navigation or insertion actions. Ctrl-click a hint
(Cmd-click on macOS) to activate its action, or use **Insert Lean Hint** from its context menu when available.
Source edits support the IDE's Undo command.

**Code → Folding → Fold All Lean Proofs** and **Unfold All Lean Proofs** control proof folds.
Enable **Fold proofs when opening a Lean file** in Lean settings to collapse proofs automatically.

### Unicode input

Type an abbreviation such as `\alpha` to enter `α`. Unique complete abbreviations convert while typing;
Tab or Enter can also finish an abbreviation. Paired delimiters place the caret inside the pair.

Use **Tools → Lean Unicode Symbols…** to search for a symbol and insert or copy it. Quick Documentation on a symbol
shows its abbreviations. **Settings → Editor → Lean Unicode Input** lets you change the leader character,
adjust automatic conversion, and add custom translations, including multiline text and `$CURSOR` templates.

### Toolchains and server settings

Manage toolchains in **Lean → Toolchains** or **Settings → Languages & Frameworks → Lean**.
**Inspect** shows installed toolchains and the active selection; **Install Toolchain** downloads a requested toolchain.
Select a toolchain and apply the settings to use it in the IDE project.

Toolchain selections apply to this IDE project only. Leave the selection empty to follow elan's normal rules,
including the project's `lean-toolchain` file. Elan is discovered through `ELAN_HOME`, `PATH`, or its default home;
you can also select its executable explicitly in settings. Linked toolchains are supported.

Configure additional Lean server arguments and project environment variables under **Server and environment**.
Lake run configurations can override environment variables and toolchain selection for individual runs.

### Building and running projects

**Tools → Lean Project** offers Build, Clean, Test, and Update Dependencies. These commands also appear in the project
view's context menu and use the nearest Lake project for the selected file or folder, including nested projects.

Create a **Lake** run configuration for executables or custom Lake commands. Configure its arguments, working directory,
environment, and toolchain, then run it in the IDE's Run console. The console supports input, cancellation, rerun,
and navigation from Lean diagnostics to source locations.

Mathlib cache commands can fetch build artifacts for the whole project, selected files, or open Lean files and their
imports. They require the project's `cache` executable and its runtime tools, including `curl` and `leantar`.
See the [Mathlib cache documentation](https://github.com/leanprover-community/mathlib4/blob/master/Cache/README.md)
for cache setup and download behavior.

### Finding modules and theorems

With a Lean file open, use **Navigate → Lean Module Hierarchy** or **Lean → Modules** to browse its imports and the
modules that import it. Expand entries to explore dependencies, navigate to their sources, or copy module names.
**Copy Lean Module Name** is also available in the editor and project-view context menus for an open Lean file.

**Tools → Search Loogle** searches definitions and theorems by name or type. Select text in the editor to use it as
the query, or enter a query in the **Loogle** tab. Results include types, documentation, and suggested searches.
Queries are sent to [Loogle](https://loogle.lean-lang.org/), so internet access is required and its library index may
differ from your project's dependencies.

## Settings and shortcuts

The following locations are under **Settings**:

| Setting | Location |
| --- | --- |
| Toolchains, server options, automatic InfoView opening, and proof folding | Languages & Frameworks → Lean |
| Unicode input and custom translations | Editor → Lean Unicode Input |
| Lean indentation | Editor → Code Style → Lean4 |
| Syntax and semantic colors, proof markers | Editor → Color Scheme → Lean4 |
| Lean server hints | Editor → Inlay Hints |
| Keyboard shortcuts | Keymap |

Lean uses two-space indentation by default. Enter continues the current indentation and adds a level after a tactic
bullet. You can find Lean commands through **Find Action** and assign shortcuts by searching for them in **Keymap**.

## Troubleshooting

- **Lean does not start:** use **Inspect** in Lean settings to check elan and the selected toolchain. Set the elan
  executable explicitly if discovery fails. The IDE's **Language Services** status widget shows server state and
  error output.
- **Goals or imports are stale:** run **Restart Lean File** to recheck the file and rebuild changed dependencies.
  Use **Restart Lean Servers** to restart all open Lean workspaces. Both preserve unsaved editor text.
- **InfoView stops responding:** use **Reload Lean InfoView**. Reloading resets transient pins and paused views
  while preserving editor documents and saved display preferences.
- **InfoView's browser cannot start:** restart the IDE using its bundled runtime. On Linux, check for missing
  native libraries using the [JetBrains JCEF troubleshooting guide](https://support.jetbrains.com/hc/en-us/articles/40480679940882-JCEF-the-missing-libs-problem-on-Linux).
  The Toolchains, Modules, and Loogle tabs remain available when InfoView cannot start.
- **A file outside the project has no language services:** add its directory as a project content root.
- **Language services disconnect in remote development:** a known IntelliJ synchronization issue can affect an open
  remote file after a backend save. **Restart Lean Servers** restores InfoView and editor language services.

Browsing dependency sources under **External Libraries** may be unavailable with older Lean toolchains.

## Building the plugin

Use JDK 21 and the Gradle wrapper:

```sh
./gradlew buildPlugin
```

The installable ZIP is written to `build/distributions`. On Windows, use `gradlew.bat`.
GitHub Actions builds the plugin on pushes and pull requests. The ZIP is also available as the `plugin.lean`
artifact of a successful **Build** workflow run in the repository's **Actions** tab.
Gradle manages the IDE SDK and Node.js/npm used to generate the bundled InfoView assets.
The installed plugin runs InfoView in the IDE's browser runtime and does not need Node.js.
Building the plugin and running unit tests do not require an installed Lean toolchain or elan.

To run the tests and check the plugin package:

```sh
./gradlew test verifyPluginStructure
```

Run `./gradlew verifyPlugin` to check binary compatibility across the configured IDE versions.
This separate check downloads the IDEs it verifies. To check specific versions, pass a comma-separated list,
for example `./gradlew verifyPlugin -PverificationIdes=IU-2026.1.4,WS-2026.3`.

## Acknowledgments

The plugin uses Lean's upstream InfoView, Unicode abbreviation catalog, and identifier rules.
Their licenses and attributions are included with the plugin.
