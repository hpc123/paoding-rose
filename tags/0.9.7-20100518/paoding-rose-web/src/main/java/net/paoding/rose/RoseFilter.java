/*
 * Copyright 2007-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License i distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.paoding.rose;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.paoding.rose.scanner.ModuleResource;
import net.paoding.rose.scanner.ModuleResourceProvider;
import net.paoding.rose.scanner.ModuleResourceProviderImpl;
import net.paoding.rose.scanning.LoadScope;
import net.paoding.rose.scanning.context.RoseWebAppContext;
import net.paoding.rose.util.Snippet;
import net.paoding.rose.web.RequestPath;
import net.paoding.rose.web.annotation.ReqMethod;
import net.paoding.rose.web.impl.mapping.Mapping;
import net.paoding.rose.web.impl.mapping.MappingImpl;
import net.paoding.rose.web.impl.mapping.MappingNode;
import net.paoding.rose.web.impl.mapping.MatchMode;
import net.paoding.rose.web.impl.mapping.TreeBuilder;
import net.paoding.rose.web.impl.mapping.WebResource;
import net.paoding.rose.web.impl.mapping.WebResourceImpl;
import net.paoding.rose.web.impl.mapping.ignored.IgnoredPath;
import net.paoding.rose.web.impl.mapping.ignored.IgnoredPathEnds;
import net.paoding.rose.web.impl.mapping.ignored.IgnoredPathEquals;
import net.paoding.rose.web.impl.mapping.ignored.IgnoredPathRegexMatch;
import net.paoding.rose.web.impl.mapping.ignored.IgnoredPathStarts;
import net.paoding.rose.web.impl.module.Module;
import net.paoding.rose.web.impl.module.ModulesBuilder;
import net.paoding.rose.web.impl.module.ModulesBuilderImpl;
import net.paoding.rose.web.impl.thread.Rose;
import net.paoding.rose.web.impl.thread.WebEngine;
import net.paoding.rose.web.instruction.InstructionExecutor;
import net.paoding.rose.web.instruction.InstructionExecutorImpl;

import org.apache.commons.lang.StringUtils;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.SpringVersion;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.filter.GenericFilterBean;
import org.springframework.web.util.NestedServletException;

/**
 * Rose 是一个基于Servlet规范、Spring“规范”的WEB开发框架。
 * <p>
 * Rose 框架通过在web.xml配置过滤器拦截并处理匹配的web请求，如果一个请求应该由在Rose框架下的类来处理，
 * 该请求将在Rose调用中完成对客户端响应.
 * 如果一个请求在Rose中没有找到合适的类来为他服务，Rose将把该请求移交给web容器的其他组件来处理。
 * <p>
 * 
 * Rose使用过滤器而非Servlet来接收web请求，这有它的合理性以及好处。
 * <p>
 * Servlet规范以“边走边看”的方式来处理请求，
 * 当服务器接收到一个web请求时，并没有要求在web.xml必须有相应的Servlet组件时才能处理，web请求被一系列Filter过滤时，
 * Filter可以拿到相应的Request和Response对象
 * ，当Filter认为自己已经能够完成整个处理，它可以不调用整个处理链的下个组件处理.
 * <p>
 * 使用过滤器的好处是，Rose可以很好地和其他web框架兼容。这在改造遗留系统、对各种uri的支持具有天然优越性。正是使用过滤器，
 * Rose不在要求请求地址具有特殊的后缀。
 * <p>
 * 为了更好地理解，可以把Rose过滤器看成能将某些请求其它Filter或Servlet传递的Servlet。这个刚好是普通Servlet无法做到的
 * ： 如果一个请求以后缀名配置给他处理时候
 * ，一旦该Servlet处理不了，Servlet规范没有提供机制使得可以由配置在web.xml的其他正常组件处理
 * (除404，500等错误处理组件之外)。
 * <p>
 * 
 * 一个web.xml中可能具有不只一个的Filter，Filter的先后顺序对系统具有重要影响，特别的，Rose自己的过滤器的配置顺序更是需要讲究
 * 。 如果一个请求在被Rose处理前应该被某些过滤器过滤，请把这些过滤器的mapping配置在Rose过滤器之前。
 * <p>
 * 
 * RoseFilter的配置，建议按以下配置即可：
 * 
 * <pre>
 * 	&lt;filter&gt;
 * 		&lt;filter-name&gt;roseFilter&lt;/filter-name&gt;
 * 		&lt;filter-class&gt;net.paoding.rose.RoseFilter&lt;/filter-class&gt;
 * 	&lt;/filter&gt;
 * 	&lt;filter-mapping&gt;
 * 		&lt;filter-name&gt;roseFilter&lt;/filter-name&gt;
 * 		&lt;url-pattern&gt;/*&lt;/url-pattern&gt;
 * 		&lt;dispatcher&gt;REQUEST&lt;/dispatcher&gt;
 * 		&lt;dispatcher&gt;FORWARD&lt;/dispatcher&gt;
 * 		&lt;dispatcher&gt;INCLUDE&lt;/dispatcher&gt;
 * 	&lt;/filter-mapping&gt;
 * </pre>
 * 
 * 1) 大多数请况下，<strong>filter-mapping</strong> 应配置在所有Filter Mapping的最后。<br>
 * 2) 不能将 <strong>FORWARD、INCLUDE</strong> 的 dispatcher 去掉，否则forward、
 * include的请求Rose框架将拦截不到<br>
 * <p>
 * 
 * Rose框架内部采用<strong>"匹配->执行"</strong>两阶段逻辑。Rose内部结构具有一个匹配树，
 * 这个数据结构可以快速判断一个请求是否应该由Rose处理并进行， 没有找到匹配的请求交给过滤器的下一个组件处理。匹配成功的请求将进入”执行“阶段。
 * 执行阶段需要经过6个步骤处理：<strong>“参数解析 -〉 验证器 -〉 拦截器 -〉 控制器 -〉 视图渲染
 * -〉渲染后"</strong>的处理链。
 * <p>
 * 
 * <strong>匹配树</strong>: <br>
 * 匹配树是一个多叉树，最根节点代表整个Rose应用，第二级代表Rose应用下的所有模块，第三级是每个模块下的处理类
 * (控制器)，第四级代表每个控制器下的操作的方法
 * 。这个匹配树的每个节点都定义了自己的匹配地址、匹配目标以及”执行逻辑“。值得注意的是，对于每个匹配节点而言它的下级节点是有序的
 * ，这个顺序可以保证请求地址被正确地匹配给所期望的控制器方法处理。
 * <p>
 * 
 * <strong>匹配过程</strong>: <br>
 * Rose以请求的地址作为处理输入(不包含Query串，即问号后的字符串)。如果这个匹配树存在某个树的路径和请求匹配成功,
 * 表示这个请求应由Rose处理。在算法上，采用的是基于左儿子有兄弟的可回溯的匹配过程。
 * <P>
 * 
 * <strong>参数解析</strong>: <br>
 * 在调用验证器、拦截器
 * 控制器之前，Rose完成2个解析：解析匹配树上动态的参数出实际值，解析控制器方法中参数实际的值。参数可能会解析失败(例如转化异常等等
 * )，此时该参数以默认值进行代替，同时Rose解析失败和异常记录起来放到专门的类中，继续下一个过程而不打断执行。
 * <P>
 * 
 * <strong>拦截器</strong>: <br>
 * Rose使用自定义的拦截器接口而非一般的拦截器接口这是有理由的。使用Rose自定义的拦截器接口可以更容易地操作、控制Rose拦截。
 * 所谓拦截即是对已经匹配的控制器调用进行拦截，在其调用之前、之后以及页面渲染之后执行某些逻辑。设计良好的拦截器可以被多个控制器使用。
 * <P>
 * 
 * <strong>控制器</strong>: <br>
 * 
 * @author 王志亮 [qieqie.wang@gmail.com]
 */
