# Анимации игрока Jujutsu Neon

Игрок рисуется моделью **R15** (15 костей, как персонаж Roblox), а все анимации — обычные анимации
**Blockbench** (формат Bedrock / GeckoLib: `.geo.json` + `.animation.json`). Движок анимаций —
**GeckoLib 4.8.4** (MIT, вшит в мод, сам не изменяется): он читает и запекает файлы Blockbench.
Поверх него — система, устроенная как анимации в Roblox (пакет `com.kira.jujutsuneon.rbx`).

Максимальный Фиолетовый не трогается: он играет по-старому через Player Animator (`MaximumPurpleAnimation`),
и пока он идёт, игрок рисуется ванильной моделью.

## Как в Roblox

| Roblox | Здесь |
|---|---|
| R15-персонаж | `geo/player_r15.geo.json` (и `_slim` для тонких рук), броня — `geo/armor_r15_*.geo.json` |
| Animation (ассет) | анимация в `.animation.json` (`Clip`) |
| Animator, AnimationTrack | `Animator`, `AnimationTrack`: `play/stop(fade)`, `adjustSpeed`, `adjustWeight`, `timePosition`, `looped`, сигналы `stopped`, `ended`, `didLoop`, `markerReached` |
| AnimationPriority | `Core < Idle < Movement < Action < Action2 < Action3 < Action4` |
| KeyframeMarker | инструкция на дорожке эффектов Blockbench (`hit`, `fire=2`) |
| Animate (LocalScript) | `AnimateScript`: стойка, шаг, бег, сверхбег, присед, прыжок, падение, плавание, лазание, сидение, полёт, дэши, предметы |
| репликация анимаций | `RbxNet`: анимацию видят все рядом |

Трек с более высоким приоритетом перекрывает нижние **только на тех костях, которые он анимирует**: анимация
только рук не мешает ногам ходить. Треки одного приоритета смешиваются по весам. Переходы — плавные
(повороты смешиваются кватернионами).

## Кости R15

`LowerTorso` (таз, корень) → `UpperTorso` → `Head`, `RightUpperArm` → `RightLowerArm` → `RightHand`
(и левые); от таза — `RightUpperLeg` → `RightLowerLeg` → `RightFoot` (и левые).
Кости `...Layer` — второй слой скина, их не анимируют (идут за своей костью).

Значения — как в Blockbench: повороты в градусах, смещения в пикселях.

* поворот X < 0 — рука/нога вперёд-вверх (`-90` — рука вперёд, `-180` — вверх);
* у корпуса X > 0 — наклон вперёд, у головы — взгляд вниз;
* предплечье X < 0 — сгиб в локте; голень X > 0 — сгиб колена;
* Z > 0 — правая рука/нога наружу (левая — внутрь); у корпуса — наклон влево. У **поднятой** руки знак Z
  разводит наоборот (рука вверх и наружу — Z < 0 для правой);
* Y > 0 — поворот вправо;
* смещение: X > 0 — влево, Y > 0 — вверх, Z > 0 — назад.

## Свойства анимации

Пишутся прямо в Blockbench инструкцией на дорожке эффектов (Timeline), в любом месте анимации:

| Инструкция | Что делает |
|---|---|
| `@priority=action` | приоритет: `core`, `idle`, `movement`, `action`, `action2`…`action4` (по умолчанию `action`) |
| `@fade_in=0.1`, `@fade_out=0.15` | плавный вход и выход, секунды |
| `@look=0.3` | насколько голова следит за взглядом, 0..1 (по умолчанию 1 у Core/Idle, 0.25 у остальных) |
| `@aim=1` | плечи следуют за взглядом (лук, щит, прицел) |
| `@fp=arms` / `right` / `left` / `none` | что видно от первого лица: руки модели или ванильные (по умолчанию для Action — `arms`) |
| `@blend=additive` | прибавляется к нижним слоям, а не заменяет их |
| `@move_speed=4.3` | для цикла шага: скорость (блоки/с), под которую он нарисован — шаг не скользит |

Остальные инструкции — маркеры (`hit`, `fire=2`). Звуки (Sound Effects) и частицы Blockbench тоже играют.
Петля: в Blockbench `Loop` — по кругу, `Hold on last frame` — застыть на последнем кадре.

## Где лежат анимации

| Откуда | Путь | Когда подхватывается |
|---|---|---|
| Мод | `assets/jujutsu_neon/animations/player/*.animation.json` | при загрузке ресурсов (F3+T) |
| Папка игры | `config/jujutsu_neon/animations/**.json` | **сразу**: сохранили файл — через секунду в игре |

