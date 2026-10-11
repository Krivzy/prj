package dev.mcc.server;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded, typed merge. Unknown settings are retained; no arbitrary HTTP keys are accepted. */
final class KernelConfigFiles {
    static final int MAX_BYTES = 1_048_576;
    enum Kind { PROPERTIES, SPIGOT, PAPER, PURPUR }
    record Edit(List<String> path, Object value) {
        Edit { path=List.copyOf(path); if(path.isEmpty()||path.size()>5||path.stream().anyMatch(k->k==null||k.isBlank()||k.length()>512))throw new IllegalArgumentException("Invalid kernel setting path");validateValue(path.getLast(),value); }
    }
    record Merged(byte[] bytes,List<Map<String,Object>> changes) {}
    private KernelConfigFiles() {}

    static Merged merge(Kind kind,byte[] original,List<Edit> edits) throws IOException {
        if(original.length>MAX_BYTES)throw new IOException("Kernel configuration exceeds 1 MiB");
        String text=utf8(original);
        if(text.indexOf('\0')>=0)throw new IOException("Invalid kernel configuration encoding");
        for(Edit edit:edits)validatePath(kind,edit.path());
        return kind==Kind.PROPERTIES?properties(text,edits):yaml(kind,text,edits);
    }
    static Map<String,Object> readYaml(Kind kind,byte[] original)throws IOException {
        if(original.length>MAX_BYTES)throw new IOException("Kernel configuration exceeds 1 MiB");
        LoaderOptions limits=new LoaderOptions();limits.setAllowDuplicateKeys(false);limits.setMaxAliasesForCollections(0);
        limits.setNestingDepthLimit(24);limits.setCodePointLimit(MAX_BYTES);
        try {
            Object root=new Yaml(new SafeConstructor(limits)).load(utf8(original));
            if(!(root instanceof Map<?,?> map))throw new IOException("Kernel YAML must be a mapping");
            int[] count={0};Map<String,Object> data=copyMap(map,0,count);
            Object version=data.get(kind==Kind.PAPER?"_version":"config-version");
            boolean supported=version instanceof Integer v&&switch(kind){case PAPER->v==31;case SPIGOT->v==12||v==13;case PURPUR->v==49;default->false;};
            if(!supported)throw new IOException("Unsupported "+kind.name().toLowerCase(Locale.ROOT)+" configuration schema");
            return data;
        }catch(IOException e){throw e;}catch(RuntimeException e){throw new IOException("Invalid kernel YAML",e);}
    }
    private static Map<String,Object> copyMap(Map<?,?> input,int depth,int[] count)throws IOException {
        if(depth>24||input.size()>32768)throw new IOException("Kernel YAML nesting or collection bound exceeded");
        Map<String,Object> out=new LinkedHashMap<>();
        for(var e:input.entrySet()){
            if(!(e.getKey() instanceof String key)||key.length()>512||++count[0]>32768)throw new IOException("Unsupported kernel YAML key");
            out.put(key,copyValue(e.getValue(),depth+1,count));
        }return out;
    }
    private static Object copyValue(Object value,int depth,int[] count)throws IOException {
        if(depth>24||++count[0]>32768)throw new IOException("Kernel YAML nesting or node bound exceeded");
        if(value instanceof Map<?,?> m)return copyMap(m,depth,count);
        if(value instanceof List<?> l){if(l.size()>32768)throw new IOException("Kernel YAML collection bound exceeded");List<Object> out=new ArrayList<>();for(Object v:l)out.add(copyValue(v,depth+1,count));return out;}
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Number||value instanceof Date)return value;
        throw new IOException("Unsupported kernel YAML value");
    }
    private static Merged yaml(Kind kind,String text,List<Edit> edits)throws IOException {
        Map<String,Object> data=readYaml(kind,text.getBytes(StandardCharsets.UTF_8));List<Map<String,Object>> changes=new ArrayList<>();
        for(Edit edit:edits){Map<String,Object> node=data;List<String> p=edit.path();
            for(int i=0;i<p.size()-1;i++){
                Object next=node.get(p.get(i));if(next==null){Map<String,Object> child=new LinkedHashMap<>();node.put(p.get(i),child);node=child;}
                else if(next instanceof Map<?,?> child){@SuppressWarnings("unchecked") Map<String,Object> cast=(Map<String,Object>)child;node=cast;}
                else throw new IOException("Kernel setting parent is not a mapping");
            }
            String key=p.getLast();Object before=node.get(key);if(before!=null)validateExisting(edit,before);
            if(!Objects.equals(before,edit.value())){node.put(key,edit.value());changes.add(change(p,before,edit.value()));}
        }
        if(changes.isEmpty())return new Merged(text.getBytes(StandardCharsets.UTF_8),List.of());
        DumperOptions options=new DumperOptions();options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);options.setIndent(2);options.setWidth(120);options.setSplitLines(false);options.setAllowUnicode(true);
        byte[] output=new Yaml(options).dump(data).getBytes(StandardCharsets.UTF_8);if(output.length>MAX_BYTES)throw new IOException("Merged configuration exceeds bound");
        // A second safe parse establishes that the emitted bytes retain a supported schema.
        readYaml(kind,output);return new Merged(output,List.copyOf(changes));
    }
    private static Merged properties(String text,List<Edit> edits)throws IOException {
        Properties data=new Properties();data.load(new StringReader(text));
        List<String> lines=new ArrayList<>(Arrays.asList(text.split("(?<=\\n)",-1)));List<Map<String,Object>> changes=new ArrayList<>();
        for(Edit edit:edits){if(edit.path().size()!=1)throw new IOException("Invalid properties setting");String key=edit.path().getFirst();
            if(!Set.of("view-distance","simulation-distance").contains(key))throw new IOException("Setting is outside properties allowlist");
            String existing=data.getProperty(key);if(existing==null)throw new IOException("Missing supported server.properties distance setting");
            int old;try{old=Integer.parseInt(existing.trim());}catch(NumberFormatException e){throw new IOException("Invalid existing distance setting");}
            if(old<2||old>32)throw new IOException("Existing distance is outside supported bounds");
            Pattern match=Pattern.compile("^\\s*"+Pattern.quote(key)+"\\s*[:=].*$");int index=-1;
            for(int i=0;i<lines.size();i++){String line=lines.get(i).replaceFirst("\\r?\\n$","");Properties entry=new Properties();entry.load(new StringReader(line));
                if(entry.containsKey(key)){if(index>=0)throw new IOException("Duplicate managed properties key");if(!match.matcher(line).matches()||line.endsWith("\\"))throw new IOException("Unsupported managed properties syntax");index=i;}
            }
            if(index<0)throw new IOException("Unsupported managed properties syntax");
            if(!Objects.equals(old,edit.value())){String line=lines.get(index);String ending=line.endsWith("\r\n")?"\r\n":line.endsWith("\n")?"\n":"";lines.set(index,key+"="+edit.value()+ending);changes.add(change(edit.path(),old,edit.value()));}
        }
        return new Merged(String.join("",lines).getBytes(StandardCharsets.UTF_8),List.copyOf(changes));
    }
    private static Map<String,Object> change(List<String> p,Object before,Object after){Map<String,Object> m=new LinkedHashMap<>();m.put("key",String.join(".",p));m.put("before",before);m.put("after",after);return m;}
    private static void validateExisting(Edit edit,Object before)throws IOException {
        Object next=edit.value();boolean valid=next instanceof Boolean?before instanceof Boolean:next instanceof Integer?before instanceof Integer:next instanceof Double?before instanceof Number n&&Double.isFinite(n.doubleValue()):before instanceof String;
        // Spigot's per-world distance sentinel is deliberately replaced by a fixed managed value.
        if(next instanceof Integer&&Set.of("view-distance","simulation-distance").contains(edit.path().getLast())&&"default".equals(before))valid=true;
        if(edit.path().getLast().equals("redstone-implementation"))valid=before instanceof String s&&Set.of("VANILLA","EIGENCRAFT","ALTERNATE_CURRENT").contains(s.toUpperCase(Locale.ROOT));
        if(edit.path().getLast().equals("delay-chunk-unloads-by"))valid=before instanceof String s&&s.matches("\\d{1,8}(?:ms|s|m|h|d)");
        if(!valid)throw new IOException("Managed setting has an unsupported existing type");
    }
    private static void validateValue(String key,Object value){
        boolean valid=switch(key){
            case "view-distance","simulation-distance" -> value instanceof Integer n&&n>=2&&n<=32;
            case "max-entity-collisions" -> value instanceof Integer n&&n>=1&&n<=8;
            case "mob-spawn-range" -> value instanceof Integer n&&n>=1&&n<=8;
            case "animals","monsters","raiders","misc","water","villagers","flying-monsters" -> value instanceof Integer n&&n>=0&&n<=128;
            case "max-auto-save-chunks-per-tick" -> value instanceof Integer n&&n>=8&&n<=200;
            case "item" -> value instanceof Double n&&Double.isFinite(n)&&n>=0&&n<=3;
            case "redstone-implementation" -> value instanceof String s&&Set.of("VANILLA","ALTERNATE_CURRENT").contains(s);
            case "delay-chunk-unloads-by" -> value instanceof String s&&Set.of("0s","10s").contains(s);
            case "tick-inactive-villagers","nerf-spawner-mobs","optimize-explosions","only-merge-items-horizontally","update-pathfinding-on-block-update","tps-catchup","use-alternate-keepalive" -> value instanceof Boolean;
            default -> false;
        };if(!valid)throw new IllegalArgumentException("Kernel setting is outside typed allowlist");
    }
    private static void validatePath(Kind kind,List<String> path)throws IOException {
        String dotted=String.join(".",path);boolean valid=switch(kind){
            case PROPERTIES -> path.size()==1&&Set.of("view-distance","simulation-distance").contains(dotted);
            case PAPER -> Set.of("misc.redstone-implementation","misc.update-pathfinding-on-block-update","environment.optimize-explosions","chunks.max-auto-save-chunks-per-tick","chunks.delay-chunk-unloads-by","entities.behavior.only-merge-items-horizontally","collisions.max-entity-collisions").contains(dotted);
            case PURPUR -> Set.of("settings.use-alternate-keepalive","settings.tps-catchup").contains(dotted);
            case SPIGOT -> path.size()>=3&&path.getFirst().equals("world-settings")&&(path.size()==3&&Set.of("view-distance","simulation-distance","mob-spawn-range","max-entity-collisions","tick-inactive-villagers","nerf-spawner-mobs").contains(path.getLast())||path.size()==4&&(path.get(2).equals("merge-radius")&&path.getLast().equals("item")||path.get(2).equals("entity-activation-range")&&Set.of("animals","monsters","raiders","misc","water","villagers","flying-monsters").contains(path.getLast())));
        };if(!valid)throw new IOException("Kernel setting path is outside the supported allowlist");
    }
    private static String utf8(byte[] bytes)throws IOException {
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException e){throw new IOException("Invalid kernel configuration UTF-8",e);}
    }
}
