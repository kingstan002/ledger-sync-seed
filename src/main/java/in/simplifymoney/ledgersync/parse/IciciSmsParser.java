package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Two formats appear in the corpus, both from the same sender:
 *
 *   V1: Acct XX9075 is debited with INR 333.33 on 04/07/2026 07:54. Info: SWIGGY.
 *
 *   V2: ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; UPI/BARBER
 *       ref no 154245459403. BalAvl Rs 52,841.30
 *
 * V2 uses abbreviated hyphens in the date, Dr/Cr instead of debited/credited,
 * and - crucially - writes whole-rupee amounts without a decimal point.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";


    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");


    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4})\\s+"
                    + "(?<dir>Dr|Cr)\\s+"
                    + "(?:INR|Rs\\.?)\\s*(?<amt>[0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s+"
                    + "on\\s+(?<when>\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2})\\s*;\\s*"
                    + "(?<merchant>[^;]+?)\\s+ref no",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher v2 = V2.matcher(m.body());
        if (v2.find()) {
            BigDecimal amount = new BigDecimal(v2.group("amt").replace(",", ""))
                    .setScale(2);
            OffsetDateTime at = Dates.ist(v2.group("when"));
            if (at == null) return Optional.empty();
            Direction d = "Dr".equalsIgnoreCase(v2.group("dir"))
                    ? Direction.DEBIT
                    : Direction.CREDIT;
            return Optional.of(new ParsedTxn(
                    v2.group("acct"), at, d, amount,
                    v2.group("merchant").trim(),
                    Amounts.statedBalance(m.body()),
                    m.messageId()));
        }

        Matcher v1 = V1.matcher(m.body());
        if (!v1.find()) return Optional.empty();

        BigDecimal amount = Amounts.first(m.body());
        OffsetDateTime at = Dates.ist(v1.group("when"));
        if (amount == null || at == null) return Optional.empty();

        Direction d = "debited".equals(v1.group("dir"))
                ? Direction.DEBIT
                : Direction.CREDIT;
        return Optional.of(new ParsedTxn(
                v1.group("acct"), at, d, amount,
                v1.group("merchant").trim(),
                Amounts.statedBalance(m.body()),
                m.messageId()));
    }
}