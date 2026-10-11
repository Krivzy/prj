export type OreWindow=60|300|600;
export type OreSeverity='GREEN'|'YELLOW'|'RED';
export const ORE_LABELS={
  diamond:['Алмазная','Diamond'],gold:['Золотая','Gold'],redstone:['Редстоун','Redstone'],coal:['Угольная','Coal'],lapis:['Лазурит','Lapis'],emerald:['Изумрудная','Emerald'],iron:['Железная','Iron'],copper:['Медная','Copper'],quartz:['Кварц','Quartz'],nether_gold:['Золото Незера','Nether gold'],ancient_debris:['Древние обломки','Ancient debris'],
} as const;
export type OreId=keyof typeof ORE_LABELS;
export type OrePlayer={id:string;name:string;world:string;online:boolean;lastMinedAt:number;counts:Record<string,Record<string,number>>;total:number;severity:OreSeverity;flaggedOres:OreId[]};
export type OreThreshold={id:OreId;material:string;yellow:number;red:number};
export type OreSnapshot={generatedAt:number;startedAt:number;observedSeconds:number;windowSeconds:number;windows:number[];ores:OreThreshold[];players:OrePlayer[];coverage:{placedTracking:string;unknownOlderPlaced:boolean;placedTtlSeconds:number;placedTracked:number;placedExcluded:number;placedEvicted:number;eventsEvicted:number;playersEvicted:number;verificationSkipped:number;pendingDropped:number;pending:number;partial:boolean};limits:{maxEvents:number;maxPlayers:number;maxPlaced:number}};
export function oreCount(player:OrePlayer,window:OreWindow,ore:OreId):number{const value=player.counts[String(window)]?.[ore];return typeof value==='number'&&Number.isFinite(value)&&value>0?Math.floor(value):0;}
export function oreTotal(player:OrePlayer,window:OreWindow):number{return (Object.keys(ORE_LABELS) as OreId[]).reduce((sum,ore)=>sum+oreCount(player,window,ore),0);}
export function filterOrePlayers(players:OrePlayer[],query:string,severity:OreSeverity|'ALL',ore:OreId|'ALL',window:OreWindow):OrePlayer[]{
  const text=query.trim().toLocaleLowerCase();
  return players.filter(player=>(!text||`${player.name} ${player.world}`.toLocaleLowerCase().includes(text))&&(severity==='ALL'||player.severity===severity)&&(ore==='ALL'||oreCount(player,window,ore)>0)).sort((a,b)=>{const level={GREEN:0,YELLOW:1,RED:2};return level[b.severity]-level[a.severity]||oreTotal(b,window)-oreTotal(a,window)||a.name.localeCompare(b.name);});
}
