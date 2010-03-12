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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.paoding.rose.scanner;

import static net.paoding.rose.RoseConstants.CONF_MODULE_IGNORED;
import static net.paoding.rose.RoseConstants.CONF_MODULE_PATH;
import static net.paoding.rose.RoseConstants.CONF_PARENT_MODULE_PATH;
import static net.paoding.rose.RoseConstants.CONTROLLERS_DIRECTORY_NAME;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs.FileName;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.VFS;
import org.springframework.core.io.Resource;
import org.springframework.util.Log4jConfigurer;

/**
 * 
 * @author 王志亮 [qieqie.wang@gmail.com]
 * 
 */
public class RoseModuleInfos {

    public static void main(String[] args) throws IOException {
        Log4jConfigurer.initLogging("src/test/java/log4j.properties");
        List<ModuleResource> moduleInfos = new RoseModuleInfos().findModuleResources();
        System.out.println("context resource="
                + Arrays.toString(moduleInfos.toArray(new ModuleResource[0])));
    }

    protected Log logger = LogFactory.getLog(RoseModuleInfos.class);

    private List<ModuleResource> moduleResourceList;

    private Map<FileObject, ModuleResource> moduleResourceMap;

    public synchronized List<ModuleResource> findModuleResources() throws IOException {
        if (moduleResourceList == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("do find module resources!");
            }
            //
            moduleResourceList = new LinkedList<ModuleResource>();
            moduleResourceMap = new HashMap<FileObject, ModuleResource>();
            //
            RoseScanner roseScanner = RoseScanner.getInstance();
            List<ResourceRef> resources = new ArrayList<ResourceRef>();
            resources.addAll(roseScanner.getClassesFolderResources());
            resources.addAll(roseScanner.getJarResources());
            List<FileObject> rootObjects = new ArrayList<FileObject>();
            FileSystemManager fsManager = VFS.getManager();
            for (ResourceRef resourceRef : resources) {

                if (resourceRef.hasModifier("controllers")) {
                    Resource resource = resourceRef.getResource();
                    File resourceFile = resource.getFile();
                    FileObject rootObject = null;
                    if (resourceFile.isFile()) {
                        String path = "jar:file:" + resourceFile.getAbsolutePath() + "!/";
                        rootObject = fsManager.resolveFile(path);
                    } else if (resourceFile.isDirectory()) {
                        rootObject = fsManager.resolveFile(resourceFile.getAbsolutePath());
                    }
                    if (rootObject == null) {
                        if (logger.isInfoEnabled()) {
                            logger.info("It's not a directory or file: " + resourceFile);
                        }
                        continue;
                    }
                    rootObjects.add(rootObject);
                    try {
                        int oldSize = moduleResourceList.size();
                        deepScanImpl(rootObject, rootObject);
                        int newSize = moduleResourceList.size();
                        if (logger.isInfoEnabled()) {
                            logger.info("got" + (newSize - oldSize) + " modules from " //
                                    + rootObject);
                        }
                    } catch (Exception e) {
                        logger.error("error happend when scanning " + rootObject, e);
                    }
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info("It's not a module/controllers file: "
                                + resourceRef.getResource().getFile());
                    }
                }
            }

            afterScanning();