public class RoseFilter extends GenericFilterBean {

    private static final String ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE = WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE;

    /** 使用的applicationContext地址 */
    private String contextConfigLocation;

    private InstructionExecutor instructionExecutor = new InstructionExecutorImpl();

    private List<Module> modules;

    private MappingNode mappingTree;

    private Class<? extends ModuleResourceProvider> moduleResourceProviderClass = ModuleResourceProviderImpl.class;

    private Class<? extends ModulesBuilder> modulesBuilderClass = ModulesBuilderImpl.class;

    private LoadScope load = new LoadScope("", "controllers");

    private IgnoredPath[] ignoredPaths = new IgnoredPath[] {
            new IgnoredPathStarts(RoseConstants.VIEWS_PATH_WITH_END_SEP),
            new IgnoredPathEquals("/favicon.ico") };

    /**
     * 改变默认行为，告知Rose要读取的applicationContext地址
     */
    public void setContextConfigLocation(String contextConfigLocation) {
        if (StringUtils.isBlank(contextConfigLocation)) {
            throw new IllegalArgumentException("contextConfigLocation");
        }
        this.contextConfigLocation = contextConfigLocation;
    }

    public void setInstructionExecutor(InstructionExecutor instructionExecutor) {
        this.instructionExecutor = instructionExecutor;
    }

