DROP TABLE IF EXISTS bench_child;
DROP TABLE IF EXISTS bench_parent;

CREATE TABLE bench_parent (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE bench_child (
  id INTEGER PRIMARY KEY,
  parent_id INTEGER NOT NULL REFERENCES bench_parent(id),
  status TEXT NOT NULL,
  value REAL NOT NULL,
  payload TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_child_parent ON bench_child(parent_id);
CREATE INDEX idx_child_status ON bench_child(status);

DROP TABLE IF EXISTS bench_warmup;
CREATE TABLE bench_warmup (
  id INTEGER PRIMARY KEY,
  payload TEXT NOT NULL
);
INSERT INTO bench_warmup (id, payload) VALUES (1, 'a'),(2, 'b'),(3, 'c'),(4, 'd'),(5, 'e'),(6, 'f'),(7, 'g'),(8, 'h'),(9, 'i'),(10, 'j');
