package org.debian.gradle.rewrite;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.NlsRewrite;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.groovy.GroovyIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.KotlinIsoVisitor;
import org.openrewrite.kotlin.tree.K;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.openrewrite.Tree.randomId;

public class RemoveExtensionRecipe extends Recipe {

    private static final String PLUGIN_BLOCK = "PLUGIN_BLOCK";

    private final boolean kotlinDsl;
    private final HashSet<String> removeMethods;
    private final HashSet<String> removePlugins;
    private final HashSet<String> removeClasspath;
    private final HashSet<String> methodWithArg;
    private final HashSet<String> removeImports;
    private final HashSet<String> methodWithTypeParameter;

    public RemoveExtensionRecipe(boolean kotlinDsl,
                                 List<String> removePlugins,
                                 List<String> removeMethods,
                                 List<String> removeClasspath,
                                 List<String> methodWithArg,
                                 List<String> removeImports,
                                 List<String> methodWithTypeParameter) {
        this.kotlinDsl = kotlinDsl;
        this.removePlugins = new HashSet<>(removePlugins);
        this.removeMethods = new HashSet<>(removeMethods);
        this.removeClasspath = new HashSet<>(removeClasspath);
        this.methodWithArg = new HashSet<>(methodWithArg);
        this.removeImports = new HashSet<>(removeImports);
        this.methodWithTypeParameter = new HashSet<>(methodWithTypeParameter);
    }

    @Override
    public @NlsRewrite.DisplayName String getDisplayName() {
        return "Remove unsupported extensions";
    }

    @Override
    public @NlsRewrite.Description String getDescription() {
        return "Removes unsupported extensions from the gradle build.";
    }

    /**
     * if ("enableFeaturePreview".equals(method.getSimpleName()) &&
     * method.getArguments().size() == 1 &&
     * J.Literal.isLiteralValue(method.getArguments().get(0), previewFeatureName)) {
     * return null;
     * }
     * return method;
     *
     * @return
     */
    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> ret;
        if (kotlinDsl) {
            return new KotlinIsoVisitor<ExecutionContext>() {

                @Override
                public J.Import visitImport(J.Import _import, ExecutionContext executionContext) {
                    J.Import newImport = filterImport(_import, executionContext);
                    if (newImport == null) {
                        return null;
                    }
                    return super.visitImport(newImport, executionContext);
                }

                @Override
                public J.Lambda visitLambda(J.Lambda lambda, ExecutionContext executionContext) {
                    J.Lambda newLambda = filterLambda(lambda, executionContext);
                    if (newLambda != lambda) {
                        return newLambda;
                    }
                    return super.visitLambda(lambda, executionContext);
                }

                @Override
                public J visitAnnotatedExpression(K.AnnotatedExpression annotatedExpression, ExecutionContext executionContext) {
                    if (annotatedExpression.getExpression() instanceof J.MethodInvocation) {
                        J.MethodInvocation m = visitMethodInvocation((J.MethodInvocation) annotatedExpression.getExpression(), executionContext);
                        if (m == null) {
                            return null;
                        }
                        annotatedExpression =  annotatedExpression.withExpression(m);
                    }
                    return super.visitAnnotatedExpression(annotatedExpression, executionContext);
                }

                @Override
                public J.@Nullable MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                    J.MethodInvocation m = filterMethodInvocation(method, ctx);
                    if (m == null) {
                        return null;
                    }
                    return super.visitMethodInvocation(m, ctx);
                }

                @Override
                public @Nullable J postVisit(@NonNull J tree, ExecutionContext executionContext) {
                    if (tree instanceof J.MethodInvocation) {
                        J.MethodInvocation call = (J.MethodInvocation) tree;
                        if (call.getSimpleName().equals("plugins")) {
                            executionContext.pollMessage(PLUGIN_BLOCK);
                        }
                    }
                    return super.postVisit(tree, executionContext);
                }
            };
        }
        return new GroovyIsoVisitor<ExecutionContext>() {

            @Override
            public J.@Nullable MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = filterMethodInvocation(method, ctx);
                if (m == null) {
                    return null;
                }
                return super.visitMethodInvocation(m, ctx);
            }
        };
    }

    private J.Import filterImport(J.Import anImport, ExecutionContext executionContext) {
        if (removeImports.contains(anImport.getQualid().toString())) {
            return null;
        }
        return anImport;
    }

    private J.Lambda filterLambda(J.Lambda lambda, ExecutionContext executionContext) {
        if (executionContext.getMessage(PLUGIN_BLOCK, false)) {
            J.Block block = (J.Block) lambda.getBody();
            List<Statement> stm = block.getStatements();
            List<Statement> newStm = stm.stream().filter( x -> {
                if (x instanceof K.ExpressionStatement) {
                    K.ExpressionStatement kStm = (K.ExpressionStatement)x;
                    //return !"signing".equals(kStm.getExpression().toString());
                }
                return true;
            }).toList();
            if (newStm.size() == stm.size()) {
                return lambda;
            }
            return lambda.withBody(new J.Block(randomId(), block.getPrefix(), block.getMarkers(),
                    JRightPadded.build(block.isStatic()),
                    newStm.stream().map(JRightPadded::build).toList(), block.getEnd()));
        }
        return lambda;
    }

    private J.@Nullable MethodInvocation filterMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {

        if (removeMethods.contains(method.getSimpleName())) {
            return null;
        }

        var tps = method.getTypeParameters();
        if (tps != null) {
            for (var tp : method.getTypeParameters()) {
                if (methodWithTypeParameter.contains(tp.toString())) {
                    return null;
                }
            }
        }

        if ("plugins".equals(method.getSimpleName())) {
            ctx.putMessage(PLUGIN_BLOCK, true);
        }
        if (methodWithArg.contains(method.toString())) {
            return null;
        }
        if ("implementation".equals(method.getSimpleName())
                || "classpath".equals(method.getSimpleName())) {
            String id = method.getArguments().get(0).print();
            if (removeClasspath.contains(id)) {
                return null;
            }
        }
        if ("id".equals(method.getSimpleName()) || "alias".equals(method.getSimpleName())) {
            String id = method.getArguments().get(0).toString();
            if (removePlugins.contains(id)) {
                return null;
            }
        }
        if ("version".equals(method.getSimpleName())) {
            J.MethodInvocation call = (J.MethodInvocation) method.getSelect();
            if (call == null) {
                return method;
            }
            String id = call.getArguments().get(0).toString();
            if (removePlugins.contains(id)) {
                return null;
            }
        }
        return method;
    }
}
