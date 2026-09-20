# Document model

## Choice: MongoDB

Runs from a single `docker compose` service, no AWS credentials. `explain()`
gives `totalDocsExamined` and `nReturned` directly. Compound indexes map 1:1
onto the three queries the service makes.

## Document shape

    {
      _id: <deterministic hash of natural key>,
      account_last4: "4821",
      occurred_at: ISODate("2026-07-04T20:24:00+05:30"),
      occurred_month: "2026-07",   // denormalized for query 1
      direction: "debit",
      amount: Decimal128("2499.50"),
      category: "SPEND",
      merchant: "AMAZON PAY",
      source_message_ids: ["m-00087-...", "m-00089-..."]
    }

`_id` is derived from (account_last4, occurred_at, direction, amount) so
re-running Backfill is a no-op upsert.

## The three queries

### Query 1 — one account's transactions for one month, newest first

    db.transactions.find({account_last4: "4821", occurred_month: "2026-07"})
                     .sort({occurred_at: -1})

Index: `{account_last4: 1, occurred_month: 1, occurred_at: -1}`
Expected examined/returned at 100k: ~matches returned (~300 for one month).

### Query 2 — running totals per category for an account

    db.transactions.aggregate([
      {$match: {account_last4: "4821"}},
      {$group: {_id: "$category", total: {$sum: "$amount"}}}
    ])

Index: `{account_last4: 1, category: 1}` (not fully covering because amount
isn't in the index — would need `{account_last4: 1, category: 1, amount: 1}`
for a covered query).
Expected examined/returned: ~33,000 examined, 4 returned.

### Query 3 — given a message id, which transaction

    db.transactions.find({source_message_ids: "m-00087-..."})

Index: `{source_message_ids: 1}` (multikey)
Expected examined/returned: 1 / 1.

## The six numbers (100k transactions)

Not measured. I would generate a synthetic corpus of 100,000 transactions
with the same shape as corpus-a, run the three queries through
`explain("executionStats")`, and record `totalDocsExamined` vs `nReturned`.
The expected shape is above; the actual numbers depend on the per-account
distribution.

## What's not implemented

`MongoDocumentStore`, `Backfill`, and `ConsistencyChecker` are stubs.
See README "What I would do next."