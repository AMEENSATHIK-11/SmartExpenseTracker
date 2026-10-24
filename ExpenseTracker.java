import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

public class ExpenseTracker {

    static final int PORT = 8080;
    static final Path DATA = Path.of("expenses.csv");

    static final List<String> CATEGORIES = List.of("Food", "Travel", "Bills", "Shopping", "Health", "Entertainment", "Other");
    static final List<String> COLORS = List.of("#2563eb", "#16a34a", "#f59e0b", "#ec4899", "#8b5cf6", "#06b6d4", "#64748b");

    // Monthly budgets (Rs.) per category
    static final Map<String, Double> BUDGETS = new LinkedHashMap<>();
    static {
        BUDGETS.put("Food", 5000.0);
        BUDGETS.put("Travel", 3000.0);
        BUDGETS.put("Bills", 8000.0);
        BUDGETS.put("Shopping", 4000.0);
        BUDGETS.put("Health", 2000.0);
        BUDGETS.put("Entertainment", 2000.0);
    }

    static final Map<String, List<String>> KEYWORDS = Map.of(
            "Food", List.of("swiggy", "zomato", "restaurant", "cafe", "grocery", "pizza", "coffee", "lunch", "dinner"),
            "Travel", List.of("uber", "ola", "train", "bus", "fuel", "petrol", "flight", "metro"),
            "Bills", List.of("electricity", "water", "internet", "rent", "recharge", "wifi", "gas bill"),
            "Shopping", List.of("amazon", "flipkart", "mall", "clothes", "shoes"),
            "Health", List.of("pharmacy", "doctor", "hospital", "medicine", "gym"),
            "Entertainment", List.of("netflix", "movie", "spotify", "prime", "game"));

    record Expense(long id, LocalDate date, String description, double amount, String category) {}

    // ---------------- Server ----------------

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/", ExpenseTracker::handle);
        server.start();

