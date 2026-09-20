package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice - "Rs.", "Rs ", "INR " - and strips
 * the thousands separators before handing back a BigDecimal.
 */
public final class Amounts {

    private Amounts() {}


    private static final Pattern AMOUNT =
            Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");


    private static final Pattern BALANCE_MARKER = Pattern.compile(
            "\\b(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)\\b\\s*:?",
            Pattern.CASE_INSENSITIVE);


    private static final Pattern BALANCE = Pattern.compile(
            "\\b(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)\\b\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);


    public static BigDecimal first(String body) {
        if (body == null) return null;


        Matcher bm = BALANCE_MARKER.matcher(body);
        String transactionPart = bm.find() ? body.substring(0, bm.start()) : body;

        Matcher m = AMOUNT.matcher(transactionPart);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /** The balance the bank quoted, if it quoted one. */
    public static BigDecimal statedBalance(String body) {
        if (body == null) return null;
        Matcher m = BALANCE.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    private static BigDecimal toDecimal(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }
}