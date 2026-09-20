-- The natural key of a ledger row.
ALTER TABLE ledger ADD CONSTRAINT ledger_natural_key
    UNIQUE (account_last4, occurred_at, direction, amount);