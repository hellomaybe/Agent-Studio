package studio.lab;

import studio.community.tools.batchrename.service.RenamePreview;
import studio.community.tools.quoteinvoice.service.InvoiceCalculator;
import studio.community.tools.quoteinvoice.dto.LineItem;
import studio.community.tools.packinglist.engine.PackingQuantityFormula;
import studio.community.tools.packinglist.dto.PackingPayload;
import studio.community.tools.titleassistant.service.TitleRuleEngine;
import studio.community.tools.titleassistant.dto.TitleGenerateRequest;
import studio.community.support.subtitle.model.SubtitleCue;
import studio.community.tools.subtitleeditor.service.SubtitleTimelineEngine;
import java.math.BigDecimal;
import java.util.*;

public final class Features {
    public record Option(String key, String label) {}
    public record Feature(String id,String title,String section,String action,String rule,String input,String hint,String example,String engine,List<Option> options) {}
    private static Option o(String key,String text){return new Option(key,text);}
    static final List<Feature> all=List.of(
      new Feature("quote-invoice","报价计税","商务","计算报价","报价税率","报价明细","每行填写「项目 | 数量 | 不含税单价」。","设计服务 | 2 | 350\n印刷样册 | 3 | 100","InvoiceCalculator.calculate",List.of(o("6","6%"),o("13","13%"),o("0","0%"))),
      new Feature("batch-rename","批量改名","文件","预览新文件名","命名模板","原文件名","每行一个文件名，预览保留扩展名。","封面设计.png\n产品目录.pdf","RenamePatternEngine.apply",List.of(o("archive","归档_001_原名"),o("delivery","交付_原名_01"))),
      new Feature("packing-list","行李清单","出行","计算打包数量","途中洗衣","出行天数","输入 1 至 90 天，计算一人的换洗衣物。","6","PackingQuantityFormula.eval",List.of(o("no","无法洗衣"),o("yes","可以洗衣"))),
      new Feature("subtitle-editor","字幕时间轴","视频","处理字幕","时间轴处理","字幕条目","每行填写「开始毫秒 | 结束毫秒 | 字幕」。","0 | 1800 | 先选素材\n1500 | 2500 | 添加转场","SubtitleTimelineEngine.analyze / repair",List.of(o("check","仅检查"),o("repair","检查并修复"))),
      new Feature("title-assistant","标题检查","内容","检查标题","发布平台","待发布标题","输入自己的标题，检查长度、用词及平台规则。","一份通勤清单，把周一的早晨留给自己","TitleRuleEngine.validate",List.of(o("xiaohongshu","小红书"),o("bilibili","哔哩哔哩"),o("wechat","微信公众号")))
    );
    record Result(String summary,List<String> columns,List<List<String>> rows) {}
    static Result run(String id,String rule,String text) {
        if (text.isBlank() || text.length()>4000 || text.lines().count()>50) throw new IllegalArgumentException("请输入 1 至 4000 字，最多 50 行。");
        List<List<String>> rows=new ArrayList<>();
        var lines=text.lines().filter(s->!s.isBlank()).toList();
        switch(id) {
          case "quote-invoice" -> {
            var items=new ArrayList<LineItem>();
            for(String line:lines){var f=parts(line);var item=new LineItem();item.desc=f[0];item.qty=money(f[1]);item.unitPrice=money(f[2]);items.add(item);}
            var sum=InvoiceCalculator.calculate(items,new BigDecimal(rule).movePointLeft(2),false);
            for(var item:sum.lineItems) rows.add(List.of(item.desc,item.qty.toPlainString(),item.amount.toPlainString()));
            return new Result("价税合计 ¥"+sum.total+"（未税 "+sum.subtotal+"，税额 "+sum.tax+"）",List.of("项目","数量","未税金额"),rows);
          }
          case "batch-rename" -> {
            int i=0;String pattern=rule.equals("archive")?"归档_{index:03}_{basename}{ext}":"交付_{basename}_{index:02}{ext}";
            for(String name:lines){if(name.contains("/")||name.contains("\\")||name.length()>128)throw new IllegalArgumentException("请输入文件名，每行最多128字。");rows.add(List.of(name,RenamePreview.name(pattern,++i,name)));}
            return new Result(i+" 个文件名已生成",List.of("原文件名","新文件名"),rows);
          }
          case "packing-list" -> {
            var p=new PackingPayload();p.days=num(text.strip(),1,90);p.options.laundryAvailable=rule.equals("yes");
            rows.add(List.of("上衣",String.valueOf(PackingQuantityFormula.eval("top_qty",p))+" 件"));
            rows.add(List.of("内衣",String.valueOf(PackingQuantityFormula.eval("underwear_qty",p))+" 件"));
            return new Result(p.days+" 天 · 单人换洗清单",List.of("物品","建议数量"),rows);
          }
          case "subtitle-editor" -> {
            var cues=new ArrayList<SubtitleCue>();
            for(String line:lines){var f=parts(line);cues.add(new SubtitleCue(cues.size()+1,num(f[0],0,86400000),num(f[1],0,86400000),f[2],1));}
            var engine=new SubtitleTimelineEngine();var findings=engine.analyze(cues);
            var adjusted=rule.equals("repair")?engine.repair(cues):cues;
            for(var cue:adjusted){var notes=findings.issues().stream().filter(v->v.seq()==cue.seq()).map(v->v.message()).toList();
              rows.add(List.of(cue.text(),cue.startMs()+" → "+cue.endMs(),String.join("；",notes)));}
            return new Result("原时间轴发现 "+findings.issues().size()+" 项问题 · "+(rule.equals("repair")?"已运行修复":"检查完成"),List.of("字幕","输出区间（毫秒）","原始问题"),rows);
          }
          case "title-assistant" -> {
            var request=new TitleGenerateRequest();request.platform=rule;request.normalize();
            var flags=TitleRuleEngine.validate(text,request);
            for(var f:flags)rows.add(List.of(switch(f.level){case "error"->"需调整";case "warn"->"提醒";default->"通过";},f.message));
            return new Result(TitleRuleEngine.hasError(flags)?"标题需要调整":"标题通过规则检查",List.of("状态","检查结果"),rows);
          }
          default -> throw new IllegalArgumentException("功能不存在");
        }
    }
    static String[] parts(String line){var f=line.split("\\s*[|｜]\\s*",3);if(f.length!=3||Arrays.stream(f).anyMatch(String::isBlank))throw new IllegalArgumentException("每行需要三列，请使用 | 分隔。");return f;}
    static BigDecimal money(String s){if(!s.matches("[0-9]{1,8}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("数量和单价应为非负数，最多两位小数。");return new BigDecimal(s);}
    static int num(String s,int min,int max){try{int n=Integer.parseInt(s);if(n>=min&&n<=max)return n;}catch(NumberFormatException ignored){}throw new IllegalArgumentException("数值必须在 "+min+" 至 "+max+" 之间。");}
}
