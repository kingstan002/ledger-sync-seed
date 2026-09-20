package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * MongoDB implementation of {@link DocumentStore}.
 *
 * Document shape (collection transactions):
 *   {
 *     _id: <sha256-truncated hash of natural key>,
 *     account_last4: "4821",
 *     occurred_at: ISODate(...),
 *     occurred_month: "2026-07",     // denormalized for query 1
 *     direction: "DEBIT",
 *     amount: "2499.50",             // stored as string, exact
 *     category: "SPEND",
 *     merchant: "AMAZON PAY",
 *     source_message_ids: [...]
 *   }
 *
 * Indexes:
 *   {account_last4: 1, occurred_month: 1, occurred_at: -1}  -> query 1
 *   {account_last4: 1, category: 1}                         -> query 2
 *   {source_message_ids: 1}                                 -> query 3 (multikey)
 */
public final class MongoDocumentStore implements DocumentStore {

    private final MongoClient client;
    private final MongoCollection<Document> txns;

    public MongoDocumentStore(String connectionString, String databaseName) {
        this.client = MongoClients.create(connectionString);
        MongoDatabase db = client.getDatabase(databaseName);
        this.txns = db.getCollection("transactions");
        ensureIndexes();
    }

    private void ensureIndexes() {
        txns.createIndex(Indexes.compoundIndex(
                Indexes.ascending("account_last4"),
                Indexes.ascending("occurred_month"),
                Indexes.descending("occurred_at")));
        txns.createIndex(Indexes.compoundIndex(
                Indexes.ascending("account_last4"),
                Indexes.ascending("category")));
        txns.createIndex(Indexes.ascending("source_message_ids"));
    }

    @Override
    public void save(NormalizedTxn t) {
        String id = naturalKeyId(t);
        Bson filter = Filters.eq("_id", id);
        Bson updates = Updates.combine(
                Updates.set("account_last4", t.accountLast4()),
                Updates.set("occurred_at", java.util.Date.from(t.occurredAt().toInstant())),
                Updates.set("occurred_month", monthOf(t.occurredAt())),
                Updates.set("direction", t.direction().name()),
                Updates.set("amount", t.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()),
                Updates.set("category", t.category().name()),
                Updates.set("merchant", t.merchant()),
                Updates.addEachToSet("source_message_ids",
                        new ArrayList<>(t.sourceMessageIds())));
        txns.updateOne(filter, updates, new UpdateOptions().upsert(true));
    }

    @Override
    public List<NormalizedTxn> transactionsForMonth(String accountLast4, String month) {
        List<NormalizedTxn> out = new ArrayList<>();
        Bson filter = Filters.and(
                Filters.eq("account_last4", accountLast4),
                Filters.eq("occurred_month", month));
        for (Document d : txns.find(filter).sort(new Document("occurred_at", -1))) {
            out.add(toTxn(d));
        }
        return out;
    }

    @Override
    public Map<Category, BigDecimal> totalsByCategory(String accountLast4) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, BigDecimal.ZERO.setScale(2));
        for (Document d : txns.find(Filters.eq("account_last4", accountLast4))) {
            Category c = Category.valueOf(d.getString("category"));
            BigDecimal amt = new BigDecimal(d.getString("amount"));
            out.put(c, out.get(c).add(amt));
        }
        return out;
    }

    @Override
    public Optional<NormalizedTxn> transactionForMessage(String messageId) {
        Document d = txns.find(Filters.eq("source_message_ids", messageId)).first();
        return d == null ? Optional.empty() : Optional.of(toTxn(d));
    }

    @Override
    public void close() {
        client.close();
    }

    // ---- helpers ----

    private static String monthOf(OffsetDateTime t) {
        return String.format("%04d-%02d", t.getYear(), t.getMonthValue());
    }

    private static String naturalKeyId(NormalizedTxn t) {
        String raw = t.accountLast4() + "|"
                + t.occurredAt().toInstant() + "|"
                + t.direction().name() + "|"
                + t.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static NormalizedTxn toTxn(Document d) {
        return new NormalizedTxn(
                d.getString("account_last4"),
                d.getDate("occurred_at").toInstant().atOffset(ZoneOffset.ofHoursMinutes(5, 30)),
                Direction.valueOf(d.getString("direction")),
                new BigDecimal(d.getString("amount")).setScale(2),
                Category.valueOf(d.getString("category")),
                d.getString("merchant"),
                d.getList("source_message_ids", String.class));
    }
    @Override
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : txns.find()) out.add(toTxn(d));
        return out;
    }
}