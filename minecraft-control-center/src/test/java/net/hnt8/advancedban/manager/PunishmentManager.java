package net.hnt8.advancedban.manager;

import net.hnt8.advancedban.utils.*;
import java.util.*;

/** In-memory test records, unrelated to actual provider storage. */
public final class PunishmentManager {
    private static final PunishmentManager INSTANCE=new PunishmentManager();
    public final Map<Integer,Punishment> rows=new LinkedHashMap<>();
    public int reads,creates,deletes;
    public static PunishmentManager get(){return INSTANCE;}
    public void reset(){rows.clear();reads=creates=deletes=0;}
    public List<Punishment> getPunishments(String target,PunishmentType type,boolean cached){reads++;return rows.values().stream().filter(p->p.getUuid().equals(target)).toList();}
    public Punishment getPunishment(int id){return rows.get(id);}
    public void save(Punishment row){creates++;rows.put(row.getId(),row);}
    public void remove(int id){deletes++;rows.remove(id);}
}
