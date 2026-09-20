package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The three queries this service makes against the document store.
 * Everything else about the store's design is up to the implementation.
 */
public interface DocumentStore extends AutoCloseable {

    /** Query 1: one account's transactions for one month (YYYY-MM), newest first. */
    List<NormalizedTxn> transactionsForMonth(String accountLast4, String month);

    /** Query 2: running totals per category for an account. */
    Map<Category, BigDecimal> totalsByCategory(String accountLast4);

    /** Query 3: given a message id, which transaction did it produce. */
    Optional<NormalizedTxn> transactionForMessage(String messageId);

    /** Every transaction in the store. */
    List<NormalizedTxn> all();

    /** Upsert a transaction. Idempotent on the natural key. */
    void save(NormalizedTxn txn);

    @Override
    void close();
}