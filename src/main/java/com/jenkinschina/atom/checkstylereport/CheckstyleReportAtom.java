package com.jenkinschina.atom.checkstylereport;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * checkstyle / pmd / spotbugs as one atom: read the XML they already wrote, count by severity, publish it.
 *
 * <p>The three formats share {@code file → error/warning/bugInstance}, so one reader covers the family. The gate counts
 * <em>errors</em> only by default: a warning budget is a team decision, a failing error is not.
 */
public class CheckstyleReportAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("pattern", "**/checkstyle-result.xml, **/pmd.xml, **/spotbugsXml.xml");
        String label = context.getParam("label", "Static analysis");
        String outDir = context.getParam("outputDir", "out/static-analysis");
        int maxErrors = Math.max(0, context.getIntParam("maxErrors", 0));

        List<Path> files = find(workspace, pattern);
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "no report matched " + pattern);
        }

        Map<String, Integer> bySeverity = new TreeMap<>();
        Map<String, Integer> byFile = new LinkedHashMap<>();
        List<Finding> findings = new ArrayList<>();
        int total = 0;

        for (Path file : files) {
            for (Finding finding : parse(file)) {
                findings.add(finding);
                total++;
                bySeverity.merge(finding.severity(), 1, Integer::sum);
                byFile.merge(finding.file(), 1, Integer::sum);
            }
        }

        int errors = bySeverity.getOrDefault("error", 0) + bySeverity.getOrDefault("high", 0)
                + bySeverity.getOrDefault("critical", 0);
        int warnings = bySeverity.getOrDefault("warning", 0) + bySeverity.getOrDefault("medium", 0);
        int infos = total - errors - warnings;

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"),
                render(label, pattern, files.size(), total, errors, warnings, infos, byFile, findings),
                StandardCharsets.UTF_8);

        context.log("[static-analysis] " + total + " finding(s): " + errors + " error, " + warnings
                + " warning, " + infos + " info from " + files.size() + " file(s)");

        AtomResult result = AtomResult.success()
                .data("total", String.valueOf(total))
                .data("errors", String.valueOf(errors))
                .data("warnings", String.valueOf(warnings))
                .data("infos", String.valueOf(infos))
                .report("report", label, dir.toString(), "index.html");

        if (errors > maxErrors) {
            return AtomResult.failure(errors + " error-level finding(s), the limit is " + maxErrors)
                    .data("total", String.valueOf(total))
                    .data("errors", String.valueOf(errors))
                    .data("warnings", String.valueOf(warnings))
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    /** checkstyle/pmd: <file name="…"><error line severity message/></file>; spotbugs: <BugInstance type category priority>. */
    private static List<Finding> parse(Path file) {
        List<Finding> findings = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(file.toFile());
            Element root = document.getDocumentElement();
            if (root == null) {
                return findings;
            }

            NodeList bugInstances = root.getElementsByTagName("BugInstance");
            if (bugInstances.getLength() > 0) { // spotbugs
                for (int i = 0; i < bugInstances.getLength(); i++) {
                    Element bug = (Element) bugInstances.item(i);
                    String priority = bug.getAttribute("priority"); // 1 high … 3 low
                    String severity = switch (priority) {
                        case "1" -> "high";
                        case "2" -> "medium";
                        default -> "low";
                    };
                    String where = firstSourceLine(bug);
                    findings.add(new Finding(where, severity, bug.getAttribute("type"),
                            abbreviate(bug.getAttribute("category"))));
                }
                return findings;
            }

            NodeList errors = root.getElementsByTagName("error");
            for (int i = 0; i < errors.getLength(); i++) {
                Element error = (Element) errors.item(i);
                String where = error.getAttribute("file");
                if (where.isBlank()) {
                    org.w3c.dom.Node parent = error.getParentNode();
                    while (parent instanceof Element element && where.isBlank()) {
                        where = element.getAttribute("name");
                        parent = parent.getParentNode();
                    }
                }
                String severity = error.getAttribute("severity");
                if (severity.isBlank()) {
                    severity = error.getAttribute("priority");
                }
                String message = error.getAttribute("message");
                if (message.isBlank()) {
                    message = error.getAttribute("rule");
                }
                findings.add(new Finding(where, normalise(severity), "line " + error.getAttribute("line"),
                        abbreviate(message)));
            }
        } catch (Exception ignored) {
            // an unreadable report is not a build failure; the next one may be fine
        }
        return findings;
    }

    private static String firstSourceLine(Element bug) {
        NodeList sources = bug.getElementsByTagName("SourceLine");
        if (sources.getLength() > 0) {
            Element source = (Element) sources.item(0);
            return source.getAttribute("sourcepath") + ":" + source.getAttribute("start");
        }
        NodeList classes = bug.getElementsByTagName("Class");
        return classes.getLength() > 0 ? ((Element) classes.item(0)).getAttribute("classname") : "?";
    }

    private static String normalise(String severity) {
        String value = severity == null ? "" : severity.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "1", "high", "error", "blocker" -> "error";
            case "2", "medium", "warning", "major" -> "warning";
            case "3", "4", "low", "info", "minor", "ignore" -> "info";
            default -> value.isEmpty() ? "info" : value;
        };
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 160 ? value.substring(0, 160) + "…" : value;
    }

    private static List<Path> find(Path workspace, String pattern) throws IOException {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : pattern.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:"
                        + (trimmed.contains("/") ? trimmed : "**/" + trimmed)));
            }
        }
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(workspace, 12)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> matchers.stream().anyMatch(m -> m.matches(workspace.relativize(path))
                            || m.matches(path.getFileName())))
                    .sorted()
                    .limit(500)
                    .forEach(found::add);
        }
        return found;
    }

    private static String render(String label, String pattern, int files, int total, int errors, int warnings,
                                 int infos, Map<String, Integer> byFile, List<Finding> findings) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(escape(label)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:20px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px 16px;min-width:100px}")
                .append(".card b{display:block;font-size:22px}.card span{font-size:12px;color:#6b7280}")
                .append(".bad b{color:#b91c1c}.warn b{color:#b45309}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3;vertical-align:top}")
                .append("th{background:#fafbfc;color:#6b7280}code{font-family:ui-monospace,Menlo,monospace}")
                .append("</style></head><body>");
        html.append("<h1>").append(escape(label)).append("</h1><p class=\"meta\">").append(files)
                .append(" report(s) · <code>").append(escape(pattern)).append("</code></p><div class=\"cards\">");
        card(html, "total", total, "");
        card(html, "errors", errors, errors > 0 ? "bad" : "");
        card(html, "warnings", warnings, warnings > 0 ? "warn" : "");
        card(html, "info", infos, "");
        html.append("</div><table><tr><th>Where</th><th>Severity</th><th>Rule</th><th>Message</th></tr>");
        int shown = 0;
        for (Finding finding : findings) {
            if (shown++ >= 300) {
                break;
            }
            html.append("<tr><td><code>").append(escape(finding.file())).append("</code></td><td>")
                    .append(escape(finding.severity())).append("</td><td>").append(escape(finding.rule()))
                    .append("</td><td>").append(escape(finding.message())).append("</td></tr>");
        }
        html.append("</table>");
        if (findings.size() > 300) {
            html.append("<p class=\"meta\">").append(findings.size() - 300).append(" more not shown</p>");
        }
        return html.append("</body></html>").toString();
    }

    private static void card(StringBuilder html, String label, int value, String css) {
        html.append("<div class=\"card ").append(css).append("\"><b>").append(value).append("</b><span>")
                .append(label).append("</span></div>");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record Finding(String file, String severity, String rule, String message) {}
}
