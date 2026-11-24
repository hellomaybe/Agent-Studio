package studio.lab;

import java.math.BigDecimal;
import java.net.URI;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import java.time.Duration;
import java.util.*;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

final class AgentModel {
    static final JsonMapper JSON=new JsonMapper();
    final String name=env("MODEL","");
    private final String endpoint=env("BASE_URL","");
    private final String key=env("API_KEY","");
    final boolean local;
    final BigDecimal inputPrice,outputPrice,daily;
    AgentModel(){
        URI uri=URI.create(endpoint);
        local=List.of("127.0.0.1","localhost","[::1]").contains(Objects.toString(uri.getHost(),""));
        if(!endpoint.isEmpty()&&(!("https".equals(uri.getScheme())||local&&"http".equals(uri.getScheme()))||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null))throw new IllegalArgumentException("模型端点无效");
        inputPrice=price("INPUT_PER_MILLION");outputPrice=price("OUTPUT_PER_MILLION");daily=price("DAILY_CNY");
    }
    static String env(String k,String fallback){return System.getenv().getOrDefault("AGENT_DEMO_"+k,fallback);}
    private static BigDecimal price(String k){var n=new BigDecimal(env(k,"0"));if(n.signum()<0)throw new IllegalArgumentException(k);return n;}
    boolean ready(){return !name.isBlank()&&!endpoint.isBlank()&&(local||!key.isBlank()&&daily.signum()>0&&inputPrice.signum()>0&&outputPrice.signum()>0);}
    BigDecimal price(int in,int out){return inputPrice.multiply(BigDecimal.valueOf(in)).add(outputPrice.multiply(BigDecimal.valueOf(out))).movePointLeft(6);}
    record Reply(String text,String model,Integer inputTokens,Integer outputTokens,List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> toolCalls){}
    Reply call(String system,String user,int limit,Features.Feature feature,String rule,boolean nativeTool){
        var builder=OpenAiChatOptions.builder().baseUrl(endpoint).apiKey(key.isBlank()?"local-inference":key).model(name)
            .maxTokens(limit).temperature(0.0).maxRetries(0).timeout(Duration.ofSeconds(75));
        if(nativeTool)builder.toolCallbacks(callback(feature,rule)).toolChoice(Map.of("type","function","function",Map.of("name",feature.id().replace('-','_')))).parallelToolCalls(false);
        var options=builder.build();
        var model=OpenAiChatModel.builder().options(options).httpClientBuilderCustomizer(c->c.interceptor(AgentModel::submitOnce)).build();
        var response=model.call(new Prompt(new SystemMessage(system),new UserMessage(user)));
        var usage=response.getMetadata().getUsage();
        var nativeUsage=usage==null?null:usage.getNativeUsage();
        return new Reply(response.getResult().getOutput().getText(),response.getMetadata().getModel(),
          nativeUsage==null?null:usage.getPromptTokens(),nativeUsage==null?null:usage.getCompletionTokens(),response.getResult().getOutput().getToolCalls());
    }
  private static Response submitOnce(Interceptor.Chain chain) throws IOException {
    var request = chain.request();
    var original = request.body();
    if (original == null) return chain.proceed(request);
    var once =
        new RequestBody() {
          @Override
          public boolean isOneShot() {
            return true;
          }

          @Override
          public MediaType contentType() {
            return original.contentType();
          }

          @Override
          public long contentLength() throws IOException {
            return original.contentLength();
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            original.writeTo(sink);
          }
        };
    return chain.proceed(request.newBuilder().method(request.method(), once).build());
  }
    private static ToolCallback callback(Features.Feature f,String rule){
        return new ToolCallback(){
            public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(f.id().replace('-','_')).description(f.action()).inputSchema("{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}").build();}
            public String call(String arguments){String value=JSON.readTree(arguments).path("text").asText();return JSON.writeValueAsString(Features.run(f.id(),rule,value));}
        };
    }
    static Features.Result tool(Features.Feature f,String rule,String input){
        return JSON.readValue(callback(f,rule).call(JSON.writeValueAsString(Map.of("text",input))),Features.Result.class);
    }
}
