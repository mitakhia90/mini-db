package ru.open.cu.student.planner;


import ru.open.cu.student.ast.QueryTree;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.ast.Expr;
import ru.open.cu.student.ast.TargetEntry;
import ru.open.cu.student.planner.node.*;

import java.util.ArrayList;
import java.util.List;


/**
 * Планировщик, преобразующий QueryTree в LogicalPlanNode.
 */
public class PlannerImpl implements Planner {

    private final CatalogManager catalogManager;

    public PlannerImpl(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
    }

    @Override
    public LogicalPlanNode plan(QueryTree queryTree) {
        if (queryTree == null) throw new IllegalArgumentException("QueryTree is null");

        return switch (queryTree.commandType) {
            case CREATE -> planCreate(queryTree);
            case INSERT -> planInsert(queryTree);
            case SELECT -> planSelect(queryTree); // поменять на нужное
            default -> throw new IllegalArgumentException("Unsupported command type: " + queryTree.commandType);
        };
    }

    // ---------- CREATE ----------
    private LogicalPlanNode planCreate(QueryTree q) {
        String tableName = extractTableName(q);

        List<ColumnDefinition> columns = new ArrayList<>();
        int position = 0;
        for (TargetEntry te : q.targetList) {
            TypeDefinition type;
            String rt = te.resultType;
            if (rt == null || rt.isBlank()) {
                throw new IllegalArgumentException("Column type is not specified");
            }

            try {
                int typeOid = Integer.parseInt(rt);
                type = catalogManager.getType(typeOid);
            } catch (NumberFormatException ignored) {
                type = catalogManager.getType(rt);
            }

            if (type == null) {
                throw new IllegalArgumentException("Type '" + rt + "' not found");
            }

            columns.add(new ColumnDefinition(
                    type.oid(),
                    te.alias,
                    position++
            ));
        }

        TableDefinition tableDef = new TableDefinition(0, tableName, "USER", tableName, 0);
        tableDef.setColumns(columns);

        return new CreateTableNode(tableDef);
    }

    // ---------- INSERT ----------
    private LogicalPlanNode planInsert(QueryTree q) {
        String tableName = extractTableName(q);
        TableDefinition tableDef = catalogManager.getTable(tableName);

        List<Expr> values = q.targetList.stream()
                .map(te -> te.expr)
                .toList();

        return new InsertNode(tableDef, values);
    }

    // ---------- SELECT ---------
    private LogicalPlanNode planSelect(QueryTree q) {
        String tableName = extractTableName(q);
        TableDefinition tableDef = catalogManager.getTable(tableName);
        LogicalPlanNode plan = new ScanNode(tableDef);

        // 2. Применение фильтра WHERE (если есть)
        if (q.whereClause != null) {
            plan = new FilterNode(q.whereClause, plan);
        }

        // 3. Проекция SELECT-списка (верхний узел)
        plan = new ProjectNode(q.targetList, plan);

        return plan;
    }

    private String extractTableName(QueryTree q) {
        if (q.rangeTable != null && !q.rangeTable.isEmpty() && q.rangeTable.get(0).relname != null) {
            return q.rangeTable.get(0).relname;
        }
        throw new IllegalArgumentException("Cannot determine table name");
    }
}