package com.qali.aterm.autogent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Hardcoded framework and language knowledge base
 * Populated into database on initialization
 */
object FrameworkKnowledgeBase {
    
    /**
     * Initialize database with framework knowledge
     */
    fun initializeDatabase(db: SQLiteDatabase) {
        android.util.Log.i("FrameworkKnowledgeBase", "Initializing database with framework knowledge")
        
        // Clear existing framework knowledge (type = "framework_knowledge")
        db.delete(LearningDatabase.TABLE_LEARNED_DATA, "${LearningDatabase.COL_TYPE} = ?", arrayOf("framework_knowledge"))
        
        // Insert all framework knowledge
        insertHtmlKnowledge(db)
        insertCssKnowledge(db)
        insertJavaScriptKnowledge(db)
        insertNodeJsKnowledge(db)
        insertPythonKnowledge(db)
        insertJavaKnowledge(db)
        insertKotlinKnowledge(db)
        insertMvcArchitectureKnowledge(db)
        
        android.util.Log.i("FrameworkKnowledgeBase", "Framework knowledge initialization completed")
    }
    
    private fun insertHtmlKnowledge(db: SQLiteDatabase) {
        val htmlKnowledge = listOf(
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    <!-- HTML5 Semantic Structure -->
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>Document</title>
                    </head>
                    <body>
                        <header></header>
                        <nav></nav>
                        <main></main>
                        <section></section>
                        <article></article>
                        <aside></aside>
                        <footer></footer>
                    </body>
                    </html>
                """.trimIndent(),
                frameworkType = "HTML",
                importPatterns = "",
                eventHandlerPatterns = "onclick, onchange, onsubmit, onload, onfocus, onblur, onmouseover, onmouseout, onkeydown, onkeyup",
                codeTemplate = "HTML5 semantic structure with proper DOCTYPE and meta tags"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    <!-- Common HTML Form Elements -->
                    <form action="/submit" method="POST">
                        <input type="text" name="username" placeholder="Username" required>
                        <input type="email" name="email" placeholder="Email" required>
                        <input type="password" name="password" placeholder="Password" required>
                        <input type="number" name="age" min="0" max="120">
                        <input type="date" name="birthdate">
                        <input type="checkbox" name="agree" id="agree">
                        <label for="agree">I agree</label>
                        <select name="country">
                            <option value="">Select country</option>
                            <option value="us">USA</option>
                        </select>
                        <textarea name="message" rows="4" cols="50"></textarea>
                        <button type="submit">Submit</button>
                    </form>
                """.trimIndent(),
                frameworkType = "HTML",
                importPatterns = "",
                eventHandlerPatterns = "onsubmit, onchange, oninput, onfocus, onblur",
                codeTemplate = "HTML form with validation and common input types"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    <!-- Event Handlers in HTML -->
                    <button onclick="handleClick()">Click Me</button>
                    <input onchange="handleChange(event)" oninput="handleInput(event)">
                    <form onsubmit="handleSubmit(event)">
                        <button type="submit">Submit</button>
                    </form>
                    <div onmouseover="handleMouseOver()" onmouseout="handleMouseOut()">
                        Hover me
                    </div>
                """.trimIndent(),
                frameworkType = "HTML",
                importPatterns = "",
                eventHandlerPatterns = "onclick, onchange, oninput, onsubmit, onmouseover, onmouseout, onkeydown, onkeyup",
                codeTemplate = "HTML elements with inline event handlers"
            )
        )
        
        insertEntries(db, htmlKnowledge)
    }
    
    private fun insertCssKnowledge(db: SQLiteDatabase) {
        val cssKnowledge = listOf(
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    /* CSS Flexbox Layout */
                    .container {
                        display: flex;
                        flex-direction: row;
                        justify-content: center;
                        align-items: center;
                        gap: 1rem;
                    }
                    
                    .item {
                        flex: 1;
                    }
                """.trimIndent(),
                frameworkType = "CSS",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "CSS Flexbox layout with container and items"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    /* CSS Grid Layout */
                    .grid-container {
                        display: grid;
                        grid-template-columns: repeat(3, 1fr);
                        grid-template-rows: auto;
                        gap: 1rem;
                    }
                    
                    .grid-item {
                        grid-column: span 1;
                    }
                """.trimIndent(),
                frameworkType = "CSS",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "CSS Grid layout with responsive columns"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    /* CSS Responsive Design */
                    @media (max-width: 768px) {
                        .container {
                            flex-direction: column;
                        }
                    }
                    
                    @media (min-width: 769px) and (max-width: 1024px) {
                        .container {
                            flex-direction: row;
                        }
                    }
                """.trimIndent(),
                frameworkType = "CSS",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "CSS media queries for responsive design"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    /* CSS Animations */
                    @keyframes fadeIn {
                        from {
                            opacity: 0;
                        }
                        to {
                            opacity: 1;
                        }
                    }
                    
                    .animated {
                        animation: fadeIn 0.5s ease-in-out;
                    }
                """.trimIndent(),
                frameworkType = "CSS",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "CSS keyframe animations"
            )
        )
        
        insertEntries(db, cssKnowledge)
    }
    
    private fun insertJavaScriptKnowledge(db: SQLiteDatabase) {
        val jsKnowledge = listOf(
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // JavaScript Event Handling
                    document.addEventListener('DOMContentLoaded', () => {
                        const button = document.getElementById('myButton');
                        button.addEventListener('click', handleClick);
                    });
                    
                    function handleClick(event) {
                        event.preventDefault();
                        console.log('Button clicked');
                    }
                """.trimIndent(),
                frameworkType = "JavaScript",
                importPatterns = "",
                eventHandlerPatterns = "addEventListener, onclick, onchange, onsubmit, onload",
                codeTemplate = "JavaScript event handling with addEventListener"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // ES6+ Arrow Functions and Async/Await
                    const fetchData = async () => {
                        try {
                            const response = await fetch('/api/data');
                            const data = await response.json();
                            return data;
                        } catch (error) {
                            console.error('Error:', error);
                            throw error;
                        }
                    };
                """.trimIndent(),
                frameworkType = "JavaScript",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "ES6+ async/await with error handling"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // JavaScript DOM Manipulation
                    const element = document.querySelector('.my-class');
                    element.textContent = 'New text';
                    element.classList.add('active');
                    element.setAttribute('data-id', '123');
                    element.style.color = 'red';
                """.trimIndent(),
                frameworkType = "JavaScript",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "JavaScript DOM manipulation methods"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // JavaScript Modules (ES6)
                    // file: utils.js
                    export function formatDate(date) {
                        return new Date(date).toLocaleDateString();
                    }
                    
                    export default class Utils {
                        static formatCurrency(amount) {
                            return "$" + amount.toFixed(2);
                        }
                    }
                    
                    // file: main.js
                    import { formatDate } from './utils.js';
                    import Utils from './utils.js';
                """.trimIndent(),
                frameworkType = "JavaScript",
                importPatterns = "import, export, from",
                eventHandlerPatterns = "",
                codeTemplate = "ES6 module imports and exports"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    <!-- Tic Tac Toe Game (HTML/CSS/JS Single Page App) -->
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8" />
                        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                        <title>Tic Tac Toe</title>
                        <style>
                            body { font-family: system-ui, sans-serif; display: flex; justify-content: center; align-items: center; min-height: 100vh; background: #0f172a; color: #e5e7eb; }
                            .board { display: grid; grid-template-columns: repeat(3, 100px); gap: 8px; }
                            .cell { width: 100px; height: 100px; display: flex; justify-content: center; align-items: center; font-size: 2.5rem; background: #1f2937; border-radius: 0.5rem; cursor: pointer; transition: background 0.15s ease; }
                            .cell:hover { background: #374151; }
                            .status { margin-top: 1rem; text-align: center; }
                            .controls { margin-top: 0.5rem; text-align: center; }
                            button { padding: 0.5rem 1rem; border-radius: 9999px; border: none; background: #2563eb; color: white; cursor: pointer; }
                        </style>
                    </head>
                    <body>
                        <main>
                            <div id="board" class="board"></div>
                            <div id="status" class="status"></div>
                            <div class="controls">
                                <button id="resetButton">Reset Game</button>
                            </div>
                        </main>
                        <script>
                            const boardElement = document.getElementById('board');
                            const statusElement = document.getElementById('status');
                            const resetButton = document.getElementById('resetButton');
                            
                            let board = Array(9).fill(null);
                            let currentPlayer = 'X';
                            let isGameOver = false;
                            
                            function renderBoard() {
                                boardElement.innerHTML = '';
                                board.forEach((value, index) => {
                                    const cell = document.createElement('div');
                                    cell.className = 'cell';
                                    cell.dataset.index = index.toString();
                                    cell.textContent = value || '';
                                    cell.addEventListener('click', handleCellClick);
                                    boardElement.appendChild(cell);
                                });
                            }
                            
                            function handleCellClick(event) {
                                const index = parseInt(event.currentTarget.dataset.index, 10);
                                if (board[index] || isGameOver) return;
                                board[index] = currentPlayer;
                                const winner = calculateWinner(board);
                                if (winner) {
                                    statusElement.textContent = `Player ${winner} wins!`;
                                    isGameOver = true;
                                } else if (board.every(cell => cell)) {
                                    statusElement.textContent = 'Draw!';
                                    isGameOver = true;
                                } else {
                                    currentPlayer = currentPlayer === 'X' ? 'O' : 'X';
                                    statusElement.textContent = `Player ${currentPlayer}'s turn`;
                                }
                                renderBoard();
                            }
                            
                            function calculateWinner(cells) {
                                const lines = [
                                    [0, 1, 2], [3, 4, 5], [6, 7, 8],
                                    [0, 3, 6], [1, 4, 7], [2, 5, 8],
                                    [0, 4, 8], [2, 4, 6],
                                ];
                                for (const [a, b, c] of lines) {
                                    if (cells[a] && cells[a] === cells[b] && cells[a] === cells[c]) {
                                        return cells[a];
                                    }
                                }
                                return null;
                            }
                            
                            function resetGame() {
                                board = Array(9).fill(null);
                                currentPlayer = 'X';
                                isGameOver = false;
                                statusElement.textContent = `Player ${currentPlayer}'s turn`;
                                renderBoard();
                            }
                            
                            document.addEventListener('DOMContentLoaded', () => {
                                resetButton.addEventListener('click', resetGame);
                                resetGame();
                            });
                        </script>
                    </body>
                    </html>
                """.trimIndent(),
                frameworkType = "JavaScript",
                importPatterns = "",
                eventHandlerPatterns = "addEventListener, onclick",
                codeTemplate = "HTML/CSS/JavaScript single-page Tic Tac Toe game with full game loop and UI"
            )
        )
        
