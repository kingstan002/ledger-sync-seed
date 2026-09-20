package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class EmailParser implements MessageParser {



    private static final DateTimeFormatter EMAIL_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("EEE, dd MMM yyyy HH:mm:ss ")
            .appendOffset("+HHmm", "+0000")
            .toFormatter(Locale.ENGLISH);

    private static final Pattern DATE_HEADER =
            Pattern.compile("(?m)^Date:\\s*(.+?)\\s*$");


    private static final Pattern ACCOUNT =
            Pattern.compile("account ending\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);


    private static final Pattern DEBIT_CREDIT = Pattern.compile(
            "has been\\s+(credited|debited)\\s+with\\s+"
                    + "(?:INR|Rs\\.?|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);


    private static final Pattern MERCHANT =
            Pattern.compile("(?m)^Merchant\\s*/\\s*Remarks\\s*:\\s*(.+?)\\s*$",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();
        if (body == null || body.isBlank()) return Optional.empty();


        Matcher dc = DEBIT_CREDIT.matcher(body);
        if (!dc.find()) return Optional.empty();
        Direction direction = "credited".equalsIgnoreCase(dc.group(1))
                ? Direction.CREDIT
                : Direction.DEBIT;
        BigDecimal amount = new BigDecimal(dc.group(2).replace(",", ""))
                .setScale(2);


        Matcher acct = ACCOUNT.matcher(body);
        if (!acct.find()) return Optional.empty();
        String accountLast4 = acct.group(1);


        Matcher dh = DATE_HEADER.matcher(body);
        if (!dh.find()) return Optional.empty();
        OffsetDateTime occurredAt;
        try {
            occurredAt = OffsetDateTime.parse(dh.group(1).trim(), EMAIL_DATE);
        } catch (Exception e) {
            return Optional.empty();
        }


        Matcher merch = MERCHANT.matcher(body);
        String merchant = merch.find() ? merch.group(1).trim() : null;


        BigDecimal statedBalance = null;

        return Optional.of(new ParsedTxn(
                accountLast4,
                occurredAt,
                direction,
                amount,
                merchant,
                statedBalance,
                m.messageId()));
    }
}