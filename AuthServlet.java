package com.mycompany.strivex;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@WebServlet("/auth/*")
public class AuthServlet extends HttpServlet {
    private static final int ITERATIONS=310000;
    private static final int KEY_BITS=256;
    private static final String GUEST_EMAIL_PREFIX="strivex-guest-";
    private static final String GUEST_EMAIL_DOMAIN="@guest.strivex.local";
    private static final Pattern EMAIL=Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final SecureRandom random=new SecureRandom();
    private final ConcurrentHashMap<String,RateWindow> authWindows=new ConcurrentHashMap<>();

    @Override public void init() {
        try(Connection c=DBConnection.getConnection();Statement s=c.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS users (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, email VARCHAR(254) NOT NULL UNIQUE, password_salt VARCHAR(100) NOT NULL, password_hash VARCHAR(100) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            try { ensureColumn(c,"notes","owner_id","INT NULL"); } catch(SQLException ignored) { }
            try { ensureColumn(c,"documents","owner_id","INT NULL"); } catch(SQLException ignored) { }
        }catch(Exception e){getServletContext().log("Strivex authentication database setup failed",e);}
    }

    private void ensureColumn(Connection c,String table,String column,String definition)throws SQLException{
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT "+column+" FROM "+table+" LIMIT 0")){return;}
        catch(SQLException missing){try(Statement s=c.createStatement()){s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);}}
    }

    @Override protected void doPost(HttpServletRequest req,HttpServletResponse res)throws IOException{
        req.setCharacterEncoding("UTF-8");res.setCharacterEncoding("UTF-8");res.setContentType("application/json;charset=UTF-8");
        String action=req.getPathInfo();
        try{
            if(("/register".equals(action)||"/login".equals(action)||"/guest".equals(action))&&!allowAuthAttempt(req)){
                res.setHeader("Retry-After","60");send(res,429,"{\"message\":\"Too many sign-in attempts. Please wait a minute and try again.\"}");return;
            }
            if("/register".equals(action)){register(req,res);return;}
            if("/login".equals(action)){login(req,res);return;}
            if("/guest".equals(action)){guest(req,res);return;}
            if("/logout".equals(action)){logout(req,res);return;}
            send(res,404,"{\"message\":\"That action was not found.\"}");
        }catch(Exception e){getServletContext().log("Strivex authentication request failed",e);send(res,503,"{\"message\":\"The account service is unavailable. Check that the Strivex database is running.\"}");}
    }

    @Override protected void doGet(HttpServletRequest req,HttpServletResponse res)throws IOException{
        if("/logout".equals(req.getPathInfo())){logout(req,res);return;}
        res.sendError(405);
    }

