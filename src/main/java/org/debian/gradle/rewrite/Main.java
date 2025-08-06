package org.debian.gradle.rewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.gradle.GradleParser;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.tree.ParseError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.function.Consumer;


public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws IOException {

        if (args.length < 2) {
            throw new IllegalArgumentException("<refactoring>.yaml baseDir");
        }
        InMemoryExecutionContext context = new InMemoryExecutionContext(new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) {

                logger.error(throwable.getMessage(), throwable);
            }
        });

        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(new FileReader(args[0]));
        var baseDir = Path.of(args[1]);
        var sources = parseGradle(baseDir, context);
        boolean kotlinDsl = sources.stream()
                .filter(x -> x.getSourcePath().toString().equals("build.gradle"))
                .findAny()
                .isEmpty();

        for (var source : sources) {
            if (source instanceof ParseError) {
                System.out.println("Failed to parse: " + source.getSourcePath());
            }
        }
        for (var recipe : config.keySet()) {
            Map<String, Object> recipeData = (Map<String, Object> ) config.get(recipe);
            Recipe recipeImpl = createRecipe(kotlinDsl, recipeData);
            var files = (List<String>)recipeData.get("files");
            if (files == null) {
                files = List.of();
            }
            final HashSet<String> fileSet = new HashSet<>(files);
            applyRecipe( baseDir,
                    recipeImpl,
                    sources.stream().filter( x -> fileSet.isEmpty() || fileSet.contains(x.getSourcePath().toString())).toList(),
                    context);
            removeUnwantedFiles(baseDir, (List<String>)recipeData.get("remove-files"));
        }
        System.out.println("Processed " + sources.size());
    }

    private static void removeUnwantedFiles(Path baseDir, List<String> files) {
        for (String s : files) {
            try {
                Files.deleteIfExists(baseDir.resolve(s));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static Recipe createRecipe(boolean kotlinDsl, Map<String, Object> recipeData) {
        String recipe = String.valueOf(recipeData.get("recipe"));
        switch (recipe) {
            case "remove" : {
                var removeMethods = (List<String>)recipeData.get("remove-methods");
                if (removeMethods == null) {
                    removeMethods = List.of();
                }
                var removePlugins = (List<String>)recipeData.get("remove-plugins");
                if (removePlugins == null) {
                    removePlugins = List.of();
                }
                var removeClasspath = (List<String>)recipeData.get("remove-classpath");
                if (removeClasspath == null) {
                    removeClasspath = List.of();
                }
                var methodWithArg = (List<String>)recipeData.get("method-with-arg");
                if (methodWithArg == null) {
                    methodWithArg = List.of();
                }
                var removeImport = (List<String>)recipeData.get("remove-import");
                if (removeImport == null) {
                    removeImport = List.of();
                }

                var methodWithTypeParameter = (List<String>)recipeData.get("method-with-type-parameter");
                if (methodWithTypeParameter == null) {
                    methodWithTypeParameter = List.of();
                }

                return new RemoveExtensionRecipe(kotlinDsl,
                        removePlugins,
                        removeMethods,
                        removeClasspath,
                        methodWithArg,
                        removeImport,
                        methodWithTypeParameter);
            }
        }
        throw new IllegalArgumentException("Unknown recipe "+ recipe);
    }

    public static boolean applyRecipe(Path baseDir, Recipe r, List<SourceFile> sourceFiles, ExecutionContext context)
            throws IOException {
        RecipeRun run = r.run(new InMemoryLargeSourceSet(sourceFiles), context);
        List<Result> results = run.getChangeset().getAllResults();
        for (Result result : results) {
            SourceFile after = result.getAfter();
            Files.writeString(baseDir.resolve(after.getSourcePath()), result.getAfter().printAll());
        }
        return !results.isEmpty();
    }

    private static List<SourceFile> parseGradle(Path baseDir, InMemoryExecutionContext context) throws IOException {
        Parser.Builder builder = GradleParser.builder()
                .groovyParser(GroovyParser.builder().logCompilationWarningsAndErrors(true))
                .kotlinParser(KotlinParser.builder().
                        logCompilationWarningsAndErrors(true)
                        .isKotlinScript(true)
                        .languageLevel(KotlinParser.KotlinLanguageLevel.KOTLIN_1_9)
                );

        Parser p = builder.build();
        final HashSet<String> gradleNames = new HashSet<>(Arrays.asList("build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts", "init.gradle", "init.gradle.kts"));
        ArrayList<Path> files = new ArrayList<>();
        Stack<Path> toDo = new Stack<>();
        toDo.push(baseDir);
        while (!toDo.isEmpty()) {
            Path top = toDo.pop();
            Files.list(top).forEach(path -> {
                if (Files.isRegularFile(path) && (path.toString().endsWith(".gradle")
                        || path.toString().endsWith(".gradle.kts")
                        || gradleNames.contains(path.toFile().getName()))) {
                    files.add(path);
                } else if (Files.isDirectory(path)) {
                    toDo.push(path);
                }
            });
        }
        //files.clear();
       // files.add(Path.of(baseDir + "/kotlinx-coroutines-core/build.gradle.kts"));
        return p.parse(files, baseDir, context).toList();
    }

}
