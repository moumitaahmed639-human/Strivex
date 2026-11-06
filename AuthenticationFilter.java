package com.mycompany.strivex;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.*;
import java.io.IOException;

@WebFilter("/*")
public class AuthenticationFilter implements Filter {
    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException{
        HttpServletRequest req=(HttpServletRequest)request;HttpServletResponse res=(HttpServletResponse)response;
        String path=req.getRequestURI().substring(req.getContextPath().length());
        if(isPublic(path)){chain.doFilter(request,response);return;}
        HttpSession session=req.getSession(false);
        if(session!=null&&session.getAttribute("strivexUserId") instanceof Integer){chain.doFilter(request,response);return;}
        if(path.startsWith("/api/")||path.startsWith("/save-note")){res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"message\":\"Please sign in to continue.\"}");}
        else res.sendRedirect(req.getContextPath()+"/login.html");
    }
    private boolean isPublic(String p){return p.equals("/login.html")||p.equals("/register.html")||p.equals("/favicon.ico")||p.startsWith("/css/")||p.startsWith("/js/")||p.startsWith("/images/")||p.equals("/auth/login")||p.equals("/auth/register")||p.equals("/auth/guest")||p.equals("/auth/logout");}
}
