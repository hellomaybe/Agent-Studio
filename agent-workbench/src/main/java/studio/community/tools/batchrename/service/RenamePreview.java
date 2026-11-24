package studio.community.tools.batchrename.service;

public final class RenamePreview {
    public static String name(String pattern, int index, String file) {
        var meta = new RenamePatternEngine.FileMeta("", file, "", "");
        return RenamePatternEngine.apply(RenamePatternEngine.resolvePattern(null, pattern), index, meta);
    }
}
