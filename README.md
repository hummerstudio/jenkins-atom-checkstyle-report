# jenkins-atom-checkstyle

Static analysis report — port of **checkstyle / pmd / spotbugs** (约 30,000（合计）) as an atom.

The three tools share `file → finding`, so one reader covers the family. Only error-level findings fail the
build by default: a warning budget is a team decision.

## Build and test

```bash
mvn verify
```
