# Overworld design 0.4

Новая композиция: тонкая навигация, просторный overview, четыре живых показателя, диаграмма и пульт мира, выделенные реальные рабочие разделы. Названия и числа Monocraft, длинные формы и таблицы читаемым системным sans-serif. Charcoal `#111315`, stone `#1c2124`, light text `#eef5ef`, emerald `#81e9a1`, torch `#ffc06a`. Иконки используются только там, где они помогают работе; прежнее требование заполнять интерфейс иконками отменено владельцем.

`AppMain.tsx` хранит существующий auth/live/action contract. `Shell.tsx` управляет навигацией, mobile drawer и command menu. `Login.tsx` и `Overview.tsx` создают новую композицию. `OverworldScene.tsx` содержит Three renderer и GLSL water/portal, собственный lifecycle, instancing, capped rendering, context-loss SVG fallback. `overworld-theme.css` накладывает новую визуальную систему на сохранённые функциональные layouts. `AutomationGraph.tsx` оставляет библиотечную attribution, корректную MiniMap и читаемый mobile selected-node viewport с fit-all.

Motion учитывает пользовательский reduced motion; GSAP scope очищается при unmount. Scene offscreen/document hidden pause, cleanup GPU resources, DPR ≤1.5 и render cap30. Native scroll сохранён для таблиц, операционных форм и graph interactions. Текущая Vite/React19/Java архитектура сохранена; Next.js/R3F/Lenis не добавлены без необходимости. Надпись «Создано пользователем Krivzy» сохранена.

Снимок не включает сторонние Minecraft assets, runtime secrets и личные пути; `scripts/Extract-Assets.py` восстанавливает локальные ресурсы из verified установленного клиента.
