package studio.community.support.subtitle.model;

/** 单条字幕 cue（时间轴 + 文本 + 置信度）。 */
public record SubtitleCue(
        int seq,
        int startMs,
        int endMs,
        String text,
        double confidence,
        String lockedTermsJson) {

    public SubtitleCue(int seq, int startMs, int endMs, String text, double confidence) {
        this(seq, startMs, endMs, text, confidence, null);
    }

    public SubtitleCue withText(String newText) {
        return new SubtitleCue(seq, startMs, endMs, newText, confidence, lockedTermsJson);
    }

    public SubtitleCue withTiming(int newStart, int newEnd) {
        return new SubtitleCue(seq, newStart, newEnd, text, confidence, lockedTermsJson);
    }
}
