package demo;

import onl.ycode.stormify.StormifyJ;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) {
        // Clean up any previous run
        new File("target/demo.db").delete();

        // Create a SQLite DataSource
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:target/demo.db");

        // Initialize Stormify (Java-friendly wrapper) and set as default
        // so that AutoTable.populate() can find it for lazy-loading
        StormifyJ stormify = new StormifyJ(ds).asDefault();

        // === Schema Setup (Low-Level SQL API) ===
        System.out.println("=== Schema Setup ===");
        stormify.executeUpdate(
                "CREATE TABLE user (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "email TEXT NOT NULL)"
        );
        stormify.executeUpdate(
                "CREATE TABLE task (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "title TEXT NOT NULL, " +
                        "description TEXT, " +
                        "is_completed INTEGER NOT NULL DEFAULT 0, " +
                        "user_id INTEGER NOT NULL REFERENCES user(id))"
        );
        System.out.println("Tables created.\n");

        // === Create (ORM) ===
        System.out.println("=== Creating Users ===");
        stormify.transaction(tx -> {
            User alice = new User();
            alice.setName("Alice");
            alice.setEmail("alice@example.com");
            tx.create(alice);
            System.out.println("Created: " + alice);

            User bob = new User();
            bob.setName("Bob");
            bob.setEmail("bob@example.com");
            tx.create(bob);
            System.out.println("Created: " + bob);

            System.out.println("\n=== Creating Tasks ===");
            Task t1 = new Task(0, "Buy groceries", "Milk, eggs, bread", false, alice);
            tx.create(t1);
            System.out.println("Created: " + t1);

            Task t2 = new Task(0, "Write report", "Q4 financial report", false, alice);
            tx.create(t2);
            System.out.println("Created: " + t2);

            Task t3 = new Task(0, "Fix bug #42", "NullPointerException in login", false, bob);
            tx.create(t3);
            System.out.println("Created: " + t3);
        });

        // === Read (ORM) ===
        System.out.println("\n=== Find by ID ===");
        User foundUser = stormify.findById(User.class, 1);
        System.out.println("Found user: " + foundUser);

        Task foundTask = stormify.findById(Task.class, 2);
        System.out.println("Found task: " + foundTask);

        // === Lazy-loading demo ===
        System.out.println("\n=== Lazy-Loading Reference ===");
        System.out.println("Task's user (auto-populated): " + foundTask.getUser().getName());

        System.out.println("\n=== Find All ===");
        List<Task> allTasks = stormify.findAll(Task.class);
        allTasks.forEach(t -> System.out.println("  " + t));

        // === Update (ORM) ===
        System.out.println("\n=== Update Task ===");
        foundTask.setCompleted(true);
        stormify.update(foundTask);
        Task updated = stormify.findById(Task.class, foundTask.getId());
        System.out.println("Updated: " + updated);

        // === Delete (ORM) ===
        System.out.println("\n=== Delete Task ===");
        Task toDelete = stormify.findById(Task.class, 3);
        System.out.println("Deleting: " + toDelete);
        stormify.delete(toDelete);
        List<Task> remaining = stormify.findAll(Task.class);
        System.out.println("Remaining tasks: " + remaining.size());

        // === Transaction Rollback ===
        System.out.println("\n=== Transaction Rollback Demo ===");
        int countBefore = stormify.findAll(Task.class).size();
        try {
            stormify.transaction(tx -> {
                User u = stormify.findById(User.class, 1);
                tx.create(new Task(0, "This will be rolled back", "...", false, u));
                System.out.println("Task created inside transaction");
                throw new RuntimeException("Simulated error!");
            });
        } catch (RuntimeException e) {
            System.out.println("Transaction failed: " + e.getMessage());
        }
        int countAfter = stormify.findAll(Task.class).size();
        System.out.println("Tasks before: " + countBefore + ", after: " + countAfter + " (unchanged)");

        // === Raw SQL with JOIN (Low-Level API) ===
        System.out.println("\n=== Raw SQL JOIN Query ===");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) (List<?>) stormify.read(
                Map.class,
                "SELECT u.name AS user_name, t.title AS task_title, t.is_completed " +
                        "FROM task t JOIN user u ON t.user_id = u.id"
        );
        results.forEach(row -> System.out.println("  " + row));

        System.out.println("\nDone!");
        new File("target/demo.db").delete();
    }
}
