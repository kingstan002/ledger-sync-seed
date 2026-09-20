package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proves the two stores agree, and names precisely where they do not.
 *
 * Compares per-transaction, keyed by the natural key
 * (account_last4, occurred_at, direction, amount). Does NOT compare row
 * counts — a checker that does will miss a single altered amount in a
 * million rows.
 *
 * Detects:
 *   - a transaction present in one store and not the other
 *   - amount, category, direction, occurred_at, merchant mismatches
 *   - source_message_ids set mismatches
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> out = new ArrayList<>();

        List<NormalizedTxn> sqlRows = sql.all();
        List<NormalizedTxn> mongoRows = documents.all();

        Map<String, NormalizedTxn> mongoByKey = new HashMap<>();
        for (NormalizedTxn m : mongoRows) mongoByKey.put(naturalKey(m), m);

        Map<String, NormalizedTxn> sqlByKey = new HashMap<>();
        for (NormalizedTxn s : sqlRows) sqlByKey.put(naturalKey(s), s);

        // SQL -> Mongo
        for (var e : sqlByKey.entrySet()) {
            NormalizedTxn s = e.getValue();
            NormalizedTxn m = mongoByKey.get(e.getKey());
            if (m == null) {
                out.add(new Divergence("presence: " + e.getKey(),
                        describe(s), "<missing>"));
                continue;
            }
            if (!s.amount().setScale(2, RoundingMode.HALF_UP)
                    .equals(m.amount().setScale(2, RoundingMode.HALF_UP))) {
                out.add(new Divergence("amount: " + e.getKey(),
                        s.amount().toPlainString(), m.amount().toPlainString()));
            }
            if (s.category() != m.category()) {
                out.add(new Divergence("category: " + e.getKey(),
                        s.category().name(), m.category().name()));
            }
            if (s.direction() != m.direction()) {
                out.add(new Divergence("direction: " + e.getKey(),
                        s.direction().name(), m.direction().name()));
            }
            if (!s.occurredAt().toInstant().equals(m.occurredAt().toInstant())) {
                out.add(new Divergence("occurred_at: " + e.getKey(),
                        s.occurredAt().toString(), m.occurredAt().toString()));
            }
            if (!java.util.Objects.equals(s.merchant(), m.merchant())) {
                out.add(new Divergence("merchant: " + e.getKey(),
                        String.valueOf(s.merchant()), String.valueOf(m.merchant())));
            }
            Set<String> sqlIds = new HashSet<>(s.sourceMessageIds());
            Set<String> mongoIds = new HashSet<>(m.sourceMessageIds());
            if (!sqlIds.equals(mongoIds)) {
                out.add(new Divergence("source_message_ids: " + e.getKey(),
                        sqlIds.toString(), mongoIds.toString()));
            }
        }

        // Mongo -> SQL (catches insertions in Mongo)
        for (var e : mongoByKey.entrySet()) {
            if (!sqlByKey.containsKey(e.getKey())) {
                out.add(new Divergence("presence: " + e.getKey(),
                        "<missing>", describe(e.getValue())));
            }
        }

        return out;
    }

    private static String naturalKey(NormalizedTxn t) {
        return t.accountLast4() + "|"
                + t.occurredAt().toInstant() + "|"
                + t.direction().name() + "|"
                + t.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String describe(NormalizedTxn t) {
        return t.accountLast4() + " " + t.direction() + " " + t.amount()
                + " " + t.merchant() + " @ " + t.occurredAt();
    }
    public record Divergence(String what, String inSql, String inDocuments) {}
}

