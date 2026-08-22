# Google Play Games windowed launch investigation

Дата исследования: 2026-08-22

Статус: отложено. Документ фиксирует результаты экспериментов и не является
описанием действующей архитектуры.

## Цель

Запускать Google Play Games сразу в freeform-окне, включая первый запуск с
системным диалогом разрешения уведомлений, без падения приложения, мигания,
временного fullscreen и последующей коррекции геометрии.

Решение должно быть общим для приложений. В нем не должно быть проверок имени
пакета Play Games или PermissionController.

## Наблюдаемый дефект

- Без диалога разрешения Play Games обычно запускается в окне нормально.
- После очистки данных приложение при первом запуске перенаправляет стартовую
  Activity на основную Activity и запрашивает `POST_NOTIFICATIONS`.
- В этот момент Play Games либо падает, либо начинает заметно мигать под
  системным диалогом. Результат вероятностный.
- Иногда приложение остается живым, но задача оказывается fullscreen.
- Старый системный диалог о падении может пережить остановку приложения или
  перезапуск рабочего стола и мешать оценке следующей попытки.
- MCP-запуск и ручной запуск первоначально давали разные визуальные результаты,
  но корневая гонка воспроизводилась в обоих случаях.

Исключение внутри Play Games:

```text
NullPointerException: Attempt to invoke virtual method
'java.lang.Class java.lang.Object.getClass()' on a null object reference
```

## Подтвержденная последовательность Android

1. Запускается
   `com.google.android.gms.games.ui.destination.main.MainActivity`.
2. Приложение перенаправляет запуск в
   `com.google.android.gms.games.ui.v2.MainActivity`.
3. Открывается PermissionController `GrantPermissionsActivity`.
4. Одновременно WMS регистрирует `wm_relaunch_resume_activity` с изменением
   конфигурации `d80/c00`.
5. Play Games падает или входит в цикл relaunch/мигания.

Флаги `d80` соответствуют изменению ориентации, screen layout, screen size и
smallest width. Это согласуется с переходом Activity из начальной конфигурации
экрана во freeform-конфигурацию.

Основная гипотеза: падение вызывается не самим PermissionController, а холодным
стартом Play Games в одной конфигурации с последующим relaunch в другую
конфигурацию во время внутреннего redirect и запроса разрешения.

## Что было проверено

### 1. Исходный launch transition

На `HEAD` MagicDesk запускает PendingIntent, рано определяет `taskId` и
присоединяет открывающуюся задачу к freeform transition через
`ShellPreparedTaskTransition.joinOpenAsFreeform()`.

Это хорошо работает для обычных приложений, но Activity Play Games успевает
родиться с конфигурацией, похожей на fullscreen, а затем получает freeform
relaunch. При первом запросе разрешения этого достаточно для падения.

### 2. Отключение resize pulse

Transient resize pulse был полностью отключен для проверки.

Результат:

- Play Games продолжал падать с некоторой вероятностью;
- обычные оконные сценарии, Miracast и внешний экран визуально не ухудшились;
- pulse оказался независим от причины падения и был удален отдельным уже
  закоммиченным изменением.

### 3. Временный старт fullscreen

Разрешение задаче временно остаться fullscreen иногда предотвращало падение.
Но задача могла так и остаться fullscreen, а системный диалог скрывал taskbar.

Этот вариант не соответствует контракту Windowed и не принят.

### 4. Специальная обработка permission dialog

Обсуждались исключения на время системного диалога, предварительная выдача
разрешения и привязка к PermissionController.

Все варианты отклонены:

- нельзя обходить пользовательское решение о разрешении;
- нельзя привязывать ядро оконных переходов к конкретному системному пакету;
- диалог лишь усиливает общую гонку конфигурации.

### 5. Раннее определение taskId через shell service

Существующие наблюдатели `IActivityController` и task callbacks действительно
позволяют узнать новую задачу очень рано.

Этого недостаточно: коррекция режима после создания Activity все равно вызывает
configuration relaunch. Механизм полезен для наблюдения и идентификации, но не
решает начальную конфигурацию.

### 6. Organizer root и прямой запуск target

До запуска приложения создавался organizer freeform root. Затем target пытались
запустить прямо в него через `setLaunchTaskId`.