    private void register(HttpServletRequest req,HttpServletResponse res)throws Exception{
        String name=value(req,"name").trim(),email=value(req,"email").trim().toLowerCase(),password=value(req,"password");
        if(name.isEmpty()||name.length()>100||!EMAIL.matcher(email).matches()||email.length()>254||password.length()<8||password.length()>128){send(res,400,"{\"message\":\"Enter your name, a valid email, and a password of at least 8 characters.\"}");return;}
        byte[] salt=new byte[16];random.nextBytes(salt);byte[] hash=derive(password.toCharArray(),salt);
        try(Connection c=DBConnection.getConnection()){
            boolean firstAccount;
            try(Statement count=c.createStatement();ResultSet rows=count.executeQuery("SELECT COUNT(*) FROM users")){rows.next();firstAccount=rows.getInt(1)==0;}
            try(PreparedStatement p=c.prepareStatement("INSERT INTO users(name,email,password_salt,password_hash) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
            p.setString(1,name);p.setString(2,email);p.setString(3,Base64.getEncoder().encodeToString(salt));p.setString(4,Base64.getEncoder().encodeToString(hash));p.executeUpdate();
            try(ResultSet k=p.getGeneratedKeys()){if(k.next()){int id=k.getInt(1);if(firstAccount)claimLegacyRows(c,id);establishSession(req,id,name);send(res,201,"{\"message\":\"Account created.\",\"name\":\""+json(name)+"\",\"redirect\":\"index.html\"}");}}}
        }catch(SQLIntegrityConstraintViolationException duplicate){send(res,409,"{\"message\":\"An account with that email already exists. Sign in instead.\"}");}
    }

    private void login(HttpServletRequest req,HttpServletResponse res)throws Exception{
        String email=value(req,"email").trim().toLowerCase();char[] password=value(req,"password").toCharArray();
        try(Connection c=DBConnection.getConnection();PreparedStatement p=c.prepareStatement("SELECT id,name,password_salt,password_hash FROM users WHERE email=?")){
            p.setString(1,email);
            try(ResultSet r=p.executeQuery()){
                if(!r.next()){send(res,401,"{\"message\":\"Email or password is incorrect.\"}");return;}
                byte[] salt=Base64.getDecoder().decode(r.getString("password_salt"));byte[] expected=Base64.getDecoder().decode(r.getString("password_hash"));
                if(!MessageDigest.isEqual(expected,derive(password,salt))){send(res,401,"{\"message\":\"Email or password is incorrect.\"}");return;}
                String name=r.getString("name");establishSession(req,r.getInt("id"),name);send(res,200,"{\"message\":\"Welcome back.\",\"name\":\""+json(name)+"\",\"redirect\":\"index.html\"}");
            }
        }
    }
    private synchronized void guest(HttpServletRequest req,HttpServletResponse res)throws Exception{
        try(Connection c=DBConnection.getConnection()){
            byte[] salt=new byte[16],secret=new byte[32];random.nextBytes(salt);random.nextBytes(secret);
            String name="Guest Student",email=GUEST_EMAIL_PREFIX+UUID.randomUUID()+GUEST_EMAIL_DOMAIN;
            try(PreparedStatement p=c.prepareStatement("INSERT INTO users(name,email,password_salt,password_hash) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                p.setString(1,name);p.setString(2,email);p.setString(3,Base64.getEncoder().encodeToString(salt));p.setString(4,Base64.getEncoder().encodeToString(derive(Base64.getEncoder().encodeToString(secret).toCharArray(),salt)));p.executeUpdate();
                try(ResultSet k=p.getGeneratedKeys()){if(k.next()){int id=k.getInt(1);establishGuestSession(req,id);send(res,201,"{\"message\":\"Welcome, Guest Student.\",\"name\":\"Guest Student\",\"email\":\""+json(email)+"\",\"redirect\":\"index.html\"}");}}
            }
        }
    }
    private boolean allowAuthAttempt(HttpServletRequest req){
        long now=System.currentTimeMillis();String address=req.getRemoteAddr();AtomicBoolean allowed=new AtomicBoolean();
        RateWindow window=authWindows.compute(address,(key,current)->{if(current==null||now-current.started>=60_000)current=new RateWindow(now);current.count++;allowed.set(current.count<=30);return current;});
        if(authWindows.size()>10000)authWindows.entrySet().removeIf(e->now-e.getValue().started>=120_000);
        return allowed.get();
    }
    private static final class RateWindow{final long started;int count;RateWindow(long started){this.started=started;}}
    private void establishGuestSession(HttpServletRequest req,int id){establishSession(req,id,"Guest Student");req.getSession(false).setAttribute("strivexGuest",Boolean.TRUE);}
    private void claimLegacyRows(Connection c,int id){
        for(String table:new String[]{"notes","documents"})try(PreparedStatement p=c.prepareStatement("UPDATE "+table+" SET owner_id=? WHERE owner_id IS NULL")){p.setInt(1,id);p.executeUpdate();}catch(SQLException ignored){}
    }
    private void establishSession(HttpServletRequest req,int id,String name){HttpSession session=req.getSession(true);req.changeSessionId();session.removeAttribute("strivexGuest");session.setAttribute("strivexUserId",id);session.setAttribute("strivexUserName",name);session.setMaxInactiveInterval(30*60);}
    private void logout(HttpServletRequest req,HttpServletResponse res)throws IOException{HttpSession s=req.getSession(false);if(s!=null)s.invalidate();if("POST".equalsIgnoreCase(req.getMethod()))send(res,200,"{\"redirect\":\"login.html\"}");else res.sendRedirect(req.getContextPath()+"/login.html");}
    private static byte[] derive(char[] password,byte[] salt)throws Exception{PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,KEY_BITS);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();java.util.Arrays.fill(password,(char)0);}}
    private static String value(HttpServletRequest r,String key){String v=r.getParameter(key);return v==null?"":v;}
    private static void send(HttpServletResponse r,int status,String body)throws IOException{r.setStatus(status);r.setHeader("Cache-Control","no-store");r.setHeader("X-Content-Type-Options","nosniff");r.getWriter().write(body);}
    private static String json(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
}

