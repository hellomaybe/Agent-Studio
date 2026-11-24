package studio.lab;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import static studio.lab.AgentModel.JSON;

/** Only a server-configured loopback embedding service is allowed for local indexing. */
final class AgentKnowledge {
    final String name=AgentModel.env("EMBED_MODEL","");
    final String endpoint=AgentModel.env("EMBED_URL","");
    final OpenAiEmbeddingModel model;
    AgentKnowledge(){
        if(name.isBlank()||endpoint.isBlank()){model=null;return;}
        var uri=URI.create(endpoint);
        if(!List.of("localhost","127.0.0.1","[::1]").contains(Objects.toString(uri.getHost(),""))||!Set.of("http","https").contains(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null)throw new IllegalArgumentException("向量服务须使用专用本机地址");
        var options=OpenAiEmbeddingOptions.builder().baseUrl(endpoint).model(name).apiKey("local-embedding").timeout(Duration.ofSeconds(45)).maxRetries(0).build();
        model=new OpenAiEmbeddingModel(options);
    }
    record Chunk(String source,int part,String text,float[] vector){}
    private List<float[]> embed(List<String> texts,Map<String,Object> event){
        var reply=model.call(new EmbeddingRequest(texts,null));
        var usage=reply.getMetadata().getUsage();
        ((List<Object>)event.get("embeddingCalls")).add(Map.of("count",texts.size(),"model",name,"tokens",
           usage!=null&&usage.getNativeUsage()!=null?Objects.toString(usage.getPromptTokens(),"未知"):"未知"));
        if(reply.getResults().size()!=texts.size())throw new IllegalStateException("向量响应数量无效");
        return reply.getResults().stream().sorted(Comparator.comparingInt(v->v.getIndex())).map(v->v.getOutput()).toList();
    }
    List<AgentGraph.Hit> search(AgentRepository store,String room,String app,AgentGraph g,String query,Map<String,Object> event)throws Exception{
        if(!"dense".equals(g.retrieval())){event.put("index","本地词频向量 · 余弦相似度");return g.retrieve(query);}
        if(model==null)throw new IllegalStateException("模型向量索引未配置");
        String fingerprint=AgentApi.hash(JSON.writeValueAsString(List.of(endpoint,name,g.chunkSize(),g.library())));
        String cached=store.index(room,app,fingerprint);Chunk[] chunks;
        event.put("embeddingCalls",new ArrayList<>());event.put("embeddingModel",name);event.put("index","模型向量 · H2 · 余弦相似度");event.put("reusedIndex",cached!=null);
        if(cached!=null)chunks=JSON.readValue(cached,Chunk[].class);
        else{
            var built=new ArrayList<Chunk>();
            for(String line:g.library().lines().filter(s->!s.isBlank()).toList()){
                String[] fields=line.split("[|｜]",2);String title=fields.length==2?fields[0].strip():"工作区文档",body=fields[fields.length-1].strip();
                for(int start=0,part=1;start<body.length();start+=g.chunkSize(),part++){
                    if(built.size()>=64)throw new IllegalArgumentException("单次模型索引最多64块");
                    built.add(new Chunk(title,part,body.substring(start,Math.min(start+g.chunkSize(),body.length())),null));
                }
            }
            for(int start=0;start<built.size();start+=8){
                var batch=built.subList(start,Math.min(start+8,built.size()));var vecs=embed(batch.stream().map(c->c.source()+" "+c.text()).toList(),event);
                for(int k=0;k<batch.size();k++){var c=batch.get(k);batch.set(k,new Chunk(c.source(),c.part(),c.text(),vecs.get(k)));}
            }
            chunks=built.toArray(Chunk[]::new);store.index(room,app,fingerprint,JSON.writeValueAsString(chunks));
        }
        var q=embed(List.of(query),event).get(0);var hits=new ArrayList<AgentGraph.Hit>();
        for(var c:chunks){double dot=0,a=0,b=0;float[] v=c.vector();if(q.length!=v.length)throw new IllegalStateException("索引维度不匹配");
            for(int i=0;i<v.length;i++){dot+=q[i]*v[i];a+=q[i]*q[i];b+=v[i]*v[i];}
            double score=dot/Math.max(1e-12,Math.sqrt(a*b));if(score>=g.threshold())hits.add(new AgentGraph.Hit(c.source(),c.part(),c.text(),score));}
        event.put("indexedChunks",chunks.length);return hits.stream().sorted(Comparator.comparingDouble(AgentGraph.Hit::score).reversed()).limit(g.topK()).toList();
    }
}
