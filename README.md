# 🤖 Browser AI Agent

> An AI-powered browser automation platform built with **Java, Spring Boot, LangChain4j, and Playwright** that allows Large Language Models to perform complex browser and Salesforce tasks through intelligent tool calling.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/SpringBoot-3.3-green)
![Playwright](https://img.shields.io/badge/Playwright-1.49-blue)
![LangChain4j](https://img.shields.io/badge/LangChain4j-0.36-purple)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

---

## 🚀 Overview

Browser AI Agent combines the reasoning capabilities of modern LLMs with browser automation to execute natural language tasks.

Instead of writing automation scripts, simply describe your goal:

> *"Login to Salesforce, open the Accounts object, create a new account and capture a screenshot."*

The AI agent plans the execution, invokes the appropriate browser tools, interacts with Playwright, streams execution logs in real time, and generates a rich HTML execution report.

---

# ✨ Features

## 🧠 AI Powered Automation

- Natural language browser automation
- Multi-step reasoning
- Function calling using LangChain4j
- Sliding conversation memory
- Supports multiple LLM providers

---

## 🌐 Browser Automation

Powered by Playwright.

Supports:

- Navigation
- Clicking
- Form filling
- Keyboard actions
- JavaScript execution
- Scrolling
- Screenshots
- Page text extraction
- Link extraction
- Smart element search

---

## ☁️ Salesforce Automation

Built-in Salesforce specific tools including:

- Login
- App Launcher
- Global Search
- Setup Navigation
- Object Manager
- Record creation
- Reports
- Dashboards
- Tab management
- Lightning navigation

---

## 🎯 Intelligent Element Locator

Instead of relying on CSS selectors alone, the framework searches elements using multiple strategies:

- Accessibility Roles
- Labels
- Placeholder text
- Visible text
- ARIA attributes
- JavaScript DOM inspection

If no element is found, the AI receives a list of interactive controls and retries intelligently.

---

## 🔎 AI Tool Finder

The agent includes a dedicated Tool Finder.

Instead of expecting the LLM to remember every available tool, it can simply ask:

> "Which tool should I use to open Salesforce Setup?"

The Tool Finder recommends the most relevant browser or Salesforce tools.

---

## 📊 Live Token Usage

Every LLM request tracks:

- Input Tokens
- Output Tokens
- Total Tokens
- LLM Calls
- Estimated Cost

Live updates are streamed to the UI through WebSockets.

---

## 📄 HTML Execution Report

Every run automatically generates a standalone HTML report containing:

- Task Summary
- Timeline
- Success / Failure
- Screenshots
- Token Consumption
- Estimated Cost
- Execution Duration
- Complete Action History

No external assets required.

---

## 💾 Test Case Management

Save frequently used prompts as reusable test cases.

Features include:

- Save Test
- List Tests
- Execute Saved Tests
- Delete Tests

Stored locally as JSON.

---

## ⚡ Real-Time Dashboard

Execution is streamed live using:

- STOMP
- SockJS
- WebSockets

Users can monitor:

- Browser actions
- Tool execution
- AI reasoning progress
- Token usage
- Report generation

---

# 🏗 Architecture

```text
                  +----------------------+
                  |      User UI         |
                  +----------+-----------+
                             |
                        REST / WebSocket
                             |
                             ▼
                  +----------------------+
                  |  Spring Boot API     |
                  +----------+-----------+
                             |
                             ▼
                  +----------------------+
                  | BrowserAgentService  |
                  +----------+-----------+
                             |
                  LangChain4j AI Service
                             |
             +---------------+----------------+
             |                                |
             ▼                                ▼
      Chat Language Model              Chat Memory
             |
             ▼
      Function Calling
             |
      +------+------+----------------+
      |             |                |
      ▼             ▼                ▼
 BrowserTools  SalesforceTools  ToolFinder
      |
      ▼
 Playwright Browser
      |
      ▼
 Chromium
```

---

# 🔄 Execution Flow

```text
User Prompt
      │
      ▼
LLM understands task
      │
      ▼
Selects Tool
      │
      ▼
Playwright executes
      │
      ▼
Browser updates
      │
      ▼
Tool Result
      │
      ▼
LLM decides next action
      │
      ▼
Repeat until complete
      │
      ▼
Generate HTML Report
```

---

# 🧰 Technology Stack

| Layer | Technology |
|---------|------------|
| Language | Java 21 |
| Framework | Spring Boot 3.3 |
| AI | LangChain4j |
| Browser Automation | Playwright |
| Build | Maven |
| Communication | REST + STOMP WebSocket |
| Frontend | HTML + JavaScript |
| Reporting | HTML |
| Storage | JSON |
| Browser | Chromium |

---

# 📁 Project Structure

```
src
├── agent
│   ├── BrowserTools
│   ├── SalesforceTools
│   ├── ToolFinder
│   ├── ElementLocator
│   ├── BrowserAgentService
│   ├── ReportService
│   └── TestService
│
├── config
│   ├── LangChain4jConfig
│   ├── TokenTrackingChatModel
│   └── WebSocketConfig
│
├── web
│   └── AgentController
│
└── resources
    ├── application.properties
    └── static/index.html
```

---

# 📡 REST APIs

| Endpoint | Description |
|-----------|-------------|
| POST `/api/run` | Execute task |
| GET `/api/status` | Current execution status |
| GET `/api/report` | View latest report |
| GET `/api/report/download` | Download report |
| GET `/api/tests` | List saved tests |
| POST `/api/tests` | Save new test |
| POST `/api/tests/{id}/run` | Execute saved test |
| DELETE `/api/tests/{id}` | Delete test |

---

# 📺 Live WebSocket Topics

| Topic | Description |
|---------|------------|
| `/topic/logs` | Live execution logs |
| `/topic/token-usage` | Token consumption |
| `/topic/report-ready` | Report generated |
| `/topic/done` | Task completed |

---

# 🔌 Supported LLM Providers

- Anthropic Claude
- Azure OpenAI
- OpenAI GPT

Provider selection is automatic based on available credentials.

---

# 📈 Future Roadmap

- Browser Session Recording
- Multi-Agent Collaboration
- MCP Server Support
- Vision Models
- Self-Healing Workflows
- Parallel Browser Sessions
- RAG-based Tool Memory
- Cloud Execution
- Scheduled Test Runs

---

# 🤝 Contributing

Contributions are welcome!

Feel free to open issues, suggest improvements, or submit pull requests.

---

# 📜 License

MIT License
