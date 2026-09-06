# Khabeer — copyright, licenses and acknowledgements

Khabeer is a modified Termux application mapping Hermes Agent workflows into a native Android runtime. Modification notice date: 2026-09-06. Contributors, original authors and individual modification dates are recorded in source headers and retained Git history.

The combined application is licensed under GNU GPL version 3 only. You may modify and redistribute it under that license. It is provided WITHOUT ANY WARRANTY, including implied warranties of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, to the extent permitted by law.

Source and change history: https://github.com/bakka22/khabeer

Huge thanks to Termux and Hermes for making this app possible.

## Termux

Original project: https://github.com/termux/termux-app

Original Termux copyright notices remain in source and history; Khabeer does not claim ownership of those contributions. The Termux application is GPL-3.0-only. GPL-3.0-only.txt contains the full license.

termux-shared is MIT by default with exceptions in termux-shared/LICENSE.md: com.termux.shared.termux code is GPL-3.0-only unless explicitly excepted; TermuxConstants.java and TermuxPropertyConstants.java are MIT. OpenJDK-derived filesystem files are GPL-2.0-only with Classpath exception. StreamGobbler.java includes Apache-2.0 libsuperuser code.

Android Terminal Emulator code is included in terminal-view and terminal-emulator under Apache-2.0 as stated in Termux's original license. This does not broaden that exception to unverified additions. Source: https://github.com/jackpal/Android-Terminal-Emulator

OpenJDK-derived files retain their Oracle copyright headers. GPL-2.0-only.txt and Classpath-exception-2.0.txt contain the terms. Apache-2.0.txt contains the Apache license.

MIT.txt provides the general MIT license text; the applicable source headers and original notices identify each component's copyright holders. The Hermes-specific notice below is preserved separately and does not replace other authors' copyrights.

## Hermes Agent

Original project: https://github.com/NousResearch/hermes-agent

Copyright (c) 2025 Nous Research.

Khabeer adapts/maps Hermes's agent workflows, provider patterns, memory, skills, delegation and extension concepts to Android. Hermes-Agent-MIT.txt preserves the complete copyright, permission and warranty notice. Reference checkout reviewed: 52e5e7c034edfbd0105d0b9c315c4eecb6be7f5e; see CHANGES.md for scope.

Bundled skill author and license metadata is listed in Hermes-Skills-Attribution.md. Original supporting files, port notes and per-skill LICENSE files are preserved. The docx, pdf, powerpoint and xlsx skills carry Copyright (c) 2026 Nous Research and MIT notices; humanizer carries Copyright (c) 2025 Siqi Chen and an MIT notice. Other authors remain identified in their source metadata. The project-wide Nous Research notice does not replace those individual credits.

The MCP catalog maps Hermes optional-mcps entries to Android configuration. Listing a separately hosted service or external program does not relicense that service or program.

## Dependencies and bootstrap packages

AndroidX, Material Components, Markwon, Guava, Commons IO, HiddenApiBypass and termux-am-library are used by the Java modules. Original license/copyright notices remain applicable. Module Gradle files identify versions. This summary is not an exhaustive transitive-dependency notice inventory.

The bootstrap contains separate programs/libraries under GPL, LGPL and permissive licenses. Installed license texts are under $PREFIX/share/LICENSES, with package-specific notices under $PREFIX/share/doc. docs/TERMUX_BOOTSTRAP_PACKAGE_INVENTORY.csv inventories the build archives. See RELEASING.md before distributing binaries.

These acknowledgements do not imply endorsement by Termux, Nous Research or other authors. Retain original notices in redistributed source and applicable binaries.
