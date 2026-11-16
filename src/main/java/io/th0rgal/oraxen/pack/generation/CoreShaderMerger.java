package io.th0rgal.oraxen.pack.generation;

import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative composition of shader programs with a shared main-body structure. */
final class CoreShaderMerger {
    private static final Pattern PATH = Pattern.compile("assets/[^/]+/shaders/(core/[^/]+\\.(?:vsh|fsh|json)|include/.+\\.glsl)");
    private static final Pattern TOKEN = Pattern.compile(
            "(?s:/\\*.*?\\*/)|//[^\\r\\n]*|(?m:^[\\t ]*#[^\\r\\n]*)|[A-Za-z_]\\w*|"
                    + "(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fFuU]?|"
                    + "<<=|>>=|\\+\\+|--|[+*/%=!<>|&^-]=|&&|\\|\\||<<|>>|\\S");

    private CoreShaderMerger() {}

    static boolean supports(String path) {
        return PATH.matcher(path).matches();
    }

    static byte[] merge(String path, byte[] lower, byte[] higher) {
        if (Arrays.equals(lower, higher)) return higher;
        String low = new String(lower, StandardCharsets.UTF_8);
        String high = new String(higher, StandardCharsets.UTF_8);
        String result = path.endsWith(".json")
                ? mergeJson(JsonParser.parseString(low), JsonParser.parseString(high)).toString()
                : mergeSource(low, high);
        return result.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonElement mergeJson(JsonElement lower, JsonElement higher) {
        if (lower.equals(higher)) return higher.deepCopy();
        if (lower.isJsonObject() && higher.isJsonObject()) {
            JsonObject result = lower.getAsJsonObject().deepCopy();
            higher.getAsJsonObject().entrySet().forEach(entry -> result.add(entry.getKey(), result.has(entry.getKey())
                    ? mergeJson(result.get(entry.getKey()), entry.getValue()) : entry.getValue().deepCopy()));
            return result;
        }
        if (lower.isJsonArray() && higher.isJsonArray()) {
            Map<String, JsonElement> parameters = new LinkedHashMap<>();
            for (JsonArray array : List.of(lower.getAsJsonArray(), higher.getAsJsonArray())) {
                for (JsonElement value : array) {
                    String key;
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) key = value.getAsString();
                    else if (value.isJsonObject() && value.getAsJsonObject().has("name")
                            && value.getAsJsonObject().get("name").isJsonPrimitive()
                            && value.getAsJsonObject().getAsJsonPrimitive("name").isString())
                        key = value.getAsJsonObject().get("name").getAsString();
                    else throw unsupported("incompatible shader parameter arrays");
                    JsonElement previous = parameters.putIfAbsent(key, value);
                    if (previous != null && !previous.equals(value)) throw unsupported("conflicting shader parameter " + key);
                }
            }
            JsonArray result = new JsonArray();
            parameters.values().forEach(value -> result.add(value.deepCopy()));
            return result;
        }
        throw unsupported("conflicting shader program values");
    }

    private static String mergeSource(String lower, String higher) {
        List<Part> low = parts(lower, false);
        List<Part> high = parts(higher, false);
        if (signatures(low).equals(signatures(high))) return higher;
        Map<String, Part> declarations = new LinkedHashMap<>();
        Part lowMain = null;
        Part highMain = null;
        for (Part part : low) {
            if (part.main()) {
                if (lowMain != null) throw unsupported("multiple main functions");
                lowMain = part;
            } else addDeclaration(declarations, part);
        }
        for (Part part : high) {
            if (part.main()) {
                if (highMain != null) throw unsupported("multiple main functions");
                highMain = part;
            } else addDeclaration(declarations, part);
        }
        if ((lowMain == null) != (highMain == null)) throw unsupported("incompatible main functions");
        StringBuilder result = new StringBuilder();
        // GLSL requires the version and extensions before declarations and imports.
        declarations.values().stream().filter(Part::header).forEach(part -> result.append(part.source).append('\n'));
        declarations.values().stream().filter(part -> !part.header()).forEach(part -> result.append(part.source).append('\n'));
        if (lowMain != null) {
            List<Part> body = mergeBody(parts(lowMain.body(), true), parts(highMain.body(), true));
            result.append("void main() {\n");
            body.forEach(part -> result.append(part.source).append('\n'));
            result.append("}\n");
        }
        return result.toString();
    }

    private static void addDeclaration(Map<String, Part> declarations, Part part) {
        String key = part.key();
        Part previous = declarations.putIfAbsent(key, part);
        if (previous != null && !previous.signature().equals(part.signature()))
            throw unsupported("conflicting shader declaration " + key);
    }

