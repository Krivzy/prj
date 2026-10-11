package dev.mcc.core;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure bounded graph validation; game registry lookups happen only on the game thread. */
public final class AutomationGraph {
    public static final Set<String> ACTIONS=Set.of("chat.broadcast","player.message","player.give","player.title","player.actionbar","player.sound","player.effect","player.firework","world.time","world.weather","player.heal","player.feed","player.xp","player.level","player.extinguish","player.glowing","player.particles","player.teleport","world.difficulty","world.pvp","world.gamerule","event.start","event.stop","event.pause");
    public static final Set<String> TRIGGERS=Set.of("join","quit","death","interval","respawn","worldChange","advancement","mobKill","blockBreak","blockPlace","bedEnter","levelChange","weatherChange","eventComplete");
    public static final Set<String> CONDITIONS=Set.of("onlineCount","tps","playerOnline","worldTime","weather","worldPlayers","difficulty","environment","pvp","playerHealth","playerFood","playerLevel","playerGamemode","playerPermission","playerHasItem","playerEffect","playerBiome","playerY","playerDistance","chance");
    public static final String DEFAULT_PROFILE="00000000-0000-4000-8000-000000000001";
    private static final Set<String> NUMERIC_OPS=Set.of("eq","ne","lt","lte","gt","gte");
    public record Node(String id,String type,Map<String,Object> config) {}
    public record Edge(String id,String source,String target,String handle) {}
    public record Compiled(Map<String,Object> document,List<Node> order,List<Edge> edges,String trigger,int intervalSeconds) {
        public List<Edge> outgoing(String id,String handle){return edges.stream().filter(e->e.source.equals(id)&&e.handle.equals(handle)).toList();}
    }
    private AutomationGraph(){}

