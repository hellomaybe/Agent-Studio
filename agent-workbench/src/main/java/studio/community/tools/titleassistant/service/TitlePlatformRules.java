package studio.community.tools.titleassistant.service;

/** 各平台标题规则 preset。 */
public final class TitlePlatformRules {

    private TitlePlatformRules() {}

    public static int defaultMaxLen(String platform) {
        return switch (normalize(platform)) {
            case "douyin" -> 30;
            case "xiaohongshu" -> 20;
            case "bilibili" -> 80;
            case "wechat" -> 64;
            case "youtube" -> 100;
            case "zhihu" -> 50;
            default -> 30;
        };
    }

    public static String styleHint(String platform) {
        return switch (normalize(platform)) {
            case "douyin" -> "前15字必须有强hook与冲突感，口语化，适合短视频推荐";
            case "xiaohongshu" -> "可含emoji与感叹号，种草测评感，关键词前置";
            case "bilibili" -> "可较长，可用括号补充梗或副信息";
            case "wechat" -> "可反转、有深度，适合公众号阅读";
            case "youtube" -> "SEO关键词前置，可中英混排";
            case "zhihu" -> "偏提问式或观点式，理性但有好奇缺口";
            default -> "中文自媒体爆款风格";
        };
    }

    public static String normalize(String platform) {
        if (platform == null || platform.isBlank()) {
            return "douyin";
        }
        return platform.strip().toLowerCase();
    }
}
