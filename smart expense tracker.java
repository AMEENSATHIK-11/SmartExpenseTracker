import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class ExpenseTracker {

    static final Path DATA = Path.of("expenses.csv");
    static final int PORT = 8080;

    static final Map<String, List<String>> RULES = Map.of(
            "Food", List.of("swiggy", "zomato", "restaurant", "cafe", "grocery", "pizza"),
            "Travel", List.of("uber", "ola", "train", "bus", "fuel", "petrol"),
            "Bills", List.of("electricity", "water", "internet", "rent", "recharge"),
            "Shopping", List.of("amazon", "flipkart", "mall", "clothes"));

    record Expense(long id, LocalDate date, String description, double amount, String category) {}

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", ExpenseTracker::handle);
        server.start();

        String url = "http://localhost:" + PORT + "/dashboard";
        System.out.println();
        System.out.println("Dashboard ready: " + url);
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();

        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ignored) {
            // browser didn't open; the link above still works
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if (method.equals("POST") && path.equals("/add")) {
                Map<String, String> f = parseForm(ex);
                String desc = f.getOrDefault("description", "").trim();
                double amount = Double.parseDouble(f.getOrDefault("amount", "0"));
                LocalDate date = LocalDate.parse(f.getOrDefault("date", LocalDate.now().toString()));
                String cat = f.getOrDefault("category", "").isBlank() ? guess(desc) : f.get("category");

                List<Expense> list = load();
                long id = list.stream().mapToLong(Expense::id).max().orElse(0) + 1;
                list.add(new Expense(id, date, desc.replace(",", " "), amount, cat));
                save(list);
                redirect(ex, "/dashboard");

            } else if (method.equals("POST") && path.equals("/delete")) {
                long id = Long.parseLong(parseForm(ex).get("id"));
                List<Expense> list = load();
                list.removeIf(e -> e.id() == id);
                save(list);
                redirect(ex, "/dashboard");

            } else if (path.equals("/") || path.equals("/dashboard")) {
                send(ex, 200, render(load()));

            } else {
                send(ex, 404, "Not found");
            }
        } catch (Exception e) {
            send(ex, 400, "<h2>Something went wrong</h2><p>" + esc(e.getMessage()) + "</p><a href='/dashboard'>Back</a>");
        }
    }

    // ---------- Smart categorization ----------

    static String guess(String description) {
        String text = description.toLowerCase();
        for (var entry : RULES.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (text.contains(keyword)) return entry.getKey();
            }
        }
        return "Other";
    }

    // ---------- Storage (CSV file) ----------

    static List<Expense> load() throws IOException {
        List<Expense> list = new ArrayList<>();
        if (!Files.exists(DATA)) return list;
        List<String> lines = Files.readAllLines(DATA, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split(",");
            if (p.length == 5) {
                list.add(new Expense(Long.parseLong(p[0]), LocalDate.parse(p[1]),
                        p[2], Double.parseDouble(p[3]), p[4]));
            }
        }
        return list;
    }

    static void save(List<Expense> list) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("id,date,description,amount,category");
        for (Expense e : list) {
            lines.add(String.join(",", String.valueOf(e.id()), e.date().toString(),
                    e.description(), String.valueOf(e.amount()), e.category()));
        }
        Files.write(DATA, lines, StandardCharsets.UTF_8);
    }

    // ---------- Dashboard page ----------

    static String render(List<Expense> list) {
        double total = list.stream().mapToDouble(Expense::amount).sum();

        Map<String, Double> byCat = list.stream().collect(Collectors.groupingBy(
                Expense::category, TreeMap::new, Collectors.summingDouble(Expense::amount)));
        double max = byCat.values().stream().mapToDouble(d -> d).max().orElse(1);

        StringBuilder bars = new StringBuilder();
        byCat.forEach((cat, amt) -> bars.append(String.format(Locale.US,
                "<div class='bar'><span>%s</span><div class='track'><div class='fill' style='width:%.1f'></div></div><b>Rs. %.2f</b></div>",
                cat, amt / max * 100, amt)));

        StringBuilder rows = new StringBuilder();
        list.stream()
                .sorted(Comparator.comparing(Expense::date).reversed())
                .forEach(e -> rows.append(String.format(Locale.US,
                        "<tr><td>%s</td><td>%s</td><td>%s</td><td>Rs. %.2f</td><td>"
                                + "<form method='post' action='/delete'>"
                                + "<input type='hidden' name='id' value='%d'>"
                                + "<button class='danger'>x</button></form></td></tr>",
                        e.date(), esc(e.description()), e.category(), e.amount(), e.id())));

        return TEMPLATE
                .replace("{{TOTAL}}", String.format(Locale.US, "%.2f", total))
                .replace("{{BARS}}", bars.length() == 0 ? "<p>No expenses yet.</p>" : bars.toString())
                .replace("{{ROWS}}", rows.toString())
                .replace("{{TODAY}}", LocalDate.now().toString());
    }

    static final String TEMPLATE = """
            <!DOCTYPE html>
            <html><head><meta charset='UTF-8'><title>Smart Expense Tracker</title>
            <style>
            body{font-family:system-ui,sans-serif;background:#f4f6fb;margin:0;padding:24px;color:#222}
            h1{margin-top:0}
            .grid{display:grid;grid-template-columns:1fr 1fr;gap:20px}
            .card{background:#fff;border-radius:12px;padding:20px;box-shadow:0 2px 8px rgba(0,0,0,.06)}
            .total{font-size:2rem;font-weight:700;color:#2563eb}
            .bar{display:flex;align-items:center;gap:10px;margin:8px 0}
            .bar span{width:90px}
            .track{flex:1;background:#e5e7eb;border-radius:6px;height:14px}
            .fill{background:#2563eb;height:14px;border-radius:6px}
            table{width:100%;border-collapse:collapse}
            th,td{text-align:left;padding:8px;border-bottom:1px solid #eee}
            input,select{padding:8px;margin:4px 0;width:100%;box-sizing:border-box}
            button{padding:8px 14px;background:#2563eb;color:#fff;border:0;border-radius:6px;cursor:pointer}
            .danger{background:#dc2626;padding:4px 10px}
            @media(max-width:800px){.grid{grid-template-columns:1fr}}
            </style></head>
            <body>
            <h1>Smart Expense Tracker</h1>
            <div class='grid'>
              <div class='card'><h3>Total spent</h3><div class='total'>Rs. {{TOTAL}}</div></div>
              <div class='card'><h3>By category</h3>{{BARS}}</div>
              <div class='card'><h3>Add expense</h3>
                <form method='post' action='/add'>
                  <input name='description' placeholder='Description (e.g. Swiggy dinner)' required>
                  <input name='amount' type='number' step='0.01' min='0' placeholder='Amount' required>
                  <input name='date' type='date' value='{{TODAY}}' required>
                  <select name='category'>
                    <option value=''>Auto-detect</option>
                    <option>Food</option><option>Travel</option><option>Bills</option>
                    <option>Shopping</option><option>Other</option>
                  </select>
                  <button>Add</button>
                </form>
              </div>
              <div class='card'><h3>Recent expenses</h3>
                <table>
                  <tr><th>Date</th><th>Description</th><th>Category</th><th>Amount</th><th></th></tr>
                  {{ROWS}}
                </table>
              </div>
            </div>
            </body></html>
            """;

    // ---------- HTTP helpers ----------

    static Map<String, String> parseForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}