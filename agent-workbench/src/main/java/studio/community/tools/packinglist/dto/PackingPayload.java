package studio.community.tools.packinglist.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 072 打包清单运行入参。 */
public class PackingPayload {
    public String tripId;
    public String destination;
    public String startDate;
    public int days = 1;
    public int travelers = 1;
    public List<String> activities = new ArrayList<>();
    public String mode = "departure";
    public String templateId;
    public PackingOptions options = new PackingOptions();
    public List<MemberRef> members = new ArrayList<>();

    public static class PackingOptions {
        public Boolean laundryAvailable;
        public Boolean repeatBasics;
        public Double weightLimitKg;
        public Boolean international;
        public Boolean hasBaby;
    }

    public static class MemberRef {
        public Long id;
        public String name;
    }

    public boolean isInternational() {
        if (options != null && Boolean.TRUE.equals(options.international)) {
            return true;
        }
        return activities != null && activities.contains("international");
    }

    public boolean hasBaby() {
        if (options != null && Boolean.TRUE.equals(options.hasBaby)) {
            return true;
        }
        return activities != null && activities.contains("baby");
    }

    public boolean laundryAvailable() {
        return options != null && Boolean.TRUE.equals(options.laundryAvailable);
    }

    public boolean repeatBasics() {
        return options != null && Boolean.TRUE.equals(options.repeatBasics);
    }

    public double weightLimitKg() {
        if (options != null && options.weightLimitKg != null && options.weightLimitKg > 0) {
            return options.weightLimitKg;
        }
        return 23.0;
    }
}
