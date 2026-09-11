package org.example.airplanner.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** An amount of money in a given currency. */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    public static Money of(double amount, String currency) {
        return new Money(BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP), currency);
    }

    public Money plus(Money other) {
        if (other == null) {
            return this;
        }
        if (!Objects.equals(currency, other.currency)) {
            throw new IllegalArgumentException("Cannot add " + currency + " and " + other.currency);
        }
        return new Money(amount.add(other.amount), currency);
    }

    public Money times(int factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    public Money dividedBy(int divisor) {
        return new Money(amount.divide(BigDecimal.valueOf(Math.max(divisor, 1)), 2, RoundingMode.HALF_UP), currency);
    }

    public double doubleValue() {
        return amount.doubleValue();
    }

    @Override
    public int compareTo(Money o) {
        return amount.compareTo(o.amount);
    }

    @Override
    public String toString() {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + currency;
    }
}
