package studio.community.tools.titleassistant.dto;

import java.util.ArrayList;
import java.util.List;

/** 标题生成请求（Skill: cm.title_assistant.generate_ranked）。 */
public class TitleGenerateRequest {
    public String topic;
    public String platform = "douyin";
    public String tone = "curious";
    public Integer maxLen;
    public List<String> keywords = new ArrayList<>();
    public Integer candidateCount = 10;

    public void normalize() {
        if (topic != null) {
            topic = topic.strip();
        }
        if (platform == null || platform.isBlank()) {
            platform = "douyin";
        } else {
            platform = platform.strip().toLowerCase();
        }
        if (tone == null || tone.isBlank()) {
            tone = "curious";
        } else {
            tone = tone.strip().toLowerCase();
        }
        if (candidateCount == null || candidateCount < 3) {
            candidateCount = 10;
        } else if (candidateCount > 20) {
            candidateCount = 20;
        }
        if (keywords == null) {
            keywords = new ArrayList<>();
        }
        int platformMax = defaultMaxLen(platform);
        if (maxLen == null || maxLen <= 0) maxLen = platformMax;
        maxLen = Math.max(8, Math.min(maxLen, platformMax));
    }

    private static int defaultMaxLen(String platform) {
        return switch (platform == null ? "douyin" : platform) {
            case "douyin" -> 30;
            case "xiaohongshu" -> 20;
            case "bilibili" -> 80;
            case "wechat" -> 64;
            case "youtube" -> 100;
            case "zhihu" -> 50;
            default -> 30;
        };
    }
}
