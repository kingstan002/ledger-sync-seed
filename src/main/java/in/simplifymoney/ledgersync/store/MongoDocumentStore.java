package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.util.List;

/**
 * MongoDB implementation of DocumentStore.
 *
 * NOT IMPLEMENTED. See docs/DOCUMENT_MODEL.md for the design.
 *
 * The three queries this class exists to serve:
 *   1. one account's transactions for one month, newest first
 *   2. running totals per category for an account
 *   3. given a message id, which transaction did it produce
 *
 * Indexes that would serve them:
 *   {account_last4: 1, occurred_month: 1, occurred_at: -1}   // query 1
 *   {account_last4: 1, category: 1, amount: 1}               // query 2 (covered)
 *   {source_message_ids: 1}                                  // query 3 (multikey)
 */
public final class MongoDocumentStore implements DocumentStore {


    @Override
    public List<NormalizedTxn> transactionsForMonth(String accountLast4, String month) {
        return List.of();
    }

    @Override
    public java.util.Map<in.simplifymoney.ledgersync.model.Category, java.math.BigDecimal>
    totalsByCategory(String accountLast4) {
        throw new UnsupportedOperationException(
                "MongoDocumentStore not implemented - see docs/DOCUMENT_MODEL.md");
    }

    @Override
    public java.util.Optional<in.simplifymoney.ledgersync.model.NormalizedTxn>
    transactionForMessage(String messageId) {
        throw new UnsupportedOperationException(
                "MongoDocumentStore not implemented - see docs/DOCUMENT_MODEL.md");
    }

    @Override
    public void save(NormalizedTxn txn) {

    }

    @Override
    public void close() {}
}