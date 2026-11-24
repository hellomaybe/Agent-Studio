package studio.lab;

import com.sun.net.httpserver.*;
import java.nio.file.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static studio.lab.AgentModel.JSON;

public final class AgentApi {
    static final String BASE="/open-source/lab/agent/";
    final AgentRepository db;
    final AgentRuntime runner;
    final String origin;
    AgentApi()throws Exception{
        origin=AgentModel.env("ORIGIN","http://127.0.0.1:29479");
        String password=AgentModel.env("DB_PASSWORD","");
        if(password.isBlank())throw new IllegalArgumentException("Set AGENT_DEMO_DB_PASSWORD");
        db=new AgentRepository(AgentModel.env("JDBC","jdbc:h2:file:./data/agent-flows"),password);
        runner=new AgentRuntime(db,new AgentModel());
    }
    static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    String room(HttpExchange x,boolean create)throws Exception{
        String cookie=Objects.toString(x.getRequestHeaders().getFirst("Cookie"),"");
        for(String item:cookie.split(";")){var pair=item.strip().split("=",2);if(pair.length==2&&pair[0].equals("AgentFlow")&&pair[1].matches("[a-f0-9-]{36}")){
            String digest=hash(pair[1]);if(db.known(digest))return digest;}}
        if(!create)return null;String token=UUID.randomUUID().toString(),digest=hash(token);db.createRoom(digest);
        x.getResponseHeaders().set("Set-Cookie","AgentFlow="+token+"; HttpOnly; SameSite=Strict; Max-Age=3600; Path="+BASE+(origin.startsWith("https:")?"; Secure":""));return digest;
    }
    void request(HttpExchange x)throws IOException{
        try{
            String path=x.getRequestURI().getPath().substring((BASE+"api/").length());String[] p=path.split("/");
            boolean post=x.getRequestMethod().equals("POST");
            if(post&&!origin.equals(x.getRequestHeaders().getFirst("Origin"))){send(x,403,Map.of("error","来源不匹配"));return;}
            if(!post&&!x.getRequestMethod().equals("GET")){send(x,405,Map.of("error","方法无效"));return;}
            boolean start=post&&path.equals("session");String room=room(x,start);
            if(room==null){send(x,401,Map.of("error","体验空间已过期，请刷新"));return;}
            if(start){send(x,200,Map.of("features",Features.all,"ports",AgentGraph.PORTS,"model",runner.model.name,"ready",runner.model.ready(),"inputPrice",runner.model.inputPrice,"outputPrice",runner.model.outputPrice));return;}
            var f=Features.all.stream().filter(it->it.id().equals(p[0])).findFirst().orElseThrow();
            Object result;
            if(!post&&p.length==1)result=db.state(room,f.id());
            else if(!post&&p.length==3&&p[1].equals("runs"))result=db.load(room,f.id(),p[2]);
            else if(post&&p.length==2){
                byte[] payload=x.getRequestBody().readNBytes(48001);if(payload.length>48000)throw new IllegalArgumentException("配置过大");
                var json=JSON.readTree(payload);int revision=json.path("revision").asInt(-1);
                result=switch(p[1]){
                    case "draft"->{var g=JSON.treeToValue(json.path("graph"),AgentGraph.class);g.validate(f);db.save(room,f.id(),revision,g);yield db.state(room,f.id());}
                    case "publish"->{db.publish(room,f.id(),revision);yield db.state(room,f.id());}
                    case "run"->runner.run(room,f,json.path("id").asText(),json.path("version").asInt(),json.path("input").asText());
                    case "confirm"->runner.confirm(room,f.id(),json.path("id").asText(),json.path("approve").asBoolean());
                    default->throw new NoSuchElementException();
                };
            }else throw new NoSuchElementException();
            send(x,200,result);
        }catch(ConcurrentModificationException e){send(x,409,Map.of("error","版本已变更，请重新载入"));}
         catch(NoSuchElementException e){send(x,404,Map.of("error","记录不存在"));}
         catch(IllegalArgumentException e){send(x,400,Map.of("error","输入或图定义无效，请检查参数"));}
         catch(IllegalStateException e){send(x,429,Map.of("error","已达到工作区限制"));}
         catch(Exception e){send(x,500,Map.of("error","请求未完成"));}
         finally{x.close();}
    }
    private static void send(HttpExchange x,int code,Object payload)throws IOException{
        byte[] bytes=JSON.writeValueAsBytes(payload);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store");
        x.sendResponseHeaders(code,bytes.length);
        x.getResponseBody().write(bytes);
    }
    public static void main(String[] args)throws Exception{
        var api=new AgentApi();
        var http=HttpServer.create(new InetSocketAddress(AgentModel.env("BIND","127.0.0.1"),Integer.parseInt(AgentModel.env("PORT","29479"))),32);
        http.createContext(BASE+"api/",api::request);
        var assets=Path.of(args.length==0?"site":args[0]);http.createContext(BASE,x->{
            try{String n=x.getRequestURI().getPath().substring(BASE.length());if(n.isEmpty())n="index.html";
                if(!Set.of("index.html","graph.js","graph.css").contains(n)){x.sendResponseHeaders(404,-1);return;}
                byte[] data=Files.readAllBytes(assets.resolve(n));x.getResponseHeaders().set("Content-Type",n.endsWith("js")?"text/javascript; charset=utf-8":n.endsWith("css")?"text/css":"text/html; charset=utf-8");
                x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);
            }finally{x.close();}});
        http.setExecutor(Executors.newFixedThreadPool(8));
        var cleaner=Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleWithFixedDelay(()->{try{api.db.expire();}catch(Exception ignored){System.err.println("Workspace cleanup deferred");}},1,1,TimeUnit.MINUTES);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{cleaner.shutdownNow();http.stop(1);try{api.db.close();}catch(Exception ignored){}}));http.start();
    }
}
