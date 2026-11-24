package studio.community.tools.quoteinvoice.dto;

import java.math.BigDecimal;
import java.util.List;

public class CalcTotals {
    public BigDecimal subtotal;
    public BigDecimal tax;
    public BigDecimal total;
    public List<LineItem> lineItems;
}
