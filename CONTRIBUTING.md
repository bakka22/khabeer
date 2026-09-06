# Contributing to Khabeer

Use https://github.com/bakka22/khabeer/issues and pull requests for Khabeer changes. Describe behavior, reproduction steps, Android version, provider/model and app commit. Redact credentials and personal data from logs.

Read README.md for architecture/build instructions, docs/MEMORY_SOURCE_OF_TRUTH.md for memory contracts and docs/FEATURE_ROADMAP.md for Hermes mapping. Historical checkpoints are not substitutes for verifying current behavior.

Keep changes focused. Preserve attribution/license headers. For adapted code, record its source/license, include notices and verify GPL-3.0-only compatibility. A file being new to Git does not establish independent authorship.

Run relevant tests and compile the affected variant. Describe validation and limitations in pull requests. Terminal, background, login and physical-device behavior need targeted integration tests.

Do not commit SDKs, build outputs, caches, personal databases, credentials or production signing material. app/testkey_untrusted.jks is intentionally public upstream development material.

Unless explicitly identified under a compatible component license, contributions to the combined application are submitted under GPL-3.0-only. Contributors retain their copyright; no ownership transfer is requested.
