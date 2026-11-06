package com.mycompany.strivex;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;

@WebServlet("/save-note")
public class SaveNoteServlet extends HttpServlet {

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        String title = request.getParameter("title");
        String subject = request.getParameter("subject");
        String category = request.getParameter("category");
        String content = request.getParameter("content");

        String sql =
                "INSERT INTO notes " +
                "(title, subject, category, content, owner_id) " +
                "VALUES (?, ?, ?, ?, ?)";

        try (
                Connection connection =
                        DBConnection.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {

            statement.setString(1, title);
            statement.setString(2, subject);
            statement.setString(3, category);
            statement.setString(4, content);
            statement.setInt(5, (Integer) request.getSession(false).getAttribute("strivexUserId"));

            statement.executeUpdate();

            response.sendRedirect("notes.html");

        } catch (Exception e) {

            e.printStackTrace();

            response.setContentType("text/plain");

            response.getWriter().println(
                    "Could not save note: " + e.getMessage()
            );
        }
    }
}