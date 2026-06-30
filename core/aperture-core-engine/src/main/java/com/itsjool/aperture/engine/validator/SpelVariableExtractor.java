package com.itsjool.aperture.engine.validator;

import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.VariableReference;

import java.util.HashSet;
import java.util.Set;

public class SpelVariableExtractor {
    private static final SpelExpressionParser parser = new SpelExpressionParser();

    public static Set<String> getVariables(String expressionStr) {
        Set<String> variables = new HashSet<>();
        try {
            Expression expr = parser.parseExpression(expressionStr);
            if (expr instanceof SpelExpression spelExpr) {
                extractVariables(spelExpr.getAST(), variables);
            }
        } catch (Exception e) {
            throw new RuntimeException("Invalid SpEL expression: " + expressionStr, e);
        }
        return variables;
    }

    private static void extractVariables(SpelNode node, Set<String> variables) {
        if (node == null) return;
        if (node instanceof VariableReference varRef) {
            variables.add(varRef.toStringAST().substring(1)); // Remove '#'
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            extractVariables(node.getChild(i), variables);
        }
    }
}