WMS проигнорировал или не поддержал использование organizer root как обычного
launch task. Target создавался отдельно, иногда fullscreen, либо не находился
наблюдателем.

### 7. Organizer root с дочерней задачей

Target запускался под заранее созданным root, после чего дочерняя задача
освобождалась в обычную desktop task area.

Без явной полной конфигурации root/child приложение все равно получало неверную
первую конфигурацию и падало.

### 8. Явное копирование полной Configuration

Для root и дочерней задачи атомарно задавались bounds, app bounds,
`screenWidthDp`, `screenHeightDp`, `smallestScreenWidthDp`, orientation и screen
layout.

Это единственный вариант, при котором Play Games надежно появлялся в окне,
системный permission dialog оставался в той же задаче, а падение не возникало.

Вариант отклонен как постоянное решение. Заданные поля становились task-level
override и не очищались автоматически. После обычного resize границы задачи
менялись, а Activity продолжала видеть старую Configuration. Это ломает
динамический resize, snap и fullscreen-переходы.

### 9. deferConfigToTransitionEnd

Проверялся `WindowContainerTransaction.deferConfigToTransitionEnd()`.

Он не решил проблему: Permission Activity появляется после границы исходного
transition, и изменение конфигурации target все равно пересекается с новым
жизненным циклом.

### 10. Прозрачная seed Activity MagicDesk

Добавлялась отдельная прозрачная `WindowLaunchSeedActivity`. Идея: сначала
создать нормальную задачу MagicDesk, привести ее к окончательному freeform
состоянию, затем открыть target внутри уже стабильной задачи.

Seed-задача создавалась с правильными freeform bounds. Dumpsys подтверждал ее
итоговую оконную конфигурацию.

### 11. Shell start target в seed task

Shell запускал target с `setLaunchTaskId(seedTaskId)`.

Target входил в ту же задачу, и permission dialog тоже находился в ней. Но
начальная ActivityRecord target все равно получала конфигурацию дисплея, после
чего происходил `d80` relaunch и падение.

### 12. Target запускается самой seed Activity

Seed Activity пыталась открыть target обычным app-side `startActivity()`.

Первая реализация передавала вложенный `Intent` через shell. Android 16 Intent
Redirect Hardening отклонил его из-за отсутствующего creator token:

```text
INTENT_REDIRECT_EXCEPTION_MISSING_OR_INVALID_TOKEN
```

После передачи URI и повторного безопасного разбора Intent внутри приложения
эта ошибка исчезла. Task-boundary flags удалялись, target оставался в seed task.
Однако target по-прежнему получал собственный `d80` relaunch.

### 13. Ожидание стабилизации seed Activity

Сначала команда запуска target попадала в seed во время ее собственного
configuration relaunch. Запуск был перенесен в `onPostResume()` после
пересоздания seed, без фиксированных задержек.

Seed стала стабильной до открытия target, но target все равно получал `d80`.
Гонка seed была реальной, однако не являлась основной причиной падения.

### 14. App-side ActivityOptions

Проверялись:

- публичный `setLaunchBounds()`;
- скрытый `setLaunchWindowingMode()` через reflection;
- Bundle с privileged ActivityOptions, сформированный shell и переданный app.

`setLaunchBounds()` недостаточно. Скрытый метод недоступен обычному процессу
MagicDesk из-за hidden API enforcement. Переданный Bundle WMS оценивает и
санитизирует по реальному app caller UID, поэтому shell-привилегии не
сохраняются.

### 15. Typed callback и source token

Временно добавлялся AIDL callback. Seed после `onPostResume()` передавала shell
свой `getApplicationWindowToken()`, а shell запускал target с этим source token
и privileged freeform options.

WMS все равно добавлял `NEW_TASK`, потому что у shell-вызова нет настоящего
`IApplicationThread` вызывающей Activity. Target снова получал `d80`. AIDL
прототип удален.

### 16. startActivityAsCaller

Локально исследован `IActivityTaskManager` данной прошивки. Метод
`startActivityAsCaller` мог бы использовать source Activity и ее caller
identity, но требует `android.permission.START_ACTIVITY_AS_CALLER`.

У UID 2000 этого разрешения нет. Метод доступен SystemUI, но не подходит для
обычного Shizuku shell режима MagicDesk.

