package com.demo.mvcframework.servlet;

import com.demo.mvcframework.annotation.*;
import com.demo.utils.StringUtil;

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
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DispatchServlet extends HttpServlet {
    private Properties contextConfig = new Properties(); // 读取的配置内容
    private List<String> classNameList = new ArrayList<>(); // 扫描到的类名列表
    private Map<String, Object> ioc = new HashMap<>(); // 传说中的 IOC 容器其实就是个 Map， ioc.put(beanName, instance)
    private List<Handler> handlerList = new ArrayList<>(); // 自己实现的 Handler 对象列表，存放处理请求的 Handler 信息

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
        // 加载配置文件，从 doLoadConfig 方法中，我们获取配置的内容
        // 其中 ServletConfig 的 getInitParameter 方法可以拿到 web.xml 中这个 servlet 的指定属性的值
        // 所以这里拿 contextConfigLocation 这个属性的值，结果是 application.properties
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        // 扫描指定包内的类
        // contextConfig 在上面的 doLoadConfig 方法中得到初始化
        doScan(contextConfig.getProperty("scanPackage"));

        // 构造 IOC 容器。初始化相关联的类，实例化并放入 IOC 容器中
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
     * 只有加了 Controller 和 Service 注解的类才需要实例化，应当以单例模式实例化
     * IOC 容器的 key 默认是类名首字母小写
     */
    private void doInstance() {
        // 判断在上一步中有没有扫描到类，没有直接退出
        if (classNameList.isEmpty()) {
            return;
        }
        try {
            for (String item : classNameList) {
                // 拿到指定类名的class对象，进行反射，只有加了 Controller 和 Service 注解的类才需要处理
                Class<?> clazz = Class.forName(item);
                // isAnnotationPresent 方法判断该类是否存在指定注解
                if (clazz.isAnnotationPresent(Controller.class)) {
                    // 获取底层类名，转换为首字母小写
                    // 注意 getName 和 getSimpleName 方法的区别
                    String key = StringUtil.lowerCaseFirst(clazz.getSimpleName()); // StringUtil 是个自己写的简单工具类而已
                    // 通过反射 newInstance 方法实例化此类，放入 IOC 容器中
                    ioc.put(key, clazz.newInstance());
                } else if (clazz.isAnnotationPresent(Service.class)) {
                    Service service = clazz.getAnnotation(Service.class);
                    Object instance = clazz.newInstance();
                    // Service 可能使用指定的名字，所以比上面多一点处理
                    String beanName = service.value();
                    if ("".equals(beanName.trim())) {
                        // 没有指定名字则使用默认的类名首字母小写
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
     * 扫描指定包下的全部 class ,为 classNameList 加入数据
     *
     * @param scanPackage
     */
    private void doScan(String scanPackage) {
        // 拿到包路径
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replace(".", "/"));
        File classDir = new File(url.getFile());
        // 如果指定扫描的不是包而是文件，直接加入列表返回，或者抛出异常也行
        if (classDir.isFile()) {
            classNameList.add(scanPackage);
            return;
        }
        // 因为包下面可能还有很多包，所以需要不断遍历
        // 方法之一，递归遍历整个包，代码被我注释掉了，但是我不喜欢递归。能用循环的我都用循环
/*        for (File item : classDir.listFiles()) {
            if (item.isDirectory()) {
                doScan(scanPackage + "." + item.getName());
            } else {
                String className = (scanPackage + "." + item.getName()).replace(".class", "");
                classNameList.add(className);
            }
        }*/

        // 使用循环代替遍历，就是优先搜索，这里是宽度优先搜索
        List<String> pathList = new ArrayList<>();
        List<File> dirList = new ArrayList<>();
        pathList.add(scanPackage);
        dirList.add(classDir);
        while (!dirList.isEmpty()) {
            File dirItem = dirList.remove(0);
            String pathItem = pathList.remove(0);
            for (File fileItem : dirItem.listFiles()) {
                if (fileItem.isDirectory()) {
                    dirList.add(fileItem);
                    pathList.add(pathItem + "." + fileItem.getName());
                    continue;
                }
                String className = (pathItem + "." + fileItem.getName()).replace(".class", "");
                classNameList.add(className);
            }
        }
    }

    /**
     * 从能够读取到 web.xml 配置文件中的信息，读取到 Properties 对象中
     *
     * @param contextConfigLocation
     */
    private void doLoadConfig(String contextConfigLocation) {
        // 从传入的配置文件名，读取文件中所有配置内容
        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(inputStream); // 赋值给 contextConfig
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
