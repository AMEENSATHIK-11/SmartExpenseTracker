# Smart Expense Tracker

Java Project | B.E. Electrical and Electronics Engineering

| Field | Details |
|---|---|
| Author | Ameen Sathik |
| Register Number | 711524BEE004 |
| Course | B.E. EEE |
| College | Kalaignarkarunanidhi Institute of Technology (KIT) |
| Subject | Object-Oriented Programming using Java |
| Project | Smart Expense Tracker |

---

## 📌 Project Overview

The Smart Expense Tracker is a Java-based application that helps users record, categorize, and analyze their daily expenses. It runs a local web dashboard that shows spending totals, charts, and monthly budget alerts, and it requires no external libraries.

## 🎯 Objectives

- Simplify the recording of daily expenses.
- Categorize expenses automatically based on descriptions and past entries.
- Track spending against monthly budgets.
- Provide a clear dashboard with summaries and charts.
- Demonstrate object-oriented programming concepts in Java.

## ✨ Features

- 🧾 Add, edit, and delete expenses with input validation
- 🧠 Smart categorization using past entries and keyword rules
- 🔍 Filter by month or category, and search by description
- 📊 Summary cards for total, this month, average, count, and top category
- 🍩 Donut chart for spending by category
- 📈 Six-month spending trend chart
- 🎯 Monthly budgets with alerts at 80% and 100%
- 📤 CSV export
- 🌙 Dark mode that follows the system theme

## 🛠️ Technologies Used

- Java 17 or newer
- Object-Oriented Programming (OOP)
- `com.sun.net.httpserver` (built into the JDK)
- HTML and CSS for the dashboard
- Git and GitHub

## 💻 Requirements

- Java JDK 17 or newer
- Visual Studio Code or another Java IDE
- Git (optional, for version control)

Check your Java version:

```bash
java -version
```

## 🚀 How to Run

1. Download or clone this repository.
2. Open the project folder in VS Code.
3. Open a terminal in the project folder.
4. Run the application:

   ```bash
   java ExpenseTracker.java
   ```

5. Open the link shown in the terminal:

   ```
   http://localhost:8080/dashboard
   ```

6. Press `Ctrl+C` to stop the server.

## 📖 How to Use

1. Enter a description, amount, and date to add an expense. Leave the category on Auto-detect to let the app choose it.
2. Use the **Edit** option in the expense table to change an entry.
3. Use the filters to view a specific month or category.
4. Click **Export CSV** to download your data.

Monthly budgets can be changed by editing the `BUDGETS` map in `ExpenseTracker.java`.

## 📂 Project Structure

```
smart-expense-tracker/
├── README.md
├── ExpenseTracker.java
├── .gitignore
└── expenses.csv        (created on first run; git-ignored)
```

## 📸 Screenshots

Add screenshots of the dashboard here after taking them.

## 🔮 Future Enhancements

- User login and registration
- Recurring expenses
- Multi-currency support
- Monthly PDF or email reports
- Machine-learning-based category prediction
- Splitting the code into multiple files and packages

## 👨‍🎓 Academic Project

Developed as part of the academic coursework for Object-Oriented Programming using Java.

⭐ Thank you for visiting this project!
