package ru.open.cu.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.manager.DefaultCatalogManager;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.catalog.model.TableDefinition;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DropTableFileTest {

    @Test
    void dropRemovesDataFile(@TempDir Path tempDir) throws Exception {
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            CatalogManager catalog = new DefaultCatalogManager();
            TypeDefinition varchar = catalog.getType("varchar");
            TypeDefinition integer = catalog.getType("integer");

            String tableName = "drop_test_" + Math.abs(tempDir.toAbsolutePath().toString().hashCode());

            catalog.createTable(
                    tableName,
                    List.of(
                            new ColumnDefinition(varchar.oid(), "name", 0),
                            new ColumnDefinition(integer.oid(), "id", 1)
                    )
            );

            TableDefinition td = catalog.getTable(tableName);
            assertNotNull(td);
            String dataFile = td.getFileNode();

            assertTrue(Files.exists(Path.of(dataFile)));

            catalog.dropTable(tableName);

            assertFalse(Files.exists(Path.of(dataFile)));

        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }
}
