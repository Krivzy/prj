package dev.mcc.web;

import dev.mcc.core.*;
import io.undertow.websockets.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class Realtime implements AutoCloseable {
    private final Auth auth;
    private final ConcurrentHashMap<WebSocketChannel,Client> clients=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor encoder=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),r->{Thread t=new Thread(r,"MCC-Events");t.setDaemon(true);return t;});
    private final AtomicLong sequence=new AtomicLong(),published=new AtomicLong(),dropped=new AtomicLong();
    private final RateLimiter inbound=new RateLimiter();
    private volatile boolean closed;
    private static final Set<String> TOPICS=Set.of("state","players","chat","console","timeline","events","inspector","world","spawn","inventory","punishments","build","automation");
    private static final class Client {
        final Auth.Session session;final Set<String> topics=ConcurrentHashMap.newKeySet();final ArrayDeque<String> queue=new ArrayDeque<>();boolean sending;
        Client(Auth.Session s){session=s;}
    }
    public Realtime(Auth auth){this.auth=auth;}
    public long sequence(){return sequence.get();}
    public void connect(WebSocketChannel channel,Auth.Session session){
        if(clients.size()>=16||clients.values().stream().filter(c->c.session.name().equals(session.name())).count()>=4){closeChannel(channel,1013,"Connection limit");return;}
        Client client=new Client(session);clients.put(channel,client);channel.setIdleTimeout(65000);
        channel.addCloseTask(c->clients.remove(c));
        channel.getReceiveSetter().set(new AbstractReceiveListener(){
            @Override protected long getMaxTextBufferSize(){return 4096;}
            @Override protected void onFullTextMessage(WebSocketChannel ch,BufferedTextMessage message){
                if(auth.session(session.id())==null){closeChannel(ch,1008,"Session expired");return;}
                if(!inbound.allow(session.id()+":ws",30,10000)){closeChannel(ch,1008,"Message rate exceeded");return;}
                try{Map<String,Object> body=Json.object(message.getData());String type=Json.text(body,"type","");
                    if(type.equals("ping")){offer(ch,client,Json.write(Map.of("type","pong","sequence",sequence.get())));return;}
                    if(type.equals("subscribe")){
                        Object list=body.get("topics");if(!(list instanceof List<?> topics)||topics.size()>TOPICS.size())throw new IllegalArgumentException();
                        client.topics.clear();for(Object topic:topics)if(topic instanceof String s&&TOPICS.contains(s)&&permitted(session.role(),s))client.topics.add(s);
                        offer(ch,client,Json.write(Map.of("type","subscribed","topics",client.topics,"sequence",sequence.get())));
                    }
                }catch(Exception e){closeChannel(ch,1008,"Invalid subscription");}
            }
            @Override protected void onError(WebSocketChannel ch,Throwable error){closeChannel(ch,1011,"Connection error");}
        });channel.resumeReceives();offer(channel,client,Json.write(Map.of("type","hello","sequence",sequence.get())));
    }
    private static boolean permitted(String role,String topic){return Set.of("console","build","automation").contains(topic)?Set.of("Owner","Admin").contains(role):!Set.of("chat","inventory","punishments","timeline").contains(topic)||!role.equals("Viewer");}
    public void publish(String topic,Object data){
        if(closed)return;
        try{encoder.execute(()->{
            long seq=sequence.incrementAndGet();published.incrementAndGet();String encoded=Json.write(Map.of("type","event","topic",topic,"sequence",seq,"data",data));
            clients.forEach((ch,c)->{if(c.topics.contains(topic)){if(auth.session(c.session.id())==null)closeChannel(ch,1008,"Session expired");else offer(ch,c,encoded);}});
        });}catch(RejectedExecutionException e){dropped.incrementAndGet();}
    }
    private void offer(WebSocketChannel channel,Client client,String text){
        synchronized(client){if(!channel.isOpen())return;if(client.queue.size()>=32){dropped.incrementAndGet();closeChannel(channel,1013,"Slow consumer; reconnect and resync");return;}client.queue.add(text);if(client.sending)return;client.sending=true;}
        drain(channel,client);
    }
    private void drain(WebSocketChannel channel,Client client){
        String next;synchronized(client){next=client.queue.poll();if(next==null){client.sending=false;return;}}
        WebSockets.sendText(next,channel,new WebSocketCallback<Void>(){
            @Override public void complete(WebSocketChannel ch,Void context){drain(ch,client);}
            @Override public void onError(WebSocketChannel ch,Void context,Throwable error){closeChannel(ch,1011,"Send failed");}
        });
    }
    private static void closeChannel(WebSocketChannel channel,int code,String reason){try{WebSockets.sendClose(code,reason,channel,null);}catch(Exception e){try{channel.close();}catch(Exception ignored){}}}
    public boolean subscribers(String topic){return clients.values().stream().anyMatch(c->c.topics.contains(topic));}
    public Map<String,Object> stats(){return Map.of("connections",clients.size(),"eventQueue",encoder.getQueue().size(),"published",published.get(),"dropped",dropped.get());}
    @Override public void close(){closed=true;clients.keySet().forEach(c->closeChannel(c,1001,"Server stopping"));clients.clear();encoder.shutdownNow();}
}
