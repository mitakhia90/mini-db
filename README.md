[![Review Assignment Due Date](https://classroom.github.com/assets/deadline-readme-button-22041afd0340ce965d47ae6ef1cefeee28c7c493a6346c4f15d667ab976d596c.svg)](https://classroom.github.com/a/dUA5-Nw5)

# ДЗ №5
Вам будет необходимо добавить реализацию нод и дописать классы для операций SELECT (INSERT, CREATE уже реализованы)

## Часть 1. Planner
Дописать компонент Planner, который преобразует семантически корректный SQL-запрос в логический план (LogicalPlanNode). Этот компонент формирует структуру операций, которые необходимо выполнить, не выбирая конкретные физические алгоритмы.

### Входные данные

На вход Planner получает QueryTree — результат работы семантического анализатора. QueryTree содержит уже резолвнутые таблицы, колонки и выражения. Структуру QueryTree вы задавали в предыдущем ДЗ, когда реализовывали Семантический анализатор.

Пример QueryTree для запроса:
```sql
SELECT name FROM users WHERE age > 18;
```
```
QueryTree
├── fromTables: [ResolvedTable(users)]
├── targetList: [ColumnRefResolved(users.name)]
└── where: AExprResolved(">", ColumnRefResolved(users.age), ConstResolved(18))
```
Можно подставить свою реализацию из ДЗ №4

### Выходные данные

Planner возвращает LogicalPlanNode — дерево логических операций, которое может содержать ноды типов:
* CreateNode — создание таблицы.
* InsertNode — вставка данных в таблицу.
* ScanNode — сканирование таблицы.
* FilterNode — применение предиката WHERE.
* ProjectNode — формирование SELECT-списка.

Вам нужно будет реализовать ноды для SELECT - заполнить их необходимыми полями для выполнения данной операции полями

Пример логического плана для SELECT запроса:
```
Project(name)
   |
Filter(age > 18)
   |
SeqScan(users)
```

Интерфейс в помощь:
```java
public interface Planner {
    // Преобразование высокоуровневых элементов запроса (rangeTable, targetList, where)
    //    в соответствующие логические операторы (CreateNode, InsertNode, ScanNode, FilterNode, ProjectNode).
    // Формирование дерева логических узлов, отражающего порядок выполнения операций на логическом уровне.
    // Для SELECT: строить структуру вида (Project -> Filter -> Scan).
    // Не принимать решений о физических алгоритмах (не выбирать IndexScan vs SeqScan).
    // Вычисление и закрепление схемы выходных колонок (output schema) для каждого узла плана.
    // Обработка специальных операторов CREATE/INSERT: порождать соответствующие логические узлы CreateNode/InsertNode.
    // Подготовка логического плана к передаче в Optimizer и возврат верхнего узла дерева.
    LogicalPlanNode plan(QueryTree queryTree);
}
```

По итогу в этом блоке должно быть:
* реализация Planner
* все реализации LogicalPlanNode: 1 на CREATE, 1 на INSERT, 3 на SELECT

### Hint
Для INSERT и CREATE простой случай - у них в дереве всего одна нода, для SELECT нужно собрать дерево из нескольких нод (тут вспоминаем лекцию - идет оборачивание низкоуровневых нод верхнеуровневыми - например Filter оборачивает Scan)

## Часть 2. Optimizer

Дописать компонент Optimizer, который преобразует логический план (LogicalPlanNode) в физический план (PhysicalPlanNode). Optimizer решает, как именно выполнять операции: выбирает алгоритмы сканирования, соединения, сортировки и агрегирования, чтобы минимизировать стоимость выполнения запроса. 

Уже добавлена поддержка CREATE, INSERT. Вам осталось дописать SELECT.

Вход: LogicalPlanNode — дерево логических операций.

Выход: PhysicalPlanNode — дерево физически конкретизированных операторов.

Интерфейс в помощь:
```java
public interface Optimizer {
    // В этой итерации будет "тупой" маппинг логического плана на физический,
    //    оптимизации различных видов разберем на отдельной лекции.
    // Прямое преобразование узлов в физические - переложите одну ДТО на другую
    PhysicalPlanNode optimize(LogicalPlanNode logicalPlan);
}
```

В сегодняшней реализации достаточно примитивен, так как сейчас на одну логическую ноду (операцию) только одна реализация. Поэтому сводится к набору if - else (ну или switch - case)

По итогу в этом блоке должно быть:
* реализация Optimizer
* все реализации PhysicalPlanNode: 1 на CREATE, 1 на INSERT, 3 на SELECT

### Hint
В этой лабораторной работе реализуется базовый ("тупой") маппинг логического плана на физический без сложных стратегий и cost-based оптимизаций (пока у нас реализаций не много, у логических и физических нод соответствие один-к-одному).

## Часть 3. ExecutorFactory и Executors

Дописать ExecutorFactory, который на основе физического плана создает цепочку исполнителей (Executors) для фактического выполнения запроса. Каждый Executor реализует итераторный интерфейс и отвечает за выполнение своей операции (Scan, Filter, Project). CreateTable, Insert уже написаны)

### Входные данные

PhysicalPlanNode — дерево физического плана, где каждый узел описывает конкретный оператор

### Выходные данные

Верхний Executor — корневой исполнитель, который при вызове open() -> next() -> close() выдает строки результата запроса (или выполняет операцию Create/Insert).


```java
public interface ExecutorFactory {
    Executor createExecutor(PhysicalPlanNode plan);

}
```

Вам нужно будет написать реализации Executor:

* CreateExecutor (уже реализовано) — создание таблицы.
* InsertExecutor (уже реализовано) — вставка данных в таблицу.
* SeqScanExecutor — сканирование таблицы полным перебором.
* FilterExecutor — применение предиката WHERE.
* ProjectExecutor — формирование SELECT-списка - выборка указанных колонок из отобранных строк.

по контракту:
```java
public interface Executor {
    void open();
    Object next();
    void close();
}
```


По итогу в этом блоке должно быть:
* реализация ExecutorFactory
* все реализации Executor: 1 на CREATE, 1 на INSERT, 3 на SELECT

### Hint
В функциях open() и close() пока не будет наполнения - не нужно инициализировать никакие ресурсы. В функции next() нужно пройти три этапа:
* получение данных от ноды ниже (по одной строке)
* логика самого executor - фильтрация строк для FilterExecutor и тп
* прокидывание данных (или null, если они закончились) ноде выше

## Дополнительно
Эта часть необязательна в рамках ДЗ №5 - на оценку ДЗ №5 не повлияет. 

Но в рамках финального проекта надо будет связать все части воедино, можно попробовать связать ДЗ№ 1-5 сейчас. По итогу у вас будет простая, но уже рабочая БД, которая поддерживает операции CREATE TABLE, INSERT, SELECT. Т.е. будет реализована цепочка исполнения от SQL-строки:

```sql
SELECT name FROM users WHERE id > 10;
```
до результата выполнения:

```sql
Maria
Anna
Alice
```

что-то типо:
```java
QueryTree queryTree = semanticAnalyzer.analyze(ast);
LogicalPlanNode logicalPlan = planner.plan(queryTree);
PhysicalPlanNode physicalPlan = optimizer.optimize(logicalPlan);
Executor executor = executorFactory.createExecutor(physicalPlan);
List<Object> result = engine.execute(executor);
```

## Тесты
Написать по паре тестов на каждую операцию INSERT, CREATE, SELECT - как положительные, так и отрицательные кейсы  

## Новые тесты и оптимизация вставки (storage)

Я добавил несколько unit-тестов и небольшую оптимизацию в слой операций/хранения (DefaultOperationManager):

- Тесты:
  - `OperationManagerInsertBoundaryTest` — проверяет поведение вставки при переполнении страницы (append new page) и ожидает выброс `IllegalArgumentException` при попытке вставить слишком большую строку.
  - `OperationManagerCacheBehaviorTest` — проверяет работу простого кэша последней страницы с местом: первая вставка сканирует страницы (cache miss), последующие вставки используют кэш и не инициируют полный скан.

- Оптимизация `DefaultOperationManager`:
  - Простая кэш-структура `lastWritablePage` + `lastWritableFree` (по `tableOid`) — хранит id страницы и оценку свободного места (в байтах) после последней записи.
  - При вставке сначала проверяется кэш (если оценка показывает, что строка может поместиться), затем — скан существующих страниц, и в крайнем случае — добавление новой страницы.
  - В `serializeRow` добавлена проверка размера строки и выбрасывается `IllegalArgumentException` для слишком больших строк (вместо BufferOverflowException).

Это ускоряет частые последовательные вставки в одну таблицу и делает поведение более предсказуемым.
