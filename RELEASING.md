# Publishing Khabeer releases

Source publication is separate from distributing APKs. CI builds/tests without automatically uploading APKs or creating/deleting releases.

Before a binary release:

1. Choose version metadata, test intended ABIs/devices/providers and tag the exact source. Include necessary build commands, SDK/NDK/JDK versions and configuration.
2. Use a separately managed production signing key. The tracked Termux test key is public/untrusted. Preserve package/signature compatibility guidance while the ID remains com.termux.
3. Preserve LICENSE.md, COPYING, exceptions, source notices and Hermes's MIT notice. Update CHANGES.md. LICENSES is packaged for offline access via More -> Licenses & acknowledgements and settings -> Licenses & acknowledgements.
4. Generate a release-specific Java/native/bootstrap dependency inventory with exact licenses, copyright notices and required NOTICE material. The existing bootstrap CSV is an initial inventory, not compliance certification.
5. Provide Corresponding Source for GPL-covered distributed components, including applicable bundled package source, patches and build scripts. Match exact versions/archive hashes. An unrelated upstream HEAD or bootstrap binary link alone is insufficient. Build-time downloading does not remove redistribution obligations.
6. Put source-download directions beside every APK and preserve source availability as required by the distribution method. Do not label the entire application/bootstrap MIT.
7. Verify packaged legal texts and offline legal access. Include APK SHA-256 checksums. Review store requirements before claiming store availability.

See COPYING and component licenses for the terms. docs/TERMUX_LICENSE_AUDIT.md records the earlier assessment and provenance gaps, not a certification of future releases.
