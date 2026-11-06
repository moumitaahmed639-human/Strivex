package com.mycompany.strivex;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBConnection {

    private static final String URL = setting("STRIVEX_DB_URL", "jdbc:mysql://localhost:3306/strivex_db");
    private static final String USER = setting("STRIVEX_DB_USER", "root");
    private static final String PASSWORD = setting("STRIVEX_DB_PASSWORD", "");

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    public static Connection getConnection() throws SQLException {
        try {
            // Explicit loading avoids relying on JDBC service discovery inside Tomcat.
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver is missing from the web application.", e);
        }
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
