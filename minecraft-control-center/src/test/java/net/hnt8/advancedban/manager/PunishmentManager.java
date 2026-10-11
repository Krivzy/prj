package net.hnt8.advancedban.manager;

import net.hnt8.advancedban.utils.*;
import java.util.*;

/** In-memory test records, unrelated to actual provider storage. */
public final class PunishmentManager {
    private static final PunishmentManager INSTANCE=new PunishmentManager();
    public final Map<Integer,Punishment> rows=new LinkedHashMap<>();
    public final Map<Integer,Punishment> history=new LinkedHashMap<>();
    public int reads,creates,deletes,lastLimit;
    public static PunishmentManager get(){return INSTANCE;}
    public void reset(){rows.clear();history.clear();reads=creates=deletes=lastLimit=0;}
    public List<Punishment> getPunishments(String target,PunishmentType type,boolean cached){reads++;return rows.values().stream().filter(p->p.getUuid().equals(target)).toList();}
    public List<Punishment> getPunishments(SQLQuery query,Object...parameters){reads++;lastLimit=((Number)parameters[0]).intValue();return (query==SQLQuery.SELECT_ALL_PUNISHMENTS_HISTORY_LIMIT?history:rows).values().stream().sorted(Comparator.comparingLong(Punishment::getStart).reversed()).limit(lastLimit).toList();}
    public Punishment getPunishment(int id){return rows.get(id);}
    public void save(Punishment row){creates++;rows.put(row.getId(),row);int historyId=history.size()+100;history.put(historyId,new Punishment(row.getName(),row.getUuid(),row.getReason(),row.getOperator(),row.getType(),row.getStart(),row.getEnd(),null,historyId));}
    public void remove(int id){deletes++;rows.remove(id);}
}
