package dev.mcc.game;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import static dev.mcc.game.GameService.*;

/** Loaded-only, optimistic block editing. All public methods require the server thread. */
public final class BuildEngine implements AutoCloseable {
    public static final int MAX_BLOCKS = 4096;
    private static final int HISTORY_CELLS = 8192;
    private record Cell(UUID world, Point point) {}
    public record Point(int x, int y, int z) {}
    private record Change(Point point, String before, String after) {}
    private record Edit(UUID id, UUID world, String worldName, String operation, long at, List<Change> changes) {}
    private record Preview(String token, String actor, String signature, UUID world, String worldName,
                           String operation, List<Change> changes, long expiresAt, UUID historyId, Map<String,Object> dto) {}
    private record Clipboard(UUID world, Point min, Point max, Map<Point,String> blocks) {}
    private final JavaPlugin plugin;
    private final BiConsumer<String,Object> events;
    private final Deque<Work> queue = new ArrayDeque<>();
    private final LinkedHashMap<UUID,Work> results = new LinkedHashMap<>();
    private final Map<String,Preview> previews = new HashMap<>();
    private final LinkedHashMap<String,Clipboard> clipboards = new LinkedHashMap<>();
    private final LinkedHashMap<String,History> histories = new LinkedHashMap<>();
    private final AccountChangeGates accountChanges = new AccountChangeGates();
    private final Set<Cell> reserved = new HashSet<>();
    private BiFunction<String,Map<String,Object>,CompletableFuture<Void>> persistence = (actor, document) -> CompletableFuture.completedFuture(null);
    private boolean enabled;
    private BukkitTask task;
    private BukkitTask catalogTask;
    private Map<String,Object> blockCatalog;
    private CompletableFuture<Map<String,Object>> catalogFuture;

