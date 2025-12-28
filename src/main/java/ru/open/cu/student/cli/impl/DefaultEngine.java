package ru.open.cu.student.cli.impl;

import ru.open.cu.student.SqlProcessor;
import ru.open.cu.student.cli.api.Engine;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.manager.DefaultCatalogManager;
import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.operation.DefaultOperationManager;
import ru.open.cu.student.catalog.operation.OperationManager;
import ru.open.cu.student.execution.ExecutorFactory;
import ru.open.cu.student.execution.ExecutorFactoryImpl;
import ru.open.cu.student.execution.QueryExecutionEngineImpl;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultEngine implements Engine {

    private final CatalogManager catalog = new DefaultCatalogManager();

    private final Lexer lexer = new DefaultLexer();
    private final Parser parser = new DefaultParser();

    // SqlProcessor: использует lexer/parser и переводит в HW5 QueryTree (ru.open.cu.student.ast.*)
    private final SqlProcessor sqlProcessor = new SqlProcessor(lexer, parser, catalog);

    private final Planner planner = new PlannerImpl(catalog);
    private final Optimizer optimizer = new OptimizerImpl();

    // Storage/manager (не привязан к конкретному файлу)
    private final PageFileManager pfm = new HeapPageFileManager();

    private final OperationManager opManager = new DefaultOperationManager(catalog);
    private final QueryExecutionEngineImpl execEngine = new QueryExecutionEngineImpl();

    @Override
    public String executeSql(String sql) {
        try {
            if (sql == null) throw new IllegalArgumentException("sql is null");
            String trimmed = sql.trim();

            // HELP
            if (trimmed.equalsIgnoreCase("help") || trimmed.equalsIgnoreCase("?")) {
                return "Available commands:\n" +
                        "CREATE TABLE <name>(...)\n" +
                        "INSERT INTO <name> VALUES (...)\n" +
                        "SELECT ... FROM <name> [WHERE ...]\n" +
                        "DROP TABLE <name> [IF EXISTS]\n" +
                        "DELETE FROM <name> WHERE <column> = <value>\n" +
                        "LIST TABLES\n" +
                        "HELP";
            }

            // LIST TABLES
            if (trimmed.equalsIgnoreCase("list tables") || trimmed.matches("(?i)list\\s+tables.*")) {
                List<TableDefinition> tables = catalog.listTables();
                if (tables.isEmpty()) return "(no tables)";
                return tables.stream().map(TableDefinition::getName).collect(Collectors.joining("\n"));
            }

            // Simple DELETE parsing: DELETE FROM <table> WHERE <column> = <value>
            Pattern deletePattern = Pattern.compile("(?i)^\\s*delete\\s+from\\s+(\\w+)\\s+where\\s+(\\w+)\\s*=\\s*('?\"?)([^'\";]+)\\1.*");
            Matcher delMatcher = deletePattern.matcher(trimmed);
            if (delMatcher.matches()) {
                String table = delMatcher.group(1);
                String column = delMatcher.group(2);
                String rawVal = delMatcher.group(4).trim();
                Object val = parseLiteral(rawVal);
                int deleted = opManager.delete(table, column, val);
                return "OK, deleted=" + deleted;
            }

            // 1) Lexer
            List<Token> tokens = lexer.tokenize(sql);
            log("TOKENS", tokens);

            // 2) Parser -> AST
            AstNode ast = parser.parse(tokens);
            log("AST", ast);

            // 3) SQL -> QueryTree
            var queryTree = sqlProcessor.process(sql);
            log("QUERY_TREE", queryTree);

            // Handle DROP table immediately (no need to plan/execute)
            if (queryTree != null && queryTree.commandType == ru.open.cu.student.ast.QueryType.DROP) {
                if (queryTree.tableName == null || queryTree.tableName.isBlank()) {
                    return "ERROR: Table name missing in DROP";
                }
                try {
                    catalog.dropTable(queryTree.tableName);
                    return "OK";
                } catch (IllegalArgumentException e) {
                    // If IF EXISTS specified — swallow error and return OK
                    try {
                        boolean ifExists = queryTree.dropIfExists;
                        if (ifExists) return "OK";
                    } catch (Exception ignored) { }
                    throw e;
                }
            }

            // 4) Planner -> Logical plan
            LogicalPlanNode logical = planner.plan(queryTree);
            log("LOGICAL_PLAN", logical);

            // 5) Optimizer -> Physical plan
            PhysicalPlanNode physical = optimizer.optimize(logical);
            log("PHYSICAL_PLAN", physical);

            // Определяем путь к файлу таблицы (и создаём BufferPool для этого файла)
            Path tableFile = resolveTableFile(queryTree);

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
            // Format rows: if each row is a List -> join elements with ", ", otherwise use toString
            String tableName = (queryTree.rangeTable != null && !queryTree.rangeTable.isEmpty()) ? queryTree.rangeTable.get(0).relname : null;
            return rows.stream().map(r -> {
                if (r instanceof java.util.List<?> l) {
                    return l.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
                }
                if (r instanceof byte[] b && tableName != null) {
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

    private Object parseLiteral(String rawVal) {
        if (rawVal == null) return null;
        String t = rawVal.trim();
        if (t.equalsIgnoreCase("NULL")) return null;
        if (t.equalsIgnoreCase("TRUE")) return Boolean.TRUE;
        if (t.equalsIgnoreCase("FALSE")) return Boolean.FALSE;
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e2) {
                return t;
            }
        }
    }

    private Path resolveTableFile(ru.open.cu.student.ast.QueryTree qt) {
        String tableName = (qt.rangeTable != null && !qt.rangeTable.isEmpty())
                ? qt.rangeTable.get(0).relname
                : null;

        if (tableName == null) {
            return Path.of("1.dat").toAbsolutePath();
        }

        // CREATE: таблицы ещё может не быть, поэтому используем временный файл
        if (qt.commandType != null && qt.commandType.name().equals("CREATE")) {
            return Path.of("create_tmp.dat").toAbsolutePath();
        }

        TableDefinition table = catalog.getTable(tableName);
        if (table == null) {
            // Если таблицы нет в каталоге — используем имя как файл
            return Path.of(tableName + ".dat").toAbsolutePath();
        }

        // Если в TableDefinition есть fileNode — используем его
        String fileNode = table.getFileNode();
        if (fileNode != null && !fileNode.isBlank()) {
            return Path.of(fileNode).toAbsolutePath();
        }

        // По умолчанию — oid.dat
        return Path.of(table.getOid() + ".dat").toAbsolutePath();
    }

    // (логирование)
    private void log(String tag, Object o) {
        // System.out.println(tag + ": " + o);
    }
}