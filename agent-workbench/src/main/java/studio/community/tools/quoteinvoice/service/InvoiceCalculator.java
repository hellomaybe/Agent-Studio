package studio.community.tools.quoteinvoice.service;

import studio.community.tools.quoteinvoice.dto.CalcTotals;
import studio.community.tools.quoteinvoice.dto.LineItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** 纯 Java 计税，禁止 LLM 参与金额计算。 */
public final class InvoiceCalculator {

    private static final int SCALE = 2;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;

    private InvoiceCalculator() {}

    public static CalcTotals calculate(
            List<LineItem> items, BigDecimal taxRate, boolean taxInclusive) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("至少一行明细");
        }
        BigDecimal rate = taxRate == null ? BigDecimal.ZERO : taxRate;
        List<LineItem> normalized = new ArrayList<>();
        for (LineItem src : items) {
            if (src == null || src.desc == null || src.desc.isBlank()) {
                continue;
            }
            LineItem row = new LineItem();
            row.desc = src.desc.trim();
            row.qty = nz(src.qty, BigDecimal.ONE);
            row.unitPrice = nz(src.unitPrice, BigDecimal.ZERO);
            row.amount = row.qty.multiply(row.unitPrice).setScale(SCALE, ROUND);
            normalized.add(row);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("至少一行有效明细");
        }

        CalcTotals out = new CalcTotals();
        out.lineItems = normalized;
        if (taxInclusive) {
            BigDecimal total = BigDecimal.ZERO;
            for (LineItem row : normalized) {
                total = total.add(row.amount);
            }
            total = total.setScale(SCALE, ROUND);
            BigDecimal divisor = BigDecimal.ONE.add(rate);
            BigDecimal subtotal =
                    rate.compareTo(BigDecimal.ZERO) == 0
                            ? total
                            : total.divide(divisor, SCALE, ROUND);
            out.total = total;
            out.subtotal = subtotal;
            out.tax = total.subtract(subtotal).setScale(SCALE, ROUND);
        } else {
            BigDecimal subtotal = BigDecimal.ZERO;
            for (LineItem row : normalized) {
                subtotal = subtotal.add(row.amount);
            }
            subtotal = subtotal.setScale(SCALE, ROUND);
            BigDecimal tax = subtotal.multiply(rate).setScale(SCALE, ROUND);
            out.subtotal = subtotal;
            out.tax = tax;
            out.total = subtotal.add(tax).setScale(SCALE, ROUND);
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v, BigDecimal fallback) {
        return v == null ? fallback : v;
    }
}
