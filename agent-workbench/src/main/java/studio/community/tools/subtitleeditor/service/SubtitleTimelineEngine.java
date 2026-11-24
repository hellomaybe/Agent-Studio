package studio.community.tools.subtitleeditor.service;

import studio.community.support.subtitle.model.SubtitleCue;
import java.util.ArrayList;
import java.util.List;

/** 字幕时间轴分析与确定性修复：检测非法时长、重叠和大间隔，修复时保持文本不变。 */
public class SubtitleTimelineEngine {

    public Analysis analyze(List<SubtitleCue> cues) {
        List<Issue> issues = new ArrayList<>();
        int previousEnd = 0;
        int duration = 0;
        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue cue = cues.get(i);
            if (cue.startMs() < 0 || cue.endMs() <= cue.startMs()) {
                issues.add(new Issue("invalid_duration", cue.seq(), "结束时间必须晚于开始时间"));
            }
            if (i > 0 && cue.startMs() < previousEnd) {
                issues.add(new Issue("overlap", cue.seq(), "与上一条重叠 " + (previousEnd - cue.startMs()) + "ms"));
            } else if (i > 0 && cue.startMs() - previousEnd > 2000) {
                issues.add(new Issue("large_gap", cue.seq(), "与上一条间隔 " + (cue.startMs() - previousEnd) + "ms"));
            }
            previousEnd = Math.max(previousEnd, cue.endMs());
            duration = Math.max(duration, cue.endMs());
        }
        long blocking = issues.stream().filter(i -> !"large_gap".equals(i.type())).count();
        return new Analysis(issues, duration, blocking == 0);
    }

    public List<SubtitleCue> repair(List<SubtitleCue> cues) {
        List<SubtitleCue> out = new ArrayList<>();
        int previousEnd = 0;
        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue cue = cues.get(i);
            int start = Math.max(0, cue.startMs());
            if (i > 0 && start < previousEnd) start = previousEnd + 1;
            int end = Math.max(start + 300, cue.endMs());
            out.add(new SubtitleCue(i + 1, start, end, cue.text(), cue.confidence(), cue.lockedTermsJson()));
            previousEnd = end;
        }
        return out;
    }

    public record Issue(String type, int seq, String message) {}
    public record Analysis(List<Issue> issues, int durationMs, boolean passed) {}
}
