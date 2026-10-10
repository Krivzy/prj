# Карта ответственности

Не загружать все строки таблицы. Сначала выбрать результат и технологию, затем читать нужный модуль.

| Задача | Skill | Ответственность |
|---|---|---|
| Необычная композиция, typography, hero, menu, SVG/CSS interaction | creative-frontend-expert | Художественная реализация поверхности |
| Tokens, темы, компоненты, SaaS/dashboard | ui-ux-design-system-expert | Системные визуальные правила и состояния |
| Mobile-first, fluid type, container queries, touch/overflow | responsive-design-expert | Поведение структуры на разных размерах |
| Timeline, ScrollTrigger, pinning, SVG, FLIP | gsap-animation-expert | GSAP lifecycle и choreography |
| AnimatePresence, layout, gestures, springs | motion-react-expert | React interaction state и переходы |
| Содержательная 3D-сцена | threejs-r3f-expert | Scene/resources/cameras/materials/quality |
| Custom GPU effect | glsl-shaders-expert | GLSL pipeline, uniforms, fallback |
| Оправданный smooth scroll | lenis-smooth-scroll-expert | Единственный scroll/RAF lifecycle |
| Next app/actions/API/auth/RBAC/realtime | nextjs-fullstack-architect | Границы клиент/сервер и архитектура |
| Поиск/адаптация UI из 21st | 21st-ui-components-expert | Проверка MCP, license, dependencies, адаптация |
| Повторяемые browser flows | playwright-browser-automation-expert | E2E assertions, errors, screenshots, matrix |
| Качество реального отображения | visual-quality-assurance-expert | Композиция, visual defects, repair/recheck |
| CPU/GPU, bundles, leaks, network/loading | performance (уже установлен) | Измерение и локализация bottleneck |
| LCP/INP/CLS | core-web-vitals (уже установлен) | Field/lab metric evidence |
| Keyboard/focus/contrast/screen reader/reduced motion | accessibility (уже установлен) | WCAG evidence и доступное взаимодействие |
| shadcn project components | shadcn (уже установлен) | Реальный registry и соглашения проекта |
| Выбор UI directions с локальной базой | ui-ux-pro-max (уже установлен) | Палитры, typography candidates, UX heuristics |
| Широкая творческая design direction | frontend-design (уже установлен) | При необходимости дополнительное art direction |

Отдельный Web Performance Expert и Accessibility Expert не установлены как aliases: существующие полноценные навыки выполняют эти роли. GPU-specific диагностика находится также в threejs-r3f-expert/glsl-shaders-expert; связывать её с измерением общей performance.

## Сценарии выбора

Премиальный SaaS dashboard: design system → Next architecture → shadcn → локальный Motion при необходимости → Playwright/visual QA/accessibility. Scroll pinning и WebGL не следуют из слова «премиальный».

Портфолио с scroll storytelling: creative frontend → responsive → GSAP; Lenis только при явной ценности. 3D подключать, когда форма работы/продукта требует пространства, и всегда с лёгким fallback. Проверки включают scroll restoration и reduced motion.

Одна hover-кнопка: обычно CSS и существующий компонент, без master/GSAP/Three. Ошибка auth: Next architecture, без творческих навыков.

Источники формата skills: https://learn.chatgpt.com/docs/build-skills и локальный skill-creator, проверены 2026-10-10. Global user discovery конкретной Codex installation проверять через skills/list, а не наличием папок.
