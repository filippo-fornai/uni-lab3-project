# 🧠 WORDLE 3.0 — Network Multiplayer Word Game

This repository contains the final exam project for the **Laboratorio di Reti** course at the **University of Pisa**.

The project is a networked reimplementation of the popular Wordle game, extended with multiplayer support, persistent statistics, and result sharing using multicast UDP. It is written in **Java 8** and uses multithreading, TCP sockets, and JSON configuration files.

---

## 📘 Project Overview

The objective of the project is to build a system with a **client-server architecture** where:

- The server generates a 10-letter secret word daily.
- The clients connect via TCP to play the game and guess the word.
- Clients receive feedback in the form of hints for each guess.
- Clients can **share** their results via a **multicast group**.
- The server **persists user statistics** and supports multiple concurrent players.

Each player can:
- Register and log in
- Play a daily game of Wordle
- Receive clue-based feedback (`+`, `?`, `X`)
- Share results with other players
- View personal statistics

---

## 🧪 Technologies and Design

- **Java 8**
- **Multithreading** with thread pools
- **TCP for command communication**
- **UDP multicast for result sharing**
- **JSON for configuration and persistence**
- **CLI interface** for interaction
- **Persistent daily word rotation** using a scheduled thread

---
# Execution
The project was developed entirely on windows. 2 batch files are present
to run automatically everything.

```
# Run the server
./SERVER_START.bat

# Run the client
./CLIENT_START.bat
```

To run the project under linux or compile and execute manually use the following commands inside the respective client/server folders.


#### Manual compilation

```
# On Windows and Linux
javac -cp ./gson-2.10.1.jar WordleServerMain.java       # in ./Server
javac -cp ./gson-2.10.1.jar WordleClientMain.java       # in ./Client
```

#### Manual execution
```
# On Windows
java -cp ./gson-2.10.1.jar;. WordleServerMain       # in ./Server
java -cp ./gson-2.10.1.jar;. WordleClientMain       # in ./Client

# On Linux/Mac (use ":" instead of ";")
java -cp ./gson-2.10.1.jar:. WordleServerMain       # in ./Server
java -cp ./gson-2.10.1.jar:. WordleClientMain       # in ./Client
```