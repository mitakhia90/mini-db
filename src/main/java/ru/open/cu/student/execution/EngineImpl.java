package ru.open.cu.student.execution;

import ru.open.cu.student.SqlProcessor;
import ru.open.cu.student.ast.QueryTree;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.manager.DefaultCatalogManager;
import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.operation.DefaultOperationManager;
import ru.open.cu.student.catalog.operation.OperationManager;
import ru.open.cu.student.cli.api.Engine;
import ru.open.cu.student.execution.executors.Executor;
import ru.open.cu.student.lexer.DefaultLexer;
import ru.open.cu.student.lexer.Lexer;
import ru.open.cu.student.lexer.Token;
import ru.open.cu.student.memory.buffer.BufferPoolManager;
import ru.open.cu.student.memory.buffer.DefaultBufferPoolManager;
import ru.open.cu.student.memory.manager.HeapPageFileManager;
import ru.open.cu.student.memory.manager.PageFileManager;
import ru.open.cu.student.memory.replacer.ClockReplacer;
import ru.open.cu.student.optimizer.Optimizer;
import ru.open.cu.student.optimizer.OptimizerImpl;
import ru.open.cu.student.optimizer.node.PhysicalPlanNode;
import ru.open.cu.student.parser.DefaultParser;
import ru.open.cu.student.parser.Parser;
import ru.open.cu.student.ast.AstNode;
import ru.open.cu.student.planner.Planner;
import ru.open.cu.student.planner.PlannerImpl;
import ru.open.cu.student.planner.node.LogicalPlanNode;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class EngineImpl implements Engine {

    // shared global state
    private final CatalogManager catalog = new DefaultCatalogManager();
    private final Lexer lexer = new DefaultLexer();
    private final Parser parser = new DefaultParser();
    private final SqlProcessor sqlProcessor = new SqlProcessor(lexer, parser, catalog);

    private final Planner planner = new PlannerImpl(catalog);
    private final Optimizer optimizer = new OptimizerImpl();

    private final PageFileManager pfm = new HeapPageFileManager();
    private final OperationManager opManager = new DefaultOperationManager(catalog);
    private final QueryExecutionEngine execEngine = new QueryExecutionEngineImpl();

    @Override
    public String executeSql(String sql) {
        try {
            // 1) Lexer
            List<Token> tokens = lexer.tokenize(sql);
            log("TOKENS", tokens);

            AstNode ast = parser.parse(tokens);
            log("AST", ast);

            QueryTree queryTree = sqlProcessor.process(sql);
            log("QUERY_TREE", queryTree);

            // 4) Planner
            LogicalPlanNode logical = planner.plan(queryTree);
            log("LOGICAL_PLAN", logical);

            // 5) Optimizer
            PhysicalPlanNode physical = optimizer.optimize(logical);
            log("PHYSICAL_PLAN", physical);

            // ✅ ВАЖНО: выбрать правильный файл данных для этой операции
            Path tableFile = resolveTableFile(queryTree);

            // ✅ создаём BufferPool под конкретный файл таблицы
            BufferPoolManager bufferPool = new DefaultBufferPoolManager(
                    16,
                    pfm,
                    new ClockReplacer(),
                    new ClockReplacer(),
                    tableFile
            );

            ExecutorFactory executorFactory = new ExecutorFactoryImpl(catalog, opManager, bufferPool);

            // 6) ExecutorFactory -> executor
            Executor executor = executorFactory.createExecutor(physical);
            log("EXECUTOR", executor.getClass().getSimpleName());

            // 7) execute
            List<Object> rows = execEngine.execute(executor);

            // flush, чтобы персистилось
            bufferPool.flushAllPages();

            if (rows.isEmpty()) return "OK";
            // If executor returned raw byte[] rows (legacy), try to deserialize using table columns
            String tableName = (queryTree.rangeTable != null && !queryTree.rangeTable.isEmpty()) ? queryTree.rangeTable.get(0).relname : null;
            return rows.stream().map(r -> {
                if (r instanceof java.util.List<?> l) {
                    return l.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
                }
                if (r instanceof byte[] b && tableName != null) {
                    // try to deserialize using catalog
                    var table = catalog.getTable(tableName);
                    if (table != null) {
                        var cols = catalog.getTableColumns(table);
                        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        java.util.List<String> parts = new java.util.ArrayList<>();
                        for (var col : cols) {
                            var type = catalog.getType(col.getTypeOid());
                            String tn = type.name().toLowerCase();
                            switch (tn) {
                                case "integer": parts.add(String.valueOf(buf.getInt())); break;
                                case "bigint": parts.add(String.valueOf(buf.getLong())); break;
                                case "boolean": parts.add(String.valueOf(buf.get() != 0)); break;
                                case "varchar": {
                                    int len = buf.getShort() & 0xFFFF;
                                    byte[] s = new byte[len];
                                    buf.get(s);
                                    parts.add(new String(s, java.nio.charset.StandardCharsets.UTF_8));
                                    break;
                                }
                                default: parts.add("<unk>"); break;
                            }
                        }
                        return String.join(", ", parts);
                    }
                }
                return String.valueOf(r);
            }).collect(java.util.stream.Collectors.joining("\n"));

        } catch (Exception e) {
        return "ERROR: " + e.getMessage();
        }
                }

/**
 * Определяем, какой data-файл использовать:
 * - для CREATE: если таблицы ещё нет в каталоге — предполагаем следующий oid.dat невозможно,
 *   поэтому берём tableDefinition после фактического create в executor (но у нас executor уже внутри)
 *   => решение: для CREATE используем "temp" и rely on createDataFile в CatalogManager.
 *
 * Но проще: для CREATE/INSERT/SELECT берём таблицу из rangeTable[0] и ищем в каталоге,
 * а если её нет — используем tableName + ".dat" не получится. Поэтому:
 * - CREATE: используем Path.of("create_tmp.dat") (буфер пул почти не нужен)
 * - остальные: берём из каталога fileNode.
 */
private Path resolveTableFile(QueryTree qt) {
    String tableName = (qt.rangeTable != null && !qt.rangeTable.isEmpty())
            ? qt.rangeTable.get(0).relname
            : null;

    if (tableName == null) {
        return Path.of("1.dat").toAbsolutePath();
    }

    // CREATE: таблицы ещё может не быть, поэтому буфер пул по файлу не критичен
    if (qt.commandType != null && qt.commandType.name().equals("CREATE")) {
        return Path.of("create_tmp.dat").toAbsolutePath();
    }

    TableDefinition table = catalog.getTable(tableName);
    if (table == null) {
        // если таблицы нет — пусть будет хоть что-то, но дальше planner обычно должен упасть
        return Path.of("1.dat").toAbsolutePath();
    }

    return Path.of(table.getFileNode()).toAbsolutePath();
}

private void log(String stage, Object obj) {
    System.out.println("=== " + stage + " ===");
    System.out.println(obj);
}
}
