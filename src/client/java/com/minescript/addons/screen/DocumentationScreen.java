package com.minescript.addons.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocumentationScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons/docs");
    private static final String DOCS_BASE_URL = "https://raw.githubusercontent.com/maxuser0/minescript/main/docs/";
    private static final String[] DOC_FILES = {"README.md", "mappings.md", "pyjinn.md"};
    private static final String[] DOC_TITLES = {"Minescript v5.0 docs", "Mappings", "Pyjinn"};
    private static final String[] DOC_DESCRIPTIONS = {
        "Main documentation and getting started guide",
        "Class and field mappings reference",
        "Pyjinn (Python to Java) documentation"
    };

    private final Screen parent;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public DocumentationScreen(Screen parent) {
        super(Component.translatable("text.minescript-addons.docs_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        addRenderableWidget(Button.builder(
            Component.translatable("text.minescript-addons.back"),
            btn -> onClose()
        ).bounds(width - 70, 8, 60, 20).build());

        int centerX = width / 2;
        int centerY = height / 2 - 30;

        addRenderableWidget(Button.builder(
            Component.literal("§eLoading documentation..."),
            btn -> {}
        ).bounds(centerX - 150, centerY, 300, 20).build());

        generateAndOpenHtml();
    }

    private void generateAndOpenHtml() {
        CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder html = new StringBuilder();
                html.append("<!DOCTYPE html>\n");
                html.append("<html lang=\"en\">\n");
                html.append("<head>\n");
                html.append("    <meta charset=\"UTF-8\">\n");
                html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
                html.append("    <title>Minescript Documentation</title>\n");
                html.append("    <style>\n");
                html.append(generateCss());
                html.append("    </style>\n");
                html.append("</head>\n");
                html.append("<body>\n");
                html.append("    <div id=\"page\" class=\"site\">\n");
                html.append("        <main id=\"main\" class=\"site-main\">\n");
                html.append("            <article class=\"post type-page status-publish hentry\">\n");
                html.append("                <header class=\"entry-header alignwide\">\n");
                html.append("                    <h1 class=\"entry-title\">Documentation</h1>\n");
                html.append("                </header>\n");
                html.append("                <div class=\"entry-content\">\n");

                for (int i = 0; i < DOC_FILES.length; i++) {
                    String fileName = DOC_FILES[i];
                    String content;
                    Path cacheFile = getCacheFile(fileName);

                    if (Files.exists(cacheFile)) {
                        content = Files.readString(cacheFile, StandardCharsets.UTF_8);
                    } else {
                        content = downloadDoc(fileName);
                        Files.createDirectories(cacheFile.getParent());
                        Files.writeString(cacheFile, content, StandardCharsets.UTF_8);
                    }

                    String id = fileName.replace(".md", "");
                    html.append("                    <section id=\"").append(id).append("\" class=\"doc-section\">\n");
                    if (i == 0) {
                        html.append("                        <p><a name=\"minescript-v50-docs\"></a></p>\n");
                    }
                    html.append("                        <h2>").append(DOC_TITLES[i]).append("</h2>\n");
                    if (i == 0) {
                        html.append("                        <p><em>View docs for all versions of Minescript on <a href=\"https://github.com/maxuser0/minescript/blob/main/docs/README.md\" target=\"_blank\">GitHub</a>.</em></p>\n");
                    }
                    html.append("                        <p>").append(DOC_DESCRIPTIONS[i]).append("</p>\n");
                    html.append("                        <div class=\"markdown-content\">\n");
                    html.append(markdownToHtml(content));
                    html.append("                        </div>\n");
                    html.append("                    </section>\n");
                }

                html.append("                </div>\n");
                html.append("            </article>\n");
                html.append("        </main>\n");
                html.append("    </div>\n");
                html.append("</body>\n");
                html.append("</html>");

                Path htmlFile = getHtmlFile();
                Files.createDirectories(htmlFile.getParent());
                Files.writeString(htmlFile, html.toString(), StandardCharsets.UTF_8);

                return htmlFile;
            } catch (Exception e) {
                LOGGER.error("Failed to generate documentation HTML: {}", e.getMessage(), e);
                return null;
            }
        }, executor).thenAccept(htmlFile -> {
            if (htmlFile != null) {
                openInBrowser(htmlFile);
            }
            Minecraft.getInstance().execute(() -> minecraft.setScreen(parent));
        });
    }