    private static List<Part> mergeBody(List<Part> low, List<Part> high) {
        List<String> lowKeys = signatures(low);
        List<String> highKeys = signatures(high);
        if (new HashSet<>(lowKeys).size() != low.size() || new HashSet<>(highKeys).size() != high.size())
            throw unsupported("repeated main-body statements");
        Set<String> shared = new HashSet<>(lowKeys);
        shared.retainAll(highKeys);
        if (!lowKeys.stream().filter(shared::contains).toList().equals(highKeys.stream().filter(shared::contains).toList()))
            throw unsupported("reordered main-body statements");
        if (shared.isEmpty() && !low.isEmpty() && !high.isEmpty()) throw unsupported("unrelated main functions");
        List<Part> result = new ArrayList<>();
        int lowIndex = 0;
        int highIndex = 0;
        for (String anchor : lowKeys.stream().filter(shared::contains).toList()) {
            int nextLow = lowKeys.indexOf(anchor);
            int nextHigh = highKeys.indexOf(anchor);
            result.addAll(low.subList(lowIndex, nextLow));
            result.addAll(high.subList(highIndex, nextHigh + 1));
            lowIndex = nextLow + 1;
            highIndex = nextHigh + 1;
        }
        result.addAll(low.subList(lowIndex, low.size()));
        result.addAll(high.subList(highIndex, high.size()));
        Set<String> lowWrites = new HashSet<>();
        for (Part part : low) {
            if (!shared.contains(part.signature()) && part.statementKey() != null) lowWrites.add(part.statementKey());
        }
        for (Part part : high) {
            if (!shared.contains(part.signature()) && lowWrites.contains(part.statementKey()))
                throw unsupported("conflicting main-body statement " + part.statementKey());
        }
        for (Part part : result) {
            String first = part.tokens.getFirst().value;
            if (!shared.contains(part.signature()) && Set.of("return", "discard", "break", "continue").contains(first))
                throw unsupported("unconditional main-body exit");
        }
        return result;
    }

    private static List<String> signatures(List<Part> parts) {
        return parts.stream().map(Part::signature).toList();
    }

    private static List<Part> parts(String source, boolean body) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(source);
        while (matcher.find()) {
            String value = matcher.group();
            if (value.startsWith("//") || value.startsWith("/*")) continue;
            if (value.equals("/") && source.startsWith("/*", matcher.start())) throw unsupported("unclosed comment");
            if (value.stripLeading().startsWith("#")) {
                value = value.strip();
                if (!value.matches("#\\s*(version|extension|moj_import|define)\\b.*") || value.endsWith("\\") || body)
                    throw unsupported("unsupported shader preprocessor directive");
            }
            tokens.add(new Token(value, matcher.start(), matcher.end()));
        }
        List<Part> result = new ArrayList<>();
        int start = 0;
        int braces = 0;
        int parentheses = 0;
        int brackets = 0;
        boolean function = false;
        for (int index = 0; index < tokens.size(); index++) {
            String value = tokens.get(index).value;
            switch (value) {
                case "(" -> parentheses++;
                case ")" -> parentheses--;
                case "[" -> brackets++;
                case "]" -> brackets--;
                case "{" -> {
                    if (braces == 0) function = body || tokens.subList(start, index).stream().anyMatch(token -> token.value.equals("("));
                    braces++;
                }
                case "}" -> braces--;
            }
            if (braces < 0 || parentheses < 0 || brackets < 0) throw unsupported("unbalanced shader delimiters");
            boolean end = value.startsWith("#") || value.equals(";") || value.equals("}") && function;
            if (end && braces == 0 && parentheses == 0 && brackets == 0
                    && (index + 1 == tokens.size() || !tokens.get(index + 1).value.equals("else"))) {
                List<Token> section = tokens.subList(start, index + 1);
                if (section.getFirst().value.equals("do")) throw unsupported("do-while statements");
                result.add(new Part(source.substring(section.getFirst().start, section.getLast().end), List.copyOf(section)));
                start = index + 1;
                function = false;
            }
        }
        if (start != tokens.size() || braces != 0 || parentheses != 0 || brackets != 0)
            throw unsupported("incomplete shader section");
        return result;
    }

    private record Token(String value, int start, int end) {}

    private record Part(String source, List<Token> tokens) {
        String signature() {
            return String.join(" ", tokens.stream().map(token -> token.value.replaceAll("\\s+", " ")).toList());
        }

        boolean main() {
            return tokens.size() > 3 && tokens.get(0).value.equals("void") && tokens.get(1).value.equals("main")
                    && tokens.get(2).value.equals("(");
        }

        boolean header() {
            return tokens.getFirst().value.matches("#\\s*(version|extension)\\b.*");
        }

        String body() {
            int offset = tokens.getFirst().start;
            int open = tokens.stream().filter(token -> token.value.equals("{")).findFirst().orElseThrow().end - offset;
            return source.substring(open, tokens.getLast().start - offset);
        }

        String key() {
            String first = tokens.getFirst().value;
            if (first.startsWith("#")) {
                String[] words = first.replaceFirst("#\\s*", "").split("\\s+");
                return words[0].equals("version") ? "#version" : words[0] + " " + words[1];
            }
            int parentheses = 0;
            for (int index = 1; index < tokens.size(); index++) {
                String value = tokens.get(index).value;
                if (value.equals("(")) {
                    if (parentheses == 0 && !tokens.get(index - 1).value.equals("layout"))
                        return "function " + tokens.get(index - 1).value;
                    parentheses++;
                } else if (value.equals(")")) parentheses--;
                else if (parentheses == 0) {
                    if (Set.of(";", "=", "[", "{").contains(value)) return "declaration " + tokens.get(index - 1).value;
                    if (value.equals(",")) throw unsupported("multiple variable declarations");
                }
            }
            throw unsupported("unrecognized shader declaration");
        }

        String statementKey() {
            // Only direct declarations and assignments conflict. Conditional blocks may compose.
            for (int index = 0; index < tokens.size(); index++) {
                String value = tokens.get(index).value;
                if (Set.of("(", "{").contains(value)) return null;
                if (value.equals("=")) return String.join(" ", tokens.subList(0, index).stream().map(Token::value).toList())
                        .replaceFirst("^(?:const )?\\w+ (?=\\w+$)", "");
            }
            if (tokens.size() >= 3 && tokens.getFirst().value.matches("[A-Za-z_]\\w*")
                    && tokens.get(1).value.matches("[A-Za-z_]\\w*"))
                return tokens.get(tokens.size() - 2).value;
            return null;
        }
    }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException(reason);
    }
}