        insertEntries(db, jsKnowledge)
    }
    
    private fun insertNodeJsKnowledge(db: SQLiteDatabase) {
        val nodeJsKnowledge = listOf(
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Express.js Basic Server
                    const express = require('express');
                    const app = express();
                    
                    app.use(express.json());
                    app.use(express.urlencoded({ extended: true }));
                    
                    app.get('/api/users', (req, res) => {
                        res.json({ users: [] });
                    });
                    
                    app.post('/api/users', (req, res) => {
                        const { name, email } = req.body;
                        res.status(201).json({ id: 1, name: name, email: email });
                    });
                    
                    const PORT = process.env.PORT || 3000;
                    app.listen(PORT, () => {
                        console.log("Server running on port " + PORT);
                    });
                """.trimIndent(),
                frameworkType = "Node.js",
                importPatterns = "require('express'), require('fs'), require('path')",
                eventHandlerPatterns = "",
                codeTemplate = "Express.js server with GET and POST routes"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Node.js + Express CRUD API for a simple todo resource
                    const express = require('express');
                    const app = express();
                    
                    app.use(express.json());
                    
                    let todos = [];
                    let nextId = 1;
                    
                    // List all todos
                    app.get('/api/todos', (req, res) => {
                        res.json(todos);
                    });
                    
                    // Create a todo
                    app.post('/api/todos', (req, res) => {
                        const { title } = req.body;
                        if (!title) {
                            return res.status(400).json({ error: 'title is required' });
                        }
                        const todo = { id: nextId++, title, completed: false };
                        todos.push(todo);
                        res.status(201).json(todo);
                    });
                    
                    // Update a todo
                    app.put('/api/todos/:id', (req, res) => {
                        const id = parseInt(req.params.id, 10);
                        const todo = todos.find(t => t.id === id);
                        if (!todo) {
                            return res.status(404).json({ error: 'Todo not found' });
                        }
                        const { title, completed } = req.body;
                        if (typeof title === 'string') todo.title = title;
                        if (typeof completed === 'boolean') todo.completed = completed;
                        res.json(todo);
                    });
                    
                    // Delete a todo
                    app.delete('/api/todos/:id', (req, res) => {
                        const id = parseInt(req.params.id, 10);
                        const index = todos.findIndex(t => t.id === id);
                        if (index === -1) {
                            return res.status(404).json({ error: 'Todo not found' });
                        }
                        todos.splice(index, 1);
                        res.status(204).end();
                    });
                    
                    const PORT = process.env.PORT || 3000;
                    app.listen(PORT, () => {
                        console.log(`CRUD API server listening on port ${PORT}`);
                    });
                """.trimIndent(),
                frameworkType = "Node.js",
                importPatterns = "require('express')",
                eventHandlerPatterns = "",
                codeTemplate = "Node.js + Express CRUD API for todos with full create, read, update, and delete endpoints"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Node.js File System Operations
                    const fs = require('fs').promises;
                    const path = require('path');
                    
                    async function readFile(filePath) {
                        try {
                            const data = await fs.readFile(filePath, 'utf8');
                            return data;
                        } catch (error) {
                            console.error('Error reading file:', error);
                            throw error;
                        }
                    }
                    
                    async function writeFile(filePath, content) {
                        await fs.writeFile(filePath, content, 'utf8');
                    }
                """.trimIndent(),
                frameworkType = "Node.js",
                importPatterns = "require('fs'), require('fs').promises, require('path')",
                eventHandlerPatterns = "",
                codeTemplate = "Node.js async file system operations"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Express.js Middleware
                    const express = require('express');
                    const app = express();
                    
                    // Custom middleware
                    const logger = (req, res, next) => {
                        console.log(req.method + " " + req.path);
                        next();
                    };
                    
                    app.use(logger);
                    app.use(express.json());
                """.trimIndent(),
                frameworkType = "Node.js",
                importPatterns = "require('express')",
                eventHandlerPatterns = "",
                codeTemplate = "Express.js custom middleware"
            )
        )
        
        insertEntries(db, nodeJsKnowledge)
    }
    
    private fun insertPythonKnowledge(db: SQLiteDatabase) {
        val pythonKnowledge = listOf(
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    # Python Function with Type Hints
                    from typing import List, Dict, Optional
                    
                    def process_data(items: List[Dict[str, any]], limit: Optional[int] = None) -> List[Dict]:
                        result = []
                        for item in items[:limit] if limit else items:
                            processed = {
                                'id': item.get('id'),
                                'name': item.get('name', '').upper()
                            }
                            result.append(processed)
                        return result
                """.trimIndent(),
                frameworkType = "Python",
                importPatterns = "from typing import, import json, import os, import sys",
                eventHandlerPatterns = "",
                codeTemplate = "Python function with type hints and optional parameters"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    # Python Async/Await
                    import asyncio
                    import aiohttp
                    
                    async def fetch_url(url: str) -> str:
                        async with aiohttp.ClientSession() as session:
                            async with session.get(url) as response:
                                return await response.text()
                    
                    async def main():
                        urls = ['http://example.com', 'http://example.org']
                        result_list = await asyncio.gather(*[fetch_url(url) for url in urls])
                        return result_list
                """.trimIndent(),
                frameworkType = "Python",
                importPatterns = "import asyncio, import aiohttp, import json",
                eventHandlerPatterns = "",
                codeTemplate = "Python async/await with aiohttp"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    # Python Class with Decorators
                    class DataProcessor:
                        def __init__(self, data: list):
                            self.data = data
                        
                        @property
                        def count(self) -> int:
                            return len(self.data)
                        
                        @staticmethod
                        def validate(item: dict) -> bool:
                            return 'id' in item and 'name' in item
                        
                        def process(self) -> list:
                            return [item for item in self.data if self.validate(item)]
                """.trimIndent(),
                frameworkType = "Python",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "Python class with property and static method decorators"
            )
        )
        
        insertEntries(db, pythonKnowledge)
    }
    
    private fun insertJavaKnowledge(db: SQLiteDatabase) {
        val javaKnowledge = listOf(
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Java Spring Boot REST Controller
                    package com.example.controller;
                    
                    import org.springframework.web.bind.annotation.*;
                    import org.springframework.http.ResponseEntity;
                    
                    @RestController
                    @RequestMapping("/api/users")
                    public class UserController {
                        
                    @GetMapping
                    public ResponseEntity<List<User>> getAllUsers() {
                        return ResponseEntity.ok(userService.findAll());
                    }
                    
                    @PostMapping
                    public ResponseEntity<User> createUser(@RequestBody User userObj) {
                        User saved = userService.save(userObj);
                        return ResponseEntity.status(201).body(saved);
                    }
                    }
                """.trimIndent(),
                frameworkType = "Java",
                importPatterns = "import org.springframework.web.bind.annotation.*, import org.springframework.http.*",
                eventHandlerPatterns = "",
                codeTemplate = "Spring Boot REST controller with GET and POST mappings"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Java Streams API
                    import java.util.List;
                    import java.util.stream.Collectors;
                    
                    List<String> filtered = items.stream()
                        .filter(item -> item.getStatus().equals("active"))
                        .map(item -> item.getName())
                        .collect(Collectors.toList());
                """.trimIndent(),
                frameworkType = "Java",
                importPatterns = "import java.util.stream.*, import java.util.List",
                eventHandlerPatterns = "",
                codeTemplate = "Java Streams API for filtering and mapping"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Java Service Layer Pattern
                    @Service
                    public class UserService {
                        @Autowired
                        private UserRepository userRepository;
                        
                        public User findById(Long id) {
                            return userRepository.findById(id)
                                .orElseThrow(() -> new NotFoundException("User not found"));
                        }
                        
                        public User save(User user) {
                            return userRepository.save(user);
                        }
                    }
                """.trimIndent(),
                frameworkType = "Java",
                importPatterns = "import org.springframework.stereotype.Service, import org.springframework.beans.factory.annotation.Autowired",
                eventHandlerPatterns = "",
                codeTemplate = "Spring Boot service layer with repository injection"
            )
        )
        
        insertEntries(db, javaKnowledge)
    }
    
    private fun insertKotlinKnowledge(db: SQLiteDatabase) {
        val kotlinKnowledge = listOf(
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Kotlin Coroutines
                    import kotlinx.coroutines.*
                    
                    suspend fun fetchData(): String = withContext(Dispatchers.IO) {
                        delay(1000)
                        return@withContext "Data loaded"
                    }
                    
                    fun main() = runBlocking {
                        val data = async { fetchData() }
                        println(data.await())
                    }
                """.trimIndent(),
                frameworkType = "Kotlin",
                importPatterns = "import kotlinx.coroutines.*, import kotlinx.coroutines.Dispatchers",
                eventHandlerPatterns = "",
                codeTemplate = "Kotlin coroutines with async/await"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Kotlin Data Classes and Extension Functions
                    data class User(val id: Long, val name: String, val email: String)
                    
                    fun User.fullName(): String = name + " (" + email + ")"
                    
                    fun String.isValidEmail(): Boolean {
                        return this.contains("@") && this.contains(".")
                    }
                """.trimIndent(),
                frameworkType = "Kotlin",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "Kotlin data class with extension functions"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Kotlin Sealed Classes and When Expression
                    sealed class Result<out T> {
                        data class Success<T>(val data: T) : Result<T>()
                        data class Error(val message: String) : Result<Nothing>()
                    }
                    
                    fun handleResult(result: Result<String>) {
                        when (result) {
                            is Result.Success -> println("Success: " + result.data)
                            is Result.Error -> println("Error: " + result.message)
                        }
                    }
                """.trimIndent(),
                frameworkType = "Kotlin",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "Kotlin sealed classes with when expression"
            )
        )
        
        insertEntries(db, kotlinKnowledge)
    }
    
    private fun insertMvcArchitectureKnowledge(db: SQLiteDatabase) {
        val mvcKnowledge = listOf(
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // MVC Pattern - Model
                    class UserModel {
                        private var id: Long = 0
                        private var name: String = ""
                        private var email: String = ""
                        
                        fun getId(): Long = id
                        fun getName(): String = name
                        fun getEmail(): String = email
                        
                        fun setId(id: Long) { this.id = id }
                        fun setName(name: String) { this.name = name }
                        fun setEmail(email: String) { this.email = email }
                    }
                """.trimIndent(),
                frameworkType = "MVC",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "MVC Model class with getters and setters"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // MVC Pattern - View
                    class UserView {
                        fun displayUser(user: UserModel) {
                            println("ID: " + user.getId())
                            println("Name: " + user.getName())
                            println("Email: " + user.getEmail())
                        }
                        
                        fun displayError(message: String) {
                            println("Error: " + message)
                        }
                    }
                """.trimIndent(),
                frameworkType = "MVC",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "MVC View class for displaying data"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // MVC Pattern - Controller
                    class UserController(private val model: UserModel, private val view: UserView) {
                        fun updateUser(id: Long, name: String, email: String) {
                            model.setId(id)
                            model.setName(name)
                            model.setEmail(email)
                            view.displayUser(model)
                        }
                    }
                """.trimIndent(),
                frameworkType = "MVC",
                importPatterns = "",
                eventHandlerPatterns = "",
                codeTemplate = "MVC Controller coordinating Model and View"
            ),
            FrameworkEntry(
                type = "framework_knowledge",
                content = """
                    // Express.js Route-View Pattern
                    const express = require('express');
                    const router = express.Router();
                    
                    router.get('/users/:id', async (req, res) => {
                        const userObj = await userService.findById(req.params.id);
                        res.render('user', { user: userObj });
                    });
                    
                    module.exports = router;
                """.trimIndent(),
                frameworkType = "MVC",
                importPatterns = "require('express')",
                eventHandlerPatterns = "",
                codeTemplate = "Express.js route with view rendering"
            )
        )
        
        insertEntries(db, mvcKnowledge)
    }
    
    private fun insertEntries(db: SQLiteDatabase, entries: List<FrameworkEntry>) {
        entries.forEach { entry ->
            val values = ContentValues().apply {
                put(LearningDatabase.COL_TYPE, entry.type)
                put(LearningDatabase.COL_CONTENT, entry.content)
                put(LearningDatabase.COL_POSITIVE_SCORE, 100) // High score for framework knowledge
                put(LearningDatabase.COL_SOURCE, "framework_knowledge_base")
                put(LearningDatabase.COL_TIMESTAMP, System.currentTimeMillis())
                put(LearningDatabase.COL_FRAMEWORK_TYPE, entry.frameworkType)
                put(LearningDatabase.COL_IMPORT_PATTERNS, entry.importPatterns)
                put(LearningDatabase.COL_EVENT_HANDLER_PATTERNS, entry.eventHandlerPatterns)
                put(LearningDatabase.COL_CODE_TEMPLATE, entry.codeTemplate)
            }
            db.insert(LearningDatabase.TABLE_LEARNED_DATA, null, values)
        }
    }
    
    private data class FrameworkEntry(
        val type: String,
        val content: String,
        val frameworkType: String,
        val importPatterns: String,
        val eventHandlerPatterns: String,
        val codeTemplate: String
    )
}