### 17. startActivityWithConfig

Проверялся скрытый `startActivityWithConfig` с одноразовой launch
Configuration.

На этой прошивке метод приводит к обновлению global configuration в
`RootWindowContainer`, а не к безопасной task-local стартовой конфигурации.
Возникает конфликт activity type в `WindowConfiguration.setActivityType()` и
`ConfigurationContainer.onConfigurationChanged()`.

Подход опасен для всей системы и был полностью удален.

### 18. Подготовленная normal task и PendingIntent transition

Последний незавершенный прототип:

1. Создать organizer root.
2. Создать в нем обычную прозрачную seed task.
3. Освободить task в desktop task area уже с окончательными bounds.
4. Запустить target PendingIntent transition с `setLaunchTaskId`, freeform mode
   и flexible launch size.

От исходного механизма вариант отличается тем, что нормальная task существует
в окончательном freeform состоянии до target Activity, а transition не должен
менять ее режим после создания Activity.

После последнего изменения вариант был собран, но не был проверен. Он затронул
главный launch path и оставался большим экспериментальным diff. Код решено не
оставлять в `main` без подтверждения.

## Выводы

Сильнее всего подтверждена следующая причина:

> Cold-start Activity Play Games создается не в окончательной freeform
> конфигурации. Последующий relaunch с изменением screen size/layout совпадает
> с redirect и permission flow, после чего приложение падает.

PermissionController, resize pulse и поздняя идентификация task не являются
самостоятельной причиной.

## Контракт приемлемого решения

Будущий общий launch pipeline должен обеспечивать:

1. Target Activity рождается сразу в окончательном freeform mode и bounds.
2. Нет промежуточного fullscreen и постфактум-коррекции режима.
3. Нет configuration relaunch на холодном старте из-за действий MagicDesk.
4. Нет постоянных override для dp, app bounds, orientation и screen layout.
5. После запуска продолжают работать native resize, caption snap, fullscreen и
   restore.
6. Нет package-specific или PermissionController-specific веток.
7. Для запуска существует один авторитетный transition path.
8. Ошибка стороннего приложения не нарушает taskbar, input sink и дальнейшую
   работу MagicDesk.

## Что не повторять

- Не возвращать transient resize pulse как попытку исправить этот дефект.
- Не корректировать fullscreen в freeform после создания Activity и считать
  это решением.
- Не хранить полный Configuration override на задаче без доказанного механизма
  его своевременного снятия.
- Не использовать `startActivityWithConfig` на этой прошивке.
- Не передавать вложенный shell Intent без учета Android 16 redirect hardening.
- Не рассчитывать на `startActivityAsCaller` при UID 2000.
- Не добавлять исключения для пакетов Play Games и PermissionController.
- Не оценивать новый запуск, пока на экране остались старые crash dialogs.

## Чистая подготовка ручного эксперимента

1. Force-stop Play Games.
2. Очистить его данные, если нужен первый permission flow.
3. Закрыть оставшиеся системные crash dialogs.
4. Очистить соответствующие event/logcat записи.
5. Запустить MagicDesk и desktop session заново.
6. Выполнить ровно один запуск Play Games в Windowed.
7. Сопоставить визуальный результат с `wm_create_activity`,
   `wm_relaunch_resume_activity`, `wm_set_resumed_activity` и task config.

## Возможное продолжение

1. Один раз отдельно оценить вариант prepared normal task + PendingIntent
   transition, не смешивая его с другими изменениями.
2. Если `d80` остается, прекратить перестановку launch API и добавить точечную
   трассировку ActivityRecord/RunningTaskInfo configuration на каждом этапе.
3. Исследовать launch-scoped configuration lease: начальная конфигурация
   задается до Activity и снимается по наблюдаемому lifecycle/task событию, а не
   по таймеру. До принятия нужно доказать отсутствие resize-регрессий.
4. Добавить тестовую Activity, которая делает redirect и запрашивает runtime
   permission. Self-test должен проверять отсутствие relaunch и временного
   fullscreen без зависимости от Play Games.
5. После решения проверить обычный cold start, Play Games first run, resize,
   native caption snap, fullscreen/restore и все типы экранов.