private String generateCss() {
        return "        * { box-sizing: border-box; margin: 0; padding: 0; }\n" +
               "        :root {\n" +
               "            --bg: #FFFFFF;\n" +
               "            --text: #000000;\n" +
               "            --text-muted: #666666;\n" +
               "            --border: #E1E4E8;\n" +
               "            --link: #647AC5;\n" +
               "            --link-hover: #ED232C;\n" +
               "            --h1-h2: #009300;\n" +
               "            --h3: #647AC5;\n" +
               "            --h4: #ED232C;\n" +
"            --code-bg: #F6F8FA;\n" +
                "            --pre-bg: #F6F8FA;\n" +
                "            --pre-text: #000000;\n" +
                "            --inline-code-color: #000000;\n" +
                "            --content-max-width: 610px;\n" +
                "            --wide-max-width: 1240px;\n" +
                "            --spacing-unit: 20px;\n" +
                "            --spacing-vertical: 30px;\n" +
                "        }\n" +
                "        @media (prefers-color-scheme: dark) {\n" +
                "            :root {\n" +
                "                --bg: #242525;\n" +
                "                --text: #E5E0D8;\n" +
                "                --text-muted: #A09B90;\n" +
                "                --border: #3A3B3B;\n" +
                "                --link: #7796BC;\n" +
                "                --link-hover: #DC4548;\n" +
                "                --h1-h2: #76F06E;\n" +
                "                --h3: #7796BC;\n" +
                "                --h4: #DC4548;\n" +
                "                --code-bg: #272928;\n" +
                "                --pre-bg: #272928;\n" +
                "                --pre-text: #E5E0D8;\n" +
                "                --inline-code-color: #E5E0D8;\n" +
               "            }\n" +
               "        }\n" +
               "        html { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen-Sans, Ubuntu, Cantarell, 'Helvetica Neue', sans-serif; line-height: 1.7; }\n" +
               "        body { margin: 0; font-size: 1.25rem; font-weight: normal; color: var(--text); background-color: var(--bg); text-align: left; -webkit-font-smoothing: antialiased; -moz-osx-font-smoothing: grayscale; }\n" +
               "        #page { max-width: var(--wide-max-width); margin: 0 auto; padding: calc(3 * var(--spacing-vertical)) calc(2 * var(--spacing-unit)); }\n" +
               "        .site-main > * { margin-top: calc(3 * var(--spacing-vertical)); margin-bottom: calc(3 * var(--spacing-vertical)); }\n" +
               "        .site-main > *:first-child { margin-top: 0; }\n" +
               "        .site-main > *:last-child { margin-bottom: 0; }\n" +
               "        .entry-header { margin-top: var(--spacing-vertical); margin-bottom: var(--spacing-vertical); margin-left: auto; margin-right: auto; max-width: var(--content-max-width); }\n" +
               "        .entry-title { font-size: var(--heading--font-size-h1, 4rem); font-weight: 300; line-height: 1.1; color: var(--h1-h2); }\n" +
               "        .entry-content { margin-top: var(--spacing-vertical); margin-bottom: var(--spacing-vertical); margin-left: auto; margin-right: auto; max-width: var(--content-max-width); }\n" +
               "        .entry-content > * { margin-top: var(--spacing-vertical); margin-bottom: var(--spacing-vertical); }\n" +
               "        .entry-content > *:first-child { margin-top: 0; }\n" +
               "        .entry-content > *:last-child { margin-bottom: 0; }\n" +
               "        .doc-section { margin-bottom: calc(3 * var(--spacing-vertical)); }\n" +
               "        .doc-section:last-child { margin-bottom: 0; }\n" +
               "        h1, h2 { color: var(--h1-h2); font-weight: normal; line-height: 1.3; }\n" +
               "        h1 { font-size: var(--heading--font-size-h1, 4rem); margin: 2.5rem 0 1rem; }\n" +
               "        h2 { font-size: var(--heading--font-size-h2, 2.25rem); margin: 2.5rem 0 1rem; }\n" +
               "        h3 { color: var(--h3); font-weight: normal; font-size: var(--heading--font-size-h3, 1.875rem); line-height: 1.3; margin: 2rem 0 0.8rem; }\n" +
               "        h4 { color: var(--h4); font-weight: normal; font-size: var(--heading--font-size-h4, 1.5rem); line-height: 1.3; margin: 1.5rem 0 0.6rem; }\n" +
               "        p { margin: var(--spacing-vertical) 0; }\n" +
               "        a { color: var(--link); text-underline-offset: 3px; text-decoration-skip-ink: all; }\n" +
               "        a:hover { color: var(--link-hover); text-decoration-style: dotted; }\n" +
               "        code { --font-weight: bold; --color: var(--inline-code-color); padding: 0.2em 0.4em; background-color: var(--code-bg); border-radius: 6px; font-size: 85%; font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, 'Liberation Mono', monospace; color: var(--inline-code-color); }\n" +
               "        code.inline-code { background-color: var(--code-bg); color: var(--inline-code-color); padding: 0.2em 0.4em; border-radius: 6px; font-size: 85%; font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, 'Liberation Mono', monospace; }\n" +
               "        pre { box-sizing: border-box; line-height: 1.45; padding: 16px; background-color: var(--pre-bg); border-radius: 6px; font-size: 72%; overflow-x: auto; margin: var(--spacing-vertical) 0; }\n" +
               "        pre > code { display: block; font-size: 100%; font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, 'Liberation Mono', monospace; color: var(--pre-text); background: none; padding: 0; border-radius: 0; }\n" +
               "        ul, ol { margin: var(--spacing-vertical) 0; padding-left: 40px; }\n" +
               "        ul ul, ul ol, ol ul, ol ol { margin: 0.5rem 0; padding-left: 40px; }\n" +
               "        li { margin: 0.5rem 0; }\n" +
               "        blockquote { padding: 0; position: relative; margin: var(--spacing-vertical) 0 var(--spacing-vertical) var(--spacing-horizontal); }\n" +
               "        blockquote p { letter-spacing: 0.05em; font-family: var(--global--font-primary, inherit); font-size: var(--global--font-size-md, 1.25rem); font-style: italic; font-weight: 700; line-height: var(--global--line-height-body, 1.7); }\n" +
               "        blockquote:before { content: \"\\201C\"; font-size: var(--quote--font-size, 1.25rem); line-height: var(--quote--line-height, 1.7); position: absolute; left: calc(-0.5 * var(--global--spacing-horizontal, 25px)); }\n" +
               "        table { width: 100%; border-collapse: collapse; margin: var(--spacing-vertical) 0; font-size: 0.95rem; }\n" +
               "        th, td { border: 1px solid var(--border); padding: 10px 14px; text-align: left; }\n" +
               "        th { background: var(--code-bg); font-weight: 600; }\n" +
               "        tr:nth-child(2n) { background: var(--code-bg); }\n" +
               "        hr { border: none; border-top: 1px solid var(--border); margin: calc(3 * var(--spacing-vertical)) 0; }\n" +
               "        em { font-style: italic; }\n" +
               "        strong { font-weight: 700; }\n" +
               "        .markdown-content h1, .markdown-content h2, .markdown-content h3, .markdown-content h4 { margin-top: 2.5rem; margin-bottom: 1rem; }\n" +
               "        .markdown-content h1:first-child, .markdown-content h2:first-child, .markdown-content h3:first-child, .markdown-content h4:first-child { margin-top: 0; }\n" +
               "        .markdown-content p { margin: 1.5rem 0; }\n" +
               "        .markdown-content ul, .markdown-content ol { margin: 1.5rem 0; padding-left: 40px; }\n" +
               "        .markdown-content ul ul, .markdown-content ul ol, .markdown-content ol ul, .markdown-content ol ol { margin: 0.5rem 0; padding-left: 40px; }\n" +
               "        .markdown-content li { margin: 0.5rem 0; }\n" +
               "        .markdown-content blockquote { margin: 1.5rem 0 1.5rem 25px; }\n" +
               "        .markdown-content pre { margin: 1.5rem 0; }\n" +
               "        .markdown-content table { margin: 1.5rem 0; }\n" +
               "        .markdown-content hr { margin: 3rem 0; }\n" +
               "        .markdown-content a[name] { display: block; position: relative; top: -80px; visibility: hidden; }\n" +
               "        @media (max-width: 481px) { blockquote { padding-left: calc(0.5 * var(--global--spacing-horizontal, 25px)); } blockquote:before { left: 0; } }";
    }

