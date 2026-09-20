package in.simplifymoney.ledgersync.model;

import java.math.BigDecimal;

public record TxnKey(String accountLast4,
                     java.time.Instant occurredAt,
                     BigDecimal amount,
                     Direction direction)  {

}
