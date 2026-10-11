import type {Page} from './context';

export const pages: {id: Page; ru: string; en: string; group: number}[] = [
  {id:'dashboard', ru:'Обзор', en:'Overview', group:0},
  {id:'players', ru:'Игроки', en:'Players', group:0},
  {id:'worlds', ru:'Миры', en:'Worlds', group:0},
  {id:'map', ru:'Карта мира', en:'World map', group:0},
  {id:'mobs', ru:'Мобы', en:'Mobs', group:0},
  {id:'events', ru:'События', en:'Events', group:0},
  {id:'ores', ru:'Честная игра', en:'Fair play', group:0},
  {id:'luckperms', ru:'LuckPerms', en:'LuckPerms', group:1},
  {id:'coreprotect', ru:'CoreProtect', en:'CoreProtect', group:1},
  {id:'advancedban', ru:'Наказания', en:'Punishments', group:1},
  {id:'console', ru:'Консоль', en:'Console', group:1},
  {id:'chat', ru:'Чат', en:'Chat', group:1},
  {id:'automations', ru:'Автоматизации', en:'Automations', group:1},
  {id:'backups', ru:'Резервные копии', en:'Backups', group:2},
  {id:'plugins', ru:'Плагины', en:'Plugins', group:2},
  {id:'audit', ru:'Журнал действий', en:'Audit log', group:2},
  {id:'settings', ru:'Настройки', en:'Settings', group:2},
];

export function availablePages(role: string, integrations: string[] = []) {
  const lower = role.toLowerCase();
  return pages.filter(item =>
    !(['luckperms','coreprotect','advancedban'].includes(item.id) && !integrations.includes(item.id)) &&
    !(['luckperms','coreprotect','advancedban','ores'].includes(item.id) && !['owner','admin'].includes(lower)) &&
    !(['console','plugins','automations'].includes(item.id) && !['owner','admin'].includes(lower)) &&
    !(['chat','audit'].includes(item.id) && lower === 'viewer') &&
    !(item.id === 'backups' && lower !== 'owner'));
}