Имя анимации — ключ в файле; ключ `animation.player.walk` доступен и как `walk`. Анимация из папки игры
с тем же именем заменяет встроенную — так анимации мода правятся без пересборки.

Встроенные:

* `locomotion.animation.json` — Animate: `idle`, `idle_look`, `walk`, `run`, `sprint`, `crouch_idle`, `crouch_walk`,
  `jump`, `fall`, `land`, `swim`, `swim_idle`, `climb`, `sit`, `glide`, `sleep`, `charge`, `leap`,
  `fly_hover`, `fly_glide`, `fly_boost`, `fly_land`, `dash_front/back/left/right`, `hold_item`, `hold_crossbow`,
  `swing`, `use_eat/drink/block/bow/spear/crossbow/spyglass/horn/brush`;
* `techniques.animation.json` — техники: `red_cast`, `max_red`, `blue_charge`, `max_blue`, `lapse_blue`,
  `lapse_blue_grab_low/mid/high`, `lapse_blue_hold`, `lapse_blue_throw`, `domain_cast`, `simple_domain`,
  `m1_jab_l/r`, `m1_final`, `m1_knockdown`.

## Работа в Blockbench

1. В игре: `/jnanim export` — модели и анимации выкладываются в `config/jujutsu_neon/blockbench`.
2. Blockbench: **File → Open Model** → `player_r15.geo.json`, вкладка **Animate** → **Import Animations** →
   нужный `.animation.json`.
3. Правите, сохраняете анимацию в `config/jujutsu_neon/animations/` — в игре она обновится сама.
4. Готовую анимацию кладёте в `src/main/resources/assets/jujutsu_neon/animations/player/`.

Анимации передвижения собраны скриптом `tools/rig/gen_locomotion.py` (обёртка над форматом —
`tools/rig/anim_dsl.py`), техники переведены из старых поз Player Animator скриптом `tools/rig/convert_pa.py`
(исходники — `tools/rig/legacy_pa/`, тайминги сохранены до тика). Превью без игры:

```
python3 tools/rig/preview.py anim src/main/resources/assets/jujutsu_neon/animations/player/locomotion.animation.json animation.player.walk --views front,side
```

## Команда `/jnanim` (в игре, на себе; F5 — смотреть со стороны)

| Команда | Что делает |
|---|---|
| `list` | все анимации |
| `play <имя> [скорость]`, `loop <имя>`, `mirror <имя>` | проиграть один раз / по кругу / зеркально |
| `pose <имя> <секунда>`, `step <±секунды>` | стоп-кадр в любой момент и сдвиг стоп-кадра |
| `speed <x>` | замедлить или ускорить все анимации (0.1 — в 10 раз медленнее) |
| `stop` | плавно остановить |
| `tracks` | что сейчас играет: треки, приоритеты, веса |
| `info <имя>` | длина, петля, приоритет, переходы, маркеры, кости |
| `reload`, `errors` | перечитать папку / показать ошибки загрузки |
| `export` | выложить модели и анимации для Blockbench |
| `model r15` / `model vanilla` | модель игрока: R15 или ванильная (настройка клиента) |

## Из кода

Клиент:

```java
AnimationTrack t = PlayerAnimations.play(player, "red_cast", new PlayerAnimations.Play()
        .fade(0.1)                       // вход, секунды
        .speed(1.0).weight(1.0)
        .startAt(0.3)                    // начать с 0,3 с (догнать по времени)
        .firstPerson(true, false, 0.9)   // от первого лица — правая рука модели, первые 0,9 с
        .replicate(true));               // показать всем рядом
t.getMarkerReachedSignal("fire").connect(param -> { /* ... */ });
PlayerAnimations.stop(player, 0.2);
```

Сервер (увидят все рядом и сам игрок):

```java
RbxNet.play(serverPlayer, "red_cast");
RbxNet.play(serverPlayer, new RbxNet.Anim("red_cast").speed(1.2f).startAt(0.3f));
RbxNet.stop(serverPlayer, 0.2f);
```

## Как устроен рендер

Ванильный рендер игрока не заменяется: на время одной отрисовки у `PlayerRenderer` подменяется только модель
(`R15PlayerModel`). Она рисует сетку R15 в позе из анимаций, а ванильные части ставит по костям R15 —
поэтому работают повороты тела (плавание, элитры, сон), ник, тень, невидимость, свечение и слои других модов.
Броня рисуется по сегментам R15, предмет — в кисти, плащ и элитры идут за корпусом.
От первого лица во время анимаций с `@fp` видны руки самой модели (`R15FirstPerson`).
