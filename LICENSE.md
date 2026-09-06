# Khabeer licensing

The combined Khabeer application is released under **GNU General Public License version 3 only** (`GPL-3.0-only`). A full copy is provided in [COPYING](COPYING). This is a modified Termux application; the original `termux/termux-app` repository is released under [GPLv3 only](https://www.gnu.org/licenses/gpl-3.0.html).

Original copyright and license notices remain applicable. See [CHANGES.md](CHANGES.md) and Git history for modifications, and [LICENSES/NOTICE.md](LICENSES/NOTICE.md) for acknowledgements. This declaration does not replace the licenses of separately identified third-party components.

### Exceptions

- [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) code is used which is released under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) license. Check [`terminal-view`](terminal-view) and [`terminal-emulator`](terminal-emulator) libraries.
- Check [`termux-shared/LICENSE.md`](termux-shared/LICENSE.md) for `termux-shared` library related exceptions.
- Hermes Agent material adapted into Khabeer retains the MIT license and copyright notice of Nous Research. See [the full notice](LICENSES/Hermes-Agent-MIT.txt). The combined application remains GPL-3.0-only.
- Bundled Hermes skills retain their original MIT metadata, author credits and per-skill LICENSE/port notes. See [skill attribution](LICENSES/Hermes-Skills-Attribution.md); those third-party assets are not relicensed exclusively under GPL.
- Bundled bootstrap executables/libraries retain their individual licenses. See [the inventory](docs/TERMUX_BOOTSTRAP_PACKAGE_INVENTORY.csv) and [release requirements](RELEASING.md).

The software is provided without warranty to the extent permitted by law. You may modify and redistribute the GPL-covered work under GPL version 3. Khabeer source: https://github.com/bakka22/khabeer.
