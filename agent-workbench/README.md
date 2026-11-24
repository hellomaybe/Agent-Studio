# Agent workbench

This module is a standalone JDK 17 application with a browser-based executable graph editor, PC and mobile views, and five business tools: quote calculation, batch rename preview, packing quantities, subtitle timeline checks and title validation. Spring AI 2.0.3 connects a server-configured OpenAI-compatible chat model and optional loopback embedding service. H2 stores versioned graphs, retrieval indices, private workspace results and usage estimates.

From the repository root:

```sh
mvn -f agent-workbench/pom.xml verify
cd agent-workbench
java -jar target/agent-workbench-0.2.0.jar site
```

Before starting, set `AGENT_DEMO_DB_PASSWORD` to a strong private database password in the server environment. Keep it for restarts. The default JDBC file is under `data/` in the working directory. Open [the local graph editor](http://127.0.0.1:29479/open-source/lab/agent/).

| Server setting | Meaning |
| --- | --- |
| `AGENT_DEMO_BASE_URL`, `AGENT_DEMO_MODEL`, `AGENT_DEMO_API_KEY` | Dedicated provider base URL, model or endpoint ID, and API key. Remote providers require HTTPS. |
| `AGENT_DEMO_DAILY_CNY`, `AGENT_DEMO_INPUT_PER_MILLION`, `AGENT_DEMO_OUTPUT_PER_MILLION` | Global UTC daily spending limit and estimated CNY per million input and output tokens. Configure positive values for a remote paid provider. |
| `AGENT_DEMO_EMBED_URL`, `AGENT_DEMO_EMBED_MODEL` | Optional loopback embedding service and name. Otherwise retrieval can use local sparse vectors. |
| `AGENT_DEMO_JDBC`, `AGENT_DEMO_DB_PASSWORD` | H2 JDBC URL and password. Use an independent directory and database. |
| `AGENT_DEMO_BIND`, `AGENT_DEMO_PORT`, `AGENT_DEMO_ORIGIN` | Defaults to loopback port 29479. Public deployments require the exact public HTTPS origin and a reverse proxy. |

Choose a feature, edit the nodes and outgoing edges, save the draft and publish a version. The tool node can bind original form data or upstream model output. To run without a chat model, connect the start node directly to the tool node and publish. The PC and mobile views execute the published version. Each trace contains the real node path and references the immutable graph version used by that run.

For a model-backed run, configure a dedicated server connection and spend limit before publishing. The model node can request the selected business function using native tool calling if the provider supports it. The retrieval node accepts workspace text documents and exposes the selected chunks. Validation can return control to the model under a maximum number of calls. Usage is reserved before each call and reconciled using returned token counts. Amounts are estimates; no customer payment is collected.

The community workspace lasts one hour. Access is scoped by an HttpOnly cookie. Server API keys, provider accounts, model weights, data files and production configuration are supplied by the operator. Read [third-party notices](../THIRD_PARTY_NOTICES.md) before redistributing binary dependencies.

`verify-agent.py` is an opt-in live integration check for a local deployment with a real model and nonzero test prices. It sends model requests and may incur provider charges.
