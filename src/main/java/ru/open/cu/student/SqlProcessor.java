package ru.open.cu.student;

import ru.open.cu.student.ast.*;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.lexer.DefaultLexer;
import ru.open.cu.student.lexer.Lexer;
import ru.open.cu.student.lexer.Token;
import ru.open.cu.student.parser.DefaultParser;
import ru.open.cu.student.parser.Parser;
import ru.open.cu.student.parser.nodes.*;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.parser.nodes.CreateTableStmt;
import ru.open.cu.student.parser.nodes.SelectStmt;
import ru.open.cu.student.parser.nodes.InsertStmt;
import ru.open.cu.student.ast.AConst;
import ru.open.cu.student.ast.AExpr;


import java.util.List;

/**
 * SQL processor used in DI homeworks.
 *
 * HW5 pipeline expects {@link ru.open.cu.student.ast.QueryTree}.
 *
 * This class reuses lexer/parser implementation from HW4 and translates the parsed AST
 * into HW5 AST (ru.open.cu.student.ast.*) to keep planner/optimizer/executor logic intact.
 */
public class SqlProcessor {

    private final Lexer lexer;
    private final Parser parser;
    @SuppressWarnings("unused")
    private final CatalogManager catalogManager;

    public SqlProcessor(CatalogManager catalogManager) {
        this(new DefaultLexer(), new DefaultParser(), catalogManager);
    }

    public SqlProcessor(Lexer lexer, Parser parser, CatalogManager catalogManager) {
        this.lexer = lexer;
        this.parser = parser;
        this.catalogManager = catalogManager;
    }

    /**
     * Parse SQL string and build HW5 {@link QueryTree}.
     *
     * Supported grammar (minimal, as used by the course):
     *  - CREATE TABLE t (col TYPE, ...)
     *  - INSERT INTO t VALUES (v1, v2, ...)
     *  - SELECT col1, col2 FROM t [WHERE col OP const]
     *
     * @param sql query text
     * @return QueryTree for planner
     */
    public QueryTree process(String sql) {
        if (sql == null) throw new IllegalArgumentException("sql is null");

        List<Token> tokens = lexer.tokenize(sql);
        if (tokens.isEmpty()) throw new IllegalArgumentException("Empty SQL");

        String first = tokens.get(0).getType();

        return switch (first) {
            case "CREATE", "SELECT", "UPDATE", "INSERT" -> translateParsedAst(parser.parse(tokens));
            default -> throw new IllegalArgumentException("Unsupported statement: " + first);
        };

    }

    private QueryTree translateParsedAst(AstNode ast) {
        if (ast instanceof CreateTableStmt cs) {
            return translateCreate(cs);
        }
        if (ast instanceof SelectStmt ss) {
            return translateSelect(ss);
        }
        if (ast instanceof InsertStmt is) {
            return translateInsert(is);
        }

        throw new IllegalArgumentException("Unsupported AST node: " + ast.getClass().getSimpleName());
    }

    private QueryTree translateCreate(CreateTableStmt cs) {
        QueryTree q = new QueryTree();
        q.commandType = QueryType.CREATE;

        // Используем RangeVar вместо RangeTblEntry
        RangeVar rangeVar = new RangeVar(cs.schemaName, cs.tableName, null);
        q.rangeTable.add(rangeVar);

        // Преобразуем ColumnDefinition в TargetEntry
        for (ColumnDefinition col : cs.columns) {
            TargetEntry te = new TargetEntry(null, col.getName());
            // сохраняем не OID как строку, а "логическое" имя типа.
            // planner умеет резолвить и имя, и OID.
            te.resultType = catalogManager.getType(col.getTypeOid()).name();
            q.targetList.add(te);
        }
        return q;
    }

