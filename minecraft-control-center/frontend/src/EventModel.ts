export type LootRow={material:string;amount:number;weight:number};
export type EventField={key:string;ru:string;en:string;kind:string;default:unknown;min:number;max:number;options:string[]};
export type EventDefinition={id:string;ru:string;en:string;category:string;icon:string;description:string;descriptionEn:string;fields:EventField[]};
export type EventCatalog={presets:EventDefinition[];commonFields:EventField[];limits:Record<string,unknown>};
export function eventDefaults(definition:EventDefinition,common:EventField[]):Record<string,unknown>{const result=Object.fromEntries([...common,...definition.fields].map(field=>[field.key,structuredClone(field.default)]));if(definition.id==='champion')result.count=1;return result;}
export function updateLootRow(rows:LootRow[],index:number,patch:Partial<LootRow>):LootRow[]{return rows.map((row,i)=>i===index?{...row,...patch}:row);}
export function eventPayload(definition:EventDefinition,values:Record<string,unknown>,world:string):Record<string,unknown>{const result:Record<string,unknown>={preset:definition.id,world};for(const key of ['x','y','z','count','intervalTicks','durationSeconds','radius',...definition.fields.map(f=>f.key)])if(values[key]!==undefined)result[key]=structuredClone(values[key]);if(definition.id==='champion')result.count=1;return result;}