// Track open lists with their type and indentation level
    private static class ListState {
        final String type;
        final int indent;
        ListState(String type, int indent) { this.type = type; this.indent = indent; }
        String type() { return type; }
        int indent() { return indent; }
    }

    private String markdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        String[] lines = markdown.split("\n");
        boolean inCodeBlock = false;
        String codeBlockLang = "";
        StringBuilder codeBlockContent = new StringBuilder();
        
        java.util.Stack<ListState> listStack = new java.util.Stack<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace("\r", "");
            String trimmedLine = line.trim();
            String nextLine = (i + 1 < lines.length) ? lines[i + 1].replace("\r", "") : "";
            String trimmedNext = nextLine.trim();

            // Fenced code blocks (```)
            if (trimmedLine.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    codeBlockLang = trimmedLine.substring(3).trim();
                    codeBlockContent = new StringBuilder();
                } else {
                    inCodeBlock = false;
                    html.append("<pre><code class=\"language-").append(codeBlockLang).append("\">")
                        .append(escapeHtml(codeBlockContent.toString())).append("</code></pre>\n");
                }
                continue;
            }

            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n");
                continue;
            }

            // Indented code blocks (4+ spaces) - but NOT list items
            boolean isListItem = line.startsWith("    - ") || line.startsWith("    * ") || line.matches("^    \\d+\\. ");
            if ((line.startsWith("    ") || line.startsWith("\t")) && !isListItem) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    codeBlockLang = "";
                    codeBlockContent = new StringBuilder();
                    codeBlockContent.append(line.substring(4)).append("\n");
                } else {
                    codeBlockContent.append(line.substring(4)).append("\n");
                }
                // Check if next line is also indented (and not a list item)
                boolean nextIsListItem = nextLine.startsWith("    - ") || nextLine.startsWith("    * ") || nextLine.matches("^    \\d+\\. ");
                if (!nextLine.startsWith("    ") && !nextLine.startsWith("\t") && !nextIsListItem) {
                    inCodeBlock = false;
                    html.append("<pre><code>")
                        .append(escapeHtml(codeBlockContent.toString())).append("</code></pre>\n");
                }
                continue;
            }

            // If we were in an indented code block and this line is not indented, close it
            if (inCodeBlock && codeBlockLang.isEmpty() && (!line.startsWith("    ") && !line.startsWith("\t"))) {
                inCodeBlock = false;
                html.append("<pre><code>")
                    .append(escapeHtml(codeBlockContent.toString())).append("</code></pre>\n");
            }

            // Determine list type and indent for current line
            ListState currentList = null;
            String listContent = null;
            
            if (line.startsWith("- ") || line.startsWith("* ")) {
                currentList = new ListState("ul", 0);
                listContent = line.substring(2);
            } else if (line.matches("^\\d+\\. ")) {
                currentList = new ListState("ol", 0);
                int dotIndex = line.indexOf(". ");
                listContent = line.substring(dotIndex + 2);
            } else if (line.startsWith("    - ") || line.startsWith("    * ")) {
                currentList = new ListState("ul", 4);
                listContent = line.substring(6);
            } else if (line.matches("^    \\d+\\. ")) {
                currentList = new ListState("ol", 4);
                int dotIndex = line.indexOf(". ");
                listContent = line.substring(dotIndex + 2);
            }

            if (currentList != null) {
                // Close lists that are deeper or equal to current indent
                while (!listStack.isEmpty() && listStack.peek().indent() >= currentList.indent()) {
                    ListState closed = listStack.pop();
                    html.append("    </").append(closed.type()).append(">\n");
                    // If we closed a nested list, we're still inside the parent <li>, don't close it
                    if (!listStack.isEmpty() && listStack.peek().indent() < currentList.indent()) {
                        // Parent list continues
                    }
                }

                // If stack is empty or current is nested deeper, open new list
                if (listStack.isEmpty() || currentList.indent() > listStack.peek().indent()) {
                    if (currentList.indent() > 0 && !listStack.isEmpty()) {
                        // Nested list: the parent <li> is still open, just add nested list
                        html.append("    <").append(currentList.type()).append(">\n");
                    } else {
                        // Top-level list
                        html.append("<").append(currentList.type()).append(">\n");
                    }
                    listStack.push(currentList);
                } else if (!listStack.isEmpty() && listStack.peek().type().equals(currentList.type()) && listStack.peek().indent() == currentList.indent()) {
                    // Same level, same type - continue current list (already handled by not closing)
                } else if (!listStack.isEmpty() && listStack.peek().indent() < currentList.indent()) {
                    // Deeper nesting - open nested list
                    html.append("    <").append(currentList.type()).append(">\n");
                    listStack.push(currentList);
                }

                // Add list item
                html.append("    <li>").append(processInline(escapeHtml(listContent))).append("</li>\n");

                // Check if next line continues this list at same level
                ListState nextList = null;
                if (nextLine.startsWith("- ") || nextLine.startsWith("* ")) {
                    nextList = new ListState("ul", 0);
                } else if (nextLine.matches("^\\d+\\. ")) {
                    nextList = new ListState("ol", 0);
                } else if (nextLine.startsWith("    - ") || nextLine.startsWith("    * ")) {
                    nextList = new ListState("ul", 4);
                } else if (nextLine.matches("^    \\d+\\. ")) {
                    nextList = new ListState("ol", 4);
                }

                if (nextList == null || nextList.indent() <= currentList.indent() || 
                    (nextList.indent() == currentList.indent() && !nextList.type().equals(currentList.type()))) {
                    // Close current list (and any deeper lists)
                    while (!listStack.isEmpty() && listStack.peek().indent() >= currentList.indent()) {
                        ListState closed = listStack.pop();
                        html.append("    </").append(closed.type()).append(">\n");
                    }
                }
                continue;
            }

            // Not a list item - close all open lists
            while (!listStack.isEmpty()) {
                ListState closed = listStack.pop();
                html.append("    </").append(closed.type()).append(">\n");
            }

            // Headers with anchor links - close all open lists
            if (line.startsWith("# ")) {
                while (!listStack.isEmpty()) {
                    ListState closed = listStack.pop();
                    html.append("    </").append(closed.type()).append(">\n");
                }
                String text = escapeHtml(line.substring(2));
                String anchor = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
                html.append("<p><a name=\"").append(anchor).append("\"></a></p>\n");
                html.append("<h1>").append(text).append("</h1>\n");
                continue;
            }
            if (line.startsWith("## ")) {
                while (!listStack.isEmpty()) {
                    ListState closed = listStack.pop();
                    html.append("    </").append(closed.type()).append(">\n");
                }
                String text = escapeHtml(line.substring(3));
                String anchor = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
                html.append("<p><a name=\"").append(anchor).append("\"></a></p>\n");
                html.append("<h2>").append(text).append("</h2>\n");
                continue;
            }
            if (line.startsWith("### ")) {
                while (!listStack.isEmpty()) {
                    ListState closed = listStack.pop();
                    html.append("    </").append(closed.type()).append(">\n");
                }
                String text = escapeHtml(line.substring(4));
                String anchor = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
                html.append("<p><a name=\"").append(anchor).append("\"></a></p>\n");
                html.append("<h3>").append(text).append("</h3>\n");
                continue;
            }
            if (line.startsWith("#### ")) {
                while (!listStack.isEmpty()) {
                    ListState closed = listStack.pop();
                    html.append("    </").append(closed.type()).append(">\n");
                }
                String text = escapeHtml(line.substring(5));
                String anchor = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
                html.append("<p><a name=\"").append(anchor).append("\"></a></p>\n");
                html.append("<h4>").append(text).append("</h4>\n");
                continue;
            }

            // Horizontal rule
            if (line.trim().matches("^[-*_]{3,}$")) {
                while (!listStack.isEmpty()) {
                    ListState closed = listStack.pop();
                    html.append("    </").append(closed.type()).append(">\n");
                }
                html.append("<hr>\n");
                continue;
            }

            // Blockquote
            if (line.startsWith("> ")) {
                while (!listStack.isEmpty()) {
                    ListState closed = listStack.pop();
                    html.append("    </").append(closed.type()).append(">\n");
                }
                html.append("<blockquote><p>").append(processInline(escapeHtml(line.substring(2)))).append("</p></blockquote>\n");
                continue;
            }

            // Table
            if (line.contains("|") && line.startsWith("|") && line.endsWith("|")) {
                closeLists(html, listStack);
                
                String[] cells = line.substring(1, line.length() - 1).split("\\|");
                boolean isHeader = false;
                if (i + 1 < lines.length && lines[i + 1].trim().matches("^\\|?[-:|\\s]+\\|?$")) {
                    isHeader = true;
                }
                if (!html.toString().endsWith("<table>\n") && !html.toString().endsWith("<table>")) {
                    html.append("<table>\n");
                }
                html.append(isHeader ? "    <thead><tr>\n" : "    <tbody><tr>\n");
                for (String cell : cells) {
                    html.append("        <").append(isHeader ? "th" : "td").append(">")
                        .append(processInline(escapeHtml(cell.trim()))).append("</").append(isHeader ? "th" : "td").append(">\n");
                }
                html.append("    </tr></").append(isHeader ? "thead" : "tbody").append(">\n");
                if (isHeader) {
                    i++; // Skip separator row
                }
                if (!nextLine.contains("|") || !nextLine.startsWith("|") || !nextLine.endsWith("|")) {
                    html.append("</table>\n");
                }
                continue;
            }

            // Empty line
            if (line.trim().isEmpty()) {
                closeLists(html, listStack);
                
                html.append("<p></p>\n");
                continue;
            }

            // Regular paragraph
            closeLists(html, listStack);
            html.append("<p>").append(processInline(escapeHtml(line))).append("</p>\n");
        }

        closeLists(html, listStack);
        return html.toString();
    }

    private void closeLists(StringBuilder html, java.util.Stack<?> listStack) {
        while (!listStack.isEmpty()) {
            ListState closed = (ListState) listStack.pop();
            html.append("    </").append(closed.type()).append(">\n");
        }
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&").replace("<", "<").replace(">", ">");
    }

    private String processInline(String text) {
        // Handle escaped backticks: \`code\` -> `code`
        text = text.replaceAll("\\\\(`)", "$1");
        // Bold: **text** -> <strong>text</strong>
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        // Italic: *text* -> <em>text</em> (but not inside **)
        text = text.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
        // Inline code: `code` -> <code class="inline-code">code</code> (uses base text color)
        text = text.replaceAll("`([^`]+)`", "<code class=\"inline-code\">$1</code>");
        // Links: [text](url) -> <a href="url" target="_blank">text</a>
        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\" target=\"_blank\">$1</a>");
        return text;
    }

    private String downloadDoc(String fileName) throws IOException, InterruptedException {
        String url = DOCS_BASE_URL + fileName;
        LOGGER.info("Downloading documentation from: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "MinescriptAddons/1.0")
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return response.body();
        } else {
            throw new IOException("HTTP " + response.statusCode() + ": " + url);
        }
    }

    private Path getCacheFile(String fileName) {
        Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("minescript-addons").resolve("docs").resolve(fileName);
    }

    private Path getHtmlFile() {
        Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("minescript-addons").resolve("docs").resolve("documentation.html");
    }

    private void openInBrowser(Path file) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            Runtime rt = Runtime.getRuntime();
            String uri = file.toUri().toString();
            if (os.contains("win")) {
                rt.exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", uri});
            } else if (os.contains("mac")) {
                rt.exec(new String[]{"open", uri});
            } else {
                rt.exec(new String[]{"xdg-open", uri});
            }
        } catch (Exception e) {
            LOGGER.error("Failed to open HTML file: {}", e.getMessage());
        }
    }

    @Override
    public void onClose() {
        executor.shutdown();
        minecraft.setScreen(parent);
    }
}