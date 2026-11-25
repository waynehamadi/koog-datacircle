# Datacircle tools for Koog

Find the best GTM APIs. We benchmark all the GTM APIs and we put the results on our website.

Our website: https://datacircle.dev

This library gives [Koog](https://github.com/JetBrains/koog) a `ToolSet` of four tools that call Datacircle's open API, https://api.datacircle.dev/v1. You add them to an agent's `ToolRegistry` beside your app's own tools. You need no key and no account.

| Tool | What the tool gives | Call |
| --- | --- | --- |
| `datacircle_list_apis` | Every GTM API on Datacircle, with the API's slug, number of plans, number of providers and best plan | `GET /apis` |
| `datacircle_best_plans` | One API's plans, sorted by score out of 100, best first. Each plan comes with a rank and a price per 1,000 calls | `GET /apis/{slug}/best` |
| `datacircle_search_plans` | Plans that match a provider or plan name, on all APIs or on one API. You can sort them by score, provider or price | `GET /plans` |
| `datacircle_provider` | The APIs a provider sells, and the provider's rank on each API | `GET /providers/{slug}` |

## Install

The library is on [JitPack](https://jitpack.io/#waynehamadi/koog-datacircle). With Gradle:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("ai.koog:koog-agents:1.3.0")
    implementation("com.github.waynehamadi:koog-datacircle:0.1.0")
}
```

With Maven:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.waynehamadi</groupId>
    <artifactId>koog-datacircle</artifactId>
    <version>0.1.0</version>
</dependency>
```

The library uses the Koog your app already has, 1.0 or later, on Java 17 or later. It adds no other dependency.

## Use the tools in an agent

```kotlin
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import dev.datacircle.koog.DatacircleTools

val agent = AIAgent(
    promptExecutor = simpleOpenAIExecutor(System.getenv("OPENAI_API_KEY")),
    llmModel = OpenAIModels.Chat.GPT4o,
    systemPrompt = "Answer from the Datacircle tools.",
    toolRegistry = ToolRegistry {
        tools(DatacircleTools())
        // your app's own tools
    },
)

val answer = agent.run("Who are the 3 best providers for the email-finder API?")
```

From Java:

```java
ToolRegistry toolRegistry = ToolRegistry.builder()
        .tools(new DatacircleTools())
        .build();
```

## What the tools answer

Each tool answers in short text, one API or plan a line, with each plan's page on datacircle.dev. For example, `datacircle_best_plans` with `{"api": "email-finder", "limit": 3}` answers:

```text
Employee email (email-finder): the best 3 of 5,444 plans. Sheet: https://datacircle.dev/email-finder-api-pricing
1. Emelia, 100,000 credits per month: score 76.6/100, $4.99 per 1,000 calls, $0 per 1,000 not found, monthly. https://datacircle.dev/find-email-apis/emelia/19-monthly
2. ...
```

When a call fails, the tool answers with a line that starts with `Error:` and says what to do, for example when a slug does not exist. It does not throw.

Koog sends every parameter of a tool to the model. A parameter that has a default can be `null`, and then the tool uses its default.

## Options

`DatacircleTools(baseUrl, timeout)` sets another base URL or another timeout. The defaults are `https://api.datacircle.dev/v1` and 30 seconds.

The API allows 60 calls a minute from one address. Its spec is at https://api.datacircle.dev/v1/openapi.json.

## Tests

```bash
./gradlew test                    # offline, on a local server
DATACIRCLE_LIVE=1 ./gradlew test  # also calls each tool on the live API
./gradlew test -Pkoog=1.0.0       # on another Koog
```

## License

MIT
