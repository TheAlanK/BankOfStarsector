# Bank of Starsector

A Starsector mod that adds the **Persean Banking Confederation (PBC)**, a powerful neutral faction with a complete banking system.

## Features

### Banking System
- **Loans**: 5 tiers from Emergency (50k) to Sovereign (5M). Each loan is repaid in **monthly installments** (interest plus an even share of the principal).
- **Credit-builder loan**: for captains with no credit score or a low one (under 500), including during bankruptcy recovery. You borrow 10k–50k, but the money stays at the bank while you pay the installments (12 months, 1%/month fixed); when the loan is paid off, the money is yours. Each month paid on time builds your credit file. It doesn't count toward your loan limit, and if it defaults the bank simply keeps the deposit (no Collection Fleet), but the charge-off still goes on your credit report.
- **Repayment schedule and score chart**: every loan can show its month-by-month schedule (payment, interest, principal, balance) in the Loans tab; the Credit tab charts the last 12 monthly reports and names the factor that moved the score most each month.
- **Know what you sign**: before any loan, the terminal and the branch show the first installment, total interest, total repaid, rate and term (projected exactly as the bank bills it); before a locked investment, the lock period and the early-withdrawal penalty.
- **Fleet insurance**: House Varenne's underwriters insure every ship in your fleet. Standard (60% of a lost ship's base value, 10k deductible per ship) or Comprehensive (80%, 5k). The monthly premium follows your fleet's value, your credit score (as real insurers use credit-based insurance scores), claims paid in the last 12 months and the war surcharge. Ships lost in battle are paid after the post-battle recovery (a recovered ship is no loss). No claims in a new policy's first 30 days, nor for ships that joined the fleet less than 30 days before the loss; payouts are capped at half the insured value per month; an unpaid premium suspends the cover and a second cancels the policy. New Insurance tab in the terminal, also at the branch office and in NexusUI.
- **Colony-secured loan**: pledge a colony of size 4+ for up to 60% of its appraised value (development, structures, natural resources and traits, a year of income, adjusted for hazard), at a rate 25% lower. The colony shows a *PBC Lien*. If the loan defaults, the colony goes into **receivership**: all its income goes to the bank and it loses stability until the loan is current. With Nexerelin, still unpaid 60 days later, the PBC **forecloses**: it turns hostile and sends an invasion. A colony the PBC takes is **auctioned** to a major faction (the Confederation never keeps territory): the highest bidder pays the second-highest bid, the price pays the loan, any surplus is returned to you, and relations go back to normal. Abandoning a pledged colony makes the whole loan due at once; if another faction captures it, the loan continues unsecured.
- **Credit line**: a revolving Confederation Credit Line for scores of 500+ (limit 50k / 200k / 500k by bracket). Draw and repay freely; each month end issues a statement with a minimum payment (interest + 1% of the balance, at least 1k). Pay the statement in full and there is no interest (autopay does this by default; it can pay only the minimum instead). Six on-time statements in a row raise the limit by 20%; a late payment cuts it and blocks draws. The bureau sees the statement balance, so keeping utilization (balance ÷ limit) low matters: 1–10% is best, over 30% hurts, over 90% hurts a lot, and paying it down recovers the score at the next report.
- **Autopay**: installments appear in the vanilla **monthly income report** under *Fleet → PBC loan installments* and are settled with the rest of your monthly finances. You can turn autopay off in the terminal.
- **Grace period**: an installment only becomes late if it is still unpaid at the *next* month end, so you always get a month to pay manually.
- **Investments**: savings accounts, government bonds, commodity futures, venture funds and military contracts.
- **Sovereign debt market**: major factions borrow from the Confederation to fund their wars and repay in peace (ledger in the Overview tab). Government bonds pay a higher coupon when sovereign debt is high. If an indebted faction collapses (Nexerelin), it defaults and bonds take a haircut.
- **Credit score**: computed once a month from a credit report, like a real credit bureau (FICO-style, 300–850): payment history 35%, amounts owed 30% (installment balances and credit-line utilization), length of history 15%, new credit 10%, credit mix 10%. Every application is a hard inquiry, lateness is reported from 30 days and fades over 7 years, and there is no score until an account is 6 months old, so taking and instantly repaying loans does not farm points. Its bracket sets how many loans you can hold and adjusts your rates (−15% / 0 / +20% / +50%).
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
- puts a bankruptcy public record on your credit report for 10 years (its weight fades after 2 and 5 years);
- costs relations with the PBC;
- applies a colony income stigma.

