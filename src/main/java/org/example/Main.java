package org.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * ============================================================
 *  OpenSapphire 编译器 (osw) v1.5
 * ============================================================
 *
 *  用法:
 *      osw <yourfile>.os
 *
 *  输出 (与 .os 文件同目录):
 *      <name>.html    纯净 HTML
 *      <name>.bat     Windows 一键打开
 *      <name>         macOS / Linux 一键打开
 *
 *  功能:
 *      - 内置元素: st1..st6 / image / botton / text / link /
 *                  input / hr / row / col / box / tip / endl
 *      - 事件:     event("名") { addhttp / alert / print / ... }
 *      - 样式:     style { 选择器 { 属性: 值; } }   (类似 CSS)
 *      - 库文件:   .oslm  (org { function ... })   import lib.oslm;
 *      - SSF:      import graphic;  ssf() { color { background=#ff0000 } }
 *      - CWM:      cwm.properties   html_name / html_version / src_code
 *      - 错误处理: 语法错误也生成 HTML，内容为专用错误页
 *      - 静默模式: 编译过程中不向终端打印语法错误
 * ============================================================
 */
public class Main {

    /* ============================================================
     *  入口
     * ============================================================ */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("OpenSapphire 编译器 v1.5");
            System.err.println("用法: osw <yourfile>.os");
            System.exit(1);
        }

        Path input = Paths.get(args[0]);
        if (!Files.isRegularFile(input)) {
            System.err.println("错误: 找不到文件 " + input.toAbsolutePath());
            System.exit(1);
        }

        String source;
        try {
            source = new String(Files.readAllBytes(input), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("错误: 读取文件失败 -> " + e.getMessage());
            System.exit(1);
            return;
        }

        Path inputDir = input.toAbsolutePath().getParent();
        if (inputDir == null) inputDir = Paths.get(".").toAbsolutePath();

        CwmConfig cwm = CwmConfig.load(inputDir);

        String fileName = input.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String baseName = (dot > 0) ? fileName.substring(0, dot) : fileName;
        String outputName = cwm.outputName(baseName);

        String html;
        List<String> allErrors = new ArrayList<>();

        try {
            List<Token> tokens = new Lexer(source).tokenize();
            Parser parser = new Parser(tokens, source);
            List<Node> ast = parser.parseProgram();

            allErrors.addAll(parser.errors);

            if (!allErrors.isEmpty()) {
                html = buildErrorHtml(allErrors);
            } else {
                Map<String, FunctionDef> functions = new LinkedHashMap<>();
                collectFunctions(ast, functions);
                loadLibraries(ast, input, functions, allErrors);

                if (!allErrors.isEmpty()) {
                    html = buildErrorHtml(allErrors);
                } else {
                    html = new Compiler(functions, cwm).compile(ast, outputName);
                }
            }
        } catch (OsException e) {
            allErrors.add(e.getMessage());
            html = buildErrorHtml(allErrors);
        }

        try {
            Path htmlPath = inputDir.resolve(outputName + ".html");
            Files.write(htmlPath, html.getBytes(StandardCharsets.UTF_8));
            System.out.println("✓ 已生成 → " + htmlPath);

            Path batPath = inputDir.resolve(outputName + ".bat");
            Files.write(batPath, buildBat(outputName).getBytes(StandardCharsets.UTF_8));
            System.out.println("✓ 已生成 → " + batPath);

            Path shPath = inputDir.resolve(outputName);
            Files.write(shPath, buildSh(outputName).getBytes(StandardCharsets.UTF_8));
            try { shPath.toFile().setExecutable(true, false); } catch (Exception ignored) {}
            System.out.println("✓ 已生成 → " + shPath);

        } catch (IOException e) {
            System.err.println("错误: 写入输出失败 -> " + e.getMessage());
            System.exit(1);
        }
    }

    /* ============================================================
     *  错误页 HTML
     * ============================================================ */
    private static String buildErrorHtml(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"zh-CN\">\n");
        sb.append("<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>ERROR!</title>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");
        sb.append("<h1>ERROR!</h1>\n");
        sb.append("<br>\n");
        sb.append("<br>\n");
        sb.append("<br>\n");

        for (int i = 0; i < errors.size(); i++) {
            sb.append("<h2>错误原因</h2>\n");
            sb.append("<text>ERROR NUMBER:").append(i + 1).append("</text>\n");
            sb.append("<text>").append(escHtml(errors.get(i))).append("</text>\n");
        }

        sb.append("<style>\n");
        sb.append("    body {\n");
        sb.append("        background: #001aff;\n");
        sb.append("        font-family: -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;\n");
        sb.append("        padding: 40px 28px;\n");
        sb.append("        margin: 0;\n");
        sb.append("    }\n");
        sb.append("    h1 {\n");
        sb.append("        color: #ffffff;\n");
        sb.append("        font-size: 56px;\n");
        sb.append("        margin: 0 0 8px;\n");
        sb.append("    }\n");
        sb.append("    h2 {\n");
        sb.append("        color: #ffffff;\n");
        sb.append("        font-size: 22px;\n");
        sb.append("        margin: 32px 0 8px;\n");
        sb.append("        opacity: 0.85;\n");
        sb.append("    }\n");
        sb.append("    text {\n");
        sb.append("        display: block;\n");
        sb.append("        color: #ffffff;\n");
        sb.append("        font-family: 'JetBrains Mono', Consolas, Monaco, monospace;\n");
        sb.append("        font-size: 15px;\n");
        sb.append("        line-height: 1.7;\n");
        sb.append("        background: rgba(255, 255, 255, 0.12);\n");
        sb.append("        border-left: 3px solid rgba(255, 255, 255, 0.5);\n");
        sb.append("        padding: 8px 14px;\n");
        sb.append("        margin: 4px 0;\n");
        sb.append("        border-radius: 4px;\n");
        sb.append("        white-space: pre-wrap;\n");
        sb.append("        word-break: break-word;\n");
        sb.append("    }\n");
        sb.append("</style>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");
        return sb.toString();
    }

    /* ============================================================
     *  启动脚本
     * ============================================================ */
    private static String buildBat(String baseName) {
        return "@echo off\r\n"
                + "start \"\" \"%~dp0" + baseName + ".html\"\r\n";
    }

    private static String buildSh(String baseName) {
        return "#!/bin/sh\n"
                + "DIR=$(cd \"$(dirname \"$0\")\" && pwd)\n"
                + "FILE=\"$DIR/" + baseName + ".html\"\n"
                + "if command -v xdg-open >/dev/null 2>&1; then\n"
                + "  xdg-open \"$FILE\"\n"
                + "elif command -v open >/dev/null 2>&1; then\n"
                + "  open \"$FILE\"\n"
                + "else\n"
                + "  echo \"请手动打开: $FILE\"\n"
                + "fi\n";
    }

    /* ============================================================
     *  CWM 自定义网页模型
     * ============================================================ */
    static class CwmConfig {
        String htmlName;
        String htmlVersion;
        String srcCode = ".";

        String outputName(String fallback) {
            return (htmlName != null && !htmlName.isEmpty()) ? htmlName : fallback;
        }

        static CwmConfig load(Path dir) {
            CwmConfig c = new CwmConfig();
            Path p = dir.resolve("cwm.properties");
            if (!Files.isRegularFile(p)) {
                Path parent = dir.getParent();
                if (parent != null) {
                    Path p2 = parent.resolve("cwm.properties");
                    if (Files.isRegularFile(p2)) p = p2;
                }
            }
            if (!Files.isRegularFile(p)) return c;

            try {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    int hash = val.indexOf(" #");
                    if (hash >= 0) val = val.substring(0, hash).trim();
                    switch (key) {
                        case "html_name":    c.htmlName = val; break;
                        case "html_version": c.htmlVersion = val; break;
                        case "src_code":     c.srcCode = val; break;
                    }
                }
            } catch (IOException e) { /* 忽略 */ }
            return c;
        }
    }

    /* ============================================================
     *  库文件加载
     * ============================================================ */
    private static void loadLibraries(List<Node> ast, Path sourceFile,
                                      Map<String, FunctionDef> out,
                                      List<String> errors) {
        List<String> libs = new ArrayList<>();
        collectLibraryImports(ast, libs);

        Path baseDir = sourceFile.toAbsolutePath().getParent();
        if (baseDir == null) baseDir = Paths.get(".").toAbsolutePath();

        for (String lib : libs) {
            Path libPath;
            try {
                libPath = baseDir.resolve(lib).normalize();
            } catch (Exception e) {
                errors.add("库路径非法: " + lib);
                continue;
            }
            if (!Files.isRegularFile(libPath)) {
                errors.add("找不到库文件: " + lib);
                continue;
            }
            try {
                String src = new String(Files.readAllBytes(libPath), StandardCharsets.UTF_8);
                Parser libParser = new Parser(new Lexer(src).tokenize(), src);
                List<Node> libAst = libParser.parseProgram();
                if (!libParser.errors.isEmpty()) {
                    for (String e : libParser.errors) {
                        errors.add("[" + lib + "] " + e);
                    }
                    continue;
                }
                collectFunctions(libAst, out);
            } catch (Exception e) {
                errors.add("加载库文件失败: " + lib + " -> " + e.getMessage());
            }
        }
    }

    private static void collectLibraryImports(List<Node> stmts, List<String> out) {
        for (Node n : stmts) {
            if (n instanceof ImportNode) {
                String name = ((ImportNode) n).name;
                if (name.endsWith(".oslm") || name.contains("/") || name.contains("\\")) {
                    out.add(name);
                }
            } else if (n instanceof BlockNode) {
                collectLibraryImports(((BlockNode) n).body, out);
            } else if (n instanceof CallNode) {
                CallNode c = (CallNode) n;
                if (c.body != null) collectLibraryImports(c.body, out);
            }
        }
    }

    private static void collectFunctions(List<Node> stmts, Map<String, FunctionDef> out) {
        for (Node n : stmts) {
            if (n instanceof FunctionDef) {
                out.put(((FunctionDef) n).name, (FunctionDef) n);
            } else if (n instanceof BlockNode) {
                collectFunctions(((BlockNode) n).body, out);
            } else if (n instanceof CallNode) {
                CallNode c = (CallNode) n;
                if (c.body != null) collectFunctions(c.body, out);
            }
        }
    }

    /* ============================================================
     *  异常 & 通用工具
     * ============================================================ */
    static class OsException extends RuntimeException {
        OsException(String msg) { super(msg); }
    }

    static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    static String escAttr(String s) {
        return escHtml(s).replace("\"", "&quot;");
    }
    static String escJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "");
    }

    /* ============================================================
     *  词法分析
     * ============================================================ */
    enum TokenType { IDENT, STRING, NUMBER, PUNCT, EOF }

    static class Token {
        final TokenType type;
        final String value;
        final int line;
        final int start;
        final int end;
        Token(TokenType type, String value, int line, int start, int end) {
            this.type = type; this.value = value; this.line = line;
            this.start = start; this.end = end;
        }
    }

    static class Lexer {
        private final String src;
        private int pos = 0, line = 1;
        Lexer(String src) { this.src = src; }

        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            int n = src.length();
            while (pos < n) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    if (c == '\n') line++;
                    pos++;
                    continue;
                }
                // 行注释
                if (c == '/' && pos + 1 < n && src.charAt(pos + 1) == '/') {
                    while (pos < n && src.charAt(pos) != '\n') pos++;
                    continue;
                }
                // 块注释
                if (c == '/' && pos + 1 < n && src.charAt(pos + 1) == '*') {
                    pos += 2;
                    while (pos + 1 < n && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
                        if (src.charAt(pos) == '\n') line++;
                        pos++;
                    }
                    pos = Math.min(pos + 2, n);
                    continue;
                }
                // 字符串
                if (c == '"' || c == '\'') {
                    char q = c; int start = pos; pos++;
                    StringBuilder sb = new StringBuilder();
                    while (pos < n && src.charAt(pos) != q) {
                        char ch = src.charAt(pos);
                        if (ch == '\\' && pos + 1 < n) {
                            sb.append(src.charAt(pos + 1));
                            pos += 2;
                        } else {
                            if (ch == '\n') line++;
                            sb.append(ch);
                            pos++;
                        }
                    }
                    pos++;
                    out.add(new Token(TokenType.STRING, sb.toString(), line, start, pos));
                    continue;
                }
                // 数字
                if (Character.isDigit(c)) {
                    int start = pos;
                    while (pos < n && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
                    out.add(new Token(TokenType.NUMBER, src.substring(start, pos), line, start, pos));
                    continue;
                }
                // 标识符
                if (isIdStart(c)) {
                    int start = pos;
                    while (pos < n && isIdPart(src.charAt(pos))) pos++;
                    out.add(new Token(TokenType.IDENT, src.substring(start, pos), line, start, pos));
                    continue;
                }
                // 标点
                int start = pos;
                out.add(new Token(TokenType.PUNCT, String.valueOf(c), line, start, pos + 1));
                pos++;
            }
            out.add(new Token(TokenType.EOF, "", line, pos, pos));
            return out;
        }
        private static boolean isIdStart(char c) {
            return Character.isLetter(c) || c == '_' || c == '$';
        }
        private static boolean isIdPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '-';
        }
    }

    /* ============================================================
     *  AST
     * ============================================================ */
    static abstract class Node {}
    static class ImportNode extends Node { String name; }
    static class BlockNode extends Node { String name; List<Node> body; }
    static class CallNode extends Node {
        String name;
        List<Arg> args = new ArrayList<>();
        List<Node> body;
        List<SsfBlock> ssfBlocks;
    }
    static class EventNode extends Node { String name; List<Node> body; }
    static class StyleNode extends Node { List<StyleRule> rules; }
    static class FunctionDef extends Node {
        String name;
        List<String> paramNames = new ArrayList<>();
        List<String> paramTypes = new ArrayList<>();
        List<Node> body;
    }
    static class Arg { String name; String value; boolean isIdent; }
    static class StyleRule { String selector; List<String[]> decls = new ArrayList<>(); }
    static class SsfBlock {
        String name;
        List<String[]> props = new ArrayList<>();
    }

    /* ============================================================
     *  语法分析（含错误恢复，多个错误一次性收集）
     * ============================================================ */
    static class Parser {
        private final List<Token> tk;
        private final String src;
        private int i = 0;
        final List<String> errors = new ArrayList<>();

        Parser(List<Token> tk, String src) { this.tk = tk; this.src = src; }

        private Token peek()         { return tk.get(Math.min(i, tk.size() - 1)); }
        private Token peek(int k)    { return tk.get(Math.min(i + k, tk.size() - 1)); }
        private Token next()         { return tk.get(Math.min(i++, tk.size() - 1)); }
        private boolean at(String v) { return peek().value.equals(v) && peek().type != TokenType.EOF; }
        private boolean eat(String v){ if (at(v)) { i++; return true; } return false; }

        private OsException err(String msg) {
            Token t = peek();
            return new OsException("第 " + t.line + " 行: " + msg
                    + (t.type == TokenType.EOF ? "（文件结尾）" : "（遇到 \"" + t.value + "\"）"));
        }
        private void expect(String v) {
            if (!at(v)) throw err("缺少 \"" + v + "\"");
            i++;
        }

        List<Node> parseProgram() { return parseStatements(); }

        private List<Node> parseStatements() {
            List<Node> list = new ArrayList<>();
            while (peek().type != TokenType.EOF && !at("}")) {
                int before = i;
                try {
                    list.add(parseStatement());
                } catch (OsException e) {
                    errors.add(e.getMessage());
                    skipToStatementEnd();
                    if (i == before) i++;
                }
            }
            return list;
        }

        /** 跳过出错的语句：到 ';' / '}' / EOF 为止 */
        private void skipToStatementEnd() {
            while (peek().type != TokenType.EOF && !at(";") && !at("}")) {
                next();
            }
            if (at(";")) next();
        }

        private Node parseStatement() {
            Token t = peek();
            if (t.type != TokenType.IDENT) throw err("无法识别的语句起始符号");
            String name = t.value;

            /* -------- import -------- */
            if (name.equals("import")) {
                next();
                StringBuilder mod = new StringBuilder();
                Token first = peek();
                if (first.type == TokenType.STRING) {
                    mod.append(next().value);
                } else {
                    while (peek().type != TokenType.EOF && !at(";") && !at("}")) {
                        mod.append(next().value);
                    }
                }
                eat(";");
                ImportNode n = new ImportNode(); n.name = mod.toString(); return n;
            }

            /* -------- function -------- */
            if (name.equals("function")) {
                next();
                Token fnName = next();
                if (fnName.type != TokenType.IDENT) throw err("function 后需要函数名");

                FunctionDef fn = new FunctionDef();
                fn.name = fnName.value;

                expect("(");
                while (!at(")")) {
                    Token t1 = next();
                    if (at(";") || at(")")) {
                        fn.paramTypes.add("any");
                        fn.paramNames.add(t1.value);
                    } else {
                        Token t2 = next();
                        fn.paramTypes.add(t1.value);
                        fn.paramNames.add(t2.value);
                    }
                    if (!eat(";")) break;
                }
                expect(")");
                expect("{");
                fn.body = parseStatements();
                expect("}");
                eat(";");
                return fn;
            }

            /* -------- ssf 块 -------- */
            if (name.equals("ssf") && peek(1).value.equals("(")) {
                next();
                List<Arg> args = new ArrayList<>();
                if (eat("(")) { args = parseArgs(); expect(")"); }
                expect("{");
                List<SsfBlock> blocks = parseSsfBody();
                expect("}");
                eat(";");
                CallNode call = new CallNode();
                call.name = "ssf";
                call.args = args;
                call.ssfBlocks = blocks;
                return call;
            }

            /* -------- com / org / net -------- */
            if ((name.equals("com") || name.equals("org") || name.equals("net"))
                    && peek(1).value.equals("{")) {
                next(); expect("{");
                List<Node> body = parseStatements();
                expect("}"); eat(";");
                BlockNode n = new BlockNode(); n.name = name; n.body = body; return n;
            }

            /* -------- event -------- */
            if (name.equals("event")) {
                next();
                String evName = "";
                if (eat("(")) {
                    if (peek().type == TokenType.STRING || peek().type == TokenType.IDENT) {
                        evName = next().value;
                    }
                    expect(")");
                }
                expect("{");
                List<Node> body = parseStatements();
                expect("}"); eat(";");
                EventNode n = new EventNode(); n.name = evName; n.body = body; return n;
            }

            /* -------- style -------- */
            if (name.equals("style") && peek(1).value.equals("{")) {
                next(); expect("{");
                List<StyleRule> rules = parseStyleBody();
                expect("}"); eat(";");
                StyleNode n = new StyleNode(); n.rules = rules; return n;
            }

            /* -------- 普通调用 -------- */
            next();
            CallNode call = new CallNode();
            call.name = name;
            if (eat("(")) { call.args = parseArgs(); expect(")"); }
            if (eat("{")) { call.body = parseStatements(); expect("}"); }
            eat(";");
            return call;
        }

        private List<Arg> parseArgs() {
            List<Arg> args = new ArrayList<>();
            while (peek().type != TokenType.EOF && !at(")")) {
                Token t = next();
                Arg a = new Arg();
                if (t.type == TokenType.IDENT && at("=")) {
                    next();
                    Token v = next();
                    a.name = t.value; a.value = v.value;
                    a.isIdent = false;
                } else {
                    a.value = t.value;
                    a.isIdent = (t.type == TokenType.IDENT);
                }
                args.add(a);
                if (!eat(",")) break;
            }
            return args;
        }

        private List<StyleRule> parseStyleBody() {
            List<StyleRule> rules = new ArrayList<>();
            while (peek().type != TokenType.EOF && !at("}")) {
                StringBuilder sel = new StringBuilder();
                while (peek().type != TokenType.EOF && !at("{") && !at("}")) {
                    sel.append(next().value);
                }
                if (!eat("{")) break;

                List<String[]> decls = new ArrayList<>();
                while (peek().type != TokenType.EOF && !at("}")) {
                    if (eat(";")) continue;
                    String prop = next().value;
                    if (!eat(":")) continue;
                    StringBuilder val = new StringBuilder();
                    while (peek().type != TokenType.EOF && !at(";") && !at("}")) {
                        Token vt = next();
                        if (vt.type == TokenType.STRING) {
                            val.append('"').append(vt.value).append('"');
                        } else {
                            val.append(vt.value);
                        }
                    }
                    eat(";");
                    decls.add(new String[]{prop, val.toString().trim()});
                }
                expect("}");
                String selector = sel.toString().trim();
                if (!selector.isEmpty()) {
                    StyleRule r = new StyleRule();
                    r.selector = selector; r.decls = decls;
                    rules.add(r);
                }
            }
            return rules;
        }

        /* SSF 块体：key=value，值里允许空格 */
        private List<SsfBlock> parseSsfBody() {
            List<SsfBlock> blocks = new ArrayList<>();
            while (peek().type != TokenType.EOF && !at("}")) {
                if (eat(";")) continue;

                Token blockTok = peek();
                if (blockTok.type != TokenType.IDENT) { next(); continue; }
                next();

                SsfBlock b = new SsfBlock();
                b.name = blockTok.value;

                if (eat("{")) {
                    while (peek().type != TokenType.EOF && !at("}")) {
                        if (eat(";")) continue;
                        Token keyTok = peek();
                        if (keyTok.type != TokenType.IDENT) { next(); continue; }
                        next();
                        if (!eat("=")) continue;

                        int valStart = i;
                        while (peek().type != TokenType.EOF && !at("}") && !at(";")) {
                            if (peek().type == TokenType.IDENT && peek(1).value.equals("=")) break;
                            next();
                        }
                        String val = tokensToString(valStart, i);
                        b.props.add(new String[]{keyTok.value, val});
                    }
                    expect("}");
                }
                blocks.add(b);
            }
            return blocks;
        }

        private String tokensToString(int from, int to) {
            if (from >= to) return "";
            Token first = tk.get(from);
            Token last = tk.get(to - 1);
            int s = Math.min(first.start, src.length());
            int e = Math.min(last.end, src.length());
            return src.substring(s, e).trim();
        }
    }

    /* ============================================================
     *  代码生成
     * ============================================================ */
    static class Compiler {

        private final Map<String, FunctionDef> functions;
        private final CwmConfig cwm;
        private final Map<String, List<Node>> events = new LinkedHashMap<>();
        private final List<StyleRule> styles = new ArrayList<>();
        private final List<CallNode> ssfCalls = new ArrayList<>();
        private boolean hasTip = false;

        Compiler(Map<String, FunctionDef> functions, CwmConfig cwm) {
            this.functions = functions != null ? functions : new LinkedHashMap<>();
            this.cwm = cwm != null ? cwm : new CwmConfig();
        }

        /* tip 内置样式（只在用到 tip 时输出） */
        private static final String TIP_CSS =
                ".os-tip {\n" +
                        "  padding: 12px 16px;\n" +
                        "  margin: 8px 0;\n" +
                        "  background: #f0f9ff;\n" +
                        "  border-left: 4px solid #0284c7;\n" +
                        "  border-radius: 6px;\n" +
                        "  color: #0c4a6e;\n" +
                        "  line-height: 1.6;\n" +
                        "}\n";

        /* SSF 映射：块名.键名 → CSS 属性 */
        private static final Map<String, String> SSF_MAP = new HashMap<>();
        static {
            SSF_MAP.put("color.background", "background");
            SSF_MAP.put("color.bg", "background");
            SSF_MAP.put("color.text", "color");
            SSF_MAP.put("color.color", "color");
            SSF_MAP.put("color.border", "border-color");
            SSF_MAP.put("color.shadow", "box-shadow");

            SSF_MAP.put("font.size", "font-size");
            SSF_MAP.put("font.weight", "font-weight");
            SSF_MAP.put("font.family", "font-family");
            SSF_MAP.put("font.style", "font-style");
            SSF_MAP.put("font.align", "text-align");
            SSF_MAP.put("font.height", "line-height");
            SSF_MAP.put("font.spacing", "letter-spacing");

            SSF_MAP.put("space.padding", "padding");
            SSF_MAP.put("space.margin", "margin");
            SSF_MAP.put("space.gap", "gap");

            SSF_MAP.put("border.width", "border-width");
            SSF_MAP.put("border.color", "border-color");
            SSF_MAP.put("border.style", "border-style");
            SSF_MAP.put("border.radius", "border-radius");

            SSF_MAP.put("size.width", "width");
            SSF_MAP.put("size.height", "height");
            SSF_MAP.put("size.max-width", "max-width");
            SSF_MAP.put("size.min-width", "min-width");

            SSF_MAP.put("layout.display", "display");
            SSF_MAP.put("layout.direction", "flex-direction");
            SSF_MAP.put("layout.align", "align-items");
            SSF_MAP.put("layout.justify", "justify-content");
            SSF_MAP.put("layout.wrap", "flex-wrap");

            SSF_MAP.put("effect.opacity", "opacity");
            SSF_MAP.put("effect.cursor", "cursor");
            SSF_MAP.put("effect.shadow", "box-shadow");
            SSF_MAP.put("effect.transition", "transition");
        }

        /* 通用键名回退 */
        private static final Map<String, String> SSF_GENERIC = new HashMap<>();
        static {
            SSF_GENERIC.put("background", "background");
            SSF_GENERIC.put("bg", "background");
            SSF_GENERIC.put("text", "color");
            SSF_GENERIC.put("color", "color");
            SSF_GENERIC.put("size", "font-size");
            SSF_GENERIC.put("weight", "font-weight");
            SSF_GENERIC.put("family", "font-family");
            SSF_GENERIC.put("align", "text-align");
            SSF_GENERIC.put("padding", "padding");
            SSF_GENERIC.put("margin", "margin");
            SSF_GENERIC.put("gap", "gap");
            SSF_GENERIC.put("width", "width");
            SSF_GENERIC.put("height", "height");
            SSF_GENERIC.put("radius", "border-radius");
        }

        private static final Set<String> LENGTH_PROPS = new HashSet<>(Arrays.asList(
                "font-size", "width", "height", "max-width", "min-width",
                "max-height", "min-height",
                "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
                "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
                "gap", "row-gap", "column-gap",
                "border-radius", "border-width",
                "letter-spacing", "top", "right", "bottom", "left"
        ));

        private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
                "st1","st2","st3","st4","st5","st6",
                "image","img","botton","button","text","p",
                "link","a","input","hr","row","col","box","tip","endl","ssf"
        ));

        /* ---------- 主流程 ---------- */
        String compile(List<Node> ast, String title) {
            collect(ast);

            StringBuilder body = new StringBuilder();
            renderStatements(ast, body, 0, null);

            StringBuilder out = new StringBuilder();
            out.append("<!DOCTYPE html>\n");
            out.append("<html lang=\"zh-CN\">\n");
            out.append("<head>\n");
            out.append("<meta charset=\"UTF-8\">\n");
            out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
            out.append("<title>").append(escHtml(title)).append("</title>\n");
            if (cwm != null && cwm.htmlVersion != null && !cwm.htmlVersion.isEmpty()) {
                out.append("<meta name=\"version\" content=\"")
                        .append(escAttr(cwm.htmlVersion)).append("\">\n");
            }

            /* 按需输出 <style> */
            StringBuilder cssBuf = new StringBuilder();
            if (hasTip) cssBuf.append(TIP_CSS);
            if (!styles.isEmpty()) cssBuf.append(buildUserCss());
            if (!ssfCalls.isEmpty()) cssBuf.append(buildSsfCss());

            if (cssBuf.length() > 0) {
                out.append("<style>\n").append(cssBuf).append("</style>\n");
            }

            out.append("</head>\n<body>\n");
            out.append(body);
            out.append("</body>\n</html>\n");
            return out.toString();
        }

        private void collect(List<Node> stmts) {
            for (Node n : stmts) {
                if (n instanceof EventNode) {
                    events.put(((EventNode) n).name, ((EventNode) n).body);
                } else if (n instanceof StyleNode) {
                    styles.addAll(((StyleNode) n).rules);
                } else if (n instanceof BlockNode) {
                    collect(((BlockNode) n).body);
                } else if (n instanceof CallNode) {
                    CallNode c = (CallNode) n;
                    if (c.name.equals("tip")) hasTip = true;
                    if (c.name.equals("ssf") && c.ssfBlocks != null) ssfCalls.add(c);
                    if (c.body != null) collect(c.body);
                }
            }
        }

        private void renderStatements(List<Node> stmts, StringBuilder sb, int indent,
                                      Map<String, String> scope) {
            for (Node n : stmts) {
                if (n instanceof ImportNode || n instanceof StyleNode
                        || n instanceof EventNode || n instanceof FunctionDef) continue;
                if (n instanceof BlockNode) {
                    renderStatements(((BlockNode) n).body, sb, indent, scope);
                } else if (n instanceof CallNode) {
                    renderCall((CallNode) n, sb, indent, scope);
                }
            }
        }

        private void renderCall(CallNode c, StringBuilder sb, int indent, Map<String, String> scope) {
            String name = c.name;

            if (name.equals("ssf")) return;

            /* 用户函数调用 */
            if (!BUILTINS.contains(name) && functions.containsKey(name)) {
                FunctionDef fn = functions.get(name);
                Map<String, String> local = (scope != null) ? new HashMap<>(scope) : new HashMap<>();
                for (int k = 0; k < fn.paramNames.size() && k < c.args.size(); k++) {
                    local.put(fn.paramNames.get(k), resolveArg(c.args.get(k), scope));
                }
                renderStatements(fn.body, sb, indent, local);
                return;
            }

            List<String> pos = new ArrayList<>();
            Map<String, String> named = new LinkedHashMap<>();
            for (Arg a : c.args) {
                if (a.name != null) named.put(a.name, a.value);
                else pos.add(resolveArg(a, scope));
            }

            /* 标题 st1..st6 */
            Matcher m = Pattern.compile("^st([1-6])$").matcher(name);
            if (m.matches()) {
                String lvl = m.group(1);
                sb.append(ind(indent))
                        .append("<h").append(lvl).append(">")
                        .append(escHtml(get(pos, 0)))
                        .append("</h").append(lvl).append(">\n");
                return;
            }

            switch (name) {
                case "tip": {
                    sb.append(ind(indent))
                            .append("<div class=\"os-tip\">")
                            .append(escHtml(get(pos, 0)))
                            .append("</div>\n");
                    return;
                }
                case "endl": {
                    sb.append(ind(indent)).append("<br>\n");
                    return;
                }
                case "image":
                case "img": {
                    String src = named.containsKey("src") ? named.get("src") : get(pos, 0);
                    String alt = named.getOrDefault("alt", "");
                    sb.append(ind(indent)).append("<img src=\"").append(escAttr(src)).append("\"");
                    if (!alt.isEmpty()) sb.append(" alt=\"").append(escAttr(alt)).append("\"");
                    if (named.containsKey("width"))
                        sb.append(" width=\"").append(escAttr(named.get("width"))).append("\"");
                    if (named.containsKey("height"))
                        sb.append(" height=\"").append(escAttr(named.get("height"))).append("\"");
                    sb.append(">\n");
                    break;
                }
                case "botton":
                case "button": {
                    String label = get(pos, 0);
                    if (label.isEmpty()) label = "按钮";
                    String evName = pos.size() > 1 ? pos.get(1) : named.getOrDefault("event", "");
                    String onclick = buildOnclick(evName);
                    sb.append(ind(indent)).append("<button type=\"button\"");
                    if (!onclick.isEmpty()) {
                        sb.append(" onclick=\"").append(escAttr(onclick)).append("\"");
                    }
                    sb.append(">").append(escHtml(label)).append("</button>\n");
                    break;
                }
                case "text":
                case "p": {
                    sb.append(ind(indent)).append("<p>")
                            .append(escHtml(get(pos, 0))).append("</p>\n");
                    break;
                }
                case "link":
                case "a": {
                    String href = named.containsKey("href") ? named.get("href")
                            : (pos.size() > 1 ? pos.get(1) : "#");
                    sb.append(ind(indent))
                            .append("<a href=\"").append(escAttr(normalizeUrl(href)))
                            .append("\" target=\"_blank\" rel=\"noopener\">")
                            .append(escHtml(get(pos, 0))).append("</a>\n");
                    break;
                }
                case "input": {
                    String type = named.getOrDefault("type", "text");
                    String ph = named.containsKey("placeholder")
                            ? named.get("placeholder") : get(pos, 0);
                    sb.append(ind(indent)).append("<input type=\"").append(escAttr(type)).append("\"");
                    if (!ph.isEmpty()) {
                        sb.append(" placeholder=\"").append(escAttr(ph)).append("\"");
                    }
                    sb.append(">\n");
                    break;
                }
                case "hr": {
                    sb.append(ind(indent)).append("<hr>\n");
                    break;
                }
                case "row":
                case "col":
                case "box": {
                    sb.append(ind(indent)).append("<div>\n");
                    if (c.body != null) renderStatements(c.body, sb, indent + 1, scope);
                    sb.append(ind(indent)).append("</div>\n");
                    break;
                }
                default: {
                    if (c.body != null) {
                        renderStatements(c.body, sb, indent, scope);
                    }
                    // 未知指令静默忽略
                }
            }
        }

        private String resolveArg(Arg a, Map<String, String> scope) {
            if (a.name != null) return a.value;
            if (a.isIdent && scope != null && scope.containsKey(a.value)) {
                return scope.get(a.value);
            }
            return a.value;
        }

        private String buildOnclick(String evName) {
            List<Node> body = events.get(evName);
            if (body == null) return "";
            List<String> parts = new ArrayList<>();
            for (Node n : body) {
                if (!(n instanceof CallNode)) continue;
                CallNode c = (CallNode) n;
                List<String> pos = new ArrayList<>();
                for (Arg a : c.args) if (a.name == null) pos.add(a.value);
                switch (c.name) {
                    case "addhttp":
                    case "openurl":
                    case "goto":
                    case "jump":
                        parts.add("window.open('" + escJs(normalizeUrl(get(pos, 0)))
                                + "','_blank','noopener')");
                        break;
                    case "alert":
                        parts.add("alert('" + escJs(get(pos, 0)) + "')");
                        break;
                    case "print":
                        parts.add("console.log('" + escJs(String.join(" ", pos)) + "')");
                        break;
                    case "settext":
                        parts.add("this.textContent='" + escJs(get(pos, 0)) + "'");
                        break;
                    case "setcolor":
                        parts.add("this.style.color='" + escJs(get(pos, 0)) + "'");
                        break;
                    case "hide":
                        parts.add("this.hidden=true");
                        break;
                    // 事件中未知指令静默忽略
                }
            }
            return String.join(";", parts);
        }

        private String buildUserCss() {
            StringBuilder sb = new StringBuilder();
            for (StyleRule r : styles) {
                List<String> mapped = new ArrayList<>();
                for (String s : r.selector.split(",")) {
                    s = s.trim();
                    if (s.isEmpty()) continue;
                    mapped.add(mapSelector(s));
                }
                if (mapped.isEmpty()) continue;
                sb.append(String.join(", ", mapped)).append(" {\n");
                for (String[] d : r.decls) {
                    sb.append("  ").append(d[0]).append(": ").append(d[1]).append(";\n");
                }
                sb.append("}\n");
            }
            return sb.toString();
        }

        private String buildSsfCss() {
            StringBuilder sb = new StringBuilder();
            for (CallNode c : ssfCalls) {
                if (c.ssfBlocks == null) continue;
                String selector = "body";
                for (Arg a : c.args) {
                    if (a.name == null) { selector = a.value; break; }
                }
                sb.append(selector).append(" {\n");
                for (SsfBlock b : c.ssfBlocks) {
                    for (String[] p : b.props) {
                        String cssProp = SSF_MAP.get(b.name + "." + p[0]);
                        if (cssProp == null) cssProp = SSF_GENERIC.get(p[0]);
                        if (cssProp == null) cssProp = p[0];
                        String cssVal = p[1];
                        if (LENGTH_PROPS.contains(cssProp)
                                && cssVal.matches("^-?\\d+(\\.\\d+)?$")) {
                            cssVal += "px";
                        }
                        sb.append("  ").append(cssProp).append(": ")
                                .append(cssVal).append(";\n");
                    }
                }
                sb.append("}\n");
            }
            return sb.toString();
        }

        /* 选择器映射：st1 → h1，tip → .os-tip，等等 */
        private static final Map<String, String> TAG_MAP = new LinkedHashMap<>();
        static {
            TAG_MAP.put("st1", "h1");
            TAG_MAP.put("st2", "h2");
            TAG_MAP.put("st3", "h3");
            TAG_MAP.put("st4", "h4");
            TAG_MAP.put("st5", "h5");
            TAG_MAP.put("st6", "h6");
            TAG_MAP.put("text", "p");
            TAG_MAP.put("image", "img");
            TAG_MAP.put("botton", "button");
            TAG_MAP.put("link", "a");
            TAG_MAP.put("row", "div");
            TAG_MAP.put("col", "div");
            TAG_MAP.put("box", "div");
        }

        private static String mapSelector(String sel) {
            sel = sel.replaceAll("(?<![\\.#\\w-])tip\\b", ".os-tip");
            for (Map.Entry<String, String> e : TAG_MAP.entrySet()) {
                String key = e.getKey(), tag = e.getValue();
                if (key.equals(tag)) continue;
                sel = sel.replaceAll("(?<![\\.#\\w-])" + Pattern.quote(key) + "\\b", tag);
            }
            return sel;
        }

        /* ---------- 小工具 ---------- */
        private static String ind(int n) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append("  ");
            return sb.toString();
        }
        private static String get(List<String> list, int i) {
            return (i >= 0 && i < list.size()) ? list.get(i) : "";
        }
        private static String normalizeUrl(String u) {
            if (u == null) return "#";
            u = u.trim();
            if (u.isEmpty()) return "#";
            if (u.matches("(?i)^[a-z][a-z0-9+.\\-]*://.*")) return u;
            return "https://" + u;
        }
    }
}