package com.demo.controller;

import com.demo.mvcframework.annotation.Autowired;
import com.demo.mvcframework.annotation.Controller;
import com.demo.mvcframework.annotation.RequestMapping;
import com.demo.mvcframework.annotation.RequestParam;
import com.demo.service.impl.HelloService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Controller
@RequestMapping(path = "/hello")
public class HelloController {
    @Autowired
    private HelloService helloService;

    @RequestMapping(path = "/world")
    public void helloWorld(HttpServletRequest request, HttpServletResponse response, @RequestParam(value = "name") String name) {
        try {
            response.getWriter().write("Hello," + name);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @RequestMapping(path = "/age")
    public void getAge(HttpServletRequest request, HttpServletResponse response) {
        try {
            response.getWriter().write("Age is " + helloService.getAge());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