Loans are locked for 24 months, and you can't invest for 12.

### The Persean Banking Confederation
The Confederation has kept the Sector's books since the Collapse. Read its history, charter and relations with every faction in **[LORE.md](LORE.md)**.

- A full faction with the Aurum star system (3 planets and an orbital station). In a Nexerelin random sector, Nexerelin places the PBC markets itself and Aurum is not generated.
- **Confederation branch office** at every PBC market. You can review your file, pay past-due installments, apply for a loan or make an investment for any amount (a slider), or open a banking terminal there. The teller shows the terms before you sign.
- A powerful defensive navy with high-quality ships and officers.
- A neutral diplomatic stance: other factions avoid war with the PBC.
- Custom fleet types: Security Details, Enforcement Fleets, Confederation Task Forces.

### Mod Integration
- **Nexerelin**: wars drive interest rates, and dead factions don't count toward the war surcharge.
- **NexusUI**: a banking dashboard page (amounts due, autopay, loans, investments) with pay/autopay buttons; the data is also exposed to NexusUI's data bridge as `pbc_banking`.
- **LunaLib**: in-game settings and version checker support.

## Where to bank
- **Intel → Economy → PBC Banking Terminal**, available anywhere: Overview, Loans, Investments, Credit Score and History tabs.
- **PBC markets → "Visit the Confederation branch office"**.

## Configuration
Every number above can be changed in `data/config/bos_settings.json`, and other mods can merge their own values into it. With **LunaLib**, the main settings and the language can also be changed in-game (LunaLib settings menu); those values take precedence over the JSON.

## Languages
English and Brazilian Portuguese. `language` = `auto` follows the system language; set `en` or `pt_BR` in `bos_settings.json` or LunaLib. Translations live in `data/strings/bos_strings_<lang>.json`; any missing key falls back to English.

## Testing
`test.ps1` builds the jar and runs: a replay of Starsector's script sandbox rules over every referenced class, a link check against the game's jars on the game's JRE, a format-string safety check, a translation-table check, a save-compatibility check (every field the mod writes into saves is compared with `tests/save-fields.baseline`; see [docs/SAVE_COMPAT.md](docs/SAVE_COMPAT.md)), and a month-by-month loan simulation. `test.ps1 -Smoke` also launches the real game directly (`-DlaunchDirect`, windowed, no sound), waits for the main menu and scans `starsector.log` for errors from this mod.

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

### 0.3.0-beta (in development)
- **New:** credit-builder loan, the realistic way to start (or rebuild) a credit file.
- **New:** fleet insurance (Standard and Comprehensive policies, credit-based premiums, claims after post-battle recovery).
- **New:** colony-secured loans with receivership, Nexerelin foreclosure and auction of the colony to a major faction.
- **New:** repayment schedule per loan, and a 12-month score chart that explains each change (also in NexusUI).
- **New:** loan and investment quotes before signing (terminal confirmation and branch office), and loans/investments for any amount at the branch office.
- **New:** revolving credit line with monthly statements, minimum payments, a grace period, automatic limit increases, and utilization in the credit score. The Sovereign loan is no longer called a "credit line".
- **Fix:** a default is put on the credit report when it happens, so a seizure that settles it before month end no longer hides it; a charged-off loan no longer adds a new late mark every month.
- **Internal:** a test now fails the build if a change would break loading existing saves ([docs/SAVE_COMPAT.md](docs/SAVE_COMPAT.md)).

### 0.2.1-beta
- **New:** Persean Banking Confederation lore ([LORE.md](LORE.md)), condensed into the in-game faction and planet descriptions (English and pt-BR).

### 0.2.0-beta
- **New:** Brazilian Portuguese translation (automatic by system language), translatable faction/planet descriptions.
- **New:** LunaLib settings menu and version checker file; release zip now has a stable `BankOfStarsector.zip` asset and includes `graphics/`.
- **Fix:** the NexusUI page read game state from NexusUI's refresh thread; it now renders snapshots published from the game thread and sends actions through NexusUI's command queue.
- **New:** test suite (`test.ps1`) including an in-game smoke test.
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
