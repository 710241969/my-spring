package com.demo.controller;

import com.demo.mvcframework.annotation.Controller;
import com.demo.mvcframework.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Controller
public class IndexController {
    @RequestMapping(path = "/")
    public void index(HttpServletRequest request, HttpServletResponse response) {
        try {
            response.getWriter().write("hello world");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
