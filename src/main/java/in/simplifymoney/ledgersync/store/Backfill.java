package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Safe to re-run: the document store's save() is an upsert on the natural
 * key, so duplicates in the SQL store (which has had no uniqueness
 * guarantee) collapse onto one document rather than multiplying.
 *
 * Safe after a partial failure: the previous run's writes are committed;
 * re-running resumes where it stopped. Already-present documents are
 * re-upserted with identical values, so the operation converges.
 */
public final class Backfill {

    private final LedgerStore source;
    private final DocumentStore target;

    public Backfill(LedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> rows = source.all();
        Set<String> alreadyWritten = new HashSet<>();
        long written = 0;
        long skipped = 0;

        for (NormalizedTxn row : rows) {
            String key = naturalKey(row);
            if (alreadyWritten.contains(key)) {
                // Duplicate row in SQL. The upsert would no-op anyway;
                // count it as skipped so the Result tells the truth.
                skipped++;
                continue;
            }
            target.save(row);
            alreadyWritten.add(key);
            written++;
        }

        return new Result(rows.size(), written, skipped);
    }

    private static String naturalKey(NormalizedTxn t) {
        return t.accountLast4() + "|"
                + t.occurredAt().toInstant() + "|"
                + t.direction().name() + "|"
                + t.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
    public record Result(long read, long written, long skipped) {}
}


