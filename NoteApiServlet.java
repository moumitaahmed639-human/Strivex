package com.mycompany.strivex;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@WebServlet("/api/notes")
public class NoteApiServlet extends HttpServlet {
    @Override
    public void init() {
        try (Connection c = DBConnection.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS notes (id INT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(255) NOT NULL, subject VARCHAR(100), category VARCHAR(100), content TEXT NOT NULL, owner_id INT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        } catch (Exception e) {
            getServletContext().log("Strivex notes database is not available yet", e);
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT id,title,subject,category,content,created_at FROM notes WHERE owner_id=? ORDER BY created_at DESC, id DESC")) {
            p.setInt(1, currentUser(request));
            try (ResultSet r = p.executeQuery()) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            while (r.next()) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"id\":").append(r.getInt("id"))
                    .append(",\"title\":\"").append(escape(r.getString("title")))
                    .append("\",\"subject\":\"").append(escape(r.getString("subject")))
                    .append("\",\"category\":\"").append(escape(r.getString("category")))
                    .append("\",\"content\":\"").append(escape(r.getString("content")))
                    .append("\",\"created\":\"").append(escape(String.valueOf(r.getTimestamp("created_at")))).append("\"}");
            }
            response.getWriter().write(json.append(']').toString());
            }
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.getWriter().write("{\"error\":\"Notes database is unavailable. Local browser storage remains available.\"}");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        String title = request.getParameter("title");
        String content = request.getParameter("content");
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Title and content are required.");
            return;
        }
        response.setContentType("application/json;charset=UTF-8");
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("INSERT INTO notes(title,subject,category,content,owner_id) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, title.trim());
            p.setString(2, request.getParameter("subject"));
            p.setString(3, request.getParameter("category"));
            p.setString(4, content.trim());
            p.setInt(5, currentUser(request));
            p.executeUpdate();
            try (ResultSet keys = p.getGeneratedKeys()) {
                if (keys.next()) response.getWriter().write("{\"id\":" + keys.getInt(1) + "}");
                else response.getWriter().write("{}");
            }
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.getWriter().write("{\"error\":\"Notes database is unavailable.\"}");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String id = request.getParameter("id");
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("DELETE FROM notes WHERE id=? AND owner_id=?")) {
            p.setInt(1, Integer.parseInt(id));
            p.setInt(2, currentUser(request));
            p.executeUpdate();
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
    }

    private static int currentUser(HttpServletRequest request) { return (Integer) request.getSession(false).getAttribute("strivexUserId"); }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