    @SuppressWarnings("unchecked") public static Compiled compile(Map<String,Object> input){
        if(input==null)throw new IllegalArgumentException("Graph is required");
        if(Json.write(input).getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("Graph exceeds 64 KiB");
        Map<String,Object> body=Json.object(Json.write(input));
        boolean legacy=!body.containsKey("graphVersion")&&!body.containsKey("nodes");
        if(legacy)body=fromLegacy(body);
        String id=uuid(text(body,"id",UUID.randomUUID().toString(),36));
        String name=text(body,"name","Automation",80);if(name.isBlank())throw new IllegalArgumentException("Name is required");
        if(integer(body.getOrDefault("graphVersion",0),1,1,"graphVersion")!=1)throw new IllegalArgumentException("Unsupported graphVersion");
        String world=text(body,"world","",100);boolean enabled=bool(body.getOrDefault("enabled",false),"enabled");
        int cooldown=integer(body.getOrDefault("cooldownSeconds",30),30,86400,"cooldownSeconds");
        List<?> rawNodes=list(body.get("nodes"),1,32,"nodes"),rawEdges=list(body.get("edges"),1,64,"edges");
        LinkedHashMap<String,Node> nodes=new LinkedHashMap<>();List<Map<String,Object>> normalizedNodes=new ArrayList<>();
        String trigger=null,root=null;int interval=60,actions=0;double delays=0;
        for(Object raw:rawNodes){
            Map<String,Object> value=map(raw,"node");String nodeId=identifier(text(value,"id","",64),64,"node id");
            String type=text(value,"type","",16);Map<String,Object> config=new LinkedHashMap<>(map(value.get("config"),"config"));
            if(config.containsKey("label"))text(config,"label","",80);
            switch(type){
                case "trigger" -> {if(root!=null)throw new IllegalArgumentException("Exactly one trigger is required");root=nodeId;trigger=text(config,"trigger","",16);if(!TRIGGERS.contains(trigger))throw new IllegalArgumentException("Unsupported trigger");interval=integer(config.getOrDefault("intervalSeconds",60),30,86400,"intervalSeconds");config.put("intervalSeconds",interval);}
                case "condition" -> validateCondition(config);
                case "action" -> {String action=text(config,"action","",64);if(!ACTIONS.contains(action))throw new IllegalArgumentException("Unsupported automation action");Map<String,Object> params=map(config.getOrDefault("params",Map.of()),"params");validateAction(action,params);config.put("params",params);actions++;}
                case "delay" -> {double seconds=number(config.getOrDefault("seconds",0),0.05,60,"seconds");config.put("seconds",seconds);delays+=seconds;}
                default -> throw new IllegalArgumentException("Unsupported node type");
            }
            Map<String,Object> position=map(value.getOrDefault("pos",Map.of("x",0,"y",0)),"pos");Map<String,Object> pos=Map.of("x",number(position.get("x"),-10000,10000,"pos.x"),"y",number(position.get("y"),-10000,10000,"pos.y"));
            if(nodes.putIfAbsent(nodeId,new Node(nodeId,type,Collections.unmodifiableMap(config)))!=null)throw new IllegalArgumentException("Duplicate node id");
            normalizedNodes.add(Map.of("id",nodeId,"type",type,"config",config,"pos",pos));
        }
        if(root==null||actions==0)throw new IllegalArgumentException("One trigger and at least one action are required");
        if(delays>60)throw new IllegalArgumentException("Total graph delay exceeds 60 seconds");
        List<Edge> edges=new ArrayList<>();List<Map<String,Object>> normalizedEdges=new ArrayList<>();Set<String> edgeIds=new HashSet<>(),connections=new HashSet<>();Map<String,Integer> indegree=new HashMap<>();nodes.keySet().forEach(n->indegree.put(n,0));
        for(Object raw:rawEdges){
            Map<String,Object> value=map(raw,"edge");String edgeId=identifier(text(value,"id","",128),128,"edge id"),source=text(value,"source","",64),target=text(value,"target","",64),handle=text(value,"sourceHandle","next",16);
            if(!edgeIds.add(edgeId))throw new IllegalArgumentException("Duplicate edge id");
            Node from=nodes.get(source);if(from==null||!nodes.containsKey(target)||source.equals(target))throw new IllegalArgumentException("Dangling or self edge");
            if(from.type.equals("condition")?!Set.of("true","false").contains(handle):!handle.equals("next"))throw new IllegalArgumentException("Invalid sourceHandle");
            if(target.equals(root))throw new IllegalArgumentException("Trigger cannot have incoming edges");
            if(!connections.add(source+"\u0000"+target+"\u0000"+handle))throw new IllegalArgumentException("Duplicate connection");
            edges.add(new Edge(edgeId,source,target,handle));normalizedEdges.add(Map.of("id",edgeId,"source",source,"target",target,"sourceHandle",handle));indegree.compute(target,(k,n)->n+1);
        }
        ArrayDeque<String> queue=new ArrayDeque<>();for(String n:nodes.keySet())if(indegree.get(n)==0)queue.add(n);
        List<Node> order=new ArrayList<>();while(!queue.isEmpty()){String current=queue.remove();order.add(nodes.get(current));for(Edge e:edges)if(e.source.equals(current)&&indegree.compute(e.target,(k,n)->n-1)==0)queue.add(e.target);}
        if(order.size()!=nodes.size())throw new IllegalArgumentException("Graph contains a cycle");
        Set<String> reachable=new HashSet<>();reachable.add(root);for(Node n:order)if(reachable.contains(n.id))for(Edge e:edges)if(e.source.equals(n.id))reachable.add(e.target);
        if(reachable.size()!=nodes.size())throw new IllegalArgumentException("Every node must be reachable from the trigger");
        Map<String,Object> document=new LinkedHashMap<>();document.put("id",id);document.put("profileId",uuid(text(body,"profileId",DEFAULT_PROFILE,36)));document.put("name",name);document.put("enabled",enabled);document.put("world",world);document.put("cooldownSeconds",cooldown);document.put("graphVersion",1);document.put("nodes",normalizedNodes);document.put("edges",normalizedEdges);
        return new Compiled(Collections.unmodifiableMap(document),List.copyOf(order),List.copyOf(edges),trigger,interval);
    }

    private static Map<String,Object> fromLegacy(Map<String,Object> old){
        String trigger=text(old,"trigger","join",16),action=text(old,"action","",64);int seconds=integer(old.getOrDefault("intervalSeconds",60),30,86400,"intervalSeconds");
        Map<String,Object> out=new LinkedHashMap<>(old);out.put("graphVersion",1);out.put("cooldownSeconds",seconds);
        out.put("nodes",List.of(Map.of("id","trigger","type","trigger","config",Map.of("trigger",trigger,"intervalSeconds",seconds),"pos",Map.of("x",0,"y",0)),Map.of("id","action","type","action","config",Map.of("action",action,"params",map(old.getOrDefault("params",Map.of()),"params")),"pos",Map.of("x",260,"y",0))));
        out.put("edges",List.of(Map.of("id","legacy-edge","source","trigger","target","action","sourceHandle","next")));return out;
    }
    public static Map<String,Object> exportLegacy(Compiled graph){
        if(graph.order.size()!=2||graph.edges.size()!=1||!graph.order.get(1).type.equals("action")||!graph.edges.getFirst().handle.equals("next"))throw new IllegalArgumentException("This graph cannot be represented as a legacy rule");
        Node action=graph.order.get(1);Map<String,Object> out=new LinkedHashMap<>();for(String key:List.of("id","name","world","enabled"))out.put(key,graph.document.get(key));
        int cooldown=((Number)graph.document.get("cooldownSeconds")).intValue();if(graph.trigger.equals("interval")&&cooldown!=graph.intervalSeconds)throw new IllegalArgumentException("Different interval and cooldown cannot be exported to a legacy rule");
        out.put("trigger",graph.trigger);out.put("action",action.config.get("action"));out.put("params",action.config.get("params"));out.put("intervalSeconds",graph.trigger.equals("interval")?graph.intervalSeconds:cooldown);return out;
    }

    private static void validateCondition(Map<String,Object> config){
        String condition=text(config,"condition","",32),operator=text(config,"operator","eq",8);if(operator.equals("neq"))operator="ne";if(!CONDITIONS.contains(condition))throw new IllegalArgumentException("Unsupported condition");
        if(Set.of("weather","difficulty","environment","playerGamemode","playerBiome").contains(condition)){
            if(!Set.of("eq","ne").contains(operator))throw new IllegalArgumentException("Invalid text comparison");String value=text(config,"value","",150);if(value.isBlank())throw new IllegalArgumentException("Condition value required");
            Set<String> options=switch(condition){case "weather"->Set.of("clear","rain","thunder");case "difficulty"->Set.of("PEACEFUL","EASY","NORMAL","HARD");case "environment"->Set.of("NORMAL","NETHER","THE_END","CUSTOM");case "playerGamemode"->Set.of("SURVIVAL","CREATIVE","ADVENTURE","SPECTATOR");default->Set.of();};if(!options.isEmpty()&&!options.contains(value))throw new IllegalArgumentException("Invalid condition choice");
        }else if(Set.of("playerOnline","pvp","playerPermission","playerHasItem","playerEffect","chance").contains(condition)){
            if(!Set.of("eq","ne").contains(operator))throw new IllegalArgumentException("Invalid boolean comparison");bool(config.get("value"),"condition value");
        }else{if(!NUMERIC_OPS.contains(operator))throw new IllegalArgumentException("Invalid comparison operator");double max=condition.equals("tps")?20:condition.equals("worldTime")?23999:condition.equals("playerFood")?20:condition.equals("playerDistance")?30000000:100000;number(config.get("value"),condition.equals("playerY")?-2048:0,max,"condition value");}
        if(condition.equals("playerPermission")){String permission=text(config,"permission","",150);if(!permission.matches("[a-zA-Z0-9_.*-]+"))throw new IllegalArgumentException("Invalid permission");}
        if(condition.equals("playerHasItem")){requiredText(config,"material",100);config.put("amount",integer(config.getOrDefault("amount",1),1,2304,"amount"));}
        if(condition.equals("playerEffect"))requiredText(config,"effect",100);
        if(condition.equals("chance"))config.put("percent",number(config.getOrDefault("percent",50),0,100,"percent"));
        if(condition.equals("playerDistance"))for(String coordinate:List.of("x","y","z"))number(config.get(coordinate),-30000000,30000000,coordinate);
        if(config.containsKey("world"))text(config,"world","",100);if(config.containsKey("uuid"))uuid(text(config,"uuid","",36));config.put("operator",operator);
    }
    public static boolean compare(Object actual,String operator,Object expected){
        if(actual instanceof Number a&&expected instanceof Number b){double x=a.doubleValue(),y=b.doubleValue();if(!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("Condition returned non-finite value");return switch(operator){case "eq"->x==y;case "ne"->x!=y;case "lt"->x<y;case "lte"->x<=y;case "gt"->x>y;case "gte"->x>=y;default->throw new IllegalArgumentException("Invalid operator");};}
        return switch(operator){case "eq"->Objects.equals(actual,expected);case "ne"->!Objects.equals(actual,expected);default->throw new IllegalArgumentException("Invalid comparison");};
    }

    public static void validateAction(String action,Map<String,Object> p){
        if(!ACTIONS.contains(action))throw new IllegalArgumentException("Unsupported automation action");
        if(p.containsKey("uuid"))uuid(text(p,"uuid","",36));if(p.containsKey("world"))text(p,"world","",100);
        switch(action){
            case "chat.broadcast","player.message" -> requiredText(p,"message",1000);
            case "player.actionbar" -> requiredText(p,"message",500);
            case "player.title" -> {text(p,"title","",200);text(p,"subtitle","",300);optionalNumber(p,"seconds",1,30,true);}
            case "player.sound" -> {text(p,"sound","minecraft:entity.experience_orb.pickup",150);optionalNumber(p,"volume",0,4,false);optionalNumber(p,"pitch",.5,2,false);}
            case "player.effect" -> {requiredText(p,"effect",100);optionalNumber(p,"duration",1,72000,true);optionalNumber(p,"amplifier",0,9,true);for(String b:List.of("remove","ambient","particles"))if(p.containsKey(b))bool(p.get(b),b);}
            case "world.time" -> integer(p.get("time"),0,23999,"time");
            case "world.weather" -> {if(!Set.of("clear","rain","thunder").contains(text(p,"weather","",8)))throw new IllegalArgumentException("Invalid weather");optionalNumber(p,"durationTicks",20,240000,true);}
            case "player.give" -> {Map<String,Object> item=map(p.get("item"),"item");requiredText(item,"material",100);optionalNumber(item,"amount",1,64,true);text(item,"name","",200);if(item.containsKey("lore"))for(Object line:list(item.get("lore"),0,20,"lore"))if(!(line instanceof String s)||s.length()>300)throw new IllegalArgumentException("Invalid lore");optionalNumber(item,"damage",0,100000,true);if(item.containsKey("unbreakable"))bool(item.get("unbreakable"),"unbreakable");if(item.containsKey("enchantments")){Map<String,Object> ench=map(item.get("enchantments"),"enchantments");if(ench.size()>20)throw new IllegalArgumentException("Too many enchantments");for(var e:ench.entrySet()){if(e.getKey().length()>100)throw new IllegalArgumentException("Invalid enchantment");integer(e.getValue(),1,255,"enchantment level");}}if(item.containsKey("flags"))for(Object flag:list(item.get("flags"),0,15,"flags"))if(!(flag instanceof String s)||s.length()>100)throw new IllegalArgumentException("Invalid flag");if(item.containsKey("attributes"))for(Object attribute:list(item.get("attributes"),0,12,"attributes")){Map<String,Object> attr=map(attribute,"attribute");requiredText(attr,"attribute",100);number(attr.get("amount"),-100,100,"attribute amount");if(!Set.of("ADD_NUMBER","ADD_SCALAR","MULTIPLY_SCALAR_1").contains(text(attr,"operation","",32)))throw new IllegalArgumentException("Invalid attribute operation");}}
            case "player.firework" -> {}
            case "player.heal" -> optionalNumber(p,"amount",0,2048,false);
            case "player.feed" -> {optionalNumber(p,"food",0,20,true);optionalNumber(p,"saturation",0,20,false);}
            case "player.xp" -> integer(p.get("amount"),0,100000,"amount");
            case "player.level" -> integer(p.get("level"),0,10000,"level");
            case "player.extinguish" -> {}
            case "player.glowing" -> bool(p.get("enabled"),"enabled");
            case "player.particles" -> {requiredText(p,"particle",100);optionalNumber(p,"count",1,100,true);}
            case "player.teleport" -> {for(String key:List.of("x","y","z"))number(p.get(key),-30000000,30000000,key);}
            case "world.difficulty" -> {if(!Set.of("PEACEFUL","EASY","NORMAL","HARD").contains(text(p,"difficulty","",16)))throw new IllegalArgumentException("Invalid difficulty");}
            case "world.pvp" -> bool(p.get("enabled"),"enabled");
            case "world.gamerule" -> {requiredText(p,"rule",100);Object value=p.get("value");if(!(value instanceof Boolean))integer(value,0,1000000,"value");}
            case "event.start" -> dev.mcc.game.GameEventSpec.normalize(p);
            case "event.stop","event.pause" -> {boolean last=bool(p.getOrDefault("fromLastEvent",false),"fromLastEvent"),trigger=bool(p.getOrDefault("fromTrigger",false),"fromTrigger");if(last&&trigger||(last||trigger)&&(p.containsKey("eventId")||p.containsKey("id")))throw new IllegalArgumentException("Choose exactly one event id source");if(!last&&!trigger){if(p.containsKey("eventId"))uuid(text(p,"eventId","",36));else if(p.containsKey("id"))uuid(text(p,"id","",36));else throw new IllegalArgumentException("Event id required");}if(action.equals("event.pause"))bool(p.getOrDefault("paused",true),"paused");}
            default -> throw new IllegalArgumentException("Unsupported automation action");
        }
        for(Object value:p.values())if(value instanceof Number n&&!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Non-finite action parameter");
    }
    public static Map<String,Object> catalog(){return Map.of("graphVersion",1,"triggers",TRIGGERS.stream().sorted().toList(),"conditions",CONDITIONS.stream().sorted().toList(),"actions",ACTIONS.stream().sorted().toList(),"operators",NUMERIC_OPS.stream().sorted().toList(),"limits",Map.of("nodes",32,"edges",64,"totalDelaySeconds",60,"rules",100,"profiles",20,"concurrentRuns",4,"history",200,"queuedSignals",128,"operationsPer250ms",8,"minimumCooldownSeconds",30),"edgeHandles",Map.of("condition",List.of("true","false"),"other",List.of("next")),"execution","Every enabled profile remains active; topological order; merged nodes execute once; cancelled runs never resume");}
    public static Map<String,Object> context(Map<String,Object> body){Map<String,Object> out=new LinkedHashMap<>();if(body==null)return out;if(body.containsKey("uuid"))out.put("uuid",uuid(text(body,"uuid","",36)));for(String key:List.of("world","name","material","mob","advancement","preset"))if(body.containsKey(key))out.put(key,text(body,key,"",150));if(body.containsKey("eventId"))out.put("eventId",uuid(text(body,"eventId","",36)));if(body.containsKey("level"))out.put("level",integer(body.get("level"),0,100000,"level"));return out;}
    public static String uuid(String value){try{String canonical=UUID.fromString(value).toString();if(!canonical.equals(value))throw new IllegalArgumentException();return canonical;}catch(Exception e){throw new IllegalArgumentException("Canonical UUID required");}}
    static Map<String,Object> map(Object value,String key){if(!(value instanceof Map<?,?> raw))throw new IllegalArgumentException("Invalid "+key);Map<String,Object> out=new LinkedHashMap<>();for(var entry:raw.entrySet()){if(!(entry.getKey() instanceof String k))throw new IllegalArgumentException("Invalid "+key);out.put(k,entry.getValue());}return out;}
    static List<?> list(Object value,int min,int max,String key){if(!(value instanceof List<?> list)||list.size()<min||list.size()>max)throw new IllegalArgumentException("Invalid "+key+" size");return list;}
    static String text(Map<String,Object> body,String key,String fallback,int max){String value=Json.text(body,key,fallback);if(value.length()>max||value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid "+key);return value;}
    private static void requiredText(Map<String,Object> body,String key,int max){if(text(body,key,"",max).isEmpty())throw new IllegalArgumentException("Missing "+key);}
    private static String identifier(String value,int max,String key){if(value.isEmpty()||value.length()>max||!value.matches("[A-Za-z0-9_.:-]+"))throw new IllegalArgumentException("Invalid "+key);return value;}
    static boolean bool(Object value,String key){if(!(value instanceof Boolean b))throw new IllegalArgumentException("Invalid "+key);return b;}
    static double number(Object value,double min,double max,String key){if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)throw new IllegalArgumentException("Invalid "+key);return n.doubleValue();}
    static int integer(Object value,int min,int max,String key){double n=number(value,min,max,key);if(n!=Math.rint(n))throw new IllegalArgumentException("Invalid integer "+key);return (int)n;}
    private static void optionalNumber(Map<String,Object> body,String key,double min,double max,boolean integral){if(body.containsKey(key)){if(integral)integer(body.get(key),(int)min,(int)max,key);else number(body.get(key),min,max,key);}}
}
