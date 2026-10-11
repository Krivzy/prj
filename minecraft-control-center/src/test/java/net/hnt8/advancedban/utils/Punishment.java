package net.hnt8.advancedban.utils;

import net.hnt8.advancedban.manager.PunishmentManager;

/** Public constructor/methods mirror ABZ 4.0.1; behavior is intentionally in-memory only. */
public final class Punishment {
    private final String name,uuid,reason,operator;
    private final PunishmentType type;
    private final long start,end;
    private int id;
    public Punishment(String name,String uuid,String reason,String operator,PunishmentType type,long start,long end,String calculation,int id){this.name=name;this.uuid=uuid;this.reason=reason;this.operator=operator;this.type=type;this.start=start;this.end=end;this.id=id;}
    public void create(boolean silent){id=PunishmentManager.get().creates+1;PunishmentManager.get().save(this);}
    public void delete(String actor,boolean mass,boolean silent){PunishmentManager.get().remove(id);}
    public int getId(){return id;}public String getName(){return name;}public String getUuid(){return uuid;}public String getReason(){return reason;}public String getOperator(){return operator;}
    public PunishmentType getType(){return type;}public long getStart(){return start;}public long getEnd(){return end;}public boolean isExpired(){return false;}
}
