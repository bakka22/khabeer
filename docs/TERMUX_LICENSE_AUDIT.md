# Termux licensing assessment

Historical assessment: this records the earlier checkout named below. Publication later added license texts/notices and the Hermes skill/MCP bundle. Consult the current root LICENSE.md and LICENSES/NOTICE.md for the later licensing state; the earlier APK observations do not verify a later release.

Date: 2026-09-06. Scope: current working tree at commit `90a7bb6a2e57d6687b09c26c3fe5fe95d343c21e`, including the existing edits to AiProviderConfig.java and AiRuntimeService.java. No application code or license declarations were changed by this assessment.

**The current Khabeer app cannot be offered under MIT alone. It is a modified Termux application containing GPL-3.0-only code, with direct dependencies on additional GPL classes. Original, independently authored contributions can potentially be offered separately under MIT; that does not remove GPL obligations for the combined app.**

## Evidence and component boundaries

The checkout's origin is termux/termux-app. Its common ancestor with the locally recorded origin/master is `3df69d1da197dd9bd71a3bafd902dffd720576b4`. The comparison shows retained upstream code, modifications to TermuxService and SettingsActivity, new AI functionality, and a RawOutputListener addition to TerminalSession. This is a fork, not just an external client invoking an installed Termux app.

| Component used | License evidence | Consequence |
| --- | --- | --- |
| Retained application code: TermuxApplication, TermuxActivity, TermuxService, TermuxInstaller, RunCommandService, settings and file integration | Root LICENSE.md: GPL-3.0-only, except specifically identified code | Definite blocker to an MIT-only release of the combined application |
| termux-shared general utilities | termux-shared/LICENSE.md: MIT by default, with exceptions | Selected MIT files can be reused with their notices, after checking dependencies |
| termux-shared/src/main/java/com/termux/shared/termux/** | Same license file expressly assigns GPL-3.0-only unless a file/directory says otherwise | Definite blocker: several of these classes are directly used |
| TermuxConstants.java and TermuxPropertyConstants.java | Explicit MIT exceptions, confirmed by SPDX headers | These files are not themselves GPL blockers; do not infer the whole shared library is MIT |
| terminal-view and terminal-emulator | Root license identifies Apache-2.0 Android Terminal Emulator code in these modules | Apache-covered code can coexist with MIT code, retaining Apache terms. The wording is about inherited code, not an unambiguous blanket license for every later addition. Neither module has a separate license file in this checkout; obtain provenance/coverage confirmation before treating every file as permissively licensed |
| termux-shared/file/filesystem OpenJDK-derived files | GPL-2.0-only WITH Classpath-exception-2.0 in module license and source headers | The exception permits linking with independent modules under other terms; the covered files themselves retain their license and source obligations |
| termux-shared/shell/StreamGobbler.java | Apache-2.0 exception in module license | Retain applicable Apache notices |
| com.termux:termux-am-library:v2.0.0 | Declared in termux-shared/build.gradle:31; locally cached publisher POM declares Apache-2.0 | No GPL blocker established from this dependency's metadata; archive the exact dependency's license/notice material for release |
| Bundled bootstrap executables and libraries | Actual bootstrap ZIP package databases, native build and assembly embedding | Separate package licenses apply, including GPL and LGPL; they cannot all be relabeled MIT |

Local module declarations: settings.gradle includes all four modules. app/build.gradle:39-40 links terminal-view and termux-shared. terminal-view/build.gradle:10 exposes terminal-emulator. termux-shared also links terminal-view. Removing one direct dependency declaration does not remove transitive or copied GPL code.

The local license declarations agree with the [upstream root license](https://github.com/termux/termux-app/blob/master/LICENSE.md) and [upstream shared-library exceptions](https://github.com/termux/termux-app/blob/master/termux-shared/LICENSE.md).

## Direct dependencies in our features

| Location | Concrete dependency |
| --- | --- |
| app/src/main/java/com/termux/app/AiActivity.java:250 | Casts the service binder to TermuxService.LocalBinder and keeps the actual service object |
| AiActivity.java:5600 | Calls TermuxService.createTermuxSession and receives GPL-covered TermuxSession |
| AiActivity.java:5619 | Starts/binds the in-app TermuxService |
| AiActivity.java:5684 | Calls TermuxInstaller.setupStorageSymlinks |
| app/src/main/java/com/termux/app/AiTerminalViewClient.java:11 | Inherits GPL-covered TermuxTerminalViewClientBase |
| app/src/main/java/com/termux/app/MobileKhabeerToolExecutor.java:77 | Calls GPL-covered TermuxShellEnvironment.init before launching Bash |
| app/src/main/AndroidManifest.xml | Registers retained Termux application, activity and services alongside AiActivity and AiRuntimeService |

The existing arm64 debug APK contains DEX descriptors referencing these classes and bundles libtermux.so, liblocal-socket.so and libtermux-bootstrap.so. This corroborates the build/source evidence. It was not rebuilt or fully decompiled; it is not a certification of every distributed APK.

## Bootstrap findings

app/build.gradle pins the Android 7 bootstrap to `2026.02.12-r1+apt.android-7`. app/src/main/cpp/termux-bootstrap-zip.S embeds the ABI-specific archive into libtermux-bootstrap.so. Thus build-time downloading still results in distribution inside the APK.

[TERMUX_BOOTSTRAP_PACKAGE_INVENTORY.csv](TERMUX_BOOTSTRAP_PACKAGE_INVENTORY.csv) records the package names, versions, architectures and archive SHA-256 hashes from all four local ZIPs. It is a package inventory, not a completed license SBOM.

The aarch64 archive includes Bash 5.3.9, coreutils 9.9, apt 2.8.1-2 and dpkg 1.22.6-5, among many other packages. Current upstream [Bash](https://github.com/termux/termux-packages/blob/master/packages/bash/build.sh) and [coreutils](https://github.com/termux/termux-packages/blob/master/packages/coreutils/build.sh) recipes identify GPL-3.0; [apt](https://github.com/termux/termux-packages/blob/master/packages/apt/build.sh) identifies GPL-2.0. These current recipes corroborate the license families, but exact historical source and patch sets must still be matched to the archived versions. The ZIP also contains share/LICENSES and package-specific copyright files.

Launching Bash with ProcessBuilder, ordinary arguments and stdout/stderr is consistent with separate-program use. It does not by itself impose GPL on an independent GUI. Here, the executor also calls a GPL Java helper, and the rest of the app retains GPL implementation code. Those are separate blockers. Aggregation depends on actual independence and communication semantics, not merely putting code in separate files or processes. See the [FSF FAQ on aggregation](https://www.gnu.org/licenses/gpl-faq.html.en#MereAggregation).

## Practical options

1. **Keep the current application and release the combined work under GPL-3.0-only.** Preserve component-specific MIT/Apache/other notices. For downloadable APKs, provide matching Corresponding Source, including necessary modifications and build scripts, with clear access directions. Preserve copyright/license notices and mark changes. Account separately for bundled package source obligations. GPL permits commercial distribution. See [GPLv3 sections 4-6](https://opensource.org/license/gpl-3.0).

2. **Publish eligible original AI components under MIT while keeping the combined app GPL.** Review authorship and copied/adapted material first; being newly added in Git does not prove originality. Extract provider, chat, memory, registry and tool abstractions where feasible, using an interface for terminal/runtime operations. Clearly scope MIT to those original components. Moving existing GPL implementations into a new module, or adding an MIT wrapper, does not relicense them. This preserves permissive reuse of your own work without replacing the working Termux foundation.

3. **Build an independent MIT companion app using a separately installed Termux.** Replace the LocalBinder connection, GPL terminal client inheritance and environment helper with an independently implemented external command backend. Termux documents a [RUN_COMMAND Intent API](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) with result callbacks. It requires the RUN_COMMAND permission and allow-external-apps=true. Use only appropriately licensed protocol constants rather than importing all of termux-shared. A separate app cannot simply retain direct access to Termux's private filesystem or the current in-process terminal object; redesign file operations and interactive terminal behavior. This is a plausible architectural route, not a guarantee that any arbitrary IPC split avoids GPL.

4. **Build an independent standalone MIT application with replacement runtime integration.** Reimplement or replace the retained Termux application/services/installer, GPL shared helpers, settings and resources; audit all copied code and transitive dependencies. Keep only clearly licensed permissive components. Resolve terminal-module provenance before reuse. Reimplement from functional requirements without translating or lightly rewriting GPL implementations. Preserve package execution compatibility, storage behavior, terminal I/O and lifecycle behavior through integration tests. Bundled GPL command-line programs can remain separate works with their own compliance requirements, but then the distribution still contains non-MIT software. Requiring every shipped component to be permissive additionally requires replacing those programs and auditing their dependencies.

5. **Obtain separate licensing permission.** Permission must cover the relevant rights from all applicable copyright holders, not merely one maintainer's informal approval. A suitable alternate license or exception could permit otherwise restricted combinations, but it would not cover unrelated bundled packages automatically.

Recommendation: retain GPL for the current full application and make eligible original AI modules MIT if permissive reuse is the objective. If the application itself must be MIT, choose an independent companion architecture or budget for the standalone rewrite; neither is a license-file-only change.

## Limits and release follow-up

This assessment establishes concrete GPL blockers; it is not an exhaustive release compliance certification or a legal opinion. It did not trace every Maven transitive dependency, every asset's provenance, every bootstrap package's exact license expression, or packages installed later by users. The terminal-module exception's full coverage remains unresolved. Before claiming a permissive-only build, complete those checks against the precise release artifacts and have the proposed licensing boundaries reviewed by qualified counsel. No licensing permission was requested from upstream and no licenses were changed.