    public void setModuleResourceProviderClass(
            Class<? extends ModuleResourceProvider> moduleResourceProviderClass) {
        this.moduleResourceProviderClass = moduleResourceProviderClass;
    }

    public void setModulesBuilderClass(Class<? extends ModulesBuilder> modulesBuilderClass) {
        this.modulesBuilderClass = modulesBuilderClass;
    }

    /**
     * <pre>
     * like: &quot;controllers: com.renren.xoa, com.renren.yourapp; applicationContext: com.renren.another&quot; , etc
     * </pre>
     * 
     * @param load
     */
    public void setLoad(String load) {
        this.load = new LoadScope(load, "controllers");
    }

    /**
     * @see #quicklyPass(RequestPath)
     * @param ignoredPathStrings
     */
    public void setIgnoredPaths(String[] ignoredPathStrings) {
        List<IgnoredPath> list = new ArrayList<IgnoredPath>(ignoredPathStrings.length + 2);
        for (String ignoredPath : ignoredPathStrings) {
            ignoredPath = ignoredPath.trim();
            if (StringUtils.isEmpty(ignoredPath)) {
                continue;
            }
            if (ignoredPath.equals("*")) {
                list.add(new IgnoredPathEquals(""));
                list.add(new IgnoredPathStarts("/"));
                break;
            }
            if (ignoredPath.startsWith("regex:")) {
                list.add(new IgnoredPathRegexMatch(ignoredPath.substring("regex:".length())));
            } else {
                if (ignoredPath.length() > 0 && !ignoredPath.startsWith("/")
                        && !ignoredPath.startsWith("*")) {
                    ignoredPath = "/" + ignoredPath;
                }
                if (ignoredPath.endsWith("*")) {
                    list.add(new IgnoredPathStarts(ignoredPath.substring(0,
                            ignoredPath.length() - 1)));
                } else if (ignoredPath.startsWith("*")) {
                    list.add(new IgnoredPathEnds(ignoredPath.substring(1)));
                } else {
                    list.add(new IgnoredPathEquals(ignoredPath));
                }
            }
        }
        IgnoredPath[] ignoredPaths = Arrays.copyOf(this.ignoredPaths, this.ignoredPaths.length
                + list.size());
        for (int i = this.ignoredPaths.length; i < ignoredPaths.length; i++) {
            ignoredPaths[i] = list.get(i - this.ignoredPaths.length);
        }
        this.ignoredPaths = ignoredPaths;
    }

    /**
     * 实现 {@link GenericFilterBean#initFilterBean()}，对 Rose 进行初始化
     */
    @Override
    protected final void initFilterBean() throws ServletException {
        try {
            if (logger.isInfoEnabled()) {
                logger.info("[init] call 'init/rootContext'");
            }

            if (logger.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder();
                @SuppressWarnings("unchecked")
                Enumeration<String> iter = getFilterConfig().getInitParameterNames();
                while (iter.hasMoreElements()) {
                    String name = (String) iter.nextElement();
                    sb.append(name).append("='").append(getFilterConfig().getInitParameter(name))
                            .append("'\n");
                }
                logger.debug("[init] parameters: " + sb);
            }

            WebApplicationContext rootContext = prepareRootApplicationContext();

            if (logger.isInfoEnabled()) {
                logger.info("[init] exits from 'init/rootContext'");
                logger.info("[init] call 'init/module'");
            }

            // 识别 Rose 程序模块
            this.modules = prepareModules(rootContext);

            if (logger.isInfoEnabled()) {
                logger.info("[init] exits from 'init/module'");
                logger.info("[init] call 'init/mappingTree'");
            }

            // 创建匹配树以及各个结点的上的执行逻辑(Engine)
            this.mappingTree = prepareMappingTree(modules);

            if (logger.isInfoEnabled()) {
                logger.info("[init] exits from 'init/mappingTree'");
                logger.info("[init] exits from 'init'");
            }

            // 打印启动信息
            printRoseInfos();

            //
        } catch (final Exception e) {
            StringBuilder sb = new StringBuilder(1024);
            sb.append("[Rose-").append(RoseVersion.getVersion());
            sb.append("@Spring-").append(SpringVersion.getVersion()).append("]:");
            sb.append(e.getMessage());
            logger.error(sb.toString(), e);
            throw new NestedServletException(sb.toString(), e);
        }
    }

