package com.kkoemets.subscriptionautocomplete.context

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageAdaptersTest {
  @Test
  fun `jvm languages capture enclosing declaration imports and outline`() {
    val text = """
      package example
      import java.time.Instant

      class Clock {
        fun now(): Instant = Instant.
      }
    """.trimIndent()
    val fragments = collect("Clock.kt", "kt", text, text.indexOf("Instant.") + 8)

    assertLabels(fragments, "Enclosing JVM declaration", "Package and imports", "Nearby declarations")
  }

  @Test
  fun `typescript captures block imports and declarations`() {
    val text = """
      import { User } from './user'
      interface Result { ok: boolean }
      export function greet(user: User) {
        return user.
      }
    """.trimIndent()
    val fragments = collect("main.ts", "ts", text, text.indexOf("user.") + 5)

    assertLabels(fragments, "Enclosing JavaScript or TypeScript block", "Imports and exports", "Types and declarations")
  }

  @Test
  fun `python captures enclosing indentation block`() {
    val text = """
      from app.models import User

      class Service:
        def greet(self, user: User) -> str:
          return user.
    """.trimIndent()
    val fragments = collect("service.py", "py", text, text.indexOf("user.") + 5)

    assertLabels(fragments, "Enclosing Python declaration", "Python imports")
  }

  @Test
  fun `docker compose captures only current section and outline`() {
    val text = """
      services:
        api:
          image: example/api
          depends_on:
            - db
          environment:
            API_TOKEN: secret
        db:
          image: postgres
      volumes:
        data:
    """.trimIndent()
    val fragments = collect("docker-compose.yml", "yml", text, text.indexOf("depends_on"))

    assertLabels(fragments, "Current Docker Compose section", "Compose services and resources")
    assertTrue(fragments.first { it.label == "Current Docker Compose section" }.content.length < text.length)
  }

  @Test
  fun `plain yaml captures a bounded current section`() {
    val text = """
      application:
        server:
          port: 8080
          compression:
            enabled: true
        client:
          timeout: 10s
      unrelated:
        value: true
    """.trimIndent()
    val fragments = collect("application.yml", "yml", text, text.indexOf("enabled"))

    assertLabels(fragments, "Current YAML section", "YAML structure")
    assertFalse(fragments.first { it.label == "Current YAML section" }.content.contains("unrelated:"))
  }

  @Test
  fun `dockerfile captures only the active stage plus global stage and variable outline`() {
    val text = """
      ARG NODE_VERSION=22
      FROM node:${'$'}NODE_VERSION AS build
      WORKDIR /app
      COPY package.json ./
      RUN npm ci \\
        && npm run build

      FROM nginx:alpine AS runtime
      ENV APP_ENV=production
      COPY --from=build /app/dist /usr/share/nginx/html
      RUN chmod -R a+r /usr/share/nginx/html
    """.trimIndent()
    val fragments = collect("Dockerfile", "", text, text.indexOf("chmod"))
    val stage = fragments.first { it.label == "Current Dockerfile stage" }.content
    val outline = fragments.first { it.label == "Dockerfile stages and variables" }.content

    assertFalse(stage.contains("npm ci"))
    assertTrue(stage.contains("FROM nginx:alpine AS runtime"))
    assertTrue(stage.contains("COPY --from=build"))
    assertTrue(outline.contains("ARG NODE_VERSION=22"))
    assertTrue(outline.lines().count { it.startsWith("FROM") } == 2)
  }

  @Test
  fun `dockerfile adapter recognizes common alternate names`() {
    val text = "FROM alpine\nRUN echo ok"

    assertLabels(collect("Dockerfile.dev", "dev", text, text.length), "Current Dockerfile stage")
    assertLabels(collect("service.dockerfile", "dockerfile", text, text.length), "Current Dockerfile stage")
  }

  @Test
  fun `sql captures statement and referenced ddl`() {
    val text = """
      CREATE TABLE users (id BIGINT, name TEXT);
      SELECT u.
      FROM users u
      WHERE u.id = 1;
    """.trimIndent()
    val fragments = collect("query.sql", "sql", text, text.indexOf("u.") + 2)

    assertLabels(fragments, "Current SQL statement", "Referenced table definitions")
  }

  @Test
  fun `html reports ancestor path`() {
    val text = "<main><section class=\"card\"><button type=\"button\"></button></section></main>"
    val fragments = collect("index.html", "html", text, text.indexOf("type"))

    assertTrue(fragments.first { it.label == "Element ancestry" }.content.contains("main > section > button"))
  }

  @Test
  fun `json captures bounded current object`() {
    val text = """{"scripts":{"test":"gradle test","build":"gradle build"},"private":true}"""
    val fragments = collect("package.json", "json", text, text.indexOf("gradle test"))

    assertLabels(fragments, "Current JSON object", "JSON keys")
  }

  @Test
  fun `shell css config and markdown have focused adapters`() {
    assertLabels(
      collect("deploy.sh", "sh", "source ./lib.sh\nrun() {\n  echo ok\n}", 30),
      "Enclosing shell function",
      "Sourced scripts",
    )
    assertLabels(
      collect("main.scss", "scss", "${'$'}gap: 8px;\n.card {\n  color: red;\n}", 28),
      "Current style rule",
      "Style variables and mixins",
    )
    assertLabels(
      collect("app.toml", "toml", "[server]\nhost = 'localhost'\nport = 80", 20),
      "Current configuration section",
      "Configuration keys",
    )
    assertLabels(
      collect("README.md", "md", "# Intro\nText\n## Usage\nExample", 25),
      "Current Markdown section",
    )
  }

  private fun collect(fileName: String, extension: String, text: String, offset: Int): List<ContextFragment> =
    LanguageAdapterRegistry.collect(ContextInput(extension, fileName, extension, text, offset))

  private fun assertLabels(fragments: List<ContextFragment>, vararg labels: String) {
    val actual = fragments.map(ContextFragment::label)
    labels.forEach { assertTrue(it in actual, "Expected $it in $actual") }
  }
}