            for (FileObject fileObject : rootObjects) {
                fsManager.closeFileSystem(fileObject.getFileSystem());
            }
            logger.info("found " + moduleResourceList.size() + " module resources");
        } else {

            if (logger.isDebugEnabled()) {
                logger.debug("found cached module resources; size=" + moduleResourceList.size());
            }
        }
        return new ArrayList<ModuleResource>(moduleResourceList);
    }

    protected void deepScanImpl(FileObject rootObject, FileObject fileObject) {
        try {
            if (CONTROLLERS_DIRECTORY_NAME.equals(fileObject.getName().getBaseName())) {
                handleWithFolder(rootObject, fileObject);
            } else {
                FileObject[] children = fileObject.getChildren();
                for (FileObject child : children) {
                    if (child.getType().hasChildren()) {
                        deepScanImpl(rootObject, child);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("error happend when deep scan " + fileObject, e);
        }
    }

    protected void handleWithFolder(FileObject rootObject, FileObject matchedRootFolder)
            throws IOException {
        this.handleWithFolder(rootObject, matchedRootFolder, matchedRootFolder);
    }

    protected void handleWithFolder(FileObject rootObject, FileObject matchedRootFolder,
            FileObject thisFolder) throws IOException {

        String relativePackagePath = matchedRootFolder.getName().getRelativeName(
                thisFolder.getName());
        if (relativePackagePath.startsWith("..")) {
            throw new Error("wrong relativePackagePath '" + relativePackagePath + "' for "
                    + thisFolder.getURL());
        }

        String mappingPath = null;
        ModuleResource parentModuleInfo = moduleResourceMap.get(thisFolder.getParent());
        // 如果rose.properties设置了controllers的module.path?
        FileObject rosePropertiesFile = thisFolder.getChild("rose.properties");// (null if there is no such child.)
        if (rosePropertiesFile != null) {
            Properties p = new Properties();
            InputStream in = rosePropertiesFile.getContent().getInputStream();
            p.load(in);
            in.close();

            // 如果controllers=ignored，则...
            String ignored = p.getProperty(CONF_MODULE_IGNORED, "false").trim();
            if ("true".equalsIgnoreCase(ignored) || "1".equalsIgnoreCase(ignored)) {
                logger.info("ignored controllers folder: " + thisFolder.getName());
                return;
            }
            mappingPath = p.getProperty(CONF_MODULE_PATH);
            if (mappingPath != null) {
                mappingPath = mappingPath.trim();
                if (mappingPath.indexOf("${" + CONF_PARENT_MODULE_PATH + "}") != -1) {
                    if (thisFolder.getParent() != null) {
                        String replacePath;
                        if (parentModuleInfo == null) {
                            replacePath = "";
                        } else {
                            replacePath = parentModuleInfo.getMappingPath();
                        }
                        mappingPath = mappingPath.replace("${" + CONF_PARENT_MODULE_PATH + "}",
                                replacePath);
                    } else {
                        mappingPath = mappingPath.replace("${" + CONF_PARENT_MODULE_PATH + "}", "");
                    }
                }
                if (mappingPath.length() != 0 && !mappingPath.startsWith("/")) {
                    if (parentModuleInfo != null) {
                        mappingPath = parentModuleInfo.getMappingPath() + "/" + mappingPath;
                    } else if (StringUtils.isNotEmpty(relativePackagePath)) {
                        mappingPath = relativePackagePath + "/" + mappingPath;
                    } else {
                        mappingPath = "/" + mappingPath;
                    }
                }
                // 空串，或，以/开头的串，不能以/结尾，不能重复/
                if (mappingPath.length() != 0) {
                    while (mappingPath.indexOf("//") != -1) {
                        mappingPath = mappingPath.replace("//", "/");
                    }
                    while (mappingPath.endsWith("/")) {
                        mappingPath = mappingPath.substring(0, mappingPath.length() - 1);
                    }
                }
            }
        }
        if (mappingPath == null) {
            if (parentModuleInfo != null) {
                mappingPath = parentModuleInfo.getMappingPath() + "/"
                        + thisFolder.getName().getBaseName();
            } else {
                mappingPath = "";
            }
        }
        ModuleResource moduleResource = new ModuleResource();
        moduleResource.setMappingPath(mappingPath);
        moduleResource.setModuleUrl(thisFolder.getURL());
        moduleResource.setRelativePackagePath(relativePackagePath);
        moduleResource.setParent(parentModuleInfo);
        moduleResourceMap.put(thisFolder, moduleResource);
        moduleResourceList.add(moduleResource);
        if (logger.isDebugEnabled()) {
            logger.debug("found module '" + mappingPath + "' in " + thisFolder.getURL());
        }

        FileObject[] children = thisFolder.getChildren();
        for (FileObject child : children) {
            if (child.getType().hasContent() && !child.getType().hasChildren()) {
                handlerModuleResource(rootObject, thisFolder, child);
            }
        }
        for (FileObject child : children) {
            if (child.getType().hasChildren()) {
                handleWithFolder(rootObject, matchedRootFolder, child);
            }
        }
    }

    protected void handlerModuleResource(FileObject rootObject, FileObject thisFolder,
            FileObject resource) throws FileSystemException {
        FileName fileName = resource.getName();
        String bn = fileName.getBaseName();
        if (bn.endsWith(".class") && bn.indexOf('$') == -1) {
            addModuleClass(rootObject, thisFolder, resource);
        } else if (bn.startsWith("applicationContext") && bn.endsWith(".xml")) {
            addModuleContext(rootObject, thisFolder, resource);
        } else if (bn.startsWith("messages") && (bn.endsWith(".xml") || bn.endsWith(".properties"))) {
            addModuleMessage(rootObject, thisFolder, resource);
        }
    }

    private void addModuleContext(FileObject rootObject, FileObject thisFolder, FileObject resource)
            throws FileSystemException {
        ModuleResource moduleInfo = moduleResourceMap.get(thisFolder);
        moduleInfo.addContextResource(resource.getURL());
        if (logger.isDebugEnabled()) {
            logger.debug("module '" + moduleInfo.getMappingPath() + "': found context file, url="
                    + resource.getURL());
        }
    }

    private void addModuleMessage(FileObject rootObject, FileObject thisFolder, FileObject resource)
            throws FileSystemException {
        ModuleResource moduleInfo = moduleResourceMap.get(thisFolder);
        moduleInfo.addMessageResource(resource.getParent().getURL() + "/messages");
        if (logger.isDebugEnabled()) {
            logger.debug("module '" + moduleInfo.getMappingPath() + "': found messages file, url="
                    + resource.getURL());
        }
    }

    private void addModuleClass(FileObject rootObject, FileObject thisFolder, FileObject resource)
            throws FileSystemException {
        String className = rootObject.getName().getRelativeName(resource.getName());
        className = StringUtils.removeEnd(className, ".class");
        className = className.replace('/', '.');
        ModuleResource moduleInfo = moduleResourceMap.get(thisFolder);
        try {
            // TODO: classloader...
            moduleInfo.addModuleClass(Class.forName(className));
            if (logger.isDebugEnabled()) {
                logger.debug("module '" + moduleInfo.getMappingPath() + "': found class, name="
                        + className);
            }
        } catch (ClassNotFoundException e) {
            logger.error("", e);
        }
    }

    // FIXME: 如果一个module只有rose.properties文件也会从moduleInfoList中remove，以后是否需要修改？
    protected void afterScanning() {
        for (ModuleResource moduleResource : moduleResourceMap.values()) {
            if (moduleResource.getContextResources().size() == 0
                    && moduleResource.getModuleClasses().size() == 0) {
                moduleResourceList.remove(moduleResource);
                if (logger.isInfoEnabled()) {
                    logger.info("remove empty module '" + moduleResource.getMappingPath() + "' "
                            + moduleResource.getModuleUrl());
                }
            }
        }
    }

}
