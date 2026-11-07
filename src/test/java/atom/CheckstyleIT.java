package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The static-analysis atom: one reader over checkstyle, PMD, SpotBugs and cppcheck reports.
 *
 * <p>The gate is error-level findings only, so most of these cases are about which severity a tool's own attribute
 * maps to — and about a report nobody could parse, which is not the same as a report with no violations.
 */
class CheckstyleIT {

    private static final String TWO_ERRORS_AND_A_WARNING = """
            <?xml version="1.0"?>
            <checkstyle version="10.0">
              <file name="src/main/java/A.java">
                <error line="3" severity="error" message="Missing a Javadoc comment."/>
                <error line="7" severity="error" message="Line is longer than 120 characters."/>
                <error line="9" severity="warning" message="Parameter x should be final."/>
              </file>
            </checkstyle>
            """;

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("two checkstyle errors trip the default gate (maxErrors=0), and the report lists the findings")
    void defaultGate() throws Exception {
        AtomResult run = atom.file("build/reports/checkstyle/main.xml", TWO_ERRORS_AND_A_WARNING)
                .input("pattern", "**/checkstyle-result.xml, **/checkstyle/*.xml")
                .input("label", "Static analysis")
                .run();

        run.assertRefused();
        assertEquals("failure", run.status());
        assertEquals("2", run.field("errors"));
        assertEquals("1", run.field("warnings"));
        assertTrue(run.exists("out/static-analysis/index.html"), "no report");
        assertTrue(run.file("out/static-analysis/index.html").contains("Line is longer"),
                "report misses a finding:\n" + run.file("out/static-analysis/index.html"));
    }

    @Test
    @DisplayName("raising maxErrors turns the same input green")
    void raisedLimit() throws Exception {
        AtomResult run = atom.file("reports/checkstyle.xml",
                        "<checkstyle version=\"10.0\"><file name=\"A.java\"><error line=\"1\" severity=\"error\""
                                + " message=\"e\"/></file></checkstyle>")
                .input("pattern", "reports/checkstyle.xml")
                .input("maxErrors", "5")
                .run();

        run.assertOk();
        assertEquals("1", run.field("errors"));
    }

    @Test
    @DisplayName("PMD priorities map to error (1), warning (2) and info (3)")
    void pmdPriorities() throws Exception {
        AtomResult run = atom.file("reports/pmd.xml", """
                        <pmd version="7.0"><file name="B.java">
                          <error line="1" priority="1" message="high"/>
                          <error line="2" priority="2" message="medium"/>
                          <error line="3" priority="3" message="low"/>
                        </file></pmd>
                        """)
                .input("pattern", "reports/pmd.xml")
                .input("maxErrors", "2")
                .run();

        run.assertOk();
        assertEquals("1", run.field("errors"), "pmd priority 1 is an error");
        assertEquals("1", run.field("warnings"), "pmd priority 2 is a warning");
        assertEquals("1", run.field("infos"), "pmd priority 3 is info");
    }

    @Test
    @DisplayName("a priority-1 SpotBugs bug trips the default gate and names the rule in the report")
    void spotbugsPriority() throws Exception {
        AtomResult run = atom.file("reports/spotbugsXml.xml", """
                        <BugCollection><BugInstance type="NP_NULL_ON_SOME_PATH" category="CORRECTNESS" priority="1">
                          <Class classname="demo.C"/><SourceLine sourcepath="demo/C.java" start="12"/></BugInstance>
                        </BugCollection>
                        """)
                .input("pattern", "reports/spotbugsXml.xml")
                .input("maxErrors", "0")
                .run();

        run.assertRefused();
        assertTrue(run.file("out/static-analysis/index.html").contains("NP_NULL_ON_SOME_PATH"),
                "spotbugs rule not in the report:\n" + run.file("out/static-analysis/index.html"));
    }

    @Test
    @DisplayName("no report at all is a clear error")
    void noReports() throws Exception {
        AtomResult run = atom.input("{}").run();

        run.assertRefused();
        assertTrue(String.valueOf(run.document().getOrDefault("message", "")).contains("no report matched"),
                "message not readable: " + run.document());
    }

    @Test
    @DisplayName("a report nobody could parse is not a report without violations")
    void truncatedReport() throws Exception {
        AtomResult run = atom.file("good.xml",
                        "<?xml version=\"1.0\"?><checkstyle version=\"10.0\"><file name=\"a.py\">"
                                + "<error line=\"1\" severity=\"warning\" message=\"w\"/></file></checkstyle>")
                .file("half.xml", "<?xml version=\"1.0\"?><checkstyle version=\"10.0\"><file name=\"b.py\">"
                        + "<error line=\"1\" sever")
                .input("pattern", "*.xml")
                .input("maxErrors", "0")
                .run();

        run.assertRefused();
        assertEquals("40003", String.valueOf(run.document().get("errorCode")), "expected errorCode 40003: " + run.document());
        assertTrue(String.valueOf(run.document().getOrDefault("message", "")).contains("half.xml"),
                "the failure must name the file: " + run.document());
    }

    @Test
    @DisplayName("allowUnreadableReports lets a truncated report through")
    void truncatedReportAllowed() throws Exception {
        AtomResult run = atom.file("good.xml",
                        "<?xml version=\"1.0\"?><checkstyle version=\"10.0\"><file name=\"a.py\">"
                                + "<error line=\"1\" severity=\"warning\" message=\"w\"/></file></checkstyle>")
                .file("half.xml", "<?xml version=\"1.0\"?><checkstyle version=\"10.0\"><file name=\"b.py\">"
                        + "<error line=\"1\" sever")
                .input("pattern", "*.xml")
                .input("maxErrors", "0")
                .input("allowUnreadableReports", "true")
                .run();

        run.assertOk();
    }

    @Test
    @DisplayName("cppcheck's attribute names mean the same thing, and only the counts reach the outputs")
    void cppcheck() throws Exception {
        AtomResult run = atom.file("cppcheck.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <results version="2"><cppcheck version="2.13"/><errors>
                        <error id="nullPointer" severity="error" msg="Null pointer dereference: p" file0="src/main.c" line="42"/>
                        <error id="unusedVariable" severity="style" msg="Unused variable: x" file0="src/main.c" line="7"/>
                        <error id="arrayIndexOutOfBounds" severity="warning" msg="Index out of bounds" file0="src/util.c" line="13"/>
                        </errors></results>
                        """)
                .input("pattern", "*.xml")
                .input("maxErrors", "0")
                .run();

        run.assertRefused();
        assertEquals("3", run.field("total"), "cppcheck total");
        assertEquals("1", run.field("errors"), "cppcheck errors");
        assertEquals("1", run.field("warnings"), "cppcheck warnings");
        assertEquals("1", run.field("infos"), "cppcheck infos (style -> info)");
        // the detail lives in the report, not in output.json: a failed result only carries the counts
        String page = run.file("out/static-analysis/index.html");
        assertTrue(page.contains("src/main.c"), "file0 was not used as the location");
        assertTrue(page.contains("Null pointer"), "msg was not used as the message");
    }
}
