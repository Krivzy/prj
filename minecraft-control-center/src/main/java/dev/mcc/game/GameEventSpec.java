package dev.mcc.game;

import dev.mcc.core.Json;
import java.util.*;

/** Pure catalog and bounds shared by manual events and persisted graph actions. */
public final class GameEventSpec {
    public record Field(String key,String ru,String en,String kind,Object value,double min,double max,List<String> options) {
        Map<String,Object> dto(){return GameService.map("key",key,"ru",ru,"en",en,"kind",kind,"default",value,"min",min,"max",max,"options",options);}
    }
    public record Definition(String id,String ru,String en,String category,String icon,String description,String descriptionEn,List<Field> fields) {
        public Map<String,Object> dto(){return GameService.map("id",id,"ru",ru,"en",en,"category",category,"icon",icon,"description",description,"descriptionEn",descriptionEn,"fields",fields.stream().map(Field::dto).toList());}
    }
    private static Field number(String key,String ru,String en,double value,double min,double max){return new Field(key,ru,en,"number",value,min,max,List.of());}
    private static Field choice(String key,String ru,String en,String value,String...options){return new Field(key,ru,en,"select",value,0,0,List.of(options));}
    private static Field registry(String key,String ru,String en,String kind,String value){return new Field(key,ru,en,kind,value,0,0,List.of());}
    private static Definition definition(String id,String ru,String en,String category,String icon,String description,String descriptionEn,Field...fields){return new Definition(id,ru,en,category,icon,description,descriptionEn,List.of(fields));}
    private static final Field MOB=registry("mob","Существо","Creature","mob","ZOMBIE");
    private static final Field HEALTH=number("health","Здоровье","Health",40,1,200);
    private static final Field LOOT=new Field("loot","Ресурсы и количество","Resources and amounts","loot",List.of(Map.of("material","DIAMOND","amount",1,"weight",1)),0,0,List.of());
    private static final Field REWARD=new Field("reward","Награда победителю","Winner reward","loot",List.of(Map.of("material","EMERALD","amount",1,"weight",1)),0,0,List.of());
    private static final Field STRENGTH=number("strength","Усиление эффекта (0 — I)","Effect amplifier (0 is I)",0,0,3);
    public static final List<Field> COMMON=List.of(number("count","Число импульсов / объектов","Pulses / objects",12,1,128),number("intervalTicks","Интервал, тики","Interval, ticks",100,20,1200),number("durationSeconds","Длительность, сек.","Duration, seconds",120,10,3600),number("radius","Радиус, блоки","Radius, blocks",8,0,32));
    public static final List<Definition> DEFINITIONS=List.of(
        definition("waves","Волны мобов","Mob waves","challenge","zombie_head","Настраиваемые волны противников; только созданные событием мобы очищаются.","Configurable enemy waves; only event-owned creatures are cleaned up.",MOB,HEALTH,number("batch","Мобов в волне","Mobs per wave",3,1,8)),
        definition("fireworks","Фейерверки","Fireworks","atmosphere","firework_rocket","Цвет, форма и мощность праздничного салюта. Визуальные ракеты не наносят урон.","Set celebration color, shape and power. Visual rockets cause no damage.",registry("color","Цвет HEX","HEX color","color","81e9a1"),choice("shape","Форма","Shape","BALL","BALL","BALL_LARGE","STAR","BURST","CREEPER"),number("power","Высота полёта","Flight power",0,0,2)),
        definition("lightning","Гроза","Lightning storm","atmosphere","lightning_rod","Серия визуальных молний без огня, взрывов и урона.","A sequence of visual lightning without fire, explosions or damage."),
        definition("treasure","Сокровища","Treasure","reward","chest","Выберите ресурсы, размер каждой стопки и вероятность выпадения.","Choose resources, stack sizes and weighted drops.",LOOT),
        definition("sanctuary","Заповедник","Animal sanctuary","living","cow_spawn_egg","Группа выбранных мирных животных; взрослые или детёныши.","A group of selected peaceful animals, adults or babies.",choice("animal","Животное","Animal","COW","COW","SHEEP","PIG","CHICKEN","RABBIT","FOX"),choice("age","Возраст","Age","adult","adult","baby")),
        definition("champion","Чемпион арены","Arena champion","challenge","wither_skeleton_skull","Один противник с выбранным здоровьем, уроном и полосой босса.","One enemy with chosen health, damage and a boss bar.",MOB,HEALTH,number("damage","Урон","Attack damage",6,0,20)),
        definition("xp-rain","Дождь опыта","Experience rain","reward","experience_bottle","Ограниченное число сфер с указанным количеством XP.","A bounded number of experience orbs with chosen XP.",number("xp","XP на сферу","XP per orb",5,1,100)),
        definition("supply-drop","Снабжение","Supply drop","reward","barrel","Наборы инструментов и материалов выпадают по заданным весам.","Drop tools and materials using configured weights.",LOOT),
        definition("food-rain","Пикник","Food rain","reward","cooked_beef","Раздача выбранной еды; несъедобные ресурсы отклоняются.","Drop chosen food; non-edible resources are rejected.",new Field("loot","Еда и количество","Food and amounts","loot",List.of(Map.of("material","BREAD","amount",2,"weight",1)),0,0,List.of())),
        definition("meteor-show","Метеорный дождь","Meteor shower","atmosphere","fire_charge","Следы и вспышки метеоров без изменения блоков.","Meteor trails and flashes without changing blocks.",number("height","Высота следа","Trail height",12,2,24),number("particles","Плотность частиц","Particle density",24,1,80)),
        definition("aurora","Северное сияние","Aurora","atmosphere","amethyst_shard","Цветная полоса частиц над выбранной площадкой.","A colored particle ribbon above the selected area.",registry("color","Цвет HEX","HEX color","color","b6a7dc"),number("height","Высота","Height",8,1,24),number("particles","Частиц на импульс","Particles per pulse",30,1,80)),
        definition("music","Концерт нотных блоков","Note block concert","atmosphere","note_block","Последовательность нот с выбранным инструментом, громкостью и темпом.","A note sequence with selected instrument, volume and tempo.",registry("sound","Инструмент / звук","Instrument / sound","sound","minecraft:block.note_block.harp"),number("volume","Громкость","Volume",1,0,2),number("pitch","Базовый тон","Base pitch",1,0.5,1.5)),
        definition("healing-zone","Лагерь восстановления","Healing camp","support","golden_apple","Восстанавливает указанное число сердец игрокам в радиусе.","Restores a chosen number of hearts to players in range.",number("hearts","Сердец на импульс","Hearts per pulse",1,0.5,5)),
        definition("feast-zone","Общий стол","Community feast","support","cake","Постепенно восстанавливает голод в пределах площадки.","Gradually restores hunger within the event area.",number("food","Еды на импульс","Food points per pulse",2,1,10)),
        definition("speed-trial","Спринт","Sprint trial","support","sugar","Короткие импульсы скорости игрокам в радиусе.","Short speed pulses for players in range.",STRENGTH,number("effectSeconds","Длительность импульса","Pulse duration",5,1,30)),
        definition("moonwalk","Лунная походка","Moonwalk","support","feather","Прыжок и медленное падение без выдачи режима полёта.","Jump boost and slow falling without granting flight.",STRENGTH,number("effectSeconds","Длительность импульса","Pulse duration",5,1,30)),
        definition("glowing-hunt","Охота на огоньки","Glowing hunt","challenge","glow_ink_sac","Найдите и победите подсвеченных противников; победитель получает награду.","Find and defeat glowing enemies; the winner receives a reward.",MOB,HEALTH,number("targetScore","Побед для награды","Kills for reward",3,1,64),REWARD),
        definition("elite-patrol","Элитный патруль","Elite patrol","challenge","iron_sword","Противники с выбранной бронёй и оружием; экипировка не выпадает.","Enemies with selected armor and weapons; equipment does not drop.",MOB,HEALTH,choice("equipment","Экипировка","Equipment","iron","leather","iron","diamond")),
        definition("slime-swarm","Нашествие слизней","Slime swarm","challenge","slime_ball","Слизни выбранного размера; деление на новых неподконтрольных мобов отключено.","Slimes of chosen size; uncontrolled splitting is disabled.",number("size","Размер слизня","Slime size",2,1,4)),
        definition("bee-garden","Пчелиный сад","Bee garden","living","honeycomb","Мирные пчёлы с ограниченным временем жизни и числом сущностей.","Peaceful bees with bounded lifetime and entity count."),
        definition("snowball-arena","Снежная арена","Snowball arena","challenge","snowball","Снежные залпы от центра с выбранной скоростью, без изменения блоков.","Snowball volleys from the center at chosen speed, without block changes.",number("speed","Скорость залпа","Volley speed",0.7,0.1,1.5)),
        definition("egg-hunt","Поиск яиц","Egg hunt","challenge","egg","Собирайте подписанные событием яйца; счёт учитывает только собственные предметы.","Collect event-tagged eggs; scoring counts only event-owned items.",number("targetScore","Яиц для победы","Eggs to win",5,1,64),REWARD),
        definition("checkpoint-race","Гонка по маякам","Checkpoint race","challenge","compass","Пройдите последовательность точек с частицами; блоки не строятся.","Visit a sequence of particle checkpoints; no blocks are built.",new Field("checkpoints","Точки маршрута","Route checkpoints","checkpoints",List.of(Map.of("x",0,"y",0,"z",0),Map.of("x",4,"y",0,"z",0)),0,0,List.of()),number("checkpointRadius","Радиус зачёта точки","Checkpoint reach radius",2,1,5),REWARD),
        definition("fishing-festival","Рыбацкий турнир","Fishing festival","challenge","fishing_rod","Счёт за успешный улов в радиусе и награда за заданное число рыб.","Score successful catches within the radius and reward a chosen fish count.",number("targetScore","Уловов для победы","Catches to win",3,1,64),REWARD)
    );
    private GameEventSpec(){}
    public static String canonical(String id){return switch(id){case "animals"->"sanctuary";case "boss"->"champion";default->id;};}
    public static Definition definition(String id){String key=canonical(id);return DEFINITIONS.stream().filter(d->d.id().equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("Неизвестное событие"));}
    public static Map<String,Object> normalize(Map<String,Object> input){
        if(input==null||Json.write(input).length()>32768)throw new IllegalArgumentException("Слишком большой план события");
        Object rawId=input.get("preset");if(!(rawId instanceof String id))throw new IllegalArgumentException("Выберите событие");Definition d=definition(id);
        Map<String,Object> p=new LinkedHashMap<>();p.put("preset",d.id());
        for(String key:List.of("world","uuid","x","y","z"))if(input.containsKey(key))p.put(key,input.get(key));
        if(p.containsKey("world")&&(!(p.get("world") instanceof String world)||world.isBlank()||world.length()>100))throw new IllegalArgumentException("Выберите мир");
        if(p.containsKey("uuid")){if(!(p.get("uuid") instanceof String uuid)||!UUID.fromString(uuid).toString().equals(uuid))throw new IllegalArgumentException("UUID игрока");}
        int coordinates=0;for(String key:List.of("x","y","z"))if(p.containsKey(key))coordinates++;if(coordinates!=0&&coordinates!=3)throw new IllegalArgumentException("Укажите все X, Y, Z");
        for(String key:List.of("x","y","z"))if(p.containsKey(key))bounded(p.get(key),-30000000,30000000,key);
        List<Field> fields=new ArrayList<>(COMMON);fields.addAll(d.fields());
        for(Field f:fields){Object value=input.getOrDefault(f.key(),f.value());if(f.key().equals("loot")&&input.containsKey("item"))value=List.of(input.get("item"));
            switch(f.kind()){
                case "number"->{double n=bounded(value,f.min(),f.max(),f.key());if(!Set.of("radius","damage","hearts","pitch","volume","speed").contains(f.key())&&n!=Math.rint(n))throw new IllegalArgumentException(f.ru()+": целое число");}
                case "select"->{if(!(value instanceof String s)||!f.options().contains(s))throw new IllegalArgumentException("Неверный параметр "+f.ru());}
                case "color"->{if(!(value instanceof String s)||!s.matches("[a-fA-F0-9]{6}"))throw new IllegalArgumentException("Цвет: 6 HEX цифр");}
                case "mob","sound"->{if(!(value instanceof String s)||!s.matches("[a-zA-Z0-9_:.-]{1,150}"))throw new IllegalArgumentException("Неверный параметр "+f.ru());if(f.kind().equals("mob")&&!Set.of("ZOMBIE","HUSK","DROWNED","SKELETON","STRAY","BOGGED","SPIDER","CAVE_SPIDER","ZOMBIFIED_PIGLIN").contains(s))throw new IllegalArgumentException("Этот противник не разрешён в событиях");}
                case "loot"->value=loot(value);
                case "checkpoints"->{if(!(value instanceof List<?> rows)||rows.size()<2||rows.size()>12)throw new IllegalArgumentException("Маршрут: от 2 до 12 точек");List<Map<String,Object>> points=new ArrayList<>();for(Object row:rows){if(!(row instanceof Map<?,?> r))throw new IllegalArgumentException("Точка маршрута");Map<String,Object> point=new LinkedHashMap<>();for(String key:List.of("x","y","z"))point.put(key,bounded(r.get(key),-32,32,"offset "+key));points.add(Map.copyOf(point));}value=List.copyOf(points);}
                default->throw new IllegalArgumentException("Неизвестный тип параметра");
            }p.put(f.key(),value);
        }
        if(d.id().equals("champion"))p.put("count",1);
        return Collections.unmodifiableMap(p);
    }
    public static List<Map<String,Object>> loot(Object value){
        if(!(value instanceof List<?> rows)||rows.isEmpty()||rows.size()>12)throw new IllegalArgumentException("Ресурсы: от 1 до 12 строк");List<Map<String,Object>> result=new ArrayList<>();
        for(Object row:rows){if(!(row instanceof Map<?,?> r)||!(r.get("material") instanceof String material)||!material.matches("[a-zA-Z0-9_:]{1,100}"))throw new IllegalArgumentException("Выберите ресурс");Object amount=r.containsKey("amount")?r.get("amount"):1,weight=r.containsKey("weight")?r.get("weight"):1;double a=bounded(amount,1,64,"amount"),w=bounded(weight,1,100,"weight");if(a!=Math.rint(a)||w!=Math.rint(w))throw new IllegalArgumentException("Количество и вес: целые числа");result.add(Map.of("material",material,"amount",(int)a,"weight",(int)w));}
        return List.copyOf(result);
    }
    public static double bounded(Object value,double min,double max,String key){if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)throw new IllegalArgumentException(key+": от "+min+" до "+max);return n.doubleValue();}
    public static int weightedIndex(List<Map<String,Object>> rows,int random){int total=rows.stream().mapToInt(r->((Number)r.get("weight")).intValue()).sum();int pick=Math.floorMod(random,total);for(int i=0;i<rows.size();i++){pick-=((Number)rows.get(i).get("weight")).intValue();if(pick<0)return i;}throw new IllegalStateException();}
}
