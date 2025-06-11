/*
 * Mohist - MohistMC
 * Copyright (C) 2018-2024.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mohistmc.launcher.youer.util;

import com.mohistmc.launcher.youer.config.YouerConfigUtil;
import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessControlContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.SneakyThrows;

/**
 * @author Shawiiz_z
 * @version 0.1
 * @date 04/05/2022 22:57
 */
public class YouerModuleManager {

    public static final sun.misc.Unsafe unsafe;
    private static final MethodHandles.Lookup IMPL_LOOKUP;

    static {
        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            MethodHandles.lookup().ensureInitialized(MethodHandles.Lookup.class);
            Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            Object base = unsafe.staticFieldBase(field);
            long offset = unsafe.staticFieldOffset(field);
            IMPL_LOOKUP = (MethodHandles.Lookup) unsafe.getObject(base, offset);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public YouerModuleManager(List<String> args) {
        applyLaunchArgs(args);
        YouerConfigUtil.yml.set("youer.installation-finished", false);
        YouerConfigUtil.save();
    }

    private static void addExports(List<String> exports) throws Throwable {
        MethodHandle implAddExportsMH = IMPL_LOOKUP.findVirtual(Module.class, "implAddExports", MethodType.methodType(void.class, String.class, Module.class));
        MethodHandle implAddExportsToAllUnnamedMH = IMPL_LOOKUP.findVirtual(Module.class, "implAddExportsToAllUnnamed", MethodType.methodType(void.class, String.class));

        addExtra(exports, implAddExportsMH, implAddExportsToAllUnnamedMH);
    }

    private static void addOpens(List<String> opens) throws Throwable {
        MethodHandle implAddOpensMH = IMPL_LOOKUP.findVirtual(Module.class, "implAddOpens", MethodType.methodType(void.class, String.class, Module.class));
        MethodHandle implAddOpensToAllUnnamedMH = IMPL_LOOKUP.findVirtual(Module.class, "implAddOpensToAllUnnamed", MethodType.methodType(void.class, String.class));

        addExtra(opens, implAddOpensMH, implAddOpensToAllUnnamedMH);
    }

    private static ParserData parseModuleExtra(String extra) {
        String[] all = extra.split("=", 2);
        if (all.length < 2) {
            return null;
        }

        String[] source = all[0].split("/", 2);
        if (source.length < 2) {
            return null;
        }
        return new ParserData(source[0], source[1], all[1]);
    }

    private static void addExtra(List<String> extras, MethodHandle implAddExtraMH, MethodHandle implAddExtraToAllUnnamedMH) {
        extras.forEach(extra -> {
            ParserData data = parseModuleExtra(extra);
            if (data != null) {
                ModuleLayer.boot().findModule(data.module).ifPresent(m -> {
                    try {
                        if ("ALL-UNNAMED".equals(data.target)) {
                            implAddExtraToAllUnnamedMH.invokeWithArguments(m, data.packages);
                        } else {
                            ModuleLayer.boot().findModule(data.target).ifPresent(tm -> {
                                try {
                                    implAddExtraMH.invokeWithArguments(m, data.packages, tm);
                                } catch (Throwable t) {
                                    throw new RuntimeException(t);
                                }
                            });
                        }
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
            }
        });
    }

    @SneakyThrows
    public static void applyLaunchArgs(List<String> args) {
        List<String> opens = new ArrayList<>();
        opens.add("java.base/java.util=ALL-UNNAMED");
        opens.add("java.base/java.lang=ALL-UNNAMED");
        List<String> exports = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("-")) {
                if (arg.startsWith("-p ")) {
                    loadModules(arg.substring(2).trim());
                } else if (arg.startsWith("--add-opens")) {
                    opens.add(arg.substring("--add-opens ".length()).trim());
                } else if (arg.startsWith("--add-exports")) {
                    exports.add(arg.substring("--add-exports ".length()).trim());
                } else if (arg.startsWith("-D")) {
                    var split = arg.substring(2).split("=", 2);
                    System.setProperty(split[0], split[1]);
                }
            }
        }
        addOpens(opens);
        addExports(exports);
    }

    public static void loadModules(String modulePath) throws Throwable {
        // Find all extra modules
        ModuleFinder finder = ModuleFinder.of(Arrays.stream(modulePath.split(File.pathSeparator)).map(Paths::get).peek(JarLoader::loadJar).toArray(Path[]::new));
        MethodHandle loadModuleMH = IMPL_LOOKUP.findVirtual(Class.forName("jdk.internal.loader.BuiltinClassLoader"), "loadModule", MethodType.methodType(void.class, ModuleReference.class));

        // Get existing module names from boot layer
        Set<String> existingModuleNames = ModuleLayer.boot().configuration().modules()
                .stream()
                .map(ResolvedModule::name)
                .collect(Collectors.toSet());

        // Filter out modules that already exist
        Set<ModuleReference> newModules = finder.findAll()
                .stream()
                .filter(mref -> {
                    String moduleName = mref.descriptor().name();
                    if (existingModuleNames.contains(moduleName)) {
                        System.out.println("Skipping duplicate module: " + moduleName);
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toSet());

        // If no new modules to load, return early
        if (newModules.isEmpty()) {
            System.out.println("No new modules to load, all modules already exist");
            return;
        }

        // Create a new ModuleFinder with only the filtered modules
        ModuleFinder filteredFinder = new ModuleFinder() {
            @Override
            public java.util.Optional<ModuleReference> find(String name) {
                return newModules.stream()
                        .filter(mref -> mref.descriptor().name().equals(name))
                        .findFirst();
            }

            @Override
            public Set<ModuleReference> findAll() {
                return newModules;
            }
        };

        // Load modules using filtered finder
        newModules.forEach(mref -> {
            try {
                loadModuleMH.invokeWithArguments(Thread.currentThread().getContextClassLoader(), mref);
            } catch (Throwable throwable) {
                System.err.println("Failed to load module: " + mref.descriptor().name());
                throwable.printStackTrace();
                // Continue loading other modules instead of throwing
            }
        });

        // Only proceed with configuration if we have modules to configure
        try {
            // Resolve modules to a new config
            Configuration config = Configuration.resolveAndBind(filteredFinder, List.of(ModuleLayer.boot().configuration()), filteredFinder,
                    newModules.stream().map(ModuleReference::descriptor).map(ModuleDescriptor::name).collect(Collectors.toList()));

            // Copy the new config graph to boot module layer config
            MethodHandle graphGetter = IMPL_LOOKUP.findGetter(Configuration.class, "graph", Map.class);
            HashMap<ResolvedModule, Set<ResolvedModule>> graphMap = new HashMap<>((Map<ResolvedModule, Set<ResolvedModule>>) graphGetter.invokeWithArguments(config));
            MethodHandle cfSetter = IMPL_LOOKUP.findSetter(ResolvedModule.class, "cf", Configuration.class);

            // Reset all extra resolved modules config to boot module layer config
            graphMap.forEach((k, v) -> {
                try {
                    cfSetter.invokeWithArguments(k, ModuleLayer.boot().configuration());
                    v.forEach(m -> {
                        try {
                            cfSetter.invokeWithArguments(m, ModuleLayer.boot().configuration());
                        } catch (Throwable throwable) {
                            System.err.println("Failed to set configuration for module: " + m.name());
                            throwable.printStackTrace();
                        }
                    });
                } catch (Throwable throwable) {
                    System.err.println("Failed to set configuration for module: " + k.name());
                    throwable.printStackTrace();
                }
            });

            graphMap.putAll((Map<ResolvedModule, Set<ResolvedModule>>) graphGetter.invokeWithArguments(ModuleLayer.boot().configuration()));
            IMPL_LOOKUP.findSetter(Configuration.class, "graph", Map.class).invokeWithArguments(ModuleLayer.boot().configuration(), new HashMap<>(graphMap));

            // Reset boot module layer resolved modules as new config-resolved modules to prepare to define modules
            Set<ResolvedModule> oldBootModules = ModuleLayer.boot().configuration().modules();
            MethodHandle modulesSetter = IMPL_LOOKUP.findSetter(Configuration.class, "modules", Set.class);
            HashSet<ResolvedModule> modulesSet = new HashSet<>(config.modules());
            modulesSetter.invokeWithArguments(ModuleLayer.boot().configuration(), new HashSet<>(modulesSet));

            // Prepare to add all the new config "nameToModule" to boot module layer config
            MethodHandle nameToModuleGetter = IMPL_LOOKUP.findGetter(Configuration.class, "nameToModule", Map.class);
            HashMap<String, ResolvedModule> nameToModuleMap = new HashMap<>((Map<String, ResolvedModule>) nameToModuleGetter.invokeWithArguments(ModuleLayer.boot().configuration()));
            nameToModuleMap.putAll((Map<String, ResolvedModule>) nameToModuleGetter.invokeWithArguments(config));
            IMPL_LOOKUP.findSetter(Configuration.class, "nameToModule", Map.class).invokeWithArguments(ModuleLayer.boot().configuration(), new HashMap<>(nameToModuleMap));

            // Define all extra modules and add all the new config "nameToModule" to boot module layer config
            ((Map<String, Module>) IMPL_LOOKUP.findGetter(ModuleLayer.class, "nameToModule", Map.class).invokeWithArguments(ModuleLayer.boot())).putAll((Map<String, Module>) IMPL_LOOKUP.findStatic(Module.class, "defineModules", MethodType.methodType(Map.class, Configuration.class, Function.class, ModuleLayer.class)).invokeWithArguments(ModuleLayer.boot().configuration(), (Function<String, ClassLoader>) name -> Thread.currentThread().getContextClassLoader(), ModuleLayer.boot()));

            // Add all the resolved modules
            modulesSet.addAll(oldBootModules);
            modulesSetter.invokeWithArguments(ModuleLayer.boot().configuration(), new HashSet<>(modulesSet));

            // Reset cache of boot module layer
            IMPL_LOOKUP.findSetter(ModuleLayer.class, "modules", Set.class).invokeWithArguments(ModuleLayer.boot(), null);
            IMPL_LOOKUP.findSetter(ModuleLayer.class, "servicesCatalog", Class.forName("jdk.internal.module.ServicesCatalog")).invokeWithArguments(ModuleLayer.boot(), null);

            // Add reads from extra modules to jdk modules
            MethodHandle implAddReadsMH = IMPL_LOOKUP.findVirtual(Module.class, "implAddReads", MethodType.methodType(void.class, Module.class));
            config.modules().forEach(rm -> ModuleLayer.boot().findModule(rm.name()).ifPresent(m -> oldBootModules.forEach(brm -> ModuleLayer.boot().findModule(brm.name()).ifPresent(bm -> {
                try {
                    implAddReadsMH.invokeWithArguments(m, bm);
                } catch (Throwable throwable) {
                    System.err.println("Failed to add reads from " + m.getName() + " to " + bm.getName());
                    throwable.printStackTrace();
                }
            }))));

            System.out.println("Successfully loaded " + newModules.size() + " new modules");

        } catch (Exception e) {
            System.err.println("Failed to configure modules, but modules were loaded into classloader");
            e.printStackTrace();
            // Don't rethrow - allow the application to continue
        }
    }

    private record ParserData(String module, String packages, String target) {
    }
}

