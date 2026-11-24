package studio.community.tools.batchrename.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 批量改名规则引擎（纯本地字符串变换）。 */
final class RenamePatternEngine {

    private static final Pattern INDEX_PAD = Pattern.compile("\\{index:(\\d+)\\}");
    private static final String DEFAULT_SCREENSHOT = "{category}/{date}_{index:03}_{basename}{ext}";
    /** 模板末尾已经写死了扩展名（如 report.pdf），就不再自动补 {ext}。 */
    private static final Pattern LITERAL_EXT = Pattern.compile("\\.[A-Za-z0-9]{1,5}$");
    /** 日期格式只允许年月日与常见分隔符，避免用户写出时分秒或任意文本。 */
    private static final Pattern DATE_FORMAT_ALLOWED = Pattern.compile("[YyMmDd\\-_./ 年月日]+");

    private RenamePatternEngine() {}

    static String resolvePattern(String template, String pattern) {
        if (pattern != null && !pattern.isBlank()) {
            String p = pattern.trim();
            // 改名不改类型：模板里没有 {ext} 也没写死扩展名时，自动保留原扩展名
            if (!p.contains("{ext}") && !LITERAL_EXT.matcher(p).find()) {
                p = p + "{ext}";
            }
            return p;
        }
        if ("screenshot".equalsIgnoreCase(template)) {
            return DEFAULT_SCREENSHOT;
        }
        return DEFAULT_SCREENSHOT;
    }

    /**
     * 把用户写的日期格式（YYYYMMDD、YYYY-MM-DD、yyyy年MM月dd日 等）转成 {@link DateTimeFormatter}。
     *
     * <p>留空返回 {@code null}（按 yyyy-MM-dd）；只按年月日解释，小写 mm 也当月份。
     */
    static DateTimeFormatter parseDateFormat(String userFormat) {
        if (userFormat == null || userFormat.isBlank()) {
            return null;
        }
        String f = userFormat.trim();
        if (!DATE_FORMAT_ALLOWED.matcher(f).matches()) {
            throw new IllegalArgumentException("日期格式只支持年(YYYY)、月(MM)、日(DD)与分隔符，例如 YYYYMMDD 或 YYYY-MM-DD");
        }
        String javaFmt =
                f.replace('Y', 'y').replace('D', 'd').replace('m', 'M')
                        .replace("年", "'年'").replace("月", "'月'").replace("日", "'日'");
        if (!javaFmt.contains("y") && !javaFmt.contains("M") && !javaFmt.contains("d")) {
            throw new IllegalArgumentException("日期格式里至少要有年、月或日");
        }
        try {
            return DateTimeFormatter.ofPattern(javaFmt, Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("日期格式无效：" + f);
        }
    }

    static String apply(String pattern, int index, FileMeta meta) {
        return apply(pattern, index, meta, null, LocalDate.now());
    }

    /**
     * @param dateFormat {date} 的格式，null 为 yyyy-MM-dd
     * @param today 文件没有时间时 {date} 用的日期
     */
    static String apply(String pattern, int index, FileMeta meta, DateTimeFormatter dateFormat, LocalDate today) {
        LocalDate date = meta.date != null ? meta.date : today;
        String dateText = dateFormat == null ? date.toString() : date.format(dateFormat);
        String out = pattern;
        out = out.replace("{category}", safe(meta.category, "其他"));
        out = out.replace("{sourceApp}", safe(meta.sourceApp, "app"));
        out = out.replace("{title}", safe(meta.title, basename(meta.fileName)));
        out = out.replace("{date}", sanitizeToken(dateText));
        out = out.replace("{basename}", basename(meta.fileName));
        out = out.replace("{ext}", extension(meta.fileName));
        out = out.replace("{index}", String.valueOf(index));
        Matcher m = INDEX_PAD.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int width = Integer.parseInt(m.group(1));
            m.appendReplacement(sb, String.format(Locale.ROOT, "%0" + width + "d", index));
        }
        m.appendTail(sb);
        return sanitizePath(sb.toString());
    }

    private static String basename(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return sanitizeToken(base);
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot);
    }

    private static String safe(String v, String fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return sanitizeToken(v.trim());
    }

    private static String sanitizeToken(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private static String sanitizePath(String s) {
        return s.replaceAll("[\\\\:*?\"<>|]+", "_").replaceAll("/+", "/");
    }

    /** 平台 originalName 不含路径分隔符，apply 前将预览名转为可存储文件名。 */
    static String toStorageOriginalName(String patternName) {
        if (patternName == null || patternName.isBlank()) {
            return patternName;
        }
        return patternName.replace('\\', '_').replace('/', '_').replaceAll("_+", "_");
    }

    static final class FileMeta {
        final String fileId;
        final String fileName;
        final String category;
        final String sourceApp;
        final String title;
        /** 文件自身的日期（小程序选文件时带来的时间），为空时 {date} 取当天。 */
        final LocalDate date;

        FileMeta(String fileId, String fileName, String category, String sourceApp) {
            this(fileId, fileName, category, sourceApp, null, null);
        }

        FileMeta(String fileId, String fileName, String category, String sourceApp, String title) {
            this(fileId, fileName, category, sourceApp, title, null);
        }

        FileMeta(
                String fileId,
                String fileName,
                String category,
                String sourceApp,
                String title,
                LocalDate date) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.category = category;
            this.sourceApp = sourceApp;
            this.title = title != null && !title.isBlank() ? title : basename(fileName);
            this.date = date;
        }
    }
}
