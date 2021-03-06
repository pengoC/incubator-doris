// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.plugin;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.doris.common.UserException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DynamicPluginLoader extends PluginLoader {
    private final static Logger LOG = LogManager.getLogger(DynamicPluginLoader.class);

    protected Path installPath;

    DynamicPluginLoader(String pluginPath, String source) {
        super(pluginPath, source);
    }

    DynamicPluginLoader(String pluginPath, PluginInfo info) {
        super(pluginPath, info);
        this.installPath = FileSystems.getDefault().getPath(pluginDir.toString(), pluginInfo.getName());
    }

    @Override
    public boolean isDynamicPlugin() {
        return true;
    }

    /**
     * get Plugin .zip and read plugin.properties
     */
    @Override
    public PluginInfo getPluginInfo() throws IOException, UserException {
        // already install
        if (pluginInfo != null) {
            return pluginInfo;
        }

        if (installPath == null) {
            // download plugin and extract
            PluginZip zip = new PluginZip(source);
            Path target = Files.createTempDirectory(pluginDir, ".install_");

            installPath = zip.extract(target);
        }

        pluginInfo = PluginInfo.readFromProperties(installPath, source);

        return pluginInfo;
    }

    /**
     * move plugin to Doris's PLUGIN_DIR and dynamic load the plugin class
     */
    public void install() throws UserException, IOException {
        if (hasInstalled()) {
            throw new UserException("Plugin " + pluginInfo.getName() + " is already install.");
        }

        getPluginInfo();

        Path realPath = movePlugin();

        plugin = dynamicLoadPlugin(realPath);

        pluginInstallValid();

        plugin.init(pluginInfo, pluginContext);
    }

    private boolean hasInstalled() {
        // check already install
        if (pluginInfo != null) {
            Path targetPath = FileSystems.getDefault().getPath(pluginDir.toString(), pluginInfo.getName());
            if (Files.exists(targetPath)) {
                return true;
            }
        }

        return false;
    }

    /**
     * close plugin and delete Plugin
     */
    public void uninstall() throws IOException, UserException {
        if (plugin != null) {
            pluginUninstallValid();
            plugin.close();
        }

        if (null != installPath && Files.exists(installPath)
                && Files.isSameFile(installPath.getParent(), pluginDir)) {
            FileUtils.deleteQuietly(installPath.toFile());
        }
    }

    /**
     * reload plugin if plugin has already been installed, else will re-install
     */
    public void reload() throws IOException, UserException {
        if (hasInstalled()) {
            plugin = dynamicLoadPlugin(installPath);
            pluginInstallValid();
            plugin.init(pluginInfo, pluginContext);

        } else {
            // re-install
            this.pluginInfo = null;
            this.installPath = null;

            install();
        }
    }

    Plugin dynamicLoadPlugin(Path path) throws IOException, UserException {
        Set<URL> jarList = getJarUrl(path);

        // create a child to load the plugin in this bundle
        ClassLoader parentLoader = PluginClassLoader.createLoader(getClass().getClassLoader(), Collections.EMPTY_LIST);
        ClassLoader loader = URLClassLoader.newInstance(jarList.toArray(new URL[0]), parentLoader);

        Class<? extends Plugin> pluginClass;
        try {
             pluginClass = loader.loadClass(pluginInfo.getClassName()).asSubclass(Plugin.class);
        } catch (ClassNotFoundException e) {
            throw new UserException("Could not find plugin class [" + pluginInfo.getClassName() + "]", e);
        }

        return loadPluginClass(pluginClass);
    }

    private Plugin loadPluginClass(Class<? extends Plugin> pluginClass) {
        final Constructor<?>[] constructors = pluginClass.getConstructors();
        if (constructors.length == 0) {
            throw new IllegalStateException("no public constructor for [" + pluginClass.getName() + "]");
        }

        if (constructors.length > 1) {
            throw new IllegalStateException("no unique public constructor for [" + pluginClass.getName() + "]");
        }

        final Constructor<?> constructor = constructors[0];

        try {
            if (constructor.getParameterCount() == 0) {
                return (Plugin) constructor.newInstance();
            } else {
                throw new IllegalStateException("failed to find correct constructor.");
            }
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("failed to load plugin class [" + pluginClass.getName() + "]", e);
        }
    }

    private Set<URL> getJarUrl(Path path) throws IOException {
        Set<URL> urls = new LinkedHashSet<>();
        // gather urls for jar files
        try (DirectoryStream<Path> jarStream = Files.newDirectoryStream(path, "*.jar")) {
            for (Path jar : jarStream) {
                // normalize with toRealPath to get symlinks out of our hair
                URL url = jar.toRealPath().toUri().toURL();
                if (!urls.add(url)) {
                    throw new IllegalStateException("duplicate codebase: " + url);
                }
            }
        }

        return urls;
    }

    /**
     * move plugin's temp install directory to Doris's PLUGIN_DIR
     */
    Path movePlugin() throws UserException, IOException {

        if (installPath == null || !Files.exists(installPath)) {
            throw new UserException("Install plugin " + pluginInfo.getName() + " failed, because install path isn't "
                    + "exists.");
        }

        Path targetPath = FileSystems.getDefault().getPath(pluginDir.toString(), pluginInfo.getName());
        if (Files.exists(targetPath)) {
            if (!Files.isSameFile(installPath, targetPath)) {
                throw new UserException(
                        "Install plugin " + pluginInfo.getName() + " failed. because " + installPath.toString()
                                + " exists");
            }
        } else {
            Files.move(installPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
        }

        // move success
        installPath = targetPath;

        return installPath;
    }

}
