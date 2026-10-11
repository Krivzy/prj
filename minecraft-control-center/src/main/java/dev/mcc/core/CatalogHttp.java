package dev.mcc.core;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Only first-party API/CDN hosts; no redirects, caller URLs, authentication, or cookies. */
final class CatalogHttp implements PluginCatalog.Transport,AutoCloseable {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore requests=new Semaphore(4);
    @Override public byte[] get(URI uri,int limit)throws Exception {
        validate(uri);
        HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(limit>8*1024*1024?60:15)).header("User-Agent","MinecraftControlCenter/0.6 (https://github.com/Krivzy/prj)").header("Accept",uri.getHost().equals("api.modrinth.com")?"application/json":"application/java-archive").GET().build();
        requests.acquire();try{HttpResponse<byte[]> response=client.send(request,info->new LimitedBody(info.statusCode()==200?limit:8192));
        if(response.statusCode()!=200)throw new IOException("Plugin source returned HTTP "+response.statusCode());return response.body();}finally{requests.release();}
    }
    static void validate(URI uri) {
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getFragment()!=null||!Set.of("api.modrinth.com","cdn.modrinth.com").contains(uri.getHost())||!uri.normalize().equals(uri))throw new IllegalArgumentException("Untrusted plugin source");
        if(uri.getHost().equals("api.modrinth.com")&&!uri.getRawPath().startsWith("/v2/"))throw new IllegalArgumentException("Untrusted API path");
        if(uri.getHost().equals("cdn.modrinth.com")&&(uri.getQuery()!=null||uri.getPath().contains("..")||uri.getPath().contains("\\")||uri.getPath().indexOf('\0')>=0||!uri.getRawPath().matches("/data/[A-Za-z0-9]+/versions/[A-Za-z0-9]+/[A-Za-z0-9_.%+~-]+\\.jar")))throw new IllegalArgumentException("Untrusted artifact path");
    }
    @Override public void close(){client.shutdownNow();}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;private final ByteArrayOutputStream data=new ByteArrayOutputStream();private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
        LimitedBody(int limit){this.limit=limit;}
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){try {for(ByteBuffer buffer:buffers){int n=buffer.remaining();if((long)data.size()+n>limit)throw new IOException("Plugin source response exceeds its size limit");byte[] chunk=new byte[n];buffer.get(chunk);data.write(chunk);}subscription.request(1);}catch(Exception error){subscription.cancel();result.completeExceptionally(error);}}
        @Override public void onError(Throwable error){result.completeExceptionally(error);}
        @Override public void onComplete(){result.complete(data.toByteArray());}
    }
}
