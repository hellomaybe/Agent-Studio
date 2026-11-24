package studio.lab;

import java.util.*;
import java.util.regex.Pattern;

/** Versioned executable graph. Named ports define control flow, including the bounded retry edge. */
public record AgentGraph(String prompt, String library, String retrieval, int chunkSize, int topK, double threshold,
        int maxCalls, int maxTokens, double budget, boolean memory, String rule, String binding, boolean nativeTool,
        Map<String,String> edges, Map<String,List<Integer>> positions, String collection, String projection) {
    static final Map<String,List<String>> PORTS=Map.of(
        "start.next",List.of("retrieve","model","tool"), "retrieve.next",List.of("model","tool"),
        "model.next",List.of("tool"),"tool.next",List.of("check"),
        "check.pass",List.of("store","review"),"check.fail",List.of("model","review"),"review.approve",List.of("store"));
    static AgentGraph initial(Features.Feature f) {
        String instruction=switch(f.id()){
          case "quote-invoice"->"提取报价明细，每行严格输出 项目 | 数量 | 不含税单价。保留原文中的数值，不编造、不计算金额。";
          case "batch-rename"->"从请求中提取原文件名，每行一个，保留扩展名。不要自行改名。";
          case "packing-list"->"从行程中提取出行天数，只输出一个 1 至 90 的整数。";
          case "subtitle-editor"->"整理原字幕，每行输出 开始毫秒 | 结束毫秒 | 字幕。保留原文时间，交给工具修复。";
          default->"根据主题与检索到的发布规范撰写一个简短中文标题。只输出标题，无前缀，无解释。";
        };
        String doc=switch(f.id()){
          case "quote-invoice"->"报价规范 | 单价为不含税价；金额、税额与合计由报价计算器根据已发布税率计算。";
          case "batch-rename"->"文件交付规范 | 保留每个原文件的扩展名，命名模板与序号由后台生成。";
          case "packing-list"->"出行打包规范 | 出行天数按完整行程确定。换洗衣物数量由天数和途中洗衣条件决定。";
          case "subtitle-editor"->"字幕审校规范 | 时间单位为毫秒，相邻字幕避免重叠；修复模式保留字幕原文。";
          default->"标题编辑规范 | 标题围绕用户的主题，避免最好、顶级、唯一等绝对化用语。\n小红书标题 | 自然、简洁，标题最多20字。\n哔哩哔哩标题 | 避免引战，准确表达视频内容。";
        };
        var links=new LinkedHashMap<String,String>(); PORTS.forEach((k,v)->links.put(k,v.get(0)));
        links.put("check.pass",f.id().equals("batch-rename")?"review":"store");
        if(AgentModel.env("BASE_URL","").isBlank() || AgentModel.env("MODEL","").isBlank()){
            links.put("retrieve.next","tool");
            links.put("check.fail","review");
        }
        String index=AgentModel.env("EMBED_URL","").isBlank()?"lexical":"dense";
        return new AgentGraph(instruction,doc,index,200,3,0,2,160,0.05,false,f.options().get(0).key(),"auto",false,links,Map.of(),f.id()+"-results","full");
    }
    void validate(Features.Feature f){
        if(!Set.of("dense","lexical").contains(Objects.toString(retrieval,""))||prompt==null||prompt.isBlank()||prompt.length()>2000||library==null||library.length()>6000
           ||chunkSize<40||chunkSize>600||topK<1||topK>5||!Double.isFinite(threshold)||threshold<0||threshold>1
           ||maxCalls<1||maxCalls>5||maxTokens<16||maxTokens>512||!Double.isFinite(budget)||budget<0||budget>5
           ||f.options().stream().noneMatch(o->o.key().equals(rule)))throw new IllegalArgumentException("节点参数超出允许范围");
        if(edges==null||!edges.keySet().equals(PORTS.keySet())||PORTS.entrySet().stream().anyMatch(e->!e.getValue().contains(edges.get(e.getKey()))))
            throw new IllegalArgumentException("连线不完整或不兼容");
        if(binding!=null&&!Set.of("auto","input","upstream").contains(binding))throw new IllegalArgumentException("输入绑定无效");
        if(collection!=null&&!collection.matches("[a-z][a-z0-9_-]{0,47}")||projection!=null&&!Set.of("full","summary").contains(projection))throw new IllegalArgumentException("存储配置无效");
        if(positions==null||positions.size()>7||positions.entrySet().stream().anyMatch(e->!List.of("start","retrieve","model","tool","check","review","store").contains(e.getKey())||e.getValue()==null||e.getValue().size()!=2||e.getValue().stream().anyMatch(i->i==null||i<0||i>1800)))throw new IllegalArgumentException("节点位置无效");
    }
    public record Hit(String source,int chunk,String text,double score){}
    // Local sparse vector retrieval over the published knowledge corpus. No provider billing for indexing.
    List<Hit> retrieve(String query){
        var q=terms(query);var hits=new ArrayList<Hit>();
        for(String line:library.lines().toList()){
            if(line.isBlank())continue;
            String[] fields=line.split("[|｜]",2);
            String source=fields.length==2?fields[0].strip():"工作区文档";
            String body=fields[fields.length-1].strip();
            for(int pos=0,part=1;pos<body.length();pos+=chunkSize,part++){
                String chunk=body.substring(pos,Math.min(body.length(),pos+chunkSize));
                var v=terms(source+" "+chunk);double dot=0;
                for(var e:q.entrySet())dot+=e.getValue()*v.getOrDefault(e.getKey(),0);
                double score=dot/(norm(q)*norm(v));
                if(score>0&&score>=threshold)hits.add(new Hit(source,part,chunk,score));
            }
        }
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()).limit(topK).toList();
    }
    private static final Pattern WORD=Pattern.compile("[a-z0-9]+|[\\p{IsHan}]");
    private static Map<String,Integer> terms(String s){var m=new HashMap<String,Integer>();WORD.matcher(s.toLowerCase(Locale.ROOT)).results().forEach(r->m.merge(r.group(),1,Integer::sum));return m;}
    private static double norm(Map<String,Integer> v){return Math.max(1,Math.sqrt(v.values().stream().mapToDouble(i->(double)i*i).sum()));}
}
