/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.jps.build;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.compiler.runner.KotlinModuleDescriptionGenerator;
import org.jetbrains.jet.compiler.runner.KotlinModuleXmlGenerator;
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.ModuleBuildTarget;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.model.java.*;
import org.jetbrains.jps.model.library.JpsLibrary;
import org.jetbrains.jps.model.library.sdk.JpsSdk;
import org.jetbrains.jps.model.library.sdk.JpsSdkType;
import org.jetbrains.jps.model.module.JpsDependencyElement;
import org.jetbrains.jps.model.module.JpsLibraryDependency;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.module.JpsSdkDependency;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.jet.compiler.runner.KotlinModuleDescriptionGenerator.DependencyProvider;
import static org.jetbrains.jet.jps.build.JpsUtils.getAllDependencies;

public class KotlinBuilderModuleScriptGenerator {

    public static final KotlinModuleDescriptionGenerator GENERATOR = KotlinModuleXmlGenerator.INSTANCE;

    public static File generateModuleDescription(CompileContext context, ModuleBuildTarget target, List<File> sourceFiles)
            throws IOException
    {
        File outputDir = target.getOutputDir();
        if (outputDir == null) {
            throw new IllegalStateException("No output directory found for " + target);

        }
        CharSequence moduleScriptText = GENERATOR.generateModuleScript(
                target.getId(),
                outputDir.getAbsolutePath(),
                getKotlinModuleDependencies(context, target),
                sourceFiles,
                target.isTests(),
                // this excludes the output directory from the class path, to be removed for true incremental compilation
                Collections.singleton(outputDir)
        );

        File scriptFile = new File(outputDir, "script." + GENERATOR.getFileExtension());

        writeScriptToFile(context, moduleScriptText, scriptFile);

        return scriptFile;
    }

    private static DependencyProvider getKotlinModuleDependencies(final CompileContext context, final ModuleBuildTarget target) {
        return new DependencyProvider() {
            @Override
            public void processClassPath(@NotNull KotlinModuleDescriptionGenerator.DependencyProcessor processor) {
                processor.processClassPathSection("Classpath", findClassPathRoots(target));
                processor.processClassPathSection("Java Source Roots", findSourceRoots(context, target));
                processor.processAnnotationRoots(findAnnotationRoots(target));
            }
        };
    }

    private static void writeScriptToFile(CompileContext context, CharSequence moduleScriptText, File scriptFile) throws IOException {
        FileUtil.writeToFile(scriptFile, moduleScriptText.toString());
        context.processMessage(new CompilerMessage(
                "Kotlin",
                BuildMessage.Kind.INFO,
                "Created script file: " + scriptFile
        ));
    }

    @NotNull
    private static Collection<File> findClassPathRoots(@NotNull ModuleBuildTarget target) {

        return getAllDependencies(target).classes().getRoots();
    }

    @NotNull
    private static Collection<File> findSourceRoots(@NotNull CompileContext context, @NotNull ModuleBuildTarget target) {
        List<JavaSourceRootDescriptor> roots = context.getProjectDescriptor().getBuildRootIndex().getTargetRoots(target, context);
        Collection<File> result = ContainerUtil.newArrayList();
        for (JavaSourceRootDescriptor root : roots) {
            File file = root.getRootFile();
            if (file.exists()) {
                result.add(file);
            }
        }
        return result;
    }

    @NotNull
    private static List<File> findAnnotationRoots(@NotNull ModuleBuildTarget target) {
        List<File> annotationRootFiles = ContainerUtil.newArrayList();

        JpsModule module = target.getModule();
        JpsSdk sdk = module.getSdk(getSdkType(module));
        if (sdk != null) {
            annotationRootFiles.addAll(sdk.getParent().getFiles(JpsAnnotationRootType.INSTANCE));
        }

        for (JpsLibrary library : getAllDependencies(target).getLibraries()) {
            annotationRootFiles.addAll(library.getFiles(JpsAnnotationRootType.INSTANCE));
        }

        // JDK is stored locally on user's machine, so its configuration, including external annotation paths
        // is not available on TeamCity. When running on TeamCity, one has to provide extra path to JDK annotations
        String extraAnnotationsPaths = System.getProperty("jps.kotlin.extra.annotation.paths");
        if (extraAnnotationsPaths != null) {
            String[] paths = extraAnnotationsPaths.split(";");
            for (String path : paths) {
                annotationRootFiles.add(new File(path));
            }
        }

        return annotationRootFiles;
    }

    @NotNull
    private static JpsSdkType getSdkType(@NotNull JpsModule module) {
        for (JpsDependencyElement dependency : module.getDependenciesList().getDependencies()) {
            if (dependency instanceof JpsSdkDependency) {
                return ((JpsSdkDependency) dependency).getSdkType();
            }
        }
        return JpsJavaSdkType.INSTANCE;
    }

    @Nullable
    private static JpsLibrary getLibrary(@NotNull JpsDependencyElement dependencyElement) {
        if (dependencyElement instanceof JpsSdkDependency) {
            JpsSdkDependency sdkDependency = (JpsSdkDependency) dependencyElement;
            return sdkDependency.resolveSdk();
        }

        if (dependencyElement instanceof JpsLibraryDependency) {
            JpsLibraryDependency libraryDependency = (JpsLibraryDependency) dependencyElement;
            return libraryDependency.getLibrary();
        }

        return null;
    }

    private KotlinBuilderModuleScriptGenerator() {}
}
