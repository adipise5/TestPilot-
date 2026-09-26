package com.testpilot.ai.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Repair only escapes that Java rejects inside generated ordinary string literals. */
final class JavaTestStringEscapes {
    private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"", Pattern.DOTALL);
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+[A-Za-z_][\\w.]*\\s*;");

    private JavaTestStringEscapes() {}

    static String repair(String code) {
        Matcher matcher = STRING.matcher(code);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String literal = matcher.group();
            StringBuilder fixed = new StringBuilder(literal.length());
            for (int i = 0; i < literal.length(); i++) {
                char ch = literal.charAt(i);
                if (ch == '\\' && i + 1 < literal.length()) {
                    char next = literal.charAt(i + 1);
                    if ("btnfr\"'\\s01234567".indexOf(next) < 0 && next != 'u') fixed.append('\\');
                    fixed.append(ch).append(next);
                    i++;
                } else {
                    fixed.append(ch);
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(fixed.toString()));
        }
        matcher.appendTail(result);
        String normalized = result.toString();
        StringBuilder imports = new StringBuilder();
        boolean wildcard = normalized.contains("import org.junit.jupiter.api.*;");
        if (normalized.contains("@Test") && !wildcard
                && !normalized.contains("import org.junit.jupiter.api.Test;")) {
            imports.append("\nimport org.junit.jupiter.api.Test;");
        }
        if (normalized.contains("@Tag(") && !wildcard
                && !normalized.contains("import org.junit.jupiter.api.Tag;")) {
            imports.append("\nimport org.junit.jupiter.api.Tag;");
        }
        if (imports.isEmpty()) return normalized;
        Matcher packageMatcher = PACKAGE.matcher(normalized);
        if (packageMatcher.find()) {
            return normalized.substring(0, packageMatcher.end()) + imports + normalized.substring(packageMatcher.end());
        }
        return imports.substring(1) + "\n" + normalized;
    }
}