    private QueryTree translateSelect(SelectStmt ss) {
        QueryTree q = new QueryTree();
        q.commandType = QueryType.SELECT;

        // FROM
        if (ss.fromClause != null && !ss.fromClause.isEmpty()) {
            for (RangeVar rv : ss.fromClause) {
                // Добавляем RangeVar напрямую, как в translateCreate
                q.rangeTable.add(rv);
            }
        } else {
            throw new IllegalArgumentException("SELECT without FROM is not supported");
        }

        // Target list
        for (ResTarget rt : ss.targetList) {
            // Создаем TargetEntry с val (выражение) и именем (псевдоним)
            Expr expr = translateExpr(rt.val);
            TargetEntry te = new TargetEntry(expr, rt.name);
            q.targetList.add(te);
        }

        // WHERE
        if (ss.whereClause != null) {
            // Преобразуем AstNode whereClause в Expr
            q.whereClause = translateExpr(ss.whereClause);
        }

        return q;
    }

    private QueryTree translateInsert(InsertStmt is) {
        QueryTree q = new QueryTree();
        q.commandType = QueryType.INSERT;

        // Используем RangeVar, как в translateCreate
        RangeVar rangeVar = new RangeVar(is.schemaName, is.tableName, null);
        q.rangeTable.add(rangeVar);

        // Преобразуем значения в AConst выражения, со приведением типов:
        // - NUMBER -> Integer (или Long если выходит за пределы int)
        // - STRING -> String (парсер/лексер уже убирает кавычки)
        // - TRUE/FALSE -> Boolean
        // - NULL -> null
        for (String raw : is.values) {
            Object valueObj = null;
            if (raw == null) {
                valueObj = null;
            } else {
                String trimmed = raw.trim();
                if (trimmed.equalsIgnoreCase("NULL")) {
                    valueObj = null;
                } else if (trimmed.equalsIgnoreCase("TRUE")) {
                    valueObj = Boolean.TRUE;
                } else if (trimmed.equalsIgnoreCase("FALSE")) {
                    valueObj = Boolean.FALSE;
                } else {
                    // Попробуем распарсить число (int, потом long), иначе оставим строкой
                    try {
                        valueObj = Integer.parseInt(trimmed);
                    } catch (NumberFormatException e1) {
                        try {
                            valueObj = Long.parseLong(trimmed);
                        } catch (NumberFormatException e2) {
                            // не число — оставляем строкой
                            valueObj = trimmed;
                        }
                    }
                }
            }
            AConst constExpr = new AConst(valueObj);
            TargetEntry te = new TargetEntry(constExpr, null);
            q.targetList.add(te);
        }

        return q;
    }

    private Expr translateExpr(AstNode node) {
        if (node instanceof ColumnRef cr) {
            return new ru.open.cu.student.ast.ColumnRef(cr.column);
        }
        if (node instanceof AConst ac) {
            // AConst.value в парсерной AST может быть строкой; конвертируем аналогично translateInsert
            Object raw = ac.value;
            if (raw instanceof String s) {
                String trimmed = s.trim();
                if (trimmed.equalsIgnoreCase("NULL")) return new Const(null);
                if (trimmed.equalsIgnoreCase("TRUE")) return new Const(Boolean.TRUE);
                if (trimmed.equalsIgnoreCase("FALSE")) return new Const(Boolean.FALSE);
                try {
                    return new Const(Integer.parseInt(trimmed));
                } catch (NumberFormatException e1) {
                    try {
                        return new Const(Long.parseLong(trimmed));
                    } catch (NumberFormatException e2) {
                        return new Const(trimmed);
                    }
                }
            } else {
                return new Const(raw);
            }
        }
        if (node instanceof AExpr aexpr) {
            Expr left = translateExpr(aexpr.getLeft());
            Expr right = translateExpr(aexpr.getRight());
            return new AExpr(aexpr.getOp(), left, right);
        }
        // По умолчанию — не поддерживаемый узел
        throw new IllegalArgumentException("Unsupported expression node: " + node.getClass().getSimpleName());
    }
}