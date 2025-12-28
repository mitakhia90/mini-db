package ru.open.cu.student.execution;


import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.operation.OperationManager;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.execution.executors.*;
import ru.open.cu.student.memory.buffer.BufferPoolManager;
import ru.open.cu.student.optimizer.node.*;

import java.util.List;

public class ExecutorFactoryImpl implements ExecutorFactory {

    private final CatalogManager catalogManager;
    private final OperationManager operationManager;
    private final BufferPoolManager bufferPool;


    public ExecutorFactoryImpl(CatalogManager catalogManager, OperationManager operationManager, BufferPoolManager bufferPool) {
        this.catalogManager = catalogManager;
        this.operationManager = operationManager;
        this.bufferPool = bufferPool;
    }

    @Override
    public Executor createExecutor(PhysicalPlanNode plan) {
        if (plan instanceof PhysicalCreateNode create) {
            return new CreateTableExecutor(catalogManager, create.getTableDefinition());

        } else if (plan instanceof PhysicalInsertNode insert) {
            return new InsertExecutor(
                    operationManager,
                    insert.getTableDefinition(),
                    insert.getValues()
            );

        } else if (plan instanceof PhysicalSeqScanNode scan) {
            return new SeqScanExecutor(bufferPool, scan.getTableDefinition(), catalogManager);

        } else if (plan instanceof PhysicalFilterNode filter) {
            Executor child = createExecutor(filter.getChild());
            return new FilterExecutor(child, filter.getCondition());

        } else if (plan instanceof PhysicalProjectNode project) {
            Executor child = createExecutor(project.getChild());
            // Try to extract table columns from child physical node if it's a seq-scan
            List<ColumnDefinition> columns = null;
            if (project.getChild() instanceof PhysicalSeqScanNode ps) {
                TableDefinition td = ps.getTableDefinition();
                if (td != null) {
                    columns = catalogManager.getTableColumns(td);
                }
            }
            return new ProjectExecutor(child, project.getTargetList(), columns);
        }

        throw new UnsupportedOperationException(
                "Unsupported physical plan node: " + plan.getClass().getSimpleName()
        );
    }
}