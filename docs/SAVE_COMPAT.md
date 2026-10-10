# Save compatibility

Players keep their campaigns across mod updates. A change that breaks loading an old save is a release blocker. This page explains how Starsector saves this mod's data, the rules that follow from it, and the test that enforces them.

## How Starsector saves mod data

Starsector writes the whole campaign to `campaign.xml` with **XStream 1.4.10**. It does not use Java serialization (`Serializable` and `serialVersionUID` are irrelevant). What that means in practice, as verified in the game's save manager:

- **Fields are written by name.** A class name is written only when a field holds a subclass of its declared type, so most of this mod's objects appear in the save as plain field names (`<loanManager>`, `<bureau>`, ...).
- **Unknown elements are fatal.** The game tells XStream to ignore unknown elements for only one field of its own (`planetFilterData`). If a save contains a field that the class no longer has, loading fails.
- **Constructors and field initializers do not run.** XStream allocates objects without calling constructors. Fields added in a new version load as `null`, `0` or `false` from old saves, whatever the code initializes them to.
- **Enums are written by constant name.**
- **Class names are written for saved scripts, intel and persistent data**, e.g. `com.bankofstarsector.core.BankData`.

## What this mod puts in a save

| Root | Registered by | Notes |
|---|---|---|
| `core.BankData` | `getPersistentData().put("bos_data", ...)` | Everything below hangs from it |
| `intel.BankingIntelPlugin` | `IntelManager.addIntel` | The banking terminal |
| `intel.CollectionIntelPlugin`, `intel.LoanIntelPlugin` | `IntelManager.addIntel` (0.1.x) | Unused since 0.2.0, kept so 0.1.x saves load |
| `collection.CollectionFleetScript` | `sector.addScript` | One per active collection |
| `faction.PBCPostInitScript` | `sector.addScript` | |
| Memory keys `$bos_*` on fleets | `MemoryAPI.set` | `$bos_collection_fleet`, `$bos_target_loan`, `$bos_hail_cooldown`, `$bos_settled` |
| Market condition ids on colonies | `MarketAPI.addCondition` | `bos_lien`, `bos_receivership` (0.3.0). The ids are saved in each colony's condition list; never rename them. Their plugins are transient (rebuilt on load) and not saved. |

These are **not** saved: `BankCampaignScript` and `BankSnapshotScript` (transient scripts), `BankEconomyListener` (registered as a transient listener), and rule commands (`BOSBranch`, `BOSCollection`, created per use).

From `BankData` the save reaches:
- `LoanManager`, `InvestmentManager` and their `BankAccount`s;
- `CreditScoreManager` → `CreditBureau` (tradelines, events, inquiries, cached result);
- `InterestEngine`;
- `CollectionManager`, `AssetSeizureManager`, `BankruptcyManager` and `ForeclosureManager` (0.3.0);
- the transaction history.

The exact list (26 classes, every field and enum constant) is [`tests/save-fields.baseline`](../tests/save-fields.baseline).

## Rules

1. **Never remove or rename a field of a saved class.** Stop using it instead, and mark it with a comment such as `// 0.1.x, kept so old saves load`. Examples: `CreditScoreManager.creditScore` and the other old score counters.
2. **Never change a field's type.** Add a new field instead, and migrate the value lazily (see below).
3. **New fields must be null-safe.** Initialize them on first use, not only in the constructor or in a field initializer, because those do not run for old saves. Use the accessor pattern already used across the mod:
   ```java
   public List<BankAccount> getLoans() {
       if (loans == null) loans = new ArrayList<BankAccount>();
       return loans;
   }
   ```
   For primitives, choose a meaning where `0`/`false` is the correct old-save value, or keep a separate "initialized" flag.
4. **Enum constants can be added, never removed or renamed.** To retire one, keep it and stop offering it, e.g. a loan type that is no longer listed.
5. **Never move or rename a saved class**, including changing its package or nesting.
6. **Never store anonymous or local classes in a saved field.** Their generated names (`Outer$1`) change when the code around them changes. Use a named static nested class.
7. **Never store game objects that the game does not expect to be saved**, such as UI panels or settings. Fleets, markets and other campaign entities are fine. They are saved by reference, but they can disappear (a destroyed fleet, a decivilized market), so check them for null and `isAlive()` / `isInEconomy()`.
8. **Migrations go in accessors, or in `readResolve()`.** XStream calls a private `Object readResolve()` after loading an object. Use it, or the lazy accessor pattern, to fill new fields from old ones. `CreditScoreManager.bureau(LoanManager)` is the example: it builds the credit bureau for a 0.1.x save from the loans that are still open.
9. **New saved roots** (a new script added with `addScript`, a new intel type, new persistent data) must be added to `ROOTS` in `tests/SaveCompatCheck.java` and to the table above.

## The guard

`test.ps1` runs **Save compatibility** (`tests/SaveCompatCheck.java`). It walks the saved object graph from the roots above and compares every field (with its generic type) and every enum constant with `tests/save-fields.baseline`:

- **Missing or changed entry → FAIL.** The change would break existing saves. Undo it, or follow the rules above.
- **New entry → FAIL** until the baseline is updated. Make the new field null-safe (rule 3), then run:

  ```powershell
  .\test.ps1 -UpdateSaveBaseline
  ```

  and commit the updated baseline with the change. The baseline diff in the commit is the record of what each version added to saves.

## Manual check before a release

The guard cannot see everything: renamed memory keys and changed meanings of a field are not checked. Before each release, load a save from the previous version:

- **Fixture:** `E:\Games\Starsector_api\mod\save-fixtures\bos-0.2.x_MayTerrell` is a 0.2.0-beta save with the credit bureau. 0.2.1-beta has the same saved fields. It is kept outside the game folder so playing does not overwrite it.
- Copy it into `Starsector/saves/`, load it, open the banking terminal (every tab), let a month end pass, then save and load again.
- After the release, keep a save from the new version as the next fixture.
