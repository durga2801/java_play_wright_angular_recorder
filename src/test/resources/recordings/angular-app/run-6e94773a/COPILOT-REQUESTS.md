# Copilot generation requests

Recording:
`angular-app/run-6e94773a/recording.json`

## Selenium + Java + Cucumber

Read `recording.json` in this directory and `.github/copilot-instructions.md`.

Generate a complete Selenium + Java 17 + Cucumber implementation in this SAME Maven project.

Requirements:
- preserve recording order exactly
- `input` is the primary semantic identity of every control
- use `identifyBy` candidates according to documented priority
- support Angular Material/CDK overlays and API-backed search/select
- support popup/dialog flows and file upload
- use WebDriverWait; do not use arbitrary Thread.sleep
- generate feature file, step definitions, page/component objects and runner/configuration
- update only the existing pom.xml if dependencies are missing
- do not create another Maven project
- do not invent actions or assertions

## Playwright + Java + Cucumber

Read `recording.json` in this directory and `.github/copilot-instructions.md`.

Generate a complete Playwright + Java 17 + Cucumber implementation in this SAME Maven project.

Requirements:
- preserve recording order exactly
- `input` is the primary semantic identity of every control
- use `identifyBy` candidates according to documented priority
- support Angular Material/CDK overlays and API-backed search/select
- support popup/dialog flows and file upload/file chooser
- use Playwright auto-waiting and semantic locators
- generate feature file, step definitions, page/component objects and runner/configuration
- update only the existing pom.xml if dependencies are missing
- do not create another Maven project
- do not invent actions or assertions
