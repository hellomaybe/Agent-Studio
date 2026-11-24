package studio.community.tools.titleassistant.service;

import studio.community.tools.titleassistant.dto.RuleFlag;
import studio.community.tools.titleassistant.dto.TitleGenerateRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 平台规则引擎：长度、违禁词、关键词覆盖。 */
public final class TitleRuleEngine {

    private static final Pattern EMOJI =
            Pattern.compile("[\\p{So}\\p{Sk}\\u2600-\\u27BF\\uD83C-\\uDBFF\\uDC00-\\uDFFF]");

    private static final List<String> ABSOLUTE_WORDS =
            List.of(
                    "最好", "顶级", "唯一", "100%", "绝对", "全网最低", "国家级",
                    "保证赚钱", "稳赚", "零风险", "永久有效", "无门槛收益");

    private static final List<String> MEDICAL_WORDS = List.of("治愈", "根治", "药到病除", "无副作用");

    private TitleRuleEngine() {}

    public static List<RuleFlag> validate(String text, TitleGenerateRequest req) {
        List<RuleFlag> flags = new ArrayList<>();
        if (text == null || text.isBlank()) {
            flags.add(new RuleFlag("EMPTY", "error", "标题为空"));
            return flags;
        }
        String trimmed = text.strip();
        int len = trimmed.length();
        int maxLen = req.maxLen != null ? req.maxLen : TitlePlatformRules.defaultMaxLen(req.platform);

        if (len <= maxLen) {
            flags.add(new RuleFlag("LEN_OK", "ok", "字数 " + len + "/" + maxLen));
        } else {
            flags.add(new RuleFlag("LEN_EXCEED", "error", "超出字数上限 " + len + "/" + maxLen));
        }

        for (String word : ABSOLUTE_WORDS) {
            if (trimmed.contains(word)) {
                flags.add(new RuleFlag("ABSOLUTE_CLAIM", "error", "含绝对化用语：" + word));
            }
        }
        if (trimmed.matches(".*((行业|全国|全网|排名)第一|第一名|第一品牌).*")) {
            flags.add(new RuleFlag("ABSOLUTE_CLAIM", "error", "含绝对化排名表述"));
        }

        String platform = TitlePlatformRules.normalize(req.platform);
        if ("xiaohongshu".equals(platform)) {
            for (String word : MEDICAL_WORDS) {
                if (trimmed.contains(word)) {
                    flags.add(new RuleFlag("MEDICAL_CLAIM", "error", "含医疗功效词：" + word));
                }
            }
        }

        if ("bilibili".equals(platform) && containsProvocative(trimmed)) {
            flags.add(new RuleFlag("PROVOCATIVE", "warn", "可能引战，建议软化表述"));
        }

        if (EMOJI.matcher(trimmed).find()) {
            if ("bilibili".equals(platform)) {
                flags.add(new RuleFlag("EMOJI_WARN", "warn", "B站标题 emoji 使用需谨慎"));
            } else {
                flags.add(new RuleFlag("EMOJI_OK", "ok", "含 emoji"));
            }
        }

        if (req.keywords != null && !req.keywords.isEmpty()) {
            long hit = req.keywords.stream()
                    .filter(k -> k != null && !k.isBlank())
                    .filter(k -> trimmed.contains(k.strip()))
                    .count();
            if (hit == 0) {
                flags.add(new RuleFlag("KW_MISSING", "warn", "未覆盖目标关键词"));
            } else {
                flags.add(new RuleFlag("KW_OK", "ok", "关键词覆盖 " + hit + "/" + req.keywords.size()));
            }
        }

        if (flags.stream().noneMatch(f -> "error".equals(f.level))) {
            flags.add(0, new RuleFlag("PASS", "ok", "通过平台规则校验"));
        }
        return flags;
    }

    public static boolean hasError(List<RuleFlag> flags) {
        return flags.stream().anyMatch(f -> "error".equals(f.level));
    }

    private static boolean containsProvocative(String text) {
        return text.contains("垃圾") || text.contains("傻") || text.contains("骗");
    }
}
