package com.justnels.agenticdroid.workspace

import java.io.File

/**
 * Defines starter project templates for quick scaffolding.
 */
enum class ProjectTemplate(
    val id: String,
    val title: String,
    val description: String,
    val projectType: ProjectType
) {
    EMPTY(
        id = "empty",
        title = "Empty Project",
        description = "A blank workspace with a README.md",
        projectType = ProjectType.CUSTOM
    ),
    VANILLA_WEB(
        id = "vanilla_web",
        title = "HTML5 & JavaScript Web App",
        description = "Modern responsive HTML5, CSS3, and JavaScript app with live preview",
        projectType = ProjectType.WEB
    ),
    VITE_REACT(
        id = "vite_react",
        title = "Vite + React Web App",
        description = "Fast React frontend with Vite bundler and hot-module reload",
        projectType = ProjectType.WEB
    ),
    PYTHON_WEB(
        id = "python_web",
        title = "Python Web App (Flask)",
        description = "Lightweight Python web application with Flask and HTML templates",
        projectType = ProjectType.PYTHON
    ),
    PYTHON_CLI(
        id = "python_cli",
        title = "Python CLI Script",
        description = "Modular Python CLI utility with argument parsing and structure",
        projectType = ProjectType.PYTHON
    ),
    ANDROID_STARTER(
        id = "android_starter",
        title = "Android Application Starter",
        description = "Basic Gradle Android app with MainActivity and manifest",
        projectType = ProjectType.ANDROID
    );

    fun scaffold(projectDir: File, projectName: String) {
        projectDir.mkdirs()
        when (this) {
            EMPTY -> {
                File(projectDir, "README.md").writeText(
                    """
                    # $projectName
                    
                    Welcome to your mobile development workspace in AgenticDroid!
                    """.trimIndent()
                )
            }
            VANILLA_WEB -> {
                File(projectDir, "index.html").writeText(
                    """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>$projectName</title>
                        <link rel="stylesheet" href="style.css">
                    </head>
                    <body>
                        <main class="container">
                            <header>
                                <h1>$projectName</h1>
                                <p class="subtitle">Built with AgenticDroid Mobile Development Environment</p>
                            </header>
                            
                            <section class="card">
                                <h2>Interactive Counter</h2>
                                <div class="counter-display" id="count">0</div>
                                <div class="actions">
                                    <button id="dec-btn" class="btn btn-secondary">-</button>
                                    <button id="reset-btn" class="btn btn-outline">Reset</button>
                                    <button id="inc-btn" class="btn btn-primary">+</button>
                                </div>
                            </section>

                            <section class="card">
                                <h2>Quick Notes</h2>
                                <div class="note-input-row">
                                    <input type="text" id="note-input" placeholder="Type a quick note..." class="input">
                                    <button id="add-note-btn" class="btn btn-primary">Add</button>
                                </div>
                                <ul id="notes-list" class="notes-list"></ul>
                            </section>
                        </main>
                        <script src="app.js"></script>
                    </body>
                    </html>
                    """.trimIndent()
                )

                File(projectDir, "style.css").writeText(
                    """
                    :root {
                        --bg-color: #0f172a;
                        --surface-color: #1e293b;
                        --text-primary: #f8fafc;
                        --text-secondary: #94a3b8;
                        --primary-color: #3b82f6;
                        --primary-hover: #2563eb;
                        --border-color: #334155;
                    }

                    * {
                        box-sizing: border-box;
                        margin: 0;
                        padding: 0;
                    }

                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                        background-color: var(--bg-color);
                        color: var(--text-primary);
                        line-height: 1.6;
                        min-height: 100vh;
                        padding: 24px 16px;
                    }

                    .container {
                        max-width: 640px;
                        margin: 0 auto;
                        display: flex;
                        flex-direction: column;
                        gap: 20px;
                    }

                    header {
                        text-align: center;
                        padding: 16px 0;
                    }

                    h1 {
                        font-size: 1.875rem;
                        font-weight: 700;
                        color: var(--text-primary);
                    }

                    .subtitle {
                        color: var(--text-secondary);
                        font-size: 0.95rem;
                        margin-top: 4px;
                    }

                    .card {
                        background-color: var(--surface-color);
                        border: 1px solid var(--border-color);
                        border-radius: 12px;
                        padding: 20px;
                    }

                    h2 {
                        font-size: 1.25rem;
                        margin-bottom: 16px;
                        color: var(--text-primary);
                    }

                    .counter-display {
                        font-size: 3rem;
                        font-weight: 800;
                        text-align: center;
                        color: var(--primary-color);
                        margin: 12px 0;
                    }

                    .actions {
                        display: flex;
                        justify-content: center;
                        gap: 12px;
                    }

                    .btn {
                        padding: 10px 20px;
                        font-size: 1rem;
                        font-weight: 600;
                        border-radius: 8px;
                        border: none;
                        cursor: pointer;
                        transition: background-color 0.2s ease;
                    }

                    .btn-primary {
                        background-color: var(--primary-color);
                        color: white;
                    }

                    .btn-primary:hover {
                        background-color: var(--primary-hover);
                    }

                    .btn-secondary {
                        background-color: #475569;
                        color: white;
                    }

                    .btn-outline {
                        background-color: transparent;
                        border: 1px solid var(--border-color);
                        color: var(--text-secondary);
                    }

                    .note-input-row {
                        display: flex;
                        gap: 10px;
                    }

                    .input {
                        flex: 1;
                        padding: 10px 14px;
                        border-radius: 8px;
                        border: 1px solid var(--border-color);
                        background-color: #0f172a;
                        color: var(--text-primary);
                        font-size: 0.95rem;
                    }

                    .input:focus {
                        outline: 2px solid var(--primary-color);
                    }

                    .notes-list {
                        list-style: none;
                        margin-top: 14px;
                        display: flex;
                        flex-direction: column;
                        gap: 8px;
                    }

                    .notes-list li {
                        padding: 10px 14px;
                        background-color: #0f172a;
                        border-radius: 6px;
                        border-left: 3px solid var(--primary-color);
                        font-size: 0.95rem;
                    }
                    """.trimIndent()
                )

                File(projectDir, "app.js").writeText(
                    """
                    // Interactive Counter Logic
                    let count = 0;
                    const countEl = document.getElementById('count');
                    const incBtn = document.getElementById('inc-btn');
                    const decBtn = document.getElementById('dec-btn');
                    const resetBtn = document.getElementById('reset-btn');

                    incBtn.addEventListener('click', () => {
                        count++;
                        countEl.textContent = count;
                    });

                    decBtn.addEventListener('click', () => {
                        count--;
                        countEl.textContent = count;
                    });

                    resetBtn.addEventListener('click', () => {
                        count = 0;
                        countEl.textContent = count;
                    });

                    // Quick Notes Logic
                    const noteInput = document.getElementById('note-input');
                    const addNoteBtn = document.getElementById('add-note-btn');
                    const notesList = document.getElementById('notes-list');

                    function addNote() {
                        const text = noteInput.value.trim();
                        if (!text) return;
                        const li = document.createElement('li');
                        li.textContent = text;
                        notesList.prepend(li);
                        noteInput.value = '';
                    }

                    addNoteBtn.addEventListener('click', addNote);
                    noteInput.addEventListener('keypress', (e) => {
                        if (e.key === 'Enter') addNote();
                    });
                    """.trimIndent()
                )

                File(projectDir, "package.json").writeText(
                    """
                    {
                      "name": "${projectName.lowercase().replace(" ", "-")}",
                      "version": "1.0.0",
                      "description": "$projectName web application",
                      "scripts": {
                        "dev": "npx serve -l 3000 .",
                        "start": "npx serve -l 3000 ."
                      }
                    }
                    """.trimIndent()
                )

                File(projectDir, "README.md").writeText(
                    """
                    # $projectName
                    
                    A modern HTML5 web application.
                    
                    ## Running
                    1. Tap **Run** (or `npx serve -l 3000 .` in Terminal)
                    2. Tap **Preview** to see the live app in the embedded browser!
                    """.trimIndent()
                )
            }
            VITE_REACT -> {
                File(projectDir, "package.json").writeText(
                    """
                    {
                      "name": "${projectName.lowercase().replace(" ", "-")}",
                      "private": true,
                      "version": "0.0.0",
                      "type": "module",
                      "scripts": {
                        "dev": "vite --host",
                        "build": "vite build",
                        "preview": "vite preview --host"
                      },
                      "dependencies": {
                        "react": "^18.3.1",
                        "react-dom": "^18.3.1"
                      },
                      "devDependencies": {
                        "@vitejs/plugin-react": "^4.3.1",
                        "vite": "^5.4.0"
                      }
                    }
                    """.trimIndent()
                )

                File(projectDir, "vite.config.js").writeText(
                    """
                    import { defineConfig } from 'vite';
                    import react from '@vitejs/plugin-react';

                    export default defineConfig({
                      plugins: [react()],
                      server: {
                        host: true,
                        port: 5173
                      }
                    });
                    """.trimIndent()
                )

                File(projectDir, "index.html").writeText(
                    """
                    <!DOCTYPE html>
                    <html lang="en">
                      <head>
                        <meta charset="UTF-8" />
                        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                        <title>$projectName</title>
                      </head>
                      <body>
                        <div id="root"></div>
                        <script type="module" src="/src/main.jsx"></script>
                      </body>
                    </html>
                    """.trimIndent()
                )

                val srcDir = File(projectDir, "src").also { it.mkdirs() }

                File(srcDir, "main.jsx").writeText(
                    """
                    import React from 'react';
                    import ReactDOM from 'react-dom/client';
                    import App from './App.jsx';
                    import './index.css';

                    ReactDOM.createRoot(document.getElementById('root')).render(
                      <React.StrictMode>
                        <App />
                      </React.StrictMode>,
                    );
                    """.trimIndent()
                )

                File(srcDir, "App.jsx").writeText(
                    """
                    import { useState } from 'react';

                    export default function App() {
                      const [count, setCount] = useState(0);

                      return (
                        <div className="container">
                          <h1>$projectName</h1>
                          <p className="subtitle">Vite + React on AgenticDroid</p>
                          <div className="card">
                            <button className="btn" onClick={() => setCount(c => c + 1)}>
                              Count is {count}
                            </button>
                            <p>Edit <code>src/App.jsx</code> and save to hot-reload.</p>
                          </div>
                        </div>
                      );
                    }
                    """.trimIndent()
                )

                File(srcDir, "index.css").writeText(
                    """
                    body {
                      margin: 0;
                      font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                      background-color: #0f172a;
                      color: #f8fafc;
                      display: flex;
                      justify-content: center;
                      align-items: center;
                      min-height: 100vh;
                    }
                    .container {
                      text-align: center;
                      padding: 24px;
                    }
                    .subtitle {
                      color: #94a3b8;
                    }
                    .card {
                      background: #1e293b;
                      border-radius: 12px;
                      padding: 24px;
                      margin-top: 20px;
                      border: 1px solid #334155;
                    }
                    .btn {
                      background: #3b82f6;
                      color: white;
                      border: none;
                      padding: 10px 20px;
                      font-size: 1rem;
                      font-weight: 600;
                      border-radius: 8px;
                      cursor: pointer;
                    }
                    code {
                      background: #334155;
                      padding: 2px 6px;
                      border-radius: 4px;
                    }
                    """.trimIndent()
                )

                File(projectDir, "README.md").writeText(
                    """
                    # $projectName
                    
                    Vite + React development project.
                    
                    ## Setup
                    1. Tap **Install Packages** (or run `npm install`)
                    2. Tap **Run Dev Server** (or `npm run dev`)
                    3. Tap **Preview** to view in the live browser!
                    """.trimIndent()
                )
            }
            PYTHON_WEB -> {
                File(projectDir, "app.py").writeText(
                    """
                    from http.server import HTTPServer, BaseHTTPRequestHandler
                    import json

                    PORT = 8000

                    HTML_CONTENT = '''<!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>$projectName</title>
                        <style>
                            body {
                                font-family: system-ui, -apple-system, sans-serif;
                                background-color: #0f172a;
                                color: #f8fafc;
                                display: flex;
                                justify-content: center;
                                align-items: center;
                                min-height: 100vh;
                                margin: 0;
                            }
                            .card {
                                background: #1e293b;
                                padding: 32px;
                                border-radius: 12px;
                                border: 1px solid #334155;
                                text-align: center;
                                max-width: 480px;
                            }
                            h1 { color: #38bdf8; margin-top: 0; }
                            p { color: #94a3b8; line-height: 1.5; }
                            .badge {
                                display: inline-block;
                                background: #0369a1;
                                color: #e0f2fe;
                                padding: 4px 12px;
                                border-radius: 9999px;
                                font-size: 0.875rem;
                                font-weight: 600;
                            }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <span class="badge">Python Server Running</span>
                            <h1>$projectName</h1>
                            <p>Served directly by Python 3 on Android with AgenticDroid.</p>
                        </div>
                    </body>
                    </html>'''

                    class Handler(BaseHTTPRequestHandler):
                        def do_GET(self):
                            if self.path == "/api/status":
                                self.send_response(200)
                                self.send_header("Content-Type", "application/json")
                                self.end_headers()
                                self.wfile.write(json.dumps({"status": "running", "project": "$projectName"}).encode())
                            else:
                                self.send_response(200)
                                self.send_header("Content-Type", "text/html; charset=utf-8")
                                self.end_headers()
                                self.wfile.write(HTML_CONTENT.encode("utf-8"))

                        def log_message(self, format, *args):
                            print(f"[{self.log_date_time_string()}] {format % args}")

                    if __name__ == "__main__":
                        server = HTTPServer(("0.0.0.0", PORT), Handler)
                        print(f"Starting server on http://localhost:{PORT}")
                        try:
                            server.serve_forever()
                        except KeyboardInterrupt:
                            print("\nStopping server.")
                            server.server_close()
                    """.trimIndent()
                )

                File(projectDir, "requirements.txt").writeText(
                    """
                    # Add python packages here, for example:
                    # requests>=2.31.0
                    # flask>=3.0.0
                    """.trimIndent()
                )

                File(projectDir, "README.md").writeText(
                    """
                    # $projectName
                    
                    Python Web Application.
                    
                    ## Running
                    1. Tap **Run Python App** (or run `python app.py`)
                    2. Tap **Preview** to open http://localhost:8000
                    """.trimIndent()
                )
            }
            PYTHON_CLI -> {
                File(projectDir, "main.py").writeText(
                    """
                    import sys
                    import argparse

                    def main():
                        parser = argparse.ArgumentParser(description="$projectName CLI utility")
                        parser.add_argument("--name", "-n", default="World", help="Name to greet")
                        parser.add_argument("--count", "-c", type=int, default=1, help="Number of repetitions")
                        args = parser.parse_args()

                        print(f"=== $projectName CLI ===")
                        for i in range(args.count):
                            print(f"[{i+1}/{args.count}] Hello, {args.name} from Python {sys.version.split()[0]}!")

                    if __name__ == "__main__":
                        main()
                    """.trimIndent()
                )

                File(projectDir, "requirements.txt").writeText(
                    """
                    # Project dependencies
                    """.trimIndent()
                )

                File(projectDir, "README.md").writeText(
                    """
                    # $projectName
                    
                    Python CLI Application.
                    
                    ## Running
                    Run in Terminal:
                    ```sh
                    python main.py --name Developer --count 3
                    ```
                    """.trimIndent()
                )
            }
            ANDROID_STARTER -> {
                File(projectDir, "build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application") version "9.3.1" apply false
                        id("org.jetbrains.kotlin.android") version "2.4.10" apply false
                    }
                    """.trimIndent()
                )

                File(projectDir, "settings.gradle.kts").writeText(
                    """
                    rootProject.name = "$projectName"
                    include(":app")
                    """.trimIndent()
                )

                val appDir = File(projectDir, "app").also { it.mkdirs() }
                File(appDir, "build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                    }

                    android {
                        namespace = "com.example.${projectName.lowercase().replace(Regex("[^a-z0-9]"), "")}"
                        compileSdk = 34

                        defaultConfig {
                            applicationId = "com.example.${projectName.lowercase().replace(Regex("[^a-z0-9]"), "")}"
                            minSdk = 26
                            targetSdk = 34
                            versionCode = 1
                            versionName = "1.0"
                        }
                    }
                    """.trimIndent()
                )

                val mainDir = File(appDir, "src/main").also { it.mkdirs() }
                File(mainDir, "AndroidManifest.xml").writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application
                            android:label="$projectName"
                            android:supportsRtl="true">
                            <activity
                                android:name=".MainActivity"
                                android:exported="true">
                                <intent-filter>
                                    <action android:name="android.intent.action.MAIN" />
                                    <category android:name="android.intent.category.LAUNCHER" />
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>
                    """.trimIndent()
                )

                File(projectDir, "README.md").writeText(
                    """
                    # $projectName
                    
                    Android application project.
                    
                    ## Building
                    Tap **Build & Sideload APK** to compile with Gradle and install on this device!
                    """.trimIndent()
                )
            }
        }
    }
}
