# Bank of Starsector

A Starsector mod that adds the **Persean Banking Confederation (PBC)**, a powerful neutral faction with a complete banking system.

## Features

### Banking System
- **Loans**: 5 tiers from Emergency (50k) to Sovereign (5M). Each loan is repaid in **monthly installments** (interest plus an even share of the principal).
- **Autopay**: installments appear in the vanilla **monthly income report** under *Fleet → PBC loan installments* and are settled with the rest of your monthly finances. You can turn autopay off in the terminal.
- **Grace period**: an installment only becomes late if it is still unpaid at the *next* month end, so you always get a month to pay manually.
- **Investments**: savings accounts, government bonds, commodity futures, venture funds and military contracts.
- **Sovereign debt market**: major factions borrow from the Confederation to fund their wars and repay in peace (ledger in the Overview tab). Government bonds pay a higher coupon when sovereign debt is high. If an indebted faction collapses (Nexerelin), it defaults and bonds take a haircut.
- **Credit score**: a FICO-like score (300–850). Its bracket sets how many loans you can hold and adjusts your rates (−15% / 0 / +20% / +50%).
- **Interest engine**:
  - Each war between major factions adds +5% to loan rates, up to +25%. With Nexerelin this follows its live wars.
  - Disrupted industries across the sector also make loans more expensive.
  - Military contracts pay more in wartime.
  - Disrupted industries drag investment returns down, while commodity futures go up when supply is disrupted.

### Debt Collection
Debt collection escalates by how many days a payment is overdue:

| Days overdue | What happens |
|---|---|
| 1 | Payment reminder |
| 31 | Penalty interest (+50% per month overdue), and new loans are suspended |
| 61 | A **Collection Fleet** is dispatched to hunt you down |
| 90 | **Default**: the bank seizes your investments up to the defaulted balance, then garnishes 30% of your colony income every month (*Colonies → PBC income garnishment* in the report). A loan fully recovered this way is closed as SEIZED, with no payoff credit |

When the Collection Fleet reaches you, its commander hails you. You can:
- pay the amount due;
- settle the whole debt;
- surrender your most valuable non-flagship ship as collateral, credited at 60% of its value;
- stall for time;
- refuse, which costs PBC reputation and starts a fight.

Destroying a collection fleet doesn't clear the debt. A fleet 1.5× stronger follows 30 days later.

Paying what you owe at any time (in the terminal, at a branch or to the fleet) cures the loan, recalls the fleet and ends the notices.

### Bankruptcy
Bankruptcy is available once a loan has defaulted, or when your debt exceeds 3× your monthly colony income. Filing (after a confirmation prompt):
- cuts your debt by 80% and restructures it over a new term;
- liquidates your investments at 50% of their value;
- resets your credit score to 300;
- costs relations with the PBC;
- applies a colony income stigma.

Loans are locked for 24 months, and you can't invest for 12. Your score recovers by +5 each month while you stay current.

### The Persean Banking Confederation
- A full faction with the Aurum star system (3 planets and an orbital station). In a Nexerelin random sector, Nexerelin places the PBC markets itself and Aurum is not generated.
- **Confederation branch office** at every PBC market. You can review your file, pay past-due installments, or open a banking terminal there.
- A powerful defensive navy with high-quality ships and officers.
- A neutral diplomatic stance: other factions avoid war with the PBC.
- Custom fleet types: Security Details, Enforcement Fleets, Confederation Task Forces.

### Mod Integration
- **Nexerelin**: wars drive interest rates, and dead factions don't count toward the war surcharge.
- **NexusUI**: a banking dashboard page with real-time portfolio tracking.

## Where to bank
- **Intel → Economy → PBC Banking Terminal**, available anywhere: Overview, Loans, Investments, Credit Score and History tabs.
- **PBC markets → "Visit the Confederation branch office"**.

## Configuration
Every number above can be changed in `data/config/bos_settings.json`, and other mods can merge their own values into it.

## Installation
1. Download the latest release.
2. Extract it to your `Starsector/mods/` folder.
3. Enable "Bank of Starsector" in the launcher.

Saves from 0.1.x load fine. Existing loans start receiving monthly bills at the next month end.

## Building
`build.ps1` (PowerShell) or `build.bat`. To compile you need the game's `starsector-core` jars plus `ExerelinCore.jar` and `NexusUI.jar`, because the compatibility bridges are compiled against them. At runtime those classes are only loaded when the matching mod is enabled.

## Dependencies
None required.
- **Nexerelin** (optional)
- **NexusUI** (optional)

## Changelog

### 0.2.0-beta
- **Fix:** the Nexerelin and NexusUI integrations never worked. Starsector's script class loader (`com.fs.starfarer.loading.scripts.B`) rejects every `java.lang.reflect` class with *"File access and reflection are not allowed to scripts"*. Both now use direct calls through bridge classes, with no reflection at all.
- **New:** monthly installments, autopay through the vanilla monthly report, a one-month grace period, and past-due tracking.
- **New:** income garnishment is now actually applied in default, and the garnished money pays down the debt. (It was never triggered before, and the old income multiplier never reached the bank.)
- **New:** the Collection Fleet hails you with real choices (pay, settle, surrender a ship, stall, refuse). It survives save/load, and destroying it brings a stronger fleet.
- **New:** Confederation branch office at PBC markets.
- **New:** `data/config/bos_settings.json`.
- **New:** credit score recovers during bankruptcy, and missed payments are penalized.
- **New:** asset seizure on default (investments held at the bank, then colony income). Loans closed this way get the SEIZED status.
- **New:** sovereign debt simulation for NPC factions, tied to government bond yields and sovereign defaults (this code existed but was never called).
- **New:** bankruptcy now asks for confirmation; the terminal shows the loan and investment lockout timers and the real reason a loan type is unavailable.
- **Fix:** every intel icon pointed to graphics/icons/intel/credits.png, which does not exist in the game. They now use registered sprites and the PBC crest.
- **Fix:** in a Nexerelin random sector the PBC got both Nexerelin's markets and the Aurum system.
- **Fix:** a collection fleet that could not spawn (no PBC market left) blocked collection forever; it now retries later.
- **Fix:** the credit bracket descriptions didn't match the minimum scores of each loan type.
- **Change:** LazyLib is no longer a dependency (it was never used).
- **Fix:** account IDs reset to 1 after every load, so loans could collide. A persistent counter replaces it, and old saves are migrated.
- **Fix:** collection notices piled up forever. They now end when superseded or resolved.
- **Fix:** collection phase 2 doubled the loan rate permanently. Penalty interest now applies only while overdue.
- **Fix:** the "disrupted" market condition doesn't exist in vanilla, so the investment disruption modifier was always 0. It now reads `Industry.isDisrupted()`.
- **Fix:** the interest rate now uses the credit bracket modifier that the terminal displays.

### 0.1.x
- Initial releases.

## License

MIT License
