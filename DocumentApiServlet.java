package com.mycompany.strivex;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@WebServlet("/api/documents")
@MultipartConfig(maxFileSize = 10 * 1024 * 1024, maxRequestSize = 11 * 1024 * 1024)
public class DocumentApiServlet extends HttpServlet {
    @Override public void init() {
        try (Connection c=DBConnection.getConnection(); Statement s=c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS documents (id INT AUTO_INCREMENT PRIMARY KEY, file_name VARCHAR(255) NOT NULL, content_type VARCHAR(150), file_size BIGINT NOT NULL, file_data MEDIUMBLOB NOT NULL, owner_id INT NULL, uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        } catch (Exception e) { getServletContext().log("Strivex document storage is not available yet", e); }
    }

    @Override protected void doGet(HttpServletRequest req,HttpServletResponse res) throws IOException {
        String id=req.getParameter("id");
        try(Connection c=DBConnection.getConnection()) {
            if(id!=null) {
                try(PreparedStatement p=c.prepareStatement("SELECT file_name,content_type,file_data FROM documents WHERE id=? AND owner_id=?")) {
                    p.setInt(1,Integer.parseInt(id));p.setInt(2,currentUser(req));
                    try(ResultSet r=p.executeQuery()) {
                        if(!r.next()){res.sendError(404);return;}
                        String name=r.getString("file_name").replace("\r", "").replace("\n", "").replace("\"", "");
                        res.setContentType(r.getString("content_type")==null?"application/octet-stream":r.getString("content_type"));
                        res.setHeader("Content-Disposition","attachment; filename=\""+name+"\"; filename*=UTF-8''"+URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20"));
                        try(var in=r.getBinaryStream("file_data")){in.transferTo(res.getOutputStream());}
                    }
                }
                return;
            }
            res.setContentType("application/json;charset=UTF-8");
            try(PreparedStatement p=c.prepareStatement("SELECT id,file_name,file_size,uploaded_at FROM documents WHERE owner_id=? ORDER BY uploaded_at DESC,id DESC")){p.setInt(1,currentUser(req));try(ResultSet r=p.executeQuery()) {
                StringBuilder out=new StringBuilder("[");boolean first=true;
                while(r.next()){if(!first)out.append(',');first=false;out.append("{\"id\":").append(r.getInt("id")).append(",\"name\":\"").append(json(r.getString("file_name"))).append("\",\"size\":").append(r.getLong("file_size")).append(",\"date\":\"").append(json(String.valueOf(r.getTimestamp("uploaded_at")))).append("\"}");}
                res.getWriter().write(out.append(']').toString());
            }}
        } catch(Exception e){res.setStatus(503);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"error\":\"Document database is unavailable.\"}");}
    }

    @Override protected void doPost(HttpServletRequest req,HttpServletResponse res) throws ServletException,IOException {
        Part part=req.getPart("file");
        if(part==null||part.getSize()==0){res.sendError(400,"Choose a file to upload.");return;}
        String name=part.getSubmittedFileName();
        if(name==null||name.isBlank()){res.sendError(400,"Invalid file name.");return;}
        name=name.substring(name.lastIndexOf('\\')+1).substring(name.substring(name.lastIndexOf('\\')+1).lastIndexOf('/')+1);
        if(name.length()>255)name=name.substring(name.length()-255);
        String type=part.getContentType()==null?"application/octet-stream":part.getContentType();
        try(Connection c=DBConnection.getConnection();PreparedStatement p=c.prepareStatement("INSERT INTO documents(file_name,content_type,file_size,file_data,owner_id) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
            p.setString(1,name);p.setString(2,type);p.setLong(3,part.getSize());p.setBinaryStream(4,part.getInputStream(),part.getSize());p.setInt(5,currentUser(req));p.executeUpdate();
            try(ResultSet keys=p.getGeneratedKeys()){res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"id\":"+(keys.next()?keys.getInt(1):0)+"}");}
        }catch(Exception e){getServletContext().log("Could not store Strivex document",e);res.sendError(503,"Document storage is unavailable.");}
    }

    @Override protected void doDelete(HttpServletRequest req,HttpServletResponse res)throws IOException{
        try(Connection c=DBConnection.getConnection();PreparedStatement p=c.prepareStatement("DELETE FROM documents WHERE id=? AND owner_id=?")){p.setInt(1,Integer.parseInt(req.getParameter("id")));p.setInt(2,currentUser(req));p.executeUpdate();res.setStatus(204);}catch(Exception e){res.sendError(503,"Document storage is unavailable.");}
    }
    private static int currentUser(HttpServletRequest req){return (Integer)req.getSession(false).getAttribute("strivexUserId");}
    private static String json(String s){if(s==null)return "";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
}
