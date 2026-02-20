package io.a2a.extras.taskstore.jdbc.config;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

public class A2ATablePrefixNamingStrategy extends PhysicalNamingStrategyStandardImpl {

    private static String tablePrefix = "a2a_";

    public static void setTablePrefix(String prefix) {
        tablePrefix = prefix != null ? prefix : "";
    }

    @Override
    public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment context) {
        if (name == null) {
            return null;
        }
        String tableName = name.getText();
        if (tablePrefix != null && !tablePrefix.isEmpty() && !tableName.startsWith(tablePrefix)) {
            return Identifier.toIdentifier(tablePrefix + tableName);
        }
        return name;
    }
}
