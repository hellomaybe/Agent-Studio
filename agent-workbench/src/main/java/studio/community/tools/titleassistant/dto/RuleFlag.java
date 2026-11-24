package studio.community.tools.titleassistant.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/** 平台规则校验标记。 */
public class RuleFlag {
    public String code;
    public String level;
    public String message;

    public RuleFlag() {}

    public RuleFlag(String code, String level, String message) {
        this.code = code;
        this.level = level;
        this.message = message;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("level", level);
        if (message != null) {
            m.put("message", message);
        }
        return m;
    }
}