    /**
     * 接收所有进入 RoseFilter 的请求进行匹配，如果匹配到有相应的处理类处理它则由这个类来处理他、渲染并响应给客户端。
     * 如果没有找到匹配的处理器，Rose将把请求转交给整个过滤链的下一个组件，让web容器的其他组件来处理它。
     */
    @Override
    public void doFilter(ServletRequest request, final ServletResponse response,
            final FilterChain filterChain) throws IOException, ServletException {
        // cast
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 打开DEBUG级别信息能看到所有进入RoseFilter的请求
        if (logger.isDebugEnabled()) {
            logger.debug(httpRequest.getMethod() + " " + httpRequest.getRequestURL());
        }

        // 创建RequestPath对象，用于记录对地址解析的结果
        final RequestPath requestPath = new RequestPath(httpRequest);

        //  简单、快速判断本次请求，如果不应由Rose执行，返回true
        if (quicklyPass(requestPath)) {
            forwardToWebContainer(filterChain, httpRequest, httpResponse, requestPath.getUri());
            return;
        }

        // matched为true代表本次请求被Rose匹配，不需要转发给容器的其他 flter 或 servlet
        boolean matched = false;
        try {
            // rose 对象代表Rose框架对一次请求的执行：一朵玫瑰出墙来
            final Rose rose = new Rose(modules, mappingTree, httpRequest, httpResponse, requestPath);

            // 对请求进行匹配、处理、渲染以及渲染后的操作，如果找不到映配则返回false
            matched = rose.start();

        } catch (Throwable exception) {
            throwServletException(requestPath, exception);
        }

        // 非Rose的请求转发给WEB容器的其他组件处理，而且不放到上面的try-catch块中
        if (!matched) {
            forwardToWebContainer(filterChain, httpRequest, httpResponse, requestPath.getUri());
        }
    }

    /**
     * 创建最根级别的 ApplicationContext 对象，比如WEB-INF、WEB-INF/classes、
     * jar中的spring配置文件所组成代表的、整合为一个 ApplicationContext 对象
     * 
     * @return
     * @throws IOException
     */
    private WebApplicationContext prepareRootApplicationContext() throws IOException {
        if (logger.isInfoEnabled()) {
            logger.info("[init/rootContext] starting ...");
        }
        String contextConfigLocation = this.contextConfigLocation;
        // 确认所使用的applicationContext配置
        if (StringUtils.isBlank(contextConfigLocation)) {
            String webxmlContextConfigLocation = getServletContext().getInitParameter(
                    "contextConfigLocation");
            if (StringUtils.isBlank(webxmlContextConfigLocation)) {
                contextConfigLocation = RoseWebAppContext.DEFAULT_CONFIG_LOCATION;
            } else {
                contextConfigLocation = webxmlContextConfigLocation;
            }
        }

        // 如果web.xml配置使用了spring装载root应用context或多个roseFilter ...... 不可以
        if (getServletContext().getAttribute(ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE) != null) {
            throw new IllegalStateException(
                    "Cannot initialize context because there is already a root application context present - "
                            + "check whether you have multiple ContextLoader* or RoseFilter definitions in your web.xml!");
        }

        RoseWebAppContext rootContext = new RoseWebAppContext(getServletContext(), load, false);
        rootContext.setConfigLocation(contextConfigLocation);
        rootContext.setId("rose.root");
        rootContext.refresh();

        if (logger.isInfoEnabled()) {
            logger.info("[init/rootContext] exits");
        }

        /* enable: WebApplicationContextUtils.getWebApplicationContext() */
        getServletContext().setAttribute(ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, rootContext);

        if (logger.isInfoEnabled()) {
            logger.info("[init/rootContext] Published rose.root WebApplicationContext ["
                    + rootContext + "] as ServletContext attribute with name ["
                    + ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE + "]");
        }

        return rootContext;
    }