        String url = "http://localhost:" + PORT + "/dashboard";
        System.out.println();
        System.out.println("Dashboard ready: " + url);
        System.out.println("Data file: " + DATA.toAbsolutePath());
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();

        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ignored) {
            // browser didn't open; the link above still works
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            if (method.equals("GET") && (path.equals("/") || path.equals("/dashboard"))) {
                Map<String, String> q = parseParams(ex.getRequestURI().getRawQuery());
                send(ex, 200, "text/html; charset=utf-8", dashboard(q, load()));

            } else if (method.equals("GET") && path.equals("/export.csv")) {
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"expenses.csv\"");
                send(ex, 200, "text/csv; charset=utf-8", toCsv(load()));

            } else if (method.equals("POST") && path.equals("/add")) {
                Map<String, String> f = readForm(ex);
                List<Expense> list = load();
                long id = list.stream().mapToLong(Expense::id).max().orElse(0) + 1;
                list.add(fromForm(id, f, list));
                save(list);
                redirect(ex, "Expense added");

            } else if (method.equals("POST") && path.equals("/update")) {
                Map<String, String> f = readForm(ex);
                long id = Long.parseLong(f.get("id"));
                List<Expense> list = load();
                Expense updated = fromForm(id, f, list);
                list.replaceAll(e -> e.id() == id ? updated : e);
                save(list);
                redirect(ex, "Expense updated");

            } else if (method.equals("POST") && path.equals("/delete")) {
                long id = Long.parseLong(readForm(ex).get("id"));
                List<Expense> list = load();
                list.removeIf(e -> e.id() == id);
                save(list);
                redirect(ex, "Expense deleted");

            } else {
                send(ex, 404, "text/html; charset=utf-8",
                        page("Not found", "<p>Page not found.</p><a href='/dashboard'>Go to dashboard</a>"));
            }
        } catch (Exception e) {
            send(ex, 400, "text/html; charset=utf-8",
                    page("Something went wrong",
                         "<p>" + esc(String.valueOf(e.getMessage())) + "</p><a href='/dashboard'>Back to dashboard</a>"));
        }
    }

    // ---------------- Validation and smart categorization ----------------

    static Expense fromForm(long id, Map<String, String> f, List<Expense> history) {
        String desc = f.getOrDefault("description", "").replaceAll("[\\r\\n]+", " ").trim();
        if (desc.isEmpty()) throw new IllegalArgumentException("Description is required.");
        if (desc.length() > 120) throw new IllegalArgumentException("Description must be 120 characters or fewer.");

        double amount;
        try {
            amount = Double.parseDouble(f.getOrDefault("amount", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid amount.");
        }
        if (!(amount > 0) || Double.isInfinite(amount)) throw new IllegalArgumentException("Amount must be greater than zero.");

        LocalDate date;
        try {
            date = LocalDate.parse(f.getOrDefault("date", ""));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Enter a valid date.");
        }

        String cat = f.getOrDefault("category", "").trim();
        if (!CATEGORIES.contains(cat)) cat = guess(desc, history);

        return new Expense(id, date, desc, Math.round(amount * 100) / 100.0, cat);
    }

    static String guess(String desc, List<Expense> history) {
        String d = desc.toLowerCase().trim();

        // 1. Learn from your past entries (most recent match wins)
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).description().toLowerCase().trim().equals(d)) {
                return history.get(i).category();
            }
        }

        // 2. Keyword rules (whole words, or phrases for multi-word keywords)
        Set<String> words = new HashSet<>(Arrays.asList(d.split("[^a-z]+")));
        for (var entry : KEYWORDS.entrySet()) {
            for (String k : entry.getValue()) {
                if (k.contains(" ") ? d.contains(k) : words.contains(k)) return entry.getKey();
            }
        }
        return "Other";
    }

    // ---------------- Storage (CSV) ----------------

    static List<Expense> load() throws IOException {
        List<Expense> list = new ArrayList<>();
        if (!Files.exists(DATA)) return list;
        List<String> lines = Files.readAllLines(DATA, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {           // skip header
            if (lines.get(i).isBlank()) continue;
            List<String> p = splitCsv(lines.get(i));
            if (p.size() != 5) continue;
            try {
                list.add(new Expense(Long.parseLong(p.get(0)), LocalDate.parse(p.get(1)),
                        p.get(2), Double.parseDouble(p.get(3)), p.get(4)));
            } catch (RuntimeException ignored) {
                // skip corrupt row
            }
        }
        return list;
    }

    static void save(List<Expense> list) throws IOException {
        Files.writeString(DATA, toCsv(list), StandardCharsets.UTF_8);
    }

    static String toCsv(List<Expense> list) {
        StringBuilder sb = new StringBuilder("id,date,description,amount,category\n");
        for (Expense e : list) {
            sb.append(e.id()).append(',')
              .append(e.date()).append(',')
              .append(csv(e.description())).append(',')
              .append(e.amount()).append(',')
              .append(csv(e.category())).append('\n');
        }
        return sb.toString();
    }

    static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ---------------- Dashboard ----------------

    static String dashboard(Map<String, String> q, List<Expense> all) {
        String month = q.getOrDefault("month", "");
        String cat = q.getOrDefault("cat", "");
        String search = q.getOrDefault("q", "").trim().toLowerCase();
        String msg = q.getOrDefault("msg", "");
        YearMonth now = YearMonth.now();

        List<Expense> view = all.stream()
                .filter(e -> month.isEmpty() || YearMonth.from(e.date()).toString().equals(month))
                .filter(e -> cat.isEmpty() || e.category().equals(cat))
                .filter(e -> search.isEmpty() || e.description().toLowerCase().contains(search))
                .sorted((a, b) -> {
                    int c = b.date().compareTo(a.date());
                    return c != 0 ? c : Long.compare(b.id(), a.id());
                })
                .toList();

        double total = view.stream().mapToDouble(Expense::amount).sum();
        double avg = view.isEmpty() ? 0 : total / view.size();
        double thisMonth = all.stream()
                .filter(e -> YearMonth.from(e.date()).equals(now))
                .mapToDouble(Expense::amount).sum();

        Map<String, Double> byCat = view.stream().collect(
                Collectors.groupingBy(Expense::category, Collectors.summingDouble(Expense::amount)));
        String top = byCat.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("-");

        // Donut chart
        double catSum = byCat.values().stream().mapToDouble(Double::doubleValue).sum();
        List<String> stops = new ArrayList<>();
        StringBuilder legend = new StringBuilder();
        double acc = 0;
        for (String c : CATEGORIES) {
            double v = byCat.getOrDefault(c, 0.0);
            if (v <= 0) continue;
            String color = COLORS.get(CATEGORIES.indexOf(c));
            double start = acc / catSum * 100;
            acc += v;
            double end = acc / catSum * 100;
            stops.add(String.format(Locale.US, "%s %.2f%% %.2f%%", color, start, end));
            legend.append(String.format(Locale.US,
                    "<li><i style='background:%s'></i><span>%s</span><b>&#8377;%.0f</b><em>%.0f%%</em></li>",
                    color, c, v, v / catSum * 100));
        }
        String donutBg = stops.isEmpty() ? "var(--line)" : "conic-gradient(" + String.join(", ", stops) + ")";

        // Last 6 months bar chart (always shows all data, not filtered)
        Map<YearMonth, Double> monthly = new LinkedHashMap<>();
        for (int i = 5; i >= 0; i--) monthly.put(now.minusMonths(i), 0.0);
        for (Expense e : all) monthly.computeIfPresent(YearMonth.from(e.date()), (k, v) -> v + e.amount());
        double maxM = monthly.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (maxM <= 0) maxM = 1;
        StringBuilder chart = new StringBuilder();
        for (var m : monthly.entrySet()) {
            double v = m.getValue();
            chart.append(String.format(Locale.US,
                    "<div class='col' title='%s: &#8377;%.0f'><div class='bar' style='height:%.0fpx'></div><span>%s</span></div>",
                    m.getKey(), v, v / maxM * 140,
                    m.getKey().getMonth().getDisplayName(TextStyle.SHORT, Locale.US)));
        }

        // Budgets and alerts for the current month
        Map<String, Double> monthCat = all.stream()
                .filter(e -> YearMonth.from(e.date()).equals(now))
                .collect(Collectors.groupingBy(Expense::category, Collectors.summingDouble(Expense::amount)));
        StringBuilder budget = new StringBuilder();
        List<String> alerts = new ArrayList<>();
        for (var b : BUDGETS.entrySet()) {
            double spent = monthCat.getOrDefault(b.getKey(), 0.0);
            double pct = spent / b.getValue() * 100;
            String status = pct >= 100 ? "over" : pct >= 80 ? "warn" : "ok";
            if (pct >= 100) {
                alerts.add("<li class='over'>" + b.getKey() + " is over budget by &#8377;"
                        + String.format(Locale.US, "%.0f", spent - b.getValue()) + "</li>");
            } else if (pct >= 80) {
                alerts.add("<li class='warn'>" + b.getKey() + " has used "
                        + String.format(Locale.US, "%.0f", pct) + "% of its budget</li>");
            }
            budget.append(String.format(Locale.US,
                    "<div class='bud'><div class='row'><span>%s</span><span>&#8377;%.0f of &#8377;%.0f</span></div>"
                    + "<div class='track'><div class='fill %s' style='width:%.0f%%'></div></div></div>",
                    b.getKey(), spent, b.getValue(), status, Math.min(pct, 100)));
        }
        String alertHtml = alerts.isEmpty()
                ? "<p class='muted'>Everything is within budget this month.</p>"
                : "<ul class='alerts'>" + String.join("", alerts) + "</ul>";

        // Expense rows with inline edit and delete
        StringBuilder rows = new StringBuilder();
        for (Expense e : view) {
            rows.append(String.format(Locale.US,
                    "<tr><td>%s</td><td>%s</td><td><span class='tag'>%s</span></td><td class='amt'>&#8377;%.2f</td><td>"
                    + "<details><summary>Edit</summary>"
                    + "<form method='post' action='/update'>"
                    + "<input type='hidden' name='id' value='%d'>"
                    + "<input name='description' value='%s' maxlength='120' required>"
                    + "<input name='amount' type='number' step='0.01' min='0.01' value='%.2f' required>"
                    + "<input name='date' type='date' value='%s' required>"
                    + "<select name='category'>%s</select>"
                    + "<button>Save</button></form></details>"
                    + "<form class='inline' method='post' action='/delete' onsubmit=\"return confirm('Delete this expense?')\">"
                    + "<input type='hidden' name='id' value='%d'><button class='danger'>Delete</button></form>"
                    + "</td></tr>",
                    e.date(), esc(e.description()), esc(e.category()), e.amount(),
                    e.id(), esc(e.description()), e.amount(), e.date(),
                    options(e.category(), null), e.id()));
        }

        String msgHtml = msg.isEmpty() ? "" : "<div class='toast'>" + esc(msg) + "</div>";

        return TEMPLATE
                .replace("{{MSG}}", msgHtml)
                .replace("{{TOTAL}}", String.format(Locale.US, "%,.2f", total))
                .replace("{{THIS_MONTH}}", String.format(Locale.US, "%,.2f", thisMonth))
                .replace("{{COUNT}}", String.valueOf(view.size()))
                .replace("{{AVG}}", String.format(Locale.US, "%,.2f", avg))
                .replace("{{TOP}}", esc(top))
                .replace("{{TODAY}}", LocalDate.now().toString())
                .replace("{{ADD_OPTIONS}}", options("", "Auto-detect"))
                .replace("{{FILTER_MONTH}}", esc(month))
                .replace("{{FILTER_OPTIONS}}", options(cat, "All categories"))
                .replace("{{FILTER_Q}}", esc(q.getOrDefault("q", "")))
                .replace("{{DONUT}}", donutBg)
                .replace("{{LEGEND}}", legend.toString())
                .replace("{{MONTHLY}}", chart.toString())
                .replace("{{BUDGET}}", budget.toString())
                .replace("{{ALERTS}}", alertHtml)
                .replace("{{EMPTY}}", view.isEmpty() ? "<p class='muted'>No expenses match these filters.</p>" : "")
                .replace("{{ROWS}}", rows.toString());
    }

    static String options(String selected, String blankLabel) {
        StringBuilder sb = new StringBuilder();
        if (blankLabel != null) sb.append("<option value=''>").append(blankLabel).append("</option>");
        for (String c : CATEGORIES) {
            sb.append("<option").append(c.equals(selected) ? " selected" : "").append(">")
              .append(c).append("</option>");
        }
        return sb.toString();
    }

    static final String TEMPLATE = """
            <!DOCTYPE html>
            <html lang='en'><head><meta charset='UTF-8'>
            <meta name='viewport' content='width=device-width,initial-scale=1'>
            <title>Smart Expense Tracker</title>
            <style>
            :root{--bg:#f4f6fb;--card:#fff;--text:#1f2937;--muted:#6b7280;--line:#e5e7eb;--accent:#2563eb;--danger:#dc2626;--warn:#f59e0b;--ok:#16a34a}
            @media (prefers-color-scheme: dark){:root{--bg:#0f172a;--card:#1e293b;--text:#e2e8f0;--muted:#94a3b8;--line:#334155}}
            *{box-sizing:border-box}
            body{margin:0;font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:var(--bg);color:var(--text)}
            header{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;padding:20px 28px}
            h1{margin:0;font-size:1.5rem}
            h3{margin:0 0 14px}
            main{display:grid;gap:20px;padding:0 28px 40px}
            .card{background:var(--card);border-radius:14px;padding:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)}
            .muted{color:var(--muted);font-size:.9rem}
            .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:16px}
            .kpi .label{color:var(--muted);font-size:.85rem}
            .kpi .value{font-size:1.5rem;font-weight:700;margin-top:6px}
            .grid2{display:grid;grid-template-columns:1fr 1fr;gap:20px}
            label{display:grid;gap:4px;font-size:.85rem;color:var(--muted)}
            input,select{padding:9px 10px;border:1px solid var(--line);border-radius:8px;background:transparent;color:var(--text);font:inherit;width:100%}
            button{padding:9px 16px;background:var(--accent);color:#fff;border:0;border-radius:8px;cursor:pointer;font:inherit}
            button.danger{background:var(--danger);padding:5px 12px}
            .btn{display:inline-block;padding:9px 16px;border-radius:8px;text-decoration:none;color:var(--text);border:1px solid var(--line)}
            .addform{display:grid;grid-template-columns:2fr 1fr 1fr 1fr auto;gap:12px;align-items:end}
            .filters{display:flex;gap:12px;flex-wrap:wrap;align-items:end}
            .filters label{min-width:160px}
            .toast{background:#dcfce7;color:#166534;padding:12px 16px;border-radius:10px}
            .donut-wrap{display:flex;align-items:center;gap:24px;flex-wrap:wrap}
            .donut{position:relative;width:180px;height:180px;border-radius:50%}
            .donut::after{content:'';position:absolute;inset:38px;background:var(--card);border-radius:50%}
            .legend{list-style:none;margin:0;padding:0;display:grid;gap:8px;flex:1;min-width:200px}
            .legend li{display:grid;grid-template-columns:14px 1fr auto 44px;gap:8px;align-items:center}
            .legend i{width:14px;height:14px;border-radius:4px}
            .legend em{color:var(--muted);font-style:normal;text-align:right}
            .chart{display:flex;align-items:flex-end;gap:12px;height:180px}
            .col{flex:1;height:100%;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;gap:6px}
            .bar{width:100%;max-width:48px;background:var(--accent);border-radius:6px 6px 0 0;min-height:2px}
            .bud{margin:12px 0}
            .row{display:flex;justify-content:space-between;font-size:.9rem;margin-bottom:4px}
            .track{background:var(--line);border-radius:8px;height:10px;overflow:hidden}
            .fill{height:100%;background:var(--accent)}
            .fill.ok{background:var(--ok)} .fill.warn{background:var(--warn)} .fill.over{background:var(--danger)}
            .alerts{margin:0;padding-left:18px}
            .alerts .over{color:var(--danger)} .alerts .warn{color:var(--warn)}
            .table-wrap{overflow-x:auto}
            table{width:100%;border-collapse:collapse}
            th,td{text-align:left;padding:10px 8px;border-bottom:1px solid var(--line);vertical-align:top}
            .amt{font-weight:600;white-space:nowrap}
            .tag{background:var(--line);padding:3px 10px;border-radius:999px;font-size:.8rem}
            .inline{display:inline}
            details summary{cursor:pointer;color:var(--accent)}
            details form{display:grid;gap:6px;margin-top:8px;min-width:220px}
            @media (max-width:900px){.grid2{grid-template-columns:1fr}.addform{grid-template-columns:1fr}}
            </style></head>
            <body>
            <header>
              <h1>Smart Expense Tracker</h1>
              <a class='btn' href='/export.csv'>Export CSV</a>
            </header>
            <main>
            {{MSG}}
            <section class='kpis'>
              <div class='card kpi'><div class='label'>Total (filtered)</div><div class='value'>&#8377;{{TOTAL}}</div></div>
              <div class='card kpi'><div class='label'>This month</div><div class='value'>&#8377;{{THIS_MONTH}}</div></div>
              <div class='card kpi'><div class='label'>Transactions</div><div class='value'>{{COUNT}}</div></div>
              <div class='card kpi'><div class='label'>Average</div><div class='value'>&#8377;{{AVG}}</div></div>
              <div class='card kpi'><div class='label'>Top category</div><div class='value'>{{TOP}}</div></div>
            </section>

            <section class='card'>
              <h3>Add expense</h3>
              <form class='addform' method='post' action='/add'>
                <label>Description<input name='description' placeholder='e.g. Swiggy dinner' maxlength='120' required></label>
                <label>Amount (&#8377;)<input name='amount' type='number' step='0.01' min='0.01' required></label>
                <label>Date<input name='date' type='date' value='{{TODAY}}' required></label>
                <label>Category<select name='category'>{{ADD_OPTIONS}}</select></label>
                <button>Add</button>
              </form>
              <p class='muted'>Leave the category on Auto-detect and the app predicts it from keywords and your past entries.</p>
            </section>

            <section class='card'>
              <form class='filters' method='get' action='/dashboard'>
                <label>Month<input type='month' name='month' value='{{FILTER_MONTH}}'></label>
                <label>Category<select name='cat'>{{FILTER_OPTIONS}}</select></label>
                <label>Search<input name='q' placeholder='Search description' value='{{FILTER_Q}}'></label>
                <button>Apply</button>
                <a class='btn' href='/dashboard'>Clear</a>
              </form>
            </section>

            <div class='grid2'>
              <section class='card'>
                <h3>Spending by category</h3>
                <div class='donut-wrap'>
                  <div class='donut' style='background:{{DONUT}}'></div>
                  <ul class='legend'>{{LEGEND}}</ul>
                </div>
              </section>
              <section class='card'>
                <h3>Last 6 months</h3>
                <div class='chart'>{{MONTHLY}}</div>
              </section>
              <section class='card'>
                <h3>Budgets this month</h3>
                {{BUDGET}}
              </section>
              <section class='card'>
                <h3>Alerts</h3>
                {{ALERTS}}
              </section>
            </div>

            <section class='card'>
              <h3>Expenses</h3>
              <div class='table-wrap'>
                <table>
                  <tr><th>Date</th><th>Description</th><th>Category</th><th>Amount</th><th>Actions</th></tr>
                  {{ROWS}}
                </table>
              </div>
              {{EMPTY}}
            </section>
            </main>
            </body></html>
            """;

    // ---------------- Helpers ----------------

    static String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>" + esc(title) + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;padding:40px;max-width:640px}a{color:#2563eb}</style>"
                + "</head><body><h2>" + esc(title) + "</h2>" + body + "</body></html>";
    }

    static Map<String, String> readForm(HttpExchange ex) throws IOException {
        return parseParams(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    static Map<String, String> parseParams(String s) {
        Map<String, String> map = new HashMap<>();
        if (s == null || s.isEmpty()) return map;
        for (String pair : s.split("&")) {
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(k, v);
        }
        return map;
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void redirect(HttpExchange ex, String msg) throws IOException {
        ex.getResponseHeaders().set("Location",
                "/dashboard?msg=" + URLEncoder.encode(msg, StandardCharsets.UTF_8));
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}