    public BuildEngine(JavaPlugin plugin, BiConsumer<String,Object> events) { this.plugin=plugin; this.events=events; }
    /** The callback must only enqueue asynchronous storage; pending writes wait for its future. */
    public void setPersistence(BiFunction<String,Map<String,Object>,CompletableFuture<Void>> callback) {
        requireMainThread(); persistence=Objects.requireNonNull(callback);
    }
    public void enabled(boolean value) {
        requireMainThread(); enabled=value;
        if (!value) {
            for (Work work : queue) fail(work,"Строительство отключено; уже выполненные изменения доступны через undo");
            queue.clear(); previews.clear(); reserved.clear();
            if(task!=null){task.cancel();task=null;}
            if(catalogTask!=null){catalogTask.cancel();catalogTask=null;catalogFuture.completeExceptionally(new IllegalStateException("Строительство отключено"));}
        }
    }
    /** Default block states use exactly the same protection as preview; work is spread across ticks. */
    public CompletableFuture<Map<String,Object>> catalog() {
        requireMainThread();checkEnabled();if(blockCatalog!=null)return CompletableFuture.completedFuture(blockCatalog);if(catalogFuture!=null&&!catalogFuture.isDone())return catalogFuture;
        catalogFuture=new CompletableFuture<>();List<Material> materials=Arrays.stream(Material.values()).filter(m->m.isBlock()&&!m.name().startsWith("LEGACY_")).toList();List<Map<String,Object>> entries=new ArrayList<>();int[] cursor={0};
        catalogTask=Bukkit.getScheduler().runTaskTimer(plugin,()->{long deadline=System.nanoTime()+500_000;int count=0;while(cursor[0]<materials.size()&&count++<24&&System.nanoTime()<deadline){Material material=materials.get(cursor[0]++);String data="minecraft:"+material.name().toLowerCase(Locale.ROOT),reason="";try{BlockData state=Bukkit.createBlockData(material);data=state.getAsString();checkData(state);if(material.isAir())reason="Для удаления используйте ластик";}catch(RuntimeException ex){reason=ex.getMessage()==null?"Состояние блока не поддерживается":ex.getMessage();}entries.add(map("material",material.name(),"blockData",data,"supported",reason.isEmpty(),"reason",reason));}if(cursor[0]==materials.size()){blockCatalog=map("blocks",List.copyOf(entries),"maxBlocks",MAX_BLOCKS);catalogFuture.complete(blockCatalog);catalogTask.cancel();catalogTask=null;}},1,1);return catalogFuture;
    }
    public CompletableFuture<Map<String,Object>> preview(String actor, Map<String,Object> params) {
        requireMainThread(); checkEnabled(); checkAvailableActor(actor); purge();
        String operation=text(params,"operation",24).toLowerCase(Locale.ROOT);
        World world=world(text(params,"world",128));
        List<Change> changes;UUID historyId=null;
        if(operation.equals("undo")||operation.equals("redo")) {
            History h=histories.get(actor); Deque<Edit> edits=h==null?new ArrayDeque<>():operation.equals("undo")?h.undo:h.redo;
            Edit edit=edits.peekLast(); if(edit==null)throw new IllegalArgumentException("История пуста");
            historyId=edit.id;
            if(!edit.world.equals(world.getUID()))throw new IllegalArgumentException("Последняя операция относится к миру "+edit.worldName);
            changes=edit.changes.stream().map(c->operation.equals("undo")?new Change(c.point,c.after,c.before):c).toList();
        } else if(operation.equals("paste")) {
            Clipboard copy=clipboards.get(actor); if(copy==null)throw new IllegalArgumentException("Сначала скопируйте выделение");
            Point origin=point(params.get("origin")); int rotation=rotation(params);
            List<Change> list=new ArrayList<>();
            for(var entry:copy.blocks.entrySet()) {
                Point relative=new Point(entry.getKey().x-copy.min.x,entry.getKey().y-copy.min.y,entry.getKey().z-copy.min.z);
                Point transformed=rotate(relative,copy.max.x-copy.min.x+1,copy.max.z-copy.min.z+1,rotation);
                Point target=offset(origin,transformed);
                BlockData data=Bukkit.createBlockData(entry.getValue()); data.rotate(switch(rotation){case 90->StructureRotation.CLOCKWISE_90;case 180->StructureRotation.CLOCKWISE_180;case 270->StructureRotation.COUNTERCLOCKWISE_90;default->StructureRotation.NONE;});
                checkData(data); list.add(new Change(target,null,data.getAsString()));
            }
            changes=list;
        } else {
            List<Point> points=geometry(operation,params);
            String desired=operation.equals("copy")?null:operation.equals("remove")?"minecraft:air":validatedData(text(params,"blockData",512));
            changes=points.stream().map(p->new Change(p,null,desired)).toList();
        }
        if(changes.isEmpty()||changes.size()>MAX_BLOCKS)throw new IllegalArgumentException("От 1 до "+MAX_BLOCKS+" блоков на операцию");
        for(Change change:changes)checkPosition(world,change.point);
        checkQueue(actor); Work work=new Work(actor,UUID.randomUUID(),world,operation,changes,signature(params),true,null);work.historyId=historyId;
        queue.addLast(work); startTask(); return work.future;
    }
    public Map<String,Object> execute(String actor, UUID id, Map<String,Object> params, boolean confirmed) {
        requireMainThread();checkEnabled();checkAvailableActor(actor);Objects.requireNonNull(id);purge();
        Work existing=results.get(id);
        if(existing!=null) { if(!existing.actor.equals(actor))throw new IllegalArgumentException("ID операции уже используется"); if(!existing.signature.equals(signature(params)))throw new IllegalArgumentException("ID операции относится к другому запросу"); return dto(existing); }
        String operation=text(params,"operation",24).toLowerCase(Locale.ROOT);
        if(operation.equals("copy"))throw new IllegalArgumentException("copy выполняется через preview без изменения мира");
        Preview preview=previews.get(text(params,"previewToken",64));
        if(preview==null||!preview.actor.equals(actor)||preview.expiresAt<System.currentTimeMillis())throw new IllegalArgumentException("Предпросмотр истёк; обновите его");
        if(!preview.signature.equals(signature(params)))throw new IllegalArgumentException("Параметры изменены после предпросмотра");
        checkHistoryRevision(actor,operation,preview.historyId);
        if(preview.changes.size()>1&&!confirmed)throw new IllegalArgumentException("Массовая операция требует confirmed=true");
        World world=Bukkit.getWorld(preview.world);if(world==null)throw new IllegalArgumentException("Мир не загружен");
        checkQueue(actor);
        for(Change change:preview.changes) {checkPosition(world,change.point);if(reserved.contains(new Cell(world.getUID(),change.point)))throw new IllegalStateException("Область занята другой операцией");}
        Work work=new Work(actor,id,world,operation,preview.changes,preview.signature,false,preview);work.historyId=preview.historyId;
        for(Change change:work.changes)reserved.add(new Cell(world.getUID(),change.point));
        previews.remove(preview.token); results.put(id,work);queue.addLast(work);startTask();return dto(work);
    }
    public Map<String,Object> job(String actor,UUID id) {
        requireMainThread();checkAvailableActor(actor);Work work=results.get(id);
        if(work==null||!work.actor.equals(actor))throw new IllegalArgumentException("Операция не найдена");return dto(work);
    }
    public Map<String,Object> history(String actor) {
        requireMainThread();checkAvailableActor(actor);History h=histories.get(actor);
        return map("undo",h==null?List.of():h.undo.stream().map(this::editSummary).toList(),"redo",h==null?List.of():h.redo.stream().map(this::editSummary).toList(),"clipboard",clipboardDto(clipboards.get(actor)),"maxBlocks",MAX_BLOCKS,"loadedOnly",true);
    }
    public boolean hasHistory(String actor){requireMainThread();checkAvailableActor(actor);return histories.containsKey(actor);}
    /** Restore only the caller's protected document; malformed entries are rejected. */
    public void restoreHistory(String actor,Map<String,Object> document) {
        requireMainThread();checkAvailableActor(actor);if(queue.stream().anyMatch(w->w.actor.equals(actor)))throw new IllegalStateException("Есть активная операция");
        History h=new History();parseEdits(document.get("undo"),h.undo);parseEdits(document.get("redo"),h.redo);trim(h);histories.put(actor,h);trimActors(actor);
    }
    /** Hold both actor namespaces while the caller migrates the protected database document. */
    public void beginAccountChange(String oldActor,String newActor) {
        requireMainThread();checkActor(oldActor);checkActor(newActor);
        if(queue.stream().anyMatch(w->w.actor.equals(oldActor)||w.actor.equals(newActor)))throw new IllegalStateException("Дождитесь завершения строительной операции перед изменением аккаунта");
        accountChanges.begin(oldActor,newActor);
    }
    /** Complete only the matching change; failure retains the original history and clipboard. */
    public void endAccountChange(String oldActor,String newActor,boolean success) {
        requireMainThread();checkActor(oldActor);checkActor(newActor);accountChanges.requireMatching(oldActor,newActor);
        if(success&&!oldActor.equals(newActor)) {
            History history=histories.remove(oldActor);histories.remove(newActor);if(history!=null)histories.put(newActor,history);
            Clipboard clipboard=clipboards.remove(oldActor);clipboards.remove(newActor);if(clipboard!=null)clipboards.put(newActor,clipboard);
        }
        previews.entrySet().removeIf(e->e.getValue().actor.equals(oldActor)||e.getValue().actor.equals(newActor));
        results.entrySet().removeIf(e->e.getValue().actor.equals(oldActor)||e.getValue().actor.equals(newActor));
        accountChanges.end(oldActor,newActor);
    }
    private void tick() {
        if(!enabled){enabled(false);return;}
        long deadline=System.nanoTime()+1_000_000;int reads=0,writes=0,rounds=queue.size();
        while(!queue.isEmpty()&&rounds-->0&&System.nanoTime()<deadline) {
            Work work=queue.removeFirst();if(work.future.isCancelled()&&work.previewOnly)continue;
            if(System.currentTimeMillis()-work.startedAt>60_000){fail(work,"Операция прервана по таймауту; выполненные блоки сохранены в истории");continue;}
            if(Bukkit.getWorld(work.world.getUID())!=work.world){fail(work,"Мир выгружен");continue;}
            if(Bukkit.getTPS()[0]<18){work.status="paused";queue.addLast(work);continue;}
            try {
                if(work.phase==0) {
                    work.status=work.previewOnly?"sampling":"validating";
                    int local=0;
                    while(work.cursor<work.changes.size()&&reads<128&&local++<64&&System.nanoTime()<deadline) {
                        Change c=work.changes.get(work.cursor);Block block=loadedBlock(work.world,c.point);BlockData current=block.getBlockData();checkExisting(block,current);
                        String before=current.getAsString();
                        if(c.before!=null)reconcile(c.before,c.after,before);
                        if(c.after!=null)checkData(Bukkit.createBlockData(c.after));
                        work.sampled.add(new Change(c.point,before,c.after));work.cursor++;reads++;
                    }
                    if(work.cursor==work.changes.size()) {
                        if(work.previewOnly){finishPreview(work);continue;}
                        work.cursor=0;work.phase=1;prepareJournal(work);
                    }
                }
                if(work.phase==1) {
                    work.status="saving";
                    if(work.persisted==null||!work.persisted.isDone()){queue.addLast(work);continue;}
                    work.persisted.join();work.phase=2;work.status="running";
                }
                if(work.phase==2) {
                    int local=0;
                    while(work.cursor<work.changes.size()&&writes<32&&local++<16&&System.nanoTime()<deadline) {
                        Change c=work.changes.get(work.cursor);Block block=loadedBlock(work.world,c.point);BlockData current=block.getBlockData();checkExisting(block,current);String present=current.getAsString();
                        if(reconcile(c.before,c.after,present)==Decision.SKIP){work.skipped++;work.cursor++;continue;}
                        BlockData target=Bukkit.createBlockData(c.after);checkData(target);block.setBlockData(target,false);
                        work.changed++;work.cursor++;writes++;
                    }
                    if(work.cursor==work.changes.size()){finish(work);continue;}
                }
                queue.addLast(work);
            }catch(RuntimeException ex){fail(work,ex.getMessage()==null?"Ошибка операции":ex.getMessage());}
        }
        if(queue.isEmpty()&&task!=null){task.cancel();task=null;}
    }
    private void finishPreview(Work work) {
        Point min=minimum(work.sampled.stream().map(Change::point).toList()),max=maximum(work.sampled.stream().map(Change::point).toList());
        if(work.operation.equals("copy")) {
            Map<Point,String> blocks=new LinkedHashMap<>();for(Change c:work.sampled)blocks.put(c.point,c.before);
            clipboards.put(work.actor,new Clipboard(work.world.getUID(),min,max,Map.copyOf(blocks)));while(clipboards.size()>16)clipboards.remove(clipboards.keySet().iterator().next());
            work.future.complete(map("operation","copy","copied",blocks.size(),"bounds",bounds(min,max),"clipboard",clipboardDto(clipboards.get(work.actor)),"loadedOnly",true));return;
        }
        List<Change> changes=work.sampled.stream().filter(c->!matches(c.before,c.after)).toList();
        String token=UUID.randomUUID().toString();long expiry=System.currentTimeMillis()+60_000;
        Map<String,Object> result=map("token",token,"operation",work.operation,"changes",changes.size(),"affected",work.sampled.size(),"bounds",bounds(min,max),"expiresAt",expiry,"loadedOnly",true,"requiresConfirmation",changes.size()>1);
        previews.entrySet().removeIf(e->e.getValue().actor.equals(work.actor));
        if(previews.size()>=32)throw new IllegalStateException("Слишком много предпросмотров");
        previews.put(token,new Preview(token,work.actor,work.signature,work.world.getUID(),work.world.getName(),work.operation,changes,expiry,work.historyId,result));
        work.future.complete(result);
    }
    private void prepareJournal(Work work) {
        checkHistoryRevision(work.actor,work.operation,work.historyId);
        if(work.changes.isEmpty()&&!work.operation.equals("undo")&&!work.operation.equals("redo")){work.persisted=CompletableFuture.completedFuture(null);return;}
        History h=histories.computeIfAbsent(work.actor,a->new History());work.oldUndo=new ArrayDeque<>(h.undo);work.oldRedo=new ArrayDeque<>(h.redo);
        if(work.operation.equals("undo")) {Edit previous=h.undo.pollLast();if(previous==null)throw new IllegalStateException("История изменилась");h.redo.addLast(previous);}
        else if(work.operation.equals("redo")){Edit previous=h.redo.pollLast();if(previous==null)throw new IllegalStateException("История изменилась");h.undo.addLast(previous);}
        else {h.undo.addLast(new Edit(work.id,work.world.getUID(),work.world.getName(),work.operation,System.currentTimeMillis(),List.copyOf(work.changes)));h.redo.clear();}
        trim(h);trimActors(work.actor);work.journalPrepared=true;
        work.persisted=Objects.requireNonNull(persistence.apply(work.actor,document(h)),"Persistence future");
    }
    private void finish(Work work) {
        work.status="completed";work.finishedAt=System.currentTimeMillis();release(work);work.future.complete(dto(work));
        emit(work);
    }
    private void fail(Work work,String message) {
        if(work.previewOnly){work.future.completeExceptionally(new IllegalArgumentException(message));return;}
        if(work.journalPrepared&&work.changed==0) {
            History h=histories.get(work.actor);h.undo=work.oldUndo;h.redo=work.oldRedo;
            try {persistence.apply(work.actor,document(h));}catch(RuntimeException ignored){} // World was not modified; original journal remains usable if storage is unavailable.
        }
        work.message=message;work.status=work.changed>0?"partial":"failed";work.finishedAt=System.currentTimeMillis();release(work);work.future.complete(dto(work));emit(work);
    }
    private void emit(Work work) {try{events.accept("build",map("actor",work.actor,"id",work.id.toString(),"operation",work.operation,"world",work.world.getName(),"status",work.status,"changed",work.changed,"total",work.changes.size(),"message",work.message));}catch(RuntimeException ignored){} }
    private void release(Work work){for(Change c:work.changes)reserved.remove(new Cell(work.world.getUID(),c.point));}
    private void checkQueue(String actor){if(queue.size()>=4)throw new IllegalStateException("Очередь строительства заполнена");if(queue.stream().anyMatch(w->w.actor.equals(actor)))throw new IllegalStateException("У вас уже есть активная операция");}
    private void checkHistoryRevision(String actor,String operation,UUID expected){if(!operation.equals("undo")&&!operation.equals("redo"))return;History h=histories.get(actor);Edit current=h==null?null:(operation.equals("undo")?h.undo:h.redo).peekLast();if(current==null||!Objects.equals(expected,current.id))throw new IllegalStateException("История изменилась; обновите предпросмотр");}
    private void startTask(){if(task==null)task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);}
    private void checkEnabled(){if(!enabled)throw new IllegalStateException("Модуль строительства отключён");}
    private static void checkActor(String actor){if(actor==null||actor.isBlank()||actor.length()>128)throw new IllegalArgumentException("Некорректный пользователь");}
    private void checkAvailableActor(String actor){checkActor(actor);accountChanges.checkAvailable(actor);}
    private static String text(Map<String,Object> values,String key,int max){return string(values,key,max);}
    private static World world(String name){World world=Bukkit.getWorld(name);if(world==null)throw new IllegalArgumentException("Мир не загружен");return world;}
    private static Block loadedBlock(World world,Point p){checkPosition(world,p);if(!world.isChunkLoaded(p.x>>4,p.z>>4))throw new IllegalArgumentException("Чанк не загружен в "+p);return world.getBlockAt(p.x,p.y,p.z);}
    private static void checkPosition(World world,Point p){if(Math.abs((long)p.x)>29999984||Math.abs((long)p.z)>29999984||p.y<world.getMinHeight()||p.y>=world.getMaxHeight())throw new IllegalArgumentException("Координаты вне мира");if(!world.getWorldBorder().isInside(new Location(world,p.x+.5,p.y+.5,p.z+.5)))throw new IllegalArgumentException("Область за границей мира");}
    private static String validatedData(String string){BlockData data=Bukkit.createBlockData(string);checkData(data);return data.getAsString();}
    private static void checkExisting(Block block,BlockData data){if(block.getState(false) instanceof TileState)throw new IllegalArgumentException("Блок с данными защищён: "+block.getType());checkData(data);}
    private static void checkData(BlockData data) {
        Material material=data.getMaterial();String name=material.name();
        if(data.createBlockState() instanceof TileState)throw new IllegalArgumentException("Блоки с данными/NBT не поддерживаются: "+name);
        if(material.isAir())return;
        if(!material.isSolid()||material.hasGravity()||data instanceof Bisected&&!(data instanceof org.bukkit.block.data.type.Stairs)&&!(data instanceof org.bukkit.block.data.type.TrapDoor)||data instanceof Bed||data instanceof Waterlogged w&&w.isWaterlogged()
                ||name.contains("PISTON")||name.contains("PORTAL")||name.contains("COMMAND")||name.equals("TNT")||name.equals("BEDROCK")||name.equals("BARRIER")||name.equals("STRUCTURE_VOID")||name.equals("LIGHT")
                ||name.equals("RESPAWN_ANCHOR")||name.equals("DRAGON_EGG"))throw new IllegalArgumentException("Жидкости, гравитация, составные и опасные блоки защищены: "+name);
    }
    static boolean matches(String expected,String actual){return Objects.equals(expected,actual);}
    enum Decision { WRITE,SKIP }
    static Decision reconcile(String expected,String desired,String current){if(matches(desired,current))return Decision.SKIP;if(matches(expected,current))return Decision.WRITE;throw new IllegalStateException("Конфликт: блок изменён после предпросмотра");}
    private Map<String,Object> dto(Work work){return map("jobId",work.id.toString(),"operation",work.operation,"world",work.world.getName(),"status",work.status,"changed",work.changed,"skipped",work.skipped,"total",work.changes.size(),"progress",work.changes.isEmpty()?1d:(double)work.cursor/work.changes.size(),"message",work.message,"startedAt",work.startedAt,"finishedAt",work.finishedAt);}
    private Map<String,Object> editSummary(Edit e){return map("id",e.id.toString(),"world",e.worldName,"operation",e.operation,"at",e.at,"blocks",e.changes.size());}
    private static Map<String,Object> clipboardDto(Clipboard c){return c==null?Map.of():map("blocks",c.blocks.size(),"size",map("x",c.max.x-c.min.x+1,"y",c.max.y-c.min.y+1,"z",c.max.z-c.min.z+1));}
    private Map<String,Object> document(History h){return map("version",1,"undo",h.undo.stream().map(this::serializeEdit).toList(),"redo",h.redo.stream().map(this::serializeEdit).toList());}
    private Map<String,Object> serializeEdit(Edit e){return map("id",e.id.toString(),"worldId",e.world.toString(),"world",e.worldName,"operation",e.operation,"at",e.at,"changes",e.changes.stream().map(c->map("x",c.point.x,"y",c.point.y,"z",c.point.z,"before",c.before,"after",c.after)).toList());}
    @SuppressWarnings("unchecked") private static void parseEdits(Object value,Deque<Edit> target) {
        if(value==null)return;if(!(value instanceof List<?> list)||list.size()>10)throw new IllegalArgumentException("Некорректная история");
        for(Object item:list){if(!(item instanceof Map<?,?> raw))throw new IllegalArgumentException("Некорректная история");Map<String,Object> entry=(Map<String,Object>)raw;
            Object data=entry.get("changes");if(!(data instanceof List<?> changes)||changes.size()>MAX_BLOCKS)throw new IllegalArgumentException("Некорректная история");List<Change> cells=new ArrayList<>();Set<Point> unique=new HashSet<>();
            for(Object cell:changes){if(!(cell instanceof Map<?,?> r))throw new IllegalArgumentException("Некорректная история");Map<String,Object> m=(Map<String,Object>)r;Point p=point(m);if(!unique.add(p))throw new IllegalArgumentException("Повтор блока в истории");cells.add(new Change(p,validatedData(text(m,"before",512)),validatedData(text(m,"after",512))));}
            target.addLast(new Edit(parseUuid(text(entry,"id",36)),parseUuid(text(entry,"worldId",36)),text(entry,"world",128),text(entry,"operation",24),((Number)entry.getOrDefault("at",0L)).longValue(),List.copyOf(cells)));
        }
    }
    private static void trim(History h){while(h.undo.size()+h.redo.size()>10||h.undo.stream().mapToInt(e->e.changes.size()).sum()+h.redo.stream().mapToInt(e->e.changes.size()).sum()>HISTORY_CELLS){if(!h.undo.isEmpty())h.undo.removeFirst();else if(!h.redo.isEmpty())h.redo.removeFirst();else break;}}
    private void trimActors(String protectedActor) {
        Set<String> active=new HashSet<>();for(Work work:queue)active.add(work.actor);
        while(histories.size()>16){String oldest=historyEvictionCandidate(histories.keySet(),active,protectedActor);if(oldest==null)break;histories.remove(oldest);}
    }
    static String historyEvictionCandidate(Collection<String> actors,Set<String> active,String protectedActor) {
        for(String actor:actors)if(!actor.equals(protectedActor)&&!active.contains(actor))return actor;return null;
    }
    private void purge(){long now=System.currentTimeMillis();previews.entrySet().removeIf(e->e.getValue().expiresAt<now);results.entrySet().removeIf(e->e.getValue().finishedAt>0&&now-e.getValue().finishedAt>600_000);while(results.size()>128){UUID removable=results.entrySet().stream().filter(e->e.getValue().finishedAt>0).map(Map.Entry::getKey).findFirst().orElse(null);if(removable==null)break;results.remove(removable);}}
    private static String signature(Map<String,Object> params){TreeMap<String,Object> normalized=new TreeMap<>();for(String key:List.of("operation","world","min","max","origin","blockData","rotation"))if(params.containsKey(key)){Object value=params.get(key);if(value instanceof Map<?,?> point)normalized.put(key,new TreeMap<>(point));else normalized.put(key,value);}return normalized.toString();}
    static List<Point> geometry(String operation,Map<String,Object> params) {
        if(!List.of("place","remove","fill","line","box","sphere","copy").contains(operation))throw new IllegalArgumentException("Неизвестная строительная операция");
        Point a=point(params.get("min")),b=params.containsKey("max")?point(params.get("max")):a;
        if(operation.equals("place")||operation.equals("remove")) {if(!a.equals(b))throw new IllegalArgumentException("place/remove изменяет один блок");return List.of(a);}
        Point min=new Point(Math.min(a.x,b.x),Math.min(a.y,b.y),Math.min(a.z,b.z)),max=new Point(Math.max(a.x,b.x),Math.max(a.y,b.y),Math.max(a.z,b.z));
        long sx=(long)max.x-min.x+1,sy=(long)max.y-min.y+1,sz=(long)max.z-min.z+1;
        if(sx>64||sy>64||sz>64)throw new IllegalArgumentException("Максимальная сторона выделения — 64 блока");
        if(operation.equals("line")) {
            int steps=(int)Math.max(sx-1,Math.max(sy-1,sz-1));LinkedHashSet<Point> points=new LinkedHashSet<>();
            for(int i=0;i<=steps;i++){double t=steps==0?0:(double)i/steps;points.add(new Point((int)Math.round(a.x+(b.x-a.x)*t),(int)Math.round(a.y+(b.y-a.y)*t),(int)Math.round(a.z+(b.z-a.z)*t)));}return List.copyOf(points);
        }
        if(sx*sy*sz>MAX_BLOCKS)throw new IllegalArgumentException("Объём выделения не должен превышать "+MAX_BLOCKS+" блоков");
        List<Point> points=new ArrayList<>();double cx=(min.x+(double)max.x)/2,cy=(min.y+(double)max.y)/2,cz=(min.z+(double)max.z)/2,rx=sx/2d,ry=sy/2d,rz=sz/2d;
        for(int y=min.y;y<=max.y;y++)for(int z=min.z;z<=max.z;z++)for(int x=min.x;x<=max.x;x++) {
            if(operation.equals("box")&&x!=min.x&&x!=max.x&&y!=min.y&&y!=max.y&&z!=min.z&&z!=max.z)continue;
            if(operation.equals("sphere")&&square((x-cx)/rx)+square((y-cy)/ry)+square((z-cz)/rz)>1)continue;
            points.add(new Point(x,y,z));
        }return List.copyOf(points);
    }
    private static double square(double n){return n*n;}
    @SuppressWarnings("unchecked") static Point point(Object value){if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException("Ожидаются координаты {x,y,z}");Map<String,Object> m=(Map<String,Object>)map;return new Point(integer(m,"x",-29999984,29999984),integer(m,"y",-2048,2048),integer(m,"z",-29999984,29999984));}
    static int rotation(Map<String,Object> params){int value=params.containsKey("rotation")?integer(params,"rotation",0,270):0;if(value%90!=0)throw new IllegalArgumentException("Поворот: 0, 90, 180 или 270");return value;}
    static Point rotate(Point p,int sx,int sz,int angle){return switch(angle){case 90->new Point(sz-1-p.z,p.y,p.x);case 180->new Point(sx-1-p.x,p.y,sz-1-p.z);case 270->new Point(p.z,p.y,sx-1-p.x);case 0->p;default->throw new IllegalArgumentException("Некорректный поворот");};}
    private static Point offset(Point a,Point b){return new Point(Math.addExact(a.x,b.x),Math.addExact(a.y,b.y),Math.addExact(a.z,b.z));}
    private static Point minimum(List<Point> points){return new Point(points.stream().mapToInt(Point::x).min().orElse(0),points.stream().mapToInt(Point::y).min().orElse(0),points.stream().mapToInt(Point::z).min().orElse(0));}
    private static Point maximum(List<Point> points){return new Point(points.stream().mapToInt(Point::x).max().orElse(0),points.stream().mapToInt(Point::y).max().orElse(0),points.stream().mapToInt(Point::z).max().orElse(0));}
    private static Map<String,Object> bounds(Point min,Point max){return map("min",map("x",min.x,"y",min.y,"z",min.z),"max",map("x",max.x,"y",max.y,"z",max.z));}
    @Override public void close(){requireMainThread();enabled(false);}
    private static final class History {Deque<Edit> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();}
    static final class AccountChangeGates {
        private final Map<String,String> changes=new HashMap<>();
        private final Set<String> actors=new HashSet<>();
        void begin(String oldActor,String newActor) {
            checkAvailable(oldActor);checkAvailable(newActor);changes.put(oldActor,newActor);actors.add(oldActor);actors.add(newActor);
        }
        void checkAvailable(String actor){if(actors.contains(actor))throw new IllegalStateException("Аккаунт изменяется; повторите запрос после завершения");}
        void requireMatching(String oldActor,String newActor){if(!changes.containsKey(oldActor)||!Objects.equals(changes.get(oldActor),newActor))throw new IllegalStateException("Изменение аккаунта не найдено");}
        void end(String oldActor,String newActor){requireMatching(oldActor,newActor);changes.remove(oldActor);actors.remove(oldActor);actors.remove(newActor);}
    }
    private static final class Work {
        final String actor;final UUID id;final World world;final String operation;final List<Change> changes;final String signature;final boolean previewOnly;final Preview preview;
        final long startedAt=System.currentTimeMillis();final CompletableFuture<Map<String,Object>> future=new CompletableFuture<>();final List<Change> sampled=new ArrayList<>();
        int cursor,phase,changed,skipped;String status="queued",message="";long finishedAt;boolean journalPrepared;UUID historyId;CompletableFuture<Void> persisted;Deque<Edit> oldUndo,oldRedo;
        Work(String actor,UUID id,World world,String operation,List<Change> changes,String signature,boolean previewOnly,Preview preview){this.actor=actor;this.id=id;this.world=world;this.operation=operation;this.changes=changes;this.signature=signature;this.previewOnly=previewOnly;this.preview=preview;}
    }
}
