package com.demo.mvcframework.servlet;

import com.demo.mvcframework.annotation.*;
import com.demo.utils.StringUtil;
import com.sun.org.apache.xerces.internal.impl.xpath.regex.Match;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DispatchServlet extends HttpServlet {
    private Properties contextConfig = new Properties();
    private List<String> classNameList = new ArrayList<>();
    private Map<String, Object> ioc = new HashMap<>(); // ioc.put(beanName, instance)
    private List<Handler> handlerList = new ArrayList<>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            if (handlerList.isEmpty()) {
                // 没有任何处理，则返回 404
                resp.getWriter().write("404 Not Found");
                return;
            }

            // handlerList 中找到相应的 handler
            String url = req.getRequestURI();
            String contextPath = req.getContextPath();
            url = url.replace(contextPath, "").replaceAll("/+", "/");
            Handler handler = null;
            // 遍历 handlerList 找出对应该请求 uri 的 hadler
            for (Handler handlerItem : handlerList) {
                Matcher matcher = handlerItem.pattern.matcher(url);
                if (!matcher.matches()) {
                    continue;
                }
                handler = handlerItem;
            }
            if (null == handler) {
                resp.getWriter().write("404 Not Found");
                return;
            }

            Class<?>[] paramTypeArray = handler.method.getParameterTypes();
            Object[] paramValueArray = new Object[paramTypeArray.length];
            Map<String, String[]> paramMap = req.getParameterMap(); // 请求携带的参数，key 是参数名，value 是参数的值字符串数组
            for (Map.Entry<String, String[]> param : paramMap.entrySet()) {
                String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", ""); // 将 value 的字符串中的两个中括号去掉 [] ，使用 | 替换多个字符
                if (!handler.paramIndexMap.containsKey(param.getKey())) {
                    continue;
                }
                int index = handler.paramIndexMap.get(param.getKey());
                paramValueArray[index] = value;
            }
            int reqIndex = handler.paramIndexMap.get(HttpServletRequest.class.getName());
            paramValueArray[reqIndex] = req;
            int resIndex = handler.paramIndexMap.get(HttpServletResponse.class.getName());
            paramValueArray[resIndex] = resp;
            handler.method.invoke(handler.controller, paramValueArray); // 通过反射调用方法
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Server Internal Error");
            return;
        }
    }

    /**
     * 当Servlet容器启动时，会调用 DispatcherServlet 的init()方法
     *
     * @param config
     * @throws ServletException
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        // 加载配置文件，从 init 方法的参数中，我们可以拿到主配置文件的路径
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        // 扫描相关类
        doScan(contextConfig.getProperty("scanPackage"));

        // 初始化相关联的类，实例化饼放入 IOC 容器中
        doInstance();

        // 自动化依赖注入
        doAutowired();

        // 初始化 HandlerMapping
        initHandlerMapping();

    }

    /**
     * 初始化 handlerMapping
     * 其实就是将 url 和处理整个 url 的方法映射起来
     */
    private void initHandlerMapping() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            // 只针对 Controller
            Class<?> clazz = entry.getValue().getClass();
            if (!clazz.isAnnotationPresent(Controller.class)) {
                continue;
            }
            String baseUrl = "";
            if (clazz.isAnnotationPresent(RequestMapping.class)) {
                // 类上使用了 RequestMapping 注解
                RequestMapping requestMapping = clazz.getAnnotation(RequestMapping.class);
                baseUrl = requestMapping.path();
            }
            Method[] methodArray = clazz.getMethods();
            for (Method method : methodArray) {
                if (!method.isAnnotationPresent(RequestMapping.class)) {
                    continue;
                }
                RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
                String regex = (baseUrl + requestMapping.path()).toString().replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(regex);
                handlerList.add(new Handler(pattern, entry.getValue(), method));
            }
        }
    }

    private void doAutowired() {
        if (ioc.isEmpty()) {
            return;
        }
        // 依赖注入，给 ioc 容器里加了 Autowired 注解的字段赋值
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Field[] fieldArray = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fieldArray) {
                if (!field.isAnnotationPresent(Autowired.class)) {
                    continue;
                }
                Autowired autowired = field.getAnnotation(Autowired.class);
                String beanName = autowired.value().trim();
                if ("".equals(beanName)) {
                    beanName = StringUtil.lowerCaseFirst(field.getType().getSimpleName());
                }
                field.setAccessible(true); // 通过反射，获取私有属性访问权限
                try {
                    field.set(entry.getValue(), ioc.get(beanName)); // 给注解了 Autowired 的类的字段，赋值为该字段类型的实例
                } catch (Exception e) {
                    e.printStackTrace();
                    continue;
                }

            }
        }

    }

    /**
     * 实例化相关的类，并放入 IOC 容器中
     * 只有加了 Controller 和 Service 注解的类才实例化
     * 容器的 key 默认是类名首字母小写
     */
    private void doInstance() {
        // 判断有没有扫描到
        if (classNameList.isEmpty()) {
            return;
        }
        try {
            for (String item : classNameList) {
                // 拿到指定类名的class对象，进行反射
                Class<?> clazz = Class.forName(item);
                // isAnnotationPresent 判断该类是否存在该注解，然后通过反射实例化此类
                if (clazz.isAnnotationPresent(Controller.class)) {
                    String key = StringUtil.lowerCaseFirst(clazz.getSimpleName()); // 获取底层类名，转换为首字母小写
                    ioc.put(key, clazz.newInstance());
                } else if (clazz.isAnnotationPresent(Service.class)) {
                    Service service = clazz.getAnnotation(Service.class);
                    String beanName = service.value();
                    Object instance = clazz.newInstance();
                    if ("".equals(beanName.trim())) {
                        // 没有指定名字则使用类名首字母小写
                        beanName = StringUtil.lowerCaseFirst(clazz.getSimpleName());
                    }
                    ioc.put(beanName, instance);
                    // 我不用接口的方式，所以就不对接口处理
/*                    Class<?>[] interfaces = clazz.getInterfaces(); // 获取该类实现的所有接口
                    for (Class<?> i : interfaces) {
                        ioc.put(i.getName(), clazz.newInstance());
                    }*/
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 递归扫描指定包下的全部 class
     *
     * @param scanPackage
     */
    private void doScan(String scanPackage) {
        // 拿到包路径
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replace(".", "/"));
        File classDir = new File(url.getFile());
        // 递归遍历整个包
        for (File item : classDir.listFiles()) {
            if (item.isDirectory()) {
                doScan(scanPackage + "." + item.getName());
            } else {
                String className = (scanPackage + "." + item.getName()).replace(".class", "");
                classNameList.add(className);
            }
        }
    }

    /**
     * 从能够读取到配置文件中的信息，读取到Properties对象中
     *
     * @param contextConfigLocation
     */
    private void doLoadConfig(String contextConfigLocation) {
        // 拿到 Spring 配置文件路径 读取文件中所有内容
        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (null != inputStream) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    private class Handler {
        private Pattern pattern; // url 匹配
        private Object controller; // 方法对应的实例
        private Method method; // 方法
        private Map<String, Integer> paramIndexMap; // 参数对应的顺序

        private Handler(Pattern pattern, Object controller, Method method) {
            this.pattern = pattern;
            this.controller = controller;
            this.method = method;
            paramIndexMap = new HashMap<>();
            putParamIndexMapping(method);
        }

        private void putParamIndexMapping(Method method) {
            // 获取加了 annotation 注解的参数
            Annotation[][] parameterAnnotationArray = method.getParameterAnnotations(); // 结果是一个二维数组，因为参数前可以添加多个注解
            // 遍历每个参数
            for (int i = 0, len = parameterAnnotationArray.length; i < len; i++) {
                // 参数的每个注解
                for (Annotation annotation : parameterAnnotationArray[i]) {
                    // 找出注解了 RequestParam 的参数
                    if (annotation instanceof RequestParam) {
                        String paramName = ((RequestParam) annotation).value();
                        if (!"".equals(paramName)) {
                            paramIndexMap.put(paramName, i);
                        }
                    }
                }
            }
            // 特殊处理 HttpServletRequest 和 HttpServletResponse
            Class<?>[] paramTypeArray = method.getParameterTypes();
            for (int i = 0, len = paramTypeArray.length; i < len; i++) {
                Class<?> type = paramTypeArray[i];
                if (type == HttpServletRequest.class || type == HttpServletResponse.class) {
                    paramIndexMap.put(type.getName(), i);
                }
            }
        }
    }
}
