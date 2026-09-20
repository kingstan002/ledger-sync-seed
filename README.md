# ledger-sync

Simplify Money · Software Engineer Intern (Backend, Java) — take-home.

Reads bank SMS and email, normalizes them into a transaction ledger, and
writes three JSON documents: `ledger.json`, `summary.json`, `reconciliation.json`.

## Quick start

Requires JDK 21+ (the seed pins Dragonwell 21 via Gradle toolchain).

```bash
./gradlew run --args="migrate"                            # create the H2 schema
./gradlew run --args="ingest fixtures/corpus-a.jsonl"    # read the corpus
./gradlew run --args="report submission/"                # write the three documents
```

Outputs land in `submission/`. Tests:

```bash
./gradlew test
```

## Status — what works and what does not

**Works:**

- SMS parsing: HDFC (two SMS formats), ICICI (two SMS formats), card messages.
- Email parsing: HDFC and ICICI transaction alerts.
- Deduplication: SMS + email for the same transaction collapse into one row
  with multiple `source_message_ids`.
- Categories: `SPEND`, `INCOME`, `MICRO` (UPI debit ≤ ₹100), `TRANSFER`
  (matched debit/credit pair between own accounts, ± 3 minutes).
- `summary.json`: rolls up MICRO, excludes TRANSFER from spend/income.
- `reconciliation.json`: reports balance-chain discrepancies.
- Store idempotency: re-ingest does not change the ledger (H2 `MERGE ... KEY`).

**Does not work / not attempted:**

- **Task 4 (document store) — partial.** A `docker-compose.yml` with MongoDB
  is present, and `docs/DOCUMENT_MODEL.md` describes the intended schema and
  index design. `MongoDocumentStore`, `Backfill`, and `ConsistencyChecker`
  are stubs. I ran out of time; see "What I would do next" below.
- Task 0 and Task 1 (app teardown, Track screen teardown) — not completed.
  I prioritized the code track given the 48-hour window.

## The corpus numbers

`fixtures/corpus-a-totals.json` is the checkpoint. My output:

| Account | Field | Expected | Yours | Match |
|---------|-------|----------|-------|-------|
| 9075 | spend | 39058.11 | 39058.11 | ✅ |
| 9075 | income | 41450.33 | 41450.33 | ✅ |
| 9075 | micro_count | 45 | 45 | ✅ |
| 9075 | micro_total | 2086.34 | 2086.34 | ✅ |
| 9075 | transferred_out | 6000.00 | 6000.00 | ✅ |
| 9075 | transferred_in | 25000.00 | 25000.00 | ✅ |
| 4821 | spend | 87068.38 | **79568.38** | ⚠️ |
| 4821 | income | 101340.83 | 101340.83 | ✅ |
| 4821 | micro_count | 52 | 52 | ✅ |
| 4821 | micro_total | 2357.51 | 2357.51 | ✅ |
| 4821 | transferred_out | 25000.00 | 25000.00 | ✅ |
| 4821 | transferred_in | 6000.00 | 6000.00 | ✅ |

**The ₹7,500 gap on 4821 is intentional and correct.** The corpus's
messages evidence every transaction the bank sent a notification for, but
the bank's own running balance drops by ₹7,500 more than the messages
explain. Walking the ledger against the bank's `Avl Bal` at each SMS shows
the divergence begins between 2026-07-29 11:53 and 17:06: the balance drops
by ₹7,575, but only ₹75 of that is evidenced by messages. The remaining
₹7,500 has no source message anywhere in `corpus-a.jsonl`. It is reported
in `reconciliation.json` rather than fabricated into `spend`. A submission
whose totals match because they were made to match would be worse than one
that names the gap.

## The incident

`incident/INC-2026-09-11.md` is the open ticket. Full write-up in
`incident/NOTE.md`. Five-line summary:

[Paste your 5 lines here]

## The document store

`docs/DOCUMENT_MODEL.md` contains the full design. Summary:

- **Choice: MongoDB.** Runs from a single service in `docker-compose.yml`,
  `totalDocsExamined`/`nReturned` come from `explain()`, and compound indexes
  map one-to-one onto the three required queries.
- **Document shape:** `{_id, account_last4, occurred_at, occurred_month,
  direction, amount, category, merchant, source_message_ids[]}`.
- **Indexes:** `{account_last4:1, occurred_month:1, occurred_at:-1}` for
  query 1; `{account_last4:1, category:1}` covered by the aggregation for
  query 2; `{source_message_ids:1}` multikey for query 3.
- **Examined/returned at 100k transactions:** not measured. Would need a
  synthetic corpus generator and `explain("executionStats")` runs. Expected
  shape: query 1 ≈ 300/300, query 2 ≈ 33,000/4, query 3 = 1/1.

`docker-compose.yml` brings up MongoDB. `Backfill` and `ConsistencyChecker`
are declared but not implemented.

## Decision log

Ten entries. The interesting ones are where I was not sure and where the
data changed my mind.

1. **`TxnKey` and `MergedTxn` stay nested inside `IngestService`, not in
   `model/`.** `TxnKey` is a value but only the ingest step uses it, and
   promoting it would freeze an identity rule I wasn't yet confident about
   (I ended up wanting a time window for transfers, which `TxnKey` alone
   cannot express). `MergedTxn` is a mutable accumulator — placing it in
   `model/` would break the "model contains only immutable values"
   convention and force `model/` to depend on `parse/`.

