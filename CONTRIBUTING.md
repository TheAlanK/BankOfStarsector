# Contributing to Bank of Starsector

Bug reports, ideas, balance feedback and translations are all welcome.

- **Bugs:** [open an issue](https://github.com/TheAlanK/BankOfStarsector/issues/new/choose) with your mod version, the relevant part of `starsector.log` and your mod list.
- **Ideas and stories:** use [Discussions](https://github.com/TheAlanK/BankOfStarsector/discussions).
- **Translations:** copy `data/strings/bos_strings_en.json` to `bos_strings_<lang>.json`, translate the values (keep every `%s`), and open a pull request. Missing keys fall back to English.

## Code

- Java 8 source level (`--release 8`); the game runs on its own JRE 17.
- Build with `build.ps1` (or `build.bat`); test with `test.ps1` (`-Smoke` also launches the game). Every check must pass.
- Starsector's script class loader forbids reflection and file access in mod code; `tests/SandboxCheck` replays its rules.
- **Saves:** never remove or rename a saved field or enum constant. New fields must load safely as `null`/`0`. Read [docs/SAVE_COMPAT.md](docs/SAVE_COMPAT.md); `test.ps1` fails if a change would break existing saves.
- **Performance:** nothing that runs every frame, every UI render or every rate quote may scan all markets in the sector; sector-wide numbers are cached per game day (`InterestEngine`).
- Player-visible text goes in `data/strings` (English and Brazilian Portuguese). Text passed to `TooltipMakerAPI.addPara` is a format string: dynamic text goes through `"%s"`.
- Features that change what the player sees about their bank also update the NexusUI page and its JSON (`ui/BankSnapshot`, `ui/BankingNexusPage`).