    private List<Module> prepareModules(WebApplicationContext rootContext) throws Exception {
        // 自动扫描识别web层资源，纳入Rose管理
        if (logger.isInfoEnabled()) {
            logger.info("[init/mudule] starting ...");
        }

        ModuleResourceProvider provider = moduleResourceProviderClass.newInstance();

        if (logger.isInfoEnabled()) {
            logger.info("[init/module] using provider: " + provider);
            logger.info("[init/module] call 'moduleResource': to find all module resources.");
            logger.info("[init/module] load " + load);
        }
        List<ModuleResource> moduleResources = provider.findModuleResources(load);

        if (logger.isInfoEnabled()) {
            logger.info("[init/mudule] exits 'moduleResource'");
        }

        ModulesBuilder modulesBuilder = modulesBuilderClass.newInstance();

        if (logger.isInfoEnabled()) {
            logger.info("[init/module] using modulesBuilder: " + modulesBuilder);
            logger.info("[init/module] call 'moduleBuild': to build modules.");
        }

        List<Module> modules = modulesBuilder.build(moduleResources, rootContext);

        if (logger.isInfoEnabled()) {
            logger.info("[init/module] exits from 'moduleBuild'");
            logger.info("[init/mudule] found " + modules.size() + " modules.");
        }

        return modules;
    }

    private MappingNode prepareMappingTree(List<Module> modules) {
        WebEngine rootEngine = new WebEngine(instructionExecutor);
        WebResource rootResource = new WebResourceImpl("");
        rootResource.addEngine(ReqMethod.ALL, rootEngine);
        Mapping rootMapping = new MappingImpl("", MatchMode.STARTS_WITH);
        MappingNode mappingTree = new MappingNode(rootMapping);
        mappingTree.addResource(rootResource);
        new TreeBuilder().create(mappingTree, modules);
        return mappingTree;
    }

    /**
     * 简单、快速判断本次请求，如果不应由Rose执行，返回true
     * 
     * @param requestPath
     * @return
     */
    private boolean quicklyPass(final RequestPath requestPath) {
        for (IgnoredPath p : ignoredPaths) {
            if (p.hit(requestPath)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        WebApplicationContext rootContext = WebApplicationContextUtils
                .getWebApplicationContext(getServletContext());
        if (rootContext != null) {
            try {
                if (rootContext instanceof AbstractApplicationContext) {
                    ((AbstractApplicationContext) rootContext).close(); // rose.root
                }
            } catch (Throwable e) {
                logger.error("", e);
                getServletContext().log("", e);
            }
        }
        for (MappingNode cur : mappingTree) {
            for (WebResource resource : cur.getResources()) {
                try {
                    resource.destroy();
                } catch (Exception e) {
                    logger.error("", e);
                    getServletContext().log("", e);
                }
            }
        }
        super.destroy();
    }

    private void forwardToWebContainer(//
            FilterChain filterChain, //
            HttpServletRequest httpRequest,//
            HttpServletResponse httpResponse,//
            String uri)//
            throws IOException, ServletException {
        if (logger.isDebugEnabled()) {
            logger.debug("not rose uri: " + uri);
        }
        // 调用其它Filter
        filterChain.doFilter(httpRequest, httpResponse);
    }

    private void throwServletException(RequestPath requestPath, Throwable exception)
            throws ServletException {
        String msg = requestPath.getMethod() + " " + requestPath.getUri();
        ServletException servletException;
        if (exception instanceof ServletException) {
            servletException = (ServletException) exception;
        } else {
            servletException = new NestedServletException(msg, exception);
        }
        logger.error(msg, exception);
        getServletContext().log(msg, exception);
        throw servletException;
    }

    private void printRoseInfos() {
        if (logger.isDebugEnabled()) {
            logger.debug(Snippet.dumpModules(modules));
        }
        String msg = String.format("[init] rose initialized, %s modules loaded! (version=%s)",
                modules.size(), RoseVersion.getVersion());
        logger.info(msg);
        getServletContext().log(msg);
    }
}