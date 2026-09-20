package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.model.TxnKey;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * Pipeline:
 *   1. Parse each message into a ParsedTxn (or skip it as non-transaction).
 *   2. Merge ParsedTxns that describe the same transaction into one row.
 *   3. Classify each merged txn: MICRO, SPEND, INCOME (provisional).
 *   4. Cross-match debits and credits to find TRANSFERs between own accounts.
 *   5. Persist.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);


        List<ParsedTxn> parsed = new ArrayList<>();
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                System.out.println("SKIP|" + m.sender() + "|" + m.messageId()
                        + "|" + m.body().replace("\n", " "));
                continue;
            }
            parsed.add(p.get());
        }


        Map<TxnKey, MergedTxn> merged = new LinkedHashMap<>();
        for (ParsedTxn p : parsed) {
            TxnKey key = new TxnKey(
                    p.accountLast4(),
                    p.occurredAt().toInstant(),
                    p.amount().setScale(2),
                    p.direction());
            merged.computeIfAbsent(key, k -> new MergedTxn(p))
                    .addSource(p.sourceMessageId());
        }


        Map<TxnKey, NormalizedTxn> byKey = new LinkedHashMap<>();
        for (Map.Entry<TxnKey, MergedTxn> e : merged.entrySet()) {
            byKey.put(e.getKey(), e.getValue().toNormalizedTxn());
        }


        Set<String> ownAccounts = Set.of("4821", "9075", "3310");


        java.time.Duration transferWindow = java.time.Duration.ofMinutes(3);

        Set<TxnKey> transferKeys = new HashSet<>();
        List<TxnKey> keys = new ArrayList<>(byKey.keySet());

        for (int i = 0; i < keys.size(); i++) {
            NormalizedTxn a = byKey.get(keys.get(i));
            if (a.direction() != Direction.DEBIT) continue;
            if (!ownAccounts.contains(a.accountLast4())) continue;

            for (int j = 0; j < keys.size(); j++) {
                if (i == j) continue;
                NormalizedTxn b = byKey.get(keys.get(j));
                if (b.direction() != Direction.CREDIT) continue;
                if (!ownAccounts.contains(b.accountLast4())) continue;
                if (a.accountLast4().equals(b.accountLast4())) continue;
                if (!a.amount().equals(b.amount())) continue;

                java.time.Duration gap = java.time.Duration.between(
                        a.occurredAt().toInstant(), b.occurredAt().toInstant()).abs();
                if (gap.compareTo(transferWindow) > 0) continue;


                if (!looksLikeTransfer(a) && !looksLikeTransfer(b)) continue;

                transferKeys.add(keys.get(i));
                transferKeys.add(keys.get(j));
            }
        }


        for (Map.Entry<TxnKey, NormalizedTxn> e : byKey.entrySet()) {
            NormalizedTxn t = e.getValue();
            Category finalCategory = transferKeys.contains(e.getKey())
                    ? Category.TRANSFER
                    : t.category();
            NormalizedTxn out = new NormalizedTxn(
                    t.accountLast4(),
                    t.occurredAt(),
                    t.direction(),
                    t.amount(),
                    finalCategory,
                    t.merchant(),
                    t.sourceMessageIds());
            store.save(out);
        }

        return new Stats(messages.size(), byKey.size(), skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------

    private static final class MergedTxn {
        private final ParsedTxn first;
        private final Set<String> sourceIds = new LinkedHashSet<>();

        MergedTxn(ParsedTxn first) {
            this.first = first;
            addSource(first.sourceMessageId());
        }

        void addSource(String messageId) {
            if (messageId != null && !messageId.isBlank()) {
                sourceIds.add(messageId);
            }
        }

        NormalizedTxn toNormalizedTxn() {
            Category c = decideCategory(first);
            return new NormalizedTxn(
                    first.accountLast4(),
                    first.occurredAt(),
                    first.direction(),
                    first.amount(),
                    c,
                    first.merchant(),
                    List.copyOf(sourceIds));
        }

        private static Category decideCategory(ParsedTxn p) {
            if (p.direction() == Direction.DEBIT
                    && isUpi(p.merchant())
                    && p.amount().compareTo(new BigDecimal("100.00")) <= 0) {
                return Category.MICRO;
            }
            return p.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
        }

        private static boolean isUpi(String merchant) {
            if (merchant == null) return false;
            String m = merchant.toUpperCase(Locale.ROOT);
            return m.startsWith("UPI/") || m.startsWith("UPI ");
        }
    }



    private static boolean looksLikeTransfer(NormalizedTxn t) {
        String m = t.merchant();
        if (m == null) return false;
        String u = m.toUpperCase(Locale.ROOT);
        return u.contains("IMPS")
                || u.contains("NEFT")
                || u.contains("RTGS")
                || u.contains("TRANSFER")
                || u.contains("P2A")
                || u.contains("P2P");
    }



    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}