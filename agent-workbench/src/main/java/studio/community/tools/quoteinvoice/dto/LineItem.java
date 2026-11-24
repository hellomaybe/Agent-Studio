package studio.community.tools.quoteinvoice.dto;

import java.math.BigDecimal;

public class LineItem {
    public String desc;
    public BigDecimal qty;
    public BigDecimal unitPrice;
    public BigDecimal amount;
}
