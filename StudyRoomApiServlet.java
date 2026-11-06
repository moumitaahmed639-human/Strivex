package com.mycompany.strivex;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@WebServlet("/api/rooms")
public class StudyRoomApiServlet extends HttpServlet {
    @Override public void init() {
        String rooms = "CREATE TABLE IF NOT EXISTS study_rooms (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, subject VARCHAR(100) NOT NULL, owner_id INT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX idx_study_rooms_created(created_at), CONSTRAINT fk_study_rooms_owner FOREIGN KEY(owner_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB";
        String members = "CREATE TABLE IF NOT EXISTS study_room_members (room_id INT NOT NULL, user_id INT NOT NULL, joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY(room_id,user_id), CONSTRAINT fk_room_members_room FOREIGN KEY(room_id) REFERENCES study_rooms(id) ON DELETE CASCADE, CONSTRAINT fk_room_members_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB";
        try (Connection c = DBConnection.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(rooms);
            s.executeUpdate(members);
        } catch (Exception e) {
            getServletContext().log("Strivex study room database setup failed", e);
        }
    }

    @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        json(res);
        int userId = userId(req);
        String sql = "SELECT r.id,r.name,r.subject,u.name AS host_name,(r.owner_id=?) AS is_host,(SELECT COUNT(*) FROM study_room_members m WHERE m.room_id=r.id) AS member_count,EXISTS(SELECT 1 FROM study_room_members mine WHERE mine.room_id=r.id AND mine.user_id=?) AS joined FROM study_rooms r JOIN users u ON u.id=r.owner_id ORDER BY r.created_at DESC,r.id DESC";
        try (Connection c = DBConnection.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setInt(1, userId);p.setInt(2, userId);
            try (ResultSet rows = p.executeQuery()) {
                StringBuilder out = new StringBuilder("["); boolean first = true;
                while (rows.next()) {
                    if (!first) out.append(','); first = false;
                    out.append("{\"id\":").append(rows.getInt("id"))
                       .append(",\"name\":\"").append(escape(rows.getString("name")))
                       .append("\",\"subject\":\"").append(escape(rows.getString("subject")))
                       .append("\",\"host\":\"").append(escape(rows.getString("host_name")))
                       .append("\",\"isHost\":").append(rows.getBoolean("is_host"))
                       .append(",\"members\":").append(rows.getInt("member_count"))
                       .append(",\"joined\":").append(rows.getBoolean("joined")).append('}');
                }
                res.getWriter().write(out.append(']').toString());
            }
        } catch (Exception e) { fail(res, e); }
    }

    @Override protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        req.setCharacterEncoding("UTF-8"); json(res);
        int userId = userId(req); String action = req.getParameter("action");
        try (Connection c = DBConnection.getConnection()) {
            if ("join".equals(action) || "leave".equals(action)) {
                int roomId = Integer.parseInt(req.getParameter("id"));
                if ("join".equals(action)) {
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO study_room_members(room_id,user_id) SELECT id,? FROM study_rooms WHERE id=? ON DUPLICATE KEY UPDATE joined_at=CURRENT_TIMESTAMP")) { p.setInt(1,userId); p.setInt(2,roomId); if(p.executeUpdate()==0){res.setStatus(404);res.getWriter().write("{\"message\":\"This room is no longer available.\"}");return;} }
                } else {
                    try (PreparedStatement p = c.prepareStatement("DELETE FROM study_room_members WHERE room_id=? AND user_id=?")) { p.setInt(1,roomId);p.setInt(2,userId);p.executeUpdate(); }
                }
                res.getWriter().write("{\"ok\":true}"); return;
            }
            String name = req.getParameter("name")==null?"":req.getParameter("name").trim();
            String subject = req.getParameter("subject")==null?"":req.getParameter("subject").trim();
            if(name.isEmpty()||name.length()>100||subject.length()>100){res.setStatus(400);res.getWriter().write("{\"message\":\"Add a room name (up to 100 characters) and a subject up to 100 characters.\"}");return;}
            if(subject.isEmpty()) subject="General study";
            try (PreparedStatement p = c.prepareStatement("INSERT INTO study_rooms(name,subject,owner_id) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                p.setString(1,name);p.setString(2,subject);p.setInt(3,userId);p.executeUpdate();
                try(ResultSet keys=p.getGeneratedKeys()){if(keys.next()){int room=keys.getInt(1);try(PreparedStatement member=c.prepareStatement("INSERT INTO study_room_members(room_id,user_id) VALUES(?,?)")){member.setInt(1,room);member.setInt(2,userId);member.executeUpdate();}res.setStatus(201);res.getWriter().write("{\"id\":"+room+"}");}}
            }
        } catch (NumberFormatException e) { res.setStatus(400);res.getWriter().write("{\"message\":\"Invalid room. Refresh and try again.\"}"); }
        catch (Exception e) { fail(res,e); }
    }

    @Override protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
        json(res);
        try(Connection c=DBConnection.getConnection();PreparedStatement p=c.prepareStatement("DELETE FROM study_rooms WHERE id=? AND owner_id=?")){
            p.setInt(1,Integer.parseInt(req.getParameter("id")));p.setInt(2,userId(req));
            if(p.executeUpdate()==0){res.setStatus(404);res.getWriter().write("{\"message\":\"Only the room host can end this room.\"}");return;}
            res.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }catch(Exception e){fail(res,e);}
    }

    private static int userId(HttpServletRequest req){return (Integer)req.getSession(false).getAttribute("strivexUserId");}
    private static void json(HttpServletResponse res){res.setCharacterEncoding("UTF-8");res.setContentType("application/json;charset=UTF-8");res.setHeader("Cache-Control","no-store");}
    private void fail(HttpServletResponse res,Exception e)throws IOException{getServletContext().log("Strivex study room request failed",e);res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);res.getWriter().write("{\"message\":\"Study rooms are temporarily unavailable. Check that the Strivex database is running.\"}");}
    private static String escape(String value){if(value==null)return "";return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
}
