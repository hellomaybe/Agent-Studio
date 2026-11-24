package studio.lab;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import static studio.lab.AgentModel.JSON;

final class AgentRepository implements AutoCloseable {
    private final Connection db;
    AgentRepository(String url,String password)throws SQLException {
        db=DriverManager.getConnection(url,"agent",password);
        try(var s=db.createStatement()){
            s.execute("create table if not exists ag_index(room varchar,app varchar, fingerprint varchar, chunks clob, primary key(room,app))");
            s.execute("create table if not exists ag_output(room varchar,app varchar,bucket varchar,id varchar,created bigint,payload clob,primary key(room,app,id))");
            s.execute("create table if not exists ag_room(id varchar primary key, expires bigint not null)");
            s.execute("create table if not exists ag_draft(room varchar, app varchar, rev int, graph clob, primary key(room,app))");
            s.execute("create table if not exists ag_version(room varchar, app varchar, ver int, graph clob, primary key(room,app,ver))");
            s.execute("create table if not exists ag_run(room varchar, app varchar, id varchar, ver int, status varchar, created bigint, body clob, primary key(room,app,id))");
            s.execute("create table if not exists ag_cost(id varchar primary key, room varchar, app varchar, run_id varchar, ledger_day varchar, amount decimal(26,12), status varchar)");
            s.execute("update ag_run set status='interrupted' where status='running'");
            s.execute("update ag_cost set status='unknown' where status='reserved'");
        }
    }
    private PreparedStatement stmt(String sql,Object... p)throws SQLException{var s=db.prepareStatement(sql);for(int i=0;i<p.length;i++)s.setObject(i+1,p[i]);return s;}
    private int update(String sql,Object... p)throws SQLException{try(var s=stmt(sql,p)){return s.executeUpdate();}}
    private List<Map<String,Object>> rows(String sql,Object... p)throws SQLException{
        var list=new ArrayList<Map<String,Object>>();try(var s=stmt(sql,p);var rs=s.executeQuery()){
            while(rs.next()){var m=new LinkedHashMap<String,Object>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++){
                Object v=rs.getObject(i);m.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),v instanceof Clob?rs.getString(i):v);}list.add(m);}}
        return list;
    }
    private Map<String,Object> one(String sql,Object... p)throws SQLException{var a=rows(sql,p);return a.isEmpty()?Map.of():a.get(0);}
    private int count(String sql,Object...p)throws SQLException{return ((Number)one(sql,p).values().iterator().next()).intValue();}
    synchronized void expire()throws SQLException{
        long now=System.currentTimeMillis();
        for(var t:List.of("ag_draft","ag_version","ag_run","ag_index","ag_output"))update("delete from "+t+" where room in(select id from ag_room where expires<=?)",now);
        update("delete from ag_room where expires<=?",now);
        update("delete from ag_cost where ledger_day<?",LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
    }
    synchronized String index(String room,String app,String fingerprint)throws SQLException{return (String)one("select chunks from ag_index where room=? and app=? and fingerprint=?",room,app,fingerprint).get("chunks");}
    synchronized void index(String room,String app,String fingerprint,String chunks)throws SQLException{update("merge into ag_index key(room,app) values(?,?,?,?)",room,app,fingerprint,chunks);}
    synchronized boolean known(String room)throws SQLException{return count("select count(*) from ag_room where id=? and expires>?",room,System.currentTimeMillis())==1;}
    synchronized void createRoom(String room)throws SQLException{
        expire();if(count("select count(*) from ag_room")>=128)throw new IllegalStateException("工作区已满");
        db.setAutoCommit(false);try{
            update("insert into ag_room values(?,?)",room,System.currentTimeMillis()+3600000);
            for(var f:Features.all){var graph=JSON.writeValueAsString(AgentGraph.initial(f));update("insert into ag_draft values(?,?,1,?)",room,f.id(),graph);update("insert into ag_version values(?,?,1,?)",room,f.id(),graph);}
            db.commit();
        }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    synchronized Map<String,Object> state(String room,String app)throws SQLException{
        var draft=one("select rev, graph from ag_draft where room=? and app=?",room,app);
        return Map.of("revision",draft.get("rev"),"draft",JSON.readValue((String)draft.get("graph"),AgentGraph.class),"version",version(room,app),"history",rows("select id,ver,status,created from ag_run where room=? and app=? order by created desc limit 20",room,app));
    }
    synchronized int version(String room,String app)throws SQLException{return count("select max(ver) from ag_version where room=? and app=?",room,app);}
    synchronized AgentGraph graph(String room,String app,int ver)throws SQLException{
        var row=one("select graph from ag_version where room=? and app=? and ver=?",room,app,ver);if(row.isEmpty())throw new NoSuchElementException();
        return JSON.readValue((String)row.get("graph"),AgentGraph.class);
    }
    synchronized void save(String room,String app,int revision,AgentGraph graph)throws SQLException{
        if(update("update ag_draft set graph=?, rev=rev+1 where room=? and app=? and rev=?",JSON.writeValueAsString(graph),room,app,revision)!=1)throw new ConcurrentModificationException();
    }
    synchronized int publish(String room,String app,int revision)throws SQLException{
        var draft=one("select graph from ag_draft where room=? and app=? and rev=?",room,app,revision);if(draft.isEmpty())throw new ConcurrentModificationException();
        int v=version(room,app);String spec=(String)draft.get("graph");
        if(graph(room,app,v).equals(JSON.readValue(spec,AgentGraph.class)))return v;
        if(v>=30)throw new IllegalStateException("已达到工作区发布上限");
        update("insert into ag_version values(?,?,?,?)",room,app,v+1,spec);return v+1;
    }
    synchronized boolean begin(String room,String app,String id,int ver,Map<String,Object> body)throws SQLException{
        if(!one("select id from ag_run where room=? and app=? and id=?",room,app,id).isEmpty())return false;
        if(count("select count(*) from ag_run where room=?",room)>=80)throw new IllegalStateException("已达到工作区运行上限");
        update("insert into ag_run values(?,?,?,?,?,?,?)",room,app,id,ver,"running",System.currentTimeMillis(),JSON.writeValueAsString(body));return true;
    }
    synchronized void checkpoint(String room,String app,String id,String status,Map<String,Object> body)throws SQLException{
        update("update ag_run set status=?,body=? where room=? and app=? and id=?",status,JSON.writeValueAsString(body),room,app,id);
    }
    synchronized Map<String,Object> load(String room,String app,String id)throws SQLException{
        var row=one("select ver,status,body from ag_run where room=? and app=? and id=?",room,app,id);if(row.isEmpty())throw new NoSuchElementException();
        var body=JSON.readValue(row.get("body").toString(),Map.class);
        var result=new LinkedHashMap<String,Object>();for(Object k:body.keySet())result.put(k.toString(),body.get(k));
        result.put("status",row.get("status"));result.put("graph",graph(room,app,((Number)row.get("ver")).intValue()));
        var saved=one("select bucket,created,payload from ag_output where room=? and app=? and id=?",room,app,id);
        if(!saved.isEmpty()) result.put("stored",Map.of("collection",saved.get("bucket"),"storedAt",saved.get("created"),"value",JSON.readTree(saved.get("payload").toString())));
        result.put("ledger",rows("select amount,status,ledger_day from ag_cost where room=? and app=? and run_id=? order by id",room,app,id));return result;
    }
    synchronized void completed(String room,String app,String id,AgentGraph graph,Map<String,Object> body)throws SQLException{
        String bucket=Objects.toString(graph.collection(),app+"-results");
        var value=JSON.readTree(JSON.writeValueAsString(body.get("result")));
        String payload="summary".equals(graph.projection())?JSON.writeValueAsString(Map.of("summary",value.path("summary").asText())):JSON.writeValueAsString(value);
        db.setAutoCommit(false);
        try{
            update("insert into ag_output values(?,?,?,?,?,?)",room,app,bucket,id,System.currentTimeMillis(),payload);
            checkpoint(room,app,id,"completed",body);db.commit();
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    synchronized String memory(String room,String app)throws SQLException{
        var last=one("select body from ag_run where room=? and app=? and status='completed' order by created desc limit 1",room,app);
        if(last.isEmpty())return "";return JSON.readTree(last.get("body").toString()).path("result").path("summary").asText("");
    }
    synchronized String reserve(String room,String app,String run,BigDecimal amount,BigDecimal limit,BigDecimal daily)throws SQLException{
        var day=LocalDate.now(ZoneOffset.UTC).toString();
        var used=(BigDecimal)one("select coalesce(sum(amount),0.0) from ag_cost where room=? and app=? and run_id=?",room,app,run).values().iterator().next();
        var total=(BigDecimal)one("select coalesce(sum(amount),0.0) from ag_cost where ledger_day=?",day).values().iterator().next();
        if(used.add(amount).compareTo(limit)>0 || total.add(amount).compareTo(daily)>0)throw new IllegalStateException("模型预算不足，调用前已停止");
        String key=UUID.randomUUID().toString();update("insert into ag_cost values(?,?,?,?,?,?,?)",key,room,app,run,day,amount,"reserved");return key;
    }
    synchronized void settle(String key,BigDecimal actual)throws SQLException{
        if(actual==null)update("update ag_cost set status='unknown' where id=?",key);
        else update("update ag_cost set amount=?,status='estimated' where id=?",actual,key);
    }
    public synchronized void close()throws SQLException{db.close();}
}
