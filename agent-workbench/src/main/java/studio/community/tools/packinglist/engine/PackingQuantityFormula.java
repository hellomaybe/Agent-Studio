package studio.community.tools.packinglist.engine;

import studio.community.tools.packinglist.dto.PackingPayload;
import java.util.Map;

/** 打包数量公式求值。 */
public final class PackingQuantityFormula {

    private PackingQuantityFormula() {}

    public static int eval(String formula, PackingPayload payload) {
        if (formula == null || formula.isBlank()) {
            return 1;
        }
        String f = formula.trim();
        int days = Math.max(1, payload.days);
        int travelers = Math.max(1, payload.travelers);

        return switch (f) {
            case "1" -> 1;
            case "2" -> 2;
            case "travelers" -> travelers;
            case "days" -> days;
            case "days-1" -> Math.max(1, days - 1);
            case "top_qty" -> topQty(payload, days);
            case "underwear_qty" -> underwearQty(payload, days);
            case "baby_diaper_qty" -> Math.max(5, days * 3);
            case "ceil(days/3)" -> (int) Math.ceil(days / 3.0);
            default -> {
                if (f.startsWith("max(")) {
                    yield parseMax(f, payload, days, travelers);
                }
                yield 1;
            }
        };
    }

    private static int topQty(PackingPayload payload, int days) {
        if (payload.laundryAvailable() || payload.repeatBasics()) {
            return Math.max(2, (int) Math.ceil(days / 3.0));
        }
        return Math.max(2, days);
    }

    private static int underwearQty(PackingPayload payload, int days) {
        if (payload.laundryAvailable()) {
            return Math.max(2, (int) Math.ceil(days / 2.0));
        }
        if (payload.repeatBasics()) {
            return Math.max(3, (int) Math.ceil(days / 2.0));
        }
        return Math.max(3, days);
    }

    private static int parseMax(String f, PackingPayload payload, int days, int travelers) {
        // max(1, travelers-1)
        if (f.contains("travelers-1")) {
            return Math.max(1, travelers - 1);
        }
        return 1;
    }
}
