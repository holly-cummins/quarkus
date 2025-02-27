package io.quarkus.it.opentelemetry;

import java.util.HashMap;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class OracleLifecycleManager implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        try {
            System.out.println("HOLLY about to have a rest");
            Thread.sleep(30 * 1000);
            System.out.println("HOLLY done did have a rest");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println("HOLLY OOO starting OracleLifecycleManager" + this.getClass().getClassLoader());
        Map<String, String> properties = new HashMap<>();
        properties.put("quarkus.datasource.oracle.jdbc.url", "jdbc:oracle:thin:@localhost:1521/FREEPDB1");
        properties.put("quarkus.datasource.oracle.password", "quarkus");
        properties.put("quarkus.datasource.oracle.username", "SYSTEM");
        properties.put("quarkus.hibernate-orm.oracle.database.generation", "drop-and-create");
        properties.put("quarkus.hibernate-orm.oracle.active", "true");
        properties.put("quarkus.hibernate-orm.mariadb.active", "false");
        properties.put("quarkus.hibernate-orm.postgresql.active", "false");
        properties.put("quarkus.hibernate-orm.db2.active", "false");

        return properties;
    }

    @Override
    public void stop() {
        // EMPTY
    }

}