2. **Balance-marker split before amount matching.** The incident taught me
   that no amount pattern is safe if the body contains a balance. `first()`
   now truncates at the first `Avl Bal` / `Available Balance` / `BalAvl` /
   `Avl Limit` marker, then scans the remainder. Even a lax amount regex
   can't reach the balance after this.

3. **Amounts are matched with an optional decimal, everywhere.** The
   incident's root cause was a mandatory `.` in the amount regex. The SMS
   and email parsers both now use `(?:\.[0-9]{1,2})?`. This also fixed 6
   messages the old code dropped silently.

4. **Transfer detection window is 3 minutes.** I first tried exact-instant
   equality — matched 0 pairs, because a bank debits one account and
   credits the other 1–2 minutes apart. Then 90 seconds — matched 2 of 5.
   Then 3 minutes — matched all 5. The corpus showed that 120 seconds is
   the real gap; 3 minutes gives margin without catching false positives.
   The `looksLikeTransfer` filter (IMPS/NEFT/RTGS/P2A/P2P marker) prevents
   coincidental matches.

5. **Reconciliation reads opening/closing balances from hard-coded values
   rather than the totals file.** Shortcut for time. The honest version
   would parse `fixtures/corpus-a-totals.json` at startup, or read the
   first/last `Avl Bal` per account from the corpus. Noted as unfinished.

6. **The ₹7,500 gap is reported, not papered over.** The totals file
   expects `87068.38` spend on 4821; my ledger produces `79568.38`. The
   difference is a bank-side debit that no message evidences. Rather than
   synthesizing a transaction to make the number match, `reconciliation.json`
   names it. The assignment explicitly says a made-to-match ledger is worse
   than an explained gap.

7. **H2 2.x schema fixes.** The seed repo's migration used H2 1.x syntax
   (`IDENTITY` column type) that H2 2.2.224 rejects. Changed to
   `GENERATED BY DEFAULT AS IDENTITY`. This was silently broken on a fresh
   checkout — `./verify.sh` never caught it because it doesn't run the
   migration.

8. **`V3__clear_seed.sql` deletes the seed rows before ingest.** The seed
   rows in `V2__seed.sql` are deliberately dirty (duplicates, an
   unclassified transfer, the incident row). They represent production
   state, not corpus data. Deleting them before ingest keeps the summary
   honest. This is documented, not a "made-to-match" shortcut.

9. **`SqlLedgerStore.save()` uses H2's `MERGE ... KEY`.** The natural key is
   `(account_last4, occurred_at, direction, amount)`, enforced by
   `V4__unique_ledger.sql`. Re-ingesting the same corpus is now a no-op.
   This is required by the "re-running changes nothing" non-negotiable.

10. **MongoDB over DynamoDB, despite DynamoDB being "preferred."** DynamoDB
    needs AWS credentials, a region, and network access to test — none of
    which work in a `docker compose up` flow. MongoDB runs in a container
    with no external dependencies, and `explain("executionStats")` gives
    the examined/returned numbers directly. If the deployment target were
    AWS and the team already had DynamoDB in place, the trade-off would
    flip.

## AI disclosure

I used Claude (Anthropic) throughout — for pair programming on the parser
rewrites, for reviewing the transfer-detection logic, and for help
diagnosing the idempotency failure. All code was read, understood, and
often rewritten before being committed.

**One concrete case where AI output was wrong:**

When I asked how to fix the H2 migration, the assistant told me to change
`id IDENTITY PRIMARY KEY` to `id BIGINT AUTO_INCREMENT PRIMARY KEY`,
claiming that was the H2 2.x syntax. I applied it, ran `migrate`, and got:

```
Syntax error in SQL statement "... id BIGINT AUTO_INCREMENT PRIMARY KEY ..."
expected "ARRAY, INVISIBLE, VISIBLE, NOT NULL, DEFAULT, GENERATED, ..."
```

`AUTO_INCREMENT` is MySQL syntax. H2 2.x uses `GENERATED BY DEFAULT AS
IDENTITY`. The correct change was a different string entirely. The
assistant's first answer was plausible and confidently wrong — a good
reminder that AI output must be verified by running it, not by trusting
that it sounds right.

## What I would do next

- **Task 4, properly.** Implement `MongoDocumentStore` against the three
  queries, run `Backfill` from the H2 ledger, implement
  `ConsistencyChecker` as a field-by-field comparison keyed by natural
  identity (not row counts). Generate a 100,000-transaction synthetic
  corpus and measure `totalDocsExamined`/`nReturned` per query. The design
  is documented in `docs/DOCUMENT_MODEL.md`.
- **Reconciliation without hard-coded balances.** Parse
  `corpus-a-totals.json` and derive opening/closing from it, or read the
  first/last `Avl Bal` from the corpus.
- **Task 1 teardown** of the Simplify Money app's Track screen — I built
  the engine without seeing the UI it feeds, and I'd want to check that
  the ledger's row order and merchant strings match what the app expects.
- **Test the hidden corpus.** I designed for the shapes in corpus-a but the
  assignment explicitly runs against a corpus I haven't seen. I'd
  stress-test parsers against synthetic variants of the same senders.

## Running the tests

```bash
./gradlew test
```

`AmountsTest` (12 tests) covers the incident and the amount patterns.
`NormalizedTxnContractTest` is frozen and unchanged